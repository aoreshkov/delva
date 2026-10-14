package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.DocumentFilterTemplate
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonPathTemplate
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.Normalization
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.query.DocumentOrderEvaluator
import app.oreshkov.delva.internal.query.PathSearch
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.oracle.JsonPathFilterOracle
import app.oreshkov.delva.testkit.oracle.JsonPathOracle
import app.oreshkov.delva.testkit.oracle.JsonPathQueryGen
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Document filters on JSONPath searches (SPEC.md §10.5): the search returns what the oracle selects from the documents
 * whose root passes the filter, planned or scanned, in both orders, counted and resumed; the filter's root-member terms
 * narrow the candidates and skip documents that have the term only below the root.
 */
class DocumentFilterSearchTest {
    private fun options(t: Int = 1024, creation: CreationOptions = CreationOptions(fragmentBytes = t)) = EngineOptions(
        creation, StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024), maxDepth = 512), sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 7,
        log = TestEngines.LOG, backgroundFlush = false, memtableBytes = 16L shl 10,
    )

    private data class Hit(val docId: Long, val pre: Long, val json: String)

    private fun hits(result: SearchResult): List<Hit> = result.use { r ->
        r.iterator().asSequence().map { Hit(it.docId.raw, it.node.nodePre, it.readJsonAsString(Int.MAX_VALUE)) }.toList()
    }

    private fun source(result: SearchResult): PathSearch = (result as EngineSearchResult).source as PathSearch

    /** The query a generated template binds to; one without placeholders is compiled, as most queries are. */
    private fun bound(template: JsonPathQueryGen.Template): JsonPath =
        if (template.values.isEmpty()) JsonPath.compile(template.text) else JsonPathTemplate.compile(template.text).bind(template.values)

    private fun boundFilter(template: JsonPathQueryGen.Template): DocumentFilter =
        if (template.values.isEmpty()) DocumentFilter.compile(template.text) else DocumentFilterTemplate.compile(template.text).bind(template.values)

    private class Case(val documents: List<OValue>, val deletes: Set<Int>, val queries: List<Pair<JsonPathQueryGen.Template, JsonPathQueryGen.Template>>, val seed: Long)

    @Test
    fun `filtered searches return what the oracle selects from the documents the filter accepts`() {
        val gen = Gen { random, size ->
            val documents = List(random.nextInt(4, 14)) {
                JsonGen(JsonProfile.entries[random.nextInt(JsonProfile.entries.size)], fragmentSize = 64).value().generate(random, size)
            }
            val queries = JsonPathQueryGen(documents)
            val texts = List(8) {
                // Half the queries are templates, bound to the values that the oracle reads as literals.
                val templated = random.nextBoolean()
                fun text(generate: (Random) -> String): JsonPathQueryGen.Template =
                    if (templated) queries.template(random, generate) else generate(random).let { JsonPathQueryGen.Template(it, emptyMap(), it) }
                val path = text { r ->
                    when (r.nextInt(3)) {
                        0 -> queries.filteredQuery(r)
                        1 -> queries.query(r)
                        else -> listOf("$", "$..*", "$.*")[r.nextInt(3)]
                    }
                }
                path to text(queries::documentFilter)
            }
            Case(documents, documents.indices.filter { random.nextInt(6) == 0 }.toSet(), texts, random.nextLong())
        }
        var planned = 0
        var accepted = 0
        forAll("filtered search", gen, iterations = 120) { case ->
            val random = Random(case.seed)
            TestEngines.open(options(if (random.nextBoolean()) 32 else 64 * 1024)).use { store ->
                val docIds = case.documents.map { store.insert(JsonText.minified(it)).raw }
                if (random.nextBoolean()) store.flush()
                store.deleteAll(case.deletes.map { DocId(docIds[it]) })
                val live = case.documents.indices.filter { it !in case.deletes }
                for ((pathTemplate, filterTemplate) in case.queries) {
                    val text = pathTemplate.filled
                    val filterText = filterTemplate.filled
                    val oracle = runCatching { JsonPathOracle.parse(text) }.getOrNull() ?: continue
                    val filterOracle = runCatching { JsonPathFilterOracle.parse(filterText) }.getOrNull() ?: continue
                    if (bound(pathTemplate).query.segments.size > DocumentOrderEvaluator.MAX_SEGMENTS) {
                        val e = assertFailsWith<QueryRejectedException>(text) { store.search(JsonPathQuery(bound(pathTemplate), scan = ScanPolicy.ALLOW_SCAN)) }
                        assertEquals(QueryRejectedException.Reason.TOO_MANY_SEGMENTS, e.reason, text)
                        continue
                    }
                    val mode = if (random.nextInt(4) == 0) ResultMode.WHOLE_DOCUMENT else ResultMode.CONTAINING_ELEMENT
                    val nested = if (random.nextInt(4) == 0) NestedHits.OUTERMOST_ONLY else NestedHits.ALL
                    val passing = live.filter { filterOracle.test(case.documents[it]) }
                    accepted += passing.size
                    val expected = passing.flatMap { i ->
                        var selected = oracle.selectDistinct(case.documents[i])
                        if (nested == NestedHits.OUTERMOST_ONLY) {
                            var end = -1L
                            selected = selected.filter { s -> (s.pre > end).also { if (it) end = s.pre + s.size } }
                        }
                        when {
                            mode == ResultMode.WHOLE_DOCUMENT -> if (selected.isEmpty()) emptyList() else listOf(Hit(docIds[i], 0, JsonText.minified(case.documents[i])))
                            else -> selected.map { Hit(docIds[i], it.pre, JsonText.minified(it.value)) }
                        }
                    }
                    val label = "$pathTemplate where $filterTemplate ($mode, $nested)"
                    val query = JsonPathQuery(
                        bound(pathTemplate), mode, nested, limit = random.nextInt(1, 6), scan = ScanPolicy.ALLOW_SCAN,
                        documentFilter = boundFilter(filterTemplate),
                    )
                    assertEquals(expected, hits(store.search(query)), "scan of $label")
                    assertEquals(expected.reversed(), hits(store.search(query.copy(order = SearchOrder.DESCENDING))), "descending scan of $label")
                    assertEquals(expected.size.toLong(), store.count(query), "count of $label")
                    if (store.explain(query).fullScan) {
                        assertFailsWith<QueryRejectedException>(label) { store.search(query.copy(scan = ScanPolicy.REJECT)) }
                        continue
                    }
                    planned++
                    val reject = query.copy(scan = ScanPolicy.REJECT)
                    assertEquals(expected, hits(store.search(reject)), "planned $label")
                    assertEquals(expected.reversed(), hits(store.search(reject.copy(order = SearchOrder.DESCENDING))), "planned descending $label")
                    if (expected.size > 1) {
                        val cut = random.nextInt(1, expected.size)
                        val cursor = store.search(reject.copy(limit = cut)).use { it.nextPage()!!.cursor!! }
                        assertEquals(expected.drop(cut), hits(store.search(reject.copy(after = cursor))), "resumed $label after $cut")
                    }
                }
            }
        }
        assertTrue(planned > 300, "only $planned planned searches")
        assertTrue(accepted > planned, "the filters accepted only $accepted documents")
    }

    @Test
    fun `a bound value finds the documents that have it through the index, whatever its characters`() {
        val values = listOf("plain", "it's", "x' || true || 'x", "\"", "\\", "\${v}", "a\nb\u0000", "😀", "")
        TestEngines.open(options()).use { store ->
            val docIds = values.map { v ->
                val json = "{\"ns\":${JsonText.minified(OStr(v))},\"items\":[{\"id\":${JsonText.minified(OStr(v))},\"n\":1}]}"
                store.insert(json)
            }
            store.flush()
            val path = JsonPathTemplate.compile("\$.items[?@.id == \${v}].n")
            val filter = DocumentFilterTemplate.compile("@.ns == \${v}")
            for ((k, v) in values.withIndex()) {
                val byPath = JsonPathQuery(path.bind("v" to JsonQueryValue.Str(v)))
                val byFilter = JsonPathQuery(JsonPath.compile("$.items[0].n")).withDocumentFilter(filter.bind("v" to JsonQueryValue.Str(v)))
                for (query in listOf(byPath, byFilter)) {
                    assertTrue(!store.explain(query).fullScan, "'$v' needs a scan")
                    assertEquals(listOf(NodeRef(docIds[k], 5)), store.search(query).use { r -> r.iterator().asSequence().map { it.node }.toList() }, "'$v'")
                }
            }
        }
    }

    @Test
    fun `whether the root has a filter term is decided by the first posting of the document`() {
        TestEngines.open(options()).use { store ->
            // The filter's term at the root and in 500 nested objects of each document.
            val nested = (0 until 500).joinToString(",") { """{"ns":"orders","i":$it}""" }
            val ids = List(4) { store.insert("""{"ns":"orders","price":$it,"lines":[$nested]}""") }
            store.insert("""{"ns":"users","price":9,"lines":[$nested]}""")
            store.flush()
            val query = JsonPathQuery(JsonPath.compile("$.price")).withDocumentFilter(DocumentFilter.compile("@.ns == 'orders'"))
            store.search(query).use { result ->
                assertEquals(ids.map { NodeRef(it, 2) }, result.iterator().asSequence().map { it.node }.toList())
                val search = source(result)
                assertEquals(1, search.documentsSkipped)
                // Each posting run gives one entry before the first container is known; reading them all would be 2505.
                assertTrue(search.rootEntriesDecoded < 100, "${search.rootEntriesDecoded} root-term postings read")
            }
        }
    }

    @Test
    fun `the filter tests the root, and its terms make a search that would scan a planned one`() {
        TestEngines.open(options()).use { store ->
            val order = store.insert("""{"ns":"orders","key":"a","body":{"price":10}}""")
            store.insert("""{"ns":"users","key":"b","body":{"price":20}}""")
            // The term is in the document, but below the root: skipped without being read.
            repeat(10) { store.insert("""{"ns":"users","key":"c$it","body":{"ns":"orders","price":30}}""") }
            val newer = store.insert("""{"ns":"orders","key":"d","body":{"price":40}}""")
            store.flush()
            val path = JsonPathQuery(JsonPath.compile("$..price"))
            assertTrue(store.explain(path).fullScan)
            val query = path.withDocumentFilter(DocumentFilter.compile("@.ns == 'orders'"))
            val plan = store.explain(query)
            assertEquals("QueryPlan('ns' == 'orders')", plan.toString())
            store.search(query).use { result ->
                assertEquals(listOf(NodeRef(order, 4), NodeRef(newer, 4)), result.iterator().asSequence().map { it.node }.toList())
                val search = source(result)
                assertEquals(2, search.documentsEvaluated)
                assertEquals(10, search.documentsSkipped)
                assertEquals(0, search.documentsFiltered)
            }
            // `$` is the root as `@` is, and the newest hit comes first in descending order.
            val newest = query.withDocumentFilter(DocumentFilter.compile("\$.ns == 'orders'")).copy(order = SearchOrder.DESCENDING, limit = 1)
            assertEquals(listOf(Hit(newer.raw, 4, "40")), store.search(newest).use { r -> r.nextPage()!!.hits.map { Hit(it.docId.raw, it.node.nodePre, it.readJsonAsString()) } })
            // A condition the index can't check is tested on each candidate's root.
            val cheap = path.withDocumentFilter(DocumentFilter.compile("@.ns == 'orders' && @.key != 'a'"))
            store.search(cheap).use { result ->
                assertEquals(listOf(NodeRef(newer, 4)), result.iterator().asSequence().map { it.node }.toList())
                assertEquals(1, source(result).documentsFiltered)
            }
            assertEquals(1, store.count(cheap))
            // A filter without terms leaves the search to the path, which needs a scan here.
            val untermed = path.withDocumentFilter(DocumentFilter.compile("@.key != 'a'"))
            assertEquals(QueryRejectedException.Reason.NEEDS_SCAN, assertFailsWith<QueryRejectedException> { store.search(untermed) }.reason)
            assertEquals(12, hits(store.search(untermed.copy(scan = ScanPolicy.ALLOW_SCAN))).size)
        }
    }

    @Test
    fun `filter and path terms combine, and the path still anchors its own filter`() {
        TestEngines.open(options()).use { store ->
            val hit = store.insert("""{"ns":"shop","orders":[{"id":1,"status":"lost"}]}""")
            store.insert("""{"ns":"archive","orders":[{"id":2,"status":"lost"}]}""")
            store.insert("""{"ns":"shop","orders":[{"id":3,"status":"ok"}],"returns":[{"status":"lost"}]}""")
            store.insert("""{"status":"lost","orders":[{"status":"ok"}]}""")
            val both = store.insert("""{"status":"lost","orders":[{"status":"lost"}]}""")
            store.flush()
            val query = JsonPathQuery(JsonPath.compile("$.orders[?@.status == 'lost'].id"), documentFilter = DocumentFilter.compile("@.ns == 'shop'"))
            assertEquals("QueryPlan('ns' == 'shop' AND 'status' == 'lost' within \$['orders'])", store.explain(query).toString())
            assertEquals(listOf(Hit(hit.raw, 4, "1")), hits(store.search(query)))
            // The same term in the filter and in the path's anchor: each check reads postings of its own.
            val same = JsonPathQuery(JsonPath.compile("$.orders[?@.status == 'lost']"), documentFilter = DocumentFilter.compile("@.status == 'lost'"))
            assertEquals(listOf(Hit(both.raw, 3, """{"status":"lost"}""")), hits(store.search(same)))
            assertEquals(listOf(Hit(both.raw, 0, """{"status":"lost","orders":[{"status":"lost"}]}""")), hits(store.search(same.copy(mode = ResultMode.WHOLE_DOCUMENT))))
            // Nested filters, functions and absolute queries inside the filter all see the root.
            val nested = JsonPathQuery(
                JsonPath.compile("$.orders[*].id"), scan = ScanPolicy.ALLOW_SCAN,
                documentFilter = DocumentFilter.compile("@.orders[?@.status == 'lost' && length(\$.ns) == 4]"),
            )
            assertEquals(listOf(Hit(hit.raw, 4, "1")), hits(store.search(nested)))
        }
    }

    @Test
    fun `a filter's names and strings are normalized in a store with normalization`() {
        val nfc = CreationOptions(fragmentBytes = 1024, normalization = Normalization.NFC)
        TestEngines.open(options(creation = nfc)).use { store ->
            val composed = "café"
            val decomposed = "café"
            val docId = store.insert("""{"$decomposed":"$decomposed","v":1}""")
            val query = JsonPathQuery(JsonPath.compile("$.v"), documentFilter = DocumentFilter.compile("@.$composed == '$decomposed'"))
            assertEquals("'$composed' == '$composed'", store.explain(query).terms)
            assertEquals(listOf(Hit(docId.raw, 2, "1")), hits(store.search(query)))
        }
    }

    @Test
    fun `without a filter nothing changes`() {
        TestEngines.open(options()).use { store ->
            store.insert("""{"a":1}""")
            val query = JsonPathQuery(JsonPath.compile("$[?@.a == 1]"))
            assertNull(query.documentFilter)
            store.search(query).use { assertEquals(0, source(it).documentsFiltered) }
        }
    }
}
