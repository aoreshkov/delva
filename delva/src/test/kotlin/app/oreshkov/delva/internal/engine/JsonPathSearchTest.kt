package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.InvalidTypeNameException
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.query.Candidates
import app.oreshkov.delva.internal.query.DocumentOrderEvaluator
import app.oreshkov.delva.internal.query.PathSearch
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.oracle.JsonPathOracle
import app.oreshkov.delva.testkit.oracle.JsonPathQueryGen
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JSONPath search (SPEC.md §10.4): the planned search, which reads only the documents the index names, returns what
 * a full scan returns, and both return what the oracle selects — distinct nodes in `(docId, pre)` order — across
 * modes, nested-hit filtering, pages, cursors, deletes and index segments.
 */
class JsonPathSearchTest {
    private fun options(
        t: Int = 64,
        creation: CreationOptions = CreationOptions(fragmentBytes = t),
        store: StoreOptions = StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024), maxDepth = 512),
        maxRangeDocuments: Int = Candidates.MAX_RANGE_DOCUMENTS,
    ) = EngineOptions(
        creation, store, sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 11, log = TestEngines.LOG, backgroundFlush = false,
        memtableBytes = 16L shl 10, maxRangeDocuments = maxRangeDocuments,
    )

    private data class Hit(val docId: Long, val pre: Long, val json: String, val path: String)

    private fun hits(result: SearchResult): List<Hit> = result.use { r ->
        val out = ArrayList<Hit>()
        while (true) {
            val page = r.nextPage() ?: break
            page.hits.mapTo(out) { Hit(it.docId.raw, it.node.nodePre, it.readJsonAsString(Int.MAX_VALUE), it.path) }
        }
        out
    }

    private class Case(val documents: List<OValue>, val deletes: Set<Int>, val queries: List<String>, val seed: Long)

    @Test
    fun `planned searches return what a scan and the oracle return`() {
        val gen = Gen { random, size ->
            val documents = List(random.nextInt(4, 14)) {
                JsonGen(JsonProfile.entries[random.nextInt(JsonProfile.entries.size)], fragmentSize = 64).value().generate(random, size)
            }
            val queries = JsonPathQueryGen(documents)
            val texts = List(10) { if (it < 7) queries.filteredQuery(random) else queries.query(random) }
            Case(documents, documents.indices.filter { random.nextInt(6) == 0 }.toSet(), texts, random.nextLong())
        }
        var planned = 0
        var plannedHits = 0
        var withinPath = 0
        forAll("search", gen, iterations = 120) { case ->
            val random = kotlin.random.Random(case.seed)
            TestEngines.open(options(if (random.nextBoolean()) 32 else 64 * 1024)).use { store ->
                val docIds = case.documents.map { store.insert(JsonText.minified(it)).raw }
                if (random.nextBoolean()) store.flush()
                store.deleteAll(case.deletes.map { DocId(docIds[it]) })
                val live = case.documents.indices.filter { it !in case.deletes }
                for (text in case.queries) {
                    val oracle = runCatching { JsonPathOracle.parse(text) }.getOrNull() ?: continue
                    val path = JsonPath.compile(text)
                    if (path.query.segments.size > DocumentOrderEvaluator.MAX_SEGMENTS) {
                        val e = assertFailsWith<QueryRejectedException>(text) { store.search(JsonPathQuery(path, scan = ScanPolicy.ALLOW_SCAN)) }
                        assertEquals(QueryRejectedException.Reason.TOO_MANY_SEGMENTS, e.reason, text)
                        continue
                    }
                    val mode = if (random.nextInt(4) == 0) ResultMode.WHOLE_DOCUMENT else ResultMode.CONTAINING_ELEMENT
                    val nested = if (random.nextInt(4) == 0) NestedHits.OUTERMOST_ONLY else NestedHits.ALL
                    val expected = live.flatMap { i ->
                        var selected = oracle.selectDistinct(case.documents[i])
                        if (nested == NestedHits.OUTERMOST_ONLY) {
                            var end = -1L
                            selected = selected.filter { s -> (s.pre > end).also { if (it) end = s.pre + s.size } }
                        }
                        when {
                            mode == ResultMode.WHOLE_DOCUMENT -> if (selected.isEmpty()) emptyList() else listOf(Hit(docIds[i], 0, JsonText.minified(case.documents[i]), "$"))
                            else -> selected.map { Hit(docIds[i], it.pre, JsonText.minified(it.value), it.path) }
                        }
                    }
                    val limit = random.nextInt(1, 6)
                    val query = JsonPathQuery(path, mode, nested, limit = limit, scan = ScanPolicy.ALLOW_SCAN)
                    val scanned = hits(store.search(query))
                    assertEquals(expected, scanned, "scan of '$text' ($mode, $nested)")
                    assertEquals(expected.size.toLong(), store.count(query), "count of '$text' ($mode, $nested)")
                    val atMost = random.nextLong(0, 4)
                    assertEquals(minOf(atMost, expected.size.toLong()), store.count(query, atMost), "count of '$text' at most $atMost")
                    val plan = store.explain(query)
                    if (plan.fullScan) {
                        assertFailsWith<QueryRejectedException>(text) { store.search(query.copy(scan = ScanPolicy.REJECT)) }
                        assertFailsWith<QueryRejectedException>(text) { store.count(query.copy(scan = ScanPolicy.REJECT)) }
                        continue
                    }
                    planned++
                    plannedHits += expected.size
                    if (plan.within != null) withinPath++
                    assertEquals(expected, hits(store.search(query.copy(scan = ScanPolicy.REJECT))), "planned '$text' (${plan.terms})")
                    // A cursor from the middle resumes a new search after it.
                    if (expected.size > 1) {
                        val cut = random.nextInt(1, expected.size)
                        val cursor = store.search(query.copy(limit = cut)).use { it.nextPage()!!.cursor!! }
                        assertEquals(expected.drop(cut), hits(store.search(query.copy(after = cursor))), "resumed '$text' after $cut")
                        assertEquals(expected.size.toLong() - cut, store.count(query.copy(after = cursor)), "count resumed '$text' after $cut")
                    }
                }
            }
        }
        // A filter selects children, so the root's own members never match `$..[?…]`: hits are rarer than queries.
        assertTrue(planned > 300 && plannedHits > planned / 8, "$planned planned searches with $plannedHits hits")
        assertTrue(withinPath > planned / 5, "only $withinPath of $planned planned searches used a path hash")
    }

    @Test
    fun `the index narrows the documents that are read`() {
        TestEngines.open(options()).use { store ->
            repeat(50) { store.insert("""{"orders":[{"id":$it,"status":"open"},{"id":${it + 100},"status":"closed"}]}""") }
            val target = store.insert("""{"orders":[{"id":7,"status":"lost","items":[{"sku":"A"}]}]}""")
            store.flush()
            val query = JsonPathQuery(JsonPath.compile("$.orders[?@.status == 'lost'].items[*].sku"))
            assertEquals("QueryPlan('status' == 'lost' within $['orders'])", store.explain(query).toString())
            store.search(query).use { result ->
                assertEquals(listOf(NodeRef(target, 7)), result.iterator().asSequence().map { it.node }.toList())
                assertEquals(1, ((result as EngineSearchResult).source as PathSearch).documentsEvaluated)
            }
            val either = JsonPathQuery(JsonPath.compile("$..[?@.status == 'lost' || @.id == 3 && @.status == 'open']"))
            assertEquals("'status' == 'lost' OR ('id' == 3 AND 'status' == 'open')", store.explain(either).terms)
            store.search(either).use { result ->
                assertEquals(2, result.iterator().asSequence().count())
                assertEquals(2, ((result as EngineSearchResult).source as PathSearch).documentsEvaluated)
            }
        }
    }

    @Test
    fun `documents whose matching objects are under another path are skipped unread`() {
        TestEngines.open(options(1024)).use { store ->
            val hit = store.insert("""{"orders":[{"id":1,"status":"lost"}],"returns":[]}""")
            repeat(20) { store.insert("""{"orders":[{"id":1,"status":"ok"}],"returns":[{"id":2,"status":"lost"}]}""") }
            // An object named like the path elsewhere: same keys, different parent path.
            store.insert("""{"archive":{"orders":[{"status":"lost"}]}}""")
            store.flush()
            val query = JsonPathQuery(JsonPath.compile("$.orders[?@.status == 'lost']"))
            val plan = store.explain(query)
            assertEquals("\$['orders']", plan.within)
            assertEquals("QueryPlan('status' == 'lost' within \$['orders'])", plan.toString())
            store.search(query).use { result ->
                assertEquals(listOf(NodeRef(hit, 2)), result.iterator().asSequence().map { it.node }.toList())
                val search = (result as EngineSearchResult).source as PathSearch
                assertEquals(1, search.documentsEvaluated)
                assertEquals(21, search.documentsSkipped)
            }
            // Indexes and names both fix the path; a wildcard or '..' doesn't.
            assertEquals("\$['orders'][*]", store.explain(JsonPathQuery(JsonPath.compile("$.orders[-1][?@.id == 1]"))).within)
            assertEquals("$", store.explain(JsonPathQuery(JsonPath.compile("$[?@.id == 1]"))).within)
            assertNull(store.explain(JsonPathQuery(JsonPath.compile("$.*[?@.id == 1]"))).within)
            assertNull(store.explain(JsonPathQuery(JsonPath.compile("$..[?@.id == 1]"))).within)
            assertNull(store.explain(JsonPathQuery(JsonPath.compile("$.orders..[?@.id == 1]"))).within)
        }
    }

    @Test
    fun `inside a document only subtrees that hold an anchor are walked`() {
        TestEngines.open(options(1024)).use { store ->
            val groups = (0 until 200).joinToString(",") { g ->
                """{"g":$g,"rows":[${(0 until 20).joinToString(",") { r -> """{"id":$r,"status":"${if (g == 150 && r == 3) "lost" else "ok"}"}""" }}]}"""
            }
            val docId = store.insert("""{"groups":[$groups]}""")
            store.flush()
            val query = JsonPathQuery(JsonPath.compile("$..[?@.status == 'lost']"))
            store.search(query).use { result ->
                val hits = result.iterator().asSequence().toList()
                assertEquals(1, hits.size)
                assertEquals("""{"id":3,"status":"lost"}""", hits[0].readJsonAsString())
                assertEquals(docId, hits[0].docId)
                val visited = ((result as EngineSearchResult).source as PathSearch).nodesVisited
                // The document has about 200 × 20 × 3 nodes; the walk goes down one path and its siblings.
                assertTrue(visited < 400, "visited $visited nodes")
            }
            // The same answer without the index.
            assertEquals(1, hits(store.search(query.copy(scan = ScanPolicy.ALLOW_SCAN))).size)
        }
    }

    @Test
    fun `a search the index can't narrow is rejected unless it may scan`() {
        TestEngines.open(options()).use { store ->
            // Each query below selects exactly the one object.
            store.insert("""[{"a":2,"b":0}]""")
            for (text in listOf("$..a", "$[?@.a != 1]", "$[?!(@.a == 1)]", "$[?@.a == 1, 0]", "$[?@.a == 1 || @.b]", "$[?@.b <= @.a]", "$[?@.a]")) {
                val query = JsonPathQuery(JsonPath.compile(text))
                assertTrue(store.explain(query).fullScan, text)
                assertNull(store.explain(query).terms)
                val e = assertFailsWith<QueryRejectedException>(text) { store.search(query) }
                assertEquals(QueryRejectedException.Reason.NEEDS_SCAN, e.reason, text)
                assertEquals("no index term narrows the search for $text", e.detail, text)
                assertEquals(text, e.query, text)
                assertEquals(1, hits(store.search(query.copy(scan = ScanPolicy.ALLOW_SCAN))).size, text)
            }
        }
    }

    @Test
    fun `a search takes queries of at most 31 segments, and select any`() {
        TestEngines.open(options()).use { store ->
            // n nested arrays around one object: n - 1 `[0]` reach the innermost array, and the filter makes n segments.
            store.insert("[".repeat(31) + """{"a":1}""" + "]".repeat(31))
            val docId = store.insert("[".repeat(32) + """{"a":1}""" + "]".repeat(32))
            fun query(indexes: Int) = JsonPath.compile("$" + "[0]".repeat(indexes) + "[?@.a == 1]")
            assertEquals(31, query(30).query.segments.size)
            assertEquals(1, hits(store.search(JsonPathQuery(query(30)))).size, "31 segments")
            for (mode in ResultMode.entries) {
                val e = assertFailsWith<QueryRejectedException> { store.search(JsonPathQuery(query(31), mode, scan = ScanPolicy.ALLOW_SCAN)) }
                assertEquals(QueryRejectedException.Reason.TOO_MANY_SEGMENTS, e.reason)
                assertContains(e.message!!, "32 segments")
                assertFailsWith<QueryRejectedException> { store.count(JsonPathQuery(query(31), mode)) }
            }
            assertEquals(1, store.select(NodeRef(docId, 0), query(31)).use { r -> r.iterator().asSequence().count() })
        }
    }

    @Test
    fun `a range that matches more documents than a search collects is rejected unless it may scan`() {
        TestEngines.open(options(maxRangeDocuments = 3)).use { store ->
            for (price in 1..5) store.insert("""[{"price":$price}]""")
            assertEquals(3, hits(store.search(JsonPathQuery(JsonPath.compile("$[?@.price <= 3]")))).size, "at the limit")
            val wide = JsonPathQuery(JsonPath.compile("$[?@.price > 1]"))
            val e = assertFailsWith<QueryRejectedException> { store.search(wide) }
            assertEquals(QueryRejectedException.Reason.RANGE_TOO_WIDE, e.reason)
            assertContains(e.message!!, "matches more than 3 documents")
            assertEquals(4, hits(store.search(wide.copy(scan = ScanPolicy.ALLOW_SCAN))).size)
        }
    }

    @Test
    fun `comparisons of order narrow a search by ranges of terms`() {
        TestEngines.open(options(64 * 1024)).use { store ->
            // Enough significant digits for a NumberKey past 64 bytes: a lossy term, outside the ordered range.
            val big = "1" + "234567890".repeat(15)
            val long = "m" + "z".repeat(100)
            val values = listOf("-5", "-0.5", "0", "1", "1.5", "2", "10", "1e3", big, "-$big", "\"a\"", "\"b\"", "\"ba\"", "\"$long\"", "\"\"", "true", "null", "[3]")
            val docs = values.map { v -> store.insert("""[{"v":$v}]""").raw to v }
            store.flush()
            store.insert("""[{"v":1.25}]""").raw.let { docs.plus(it to "1.25") }.also { all ->
                for ((text, expected) in listOf(
                    "$[?@.v < 1]" to listOf("-5", "-0.5", "0", "-$big"),
                    "$[?@.v <= 1]" to listOf("-5", "-0.5", "0", "1", "-$big"),
                    "$[?1.5 < @.v]" to listOf("2", "10", "1e3", big),
                    "$[?@.v >= 1.25]" to listOf("1.5", "2", "10", "1e3", big, "1.25"),
                    "$[?@.v > 'a']" to listOf("\"b\"", "\"ba\"", "\"$long\""),
                    "$[?@.v < 'b']" to listOf("\"a\"", "\"\""),
                    "$[?@.v >= 'm']" to listOf("\"$long\""),
                    "$[?@.v[0] > 2]" to listOf("[3]"),
                )) {
                    val query = JsonPathQuery(JsonPath.compile(text))
                    val plan = store.explain(query)
                    assertFalse(plan.fullScan, text)
                    val found = hits(store.search(query)).map { h -> all.first { it.first == h.docId }.second }
                    assertEquals(expected, found, "$text (${plan.terms})")
                    assertEquals(found, hits(store.search(query.copy(scan = ScanPolicy.ALLOW_SCAN))).map { h -> all.first { it.first == h.docId }.second }, text)
                }
            }
            assertEquals("'v' < 10 AND 'v' >= 'a'", store.explain(JsonPathQuery(JsonPath.compile("$[?@.v < 10 && @.v >= 'a']"))).terms)
        }
    }

    @Test
    fun `index controls decide which terms can narrow a search`() {
        val controlled = CreationOptions(fragmentBytes = 1024, excludedKeys = setOf("secret"), maxIndexedStringBytes = 4)
        TestEngines.open(options(creation = controlled, store = StoreOptions(encoderBudgetBytes = 4096))).use { store ->
            store.insert("""[{"secret":1,"name":"abcdefgh","n":"ab"}]""")
            assertTrue(store.explain(JsonPathQuery(JsonPath.compile("$[?@.secret == 1]"))).fullScan)
            assertTrue(store.explain(JsonPathQuery(JsonPath.compile("$[?@.name == 'abcdefgh']"))).fullScan)
            assertFalse(store.explain(JsonPathQuery(JsonPath.compile("$[?@.n == 'ab']"))).fullScan)
            // With string lengths limited, a string range could miss unindexed strings; numbers are always indexed.
            assertTrue(store.explain(JsonPathQuery(JsonPath.compile("$[?@.n > 'a']"))).fullScan)
            assertFalse(store.explain(JsonPathQuery(JsonPath.compile("$[?@.n > 1]"))).fullScan)
            assertEquals(1, hits(store.search(JsonPathQuery(JsonPath.compile("$[?@.secret == 1]"), scan = ScanPolicy.ALLOW_SCAN))).size)
        }
    }

    @Test
    fun `the type filter keeps selected objects of the type`() {
        TestEngines.open(options()).use { store ->
            val docId = store.insert("""{"items":[{"k":1},{"k":1},2]}""", mapOf("$.items[1]" to "Item"))
            val query = JsonPathQuery(JsonPath.compile("$.items[?@.k == 1]"), type = "Item")
            assertEquals(listOf(Hit(docId.raw, 4, """{"k":1}""", "$['items'][1]")), hits(store.search(query)))
            assertEquals(emptyList(), hits(store.search(query.copy(type = "Other"))))
            assertEquals(listOf(Hit(docId.raw, 0, """{"items":[{"k":1},{"k":1},2]}""", "$")), hits(store.search(query.copy(mode = ResultMode.WHOLE_DOCUMENT))))
            assertFailsWith<InvalidTypeNameException> { store.search(query.copy(type = "")) }
            store.search(query).use { assertEquals("Item", it.iterator().next().type) }
        }
    }

    @Test
    fun `in a store with normalization the query's names and strings are normalized`() {
        val nfc = CreationOptions(fragmentBytes = 1024, normalization = app.oreshkov.delva.Normalization.NFC)
        TestEngines.open(options(creation = nfc, store = StoreOptions(encoderBudgetBytes = 4096))).use { store ->
            val composed = "café"
            val decomposed = "café"
            val docId = store.insert("""[{"$decomposed":"$decomposed"}]""")
            for (text in listOf("$[?@.$composed == '$decomposed']", "$[?@['$decomposed'] == '$composed']", "$[?match(@.$composed, '$decomposed')]")) {
                val path = JsonPath.compile(text)
                assertEquals(listOf(Hit(docId.raw, 1, """{"$composed":"$composed"}""", "$[0]")), hits(store.search(JsonPathQuery(path, scan = ScanPolicy.ALLOW_SCAN))), text)
                assertEquals(1, store.select(NodeRef(docId, 0), path).use { it.iterator().asSequence().count() }, text)
            }
            assertEquals("'$composed' == '$composed'", store.explain(JsonPathQuery(JsonPath.compile("$[?@.$decomposed == '$decomposed']"))).terms)
        }
    }

    @Test
    fun `hits can be any value and report their encoded size`() {
        TestEngines.open(options(32)).use { store ->
            val long = "x".repeat(500)
            val docId = store.insert("""{"a":{"k":"v","s":"$long","n":[1,2,3]}}""")
            val query = JsonPathQuery(JsonPath.compile("$..[?@.k == 'v'].*"))
            store.search(query).use { result ->
                val hits = result.iterator().asSequence().toList()
                assertEquals(listOf("\"v\"", "\"$long\"", "[1,2,3]"), hits.map { it.readJsonAsString() })
                assertEquals(listOf(2L, 3L, 4L), hits.map { it.node.nodePre })
                assertTrue(hits.all { it.docId == docId && it.encodedSize > 0 })
                assertTrue(hits[1].encodedSize >= 500)
            }
        }
    }
}
