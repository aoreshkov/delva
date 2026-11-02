package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.query.PathSearch
import app.oreshkov.delva.internal.query.TypeQueryPlan
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `maxDocuments`, a bound on the documents a result covers: against an oracle that takes the hits of the first N
 * documents of the unbounded result, over random stores with deletes, types, flushes and compactions, for key–value
 * and JSONPath searches, with and without a document filter, in both orders and modes, across pages cut by random
 * limits and byte budgets, in `count`, and resumed from cursors. A bounded search reads no document past the bound.
 */
class MaxDocumentsSearchTest {
    /** A search of [order], [after], [limit], [maxBytes] and the bound [maxDocuments], null for none. */
    private class Search(val label: String, val run: (order: SearchOrder, after: Cursor?, limit: Int, maxBytes: Long, maxDocuments: Int?) -> SearchResult)

    private fun nodes(result: SearchResult): List<NodeRef> = result.use { r -> r.iterator().asSequence().map { it.node }.toList() }

    /** The hits of the first [n] documents of [hits], which come grouped by document. */
    private fun firstDocuments(hits: List<NodeRef>, n: Int): List<NodeRef> {
        val documents = hits.map { it.docId }.distinct().take(n).toSet()
        return hits.takeWhile { it.docId in documents }
    }

    /** The hits of [hits] strictly past [cursor] in [order]. */
    private fun past(hits: List<NodeRef>, cursor: Cursor, order: SearchOrder): List<NodeRef> {
        val at = NodeRef(cursor.docId, cursor.nodePre)
        return hits.filter { if (order == SearchOrder.ASCENDING) it > at else it < at }
    }

    private fun doc(random: Random, i: Int, count: Int): String {
        fun obj(depth: Int): String {
            val members = ArrayList<String>()
            if (random.nextInt(3) != 0) members += """"k":${random.nextInt(4)}"""
            if (random.nextInt(3) == 0) members += """"tags":[${List(random.nextInt(1, 4)) { random.nextInt(4) }.joinToString(",")}]"""
            if (depth < 3 && random.nextInt(3) == 0) members += """"child":${obj(depth + 1)}"""
            return members.joinToString(",", "{", "}")
        }
        val items = List(count) { obj(1) }
        return """{"all":true,"i":$i,"g":${random.nextInt(3)},"k":${random.nextInt(4)},"items":[${items.joinToString(",")}]}"""
    }

    private fun options(seed: Long) = EngineOptions(
        CreationOptions(fragmentBytes = listOf(64, 256, 4096)[Math.floorMod(seed, 3)], hashTerms = true), StoreOptions(encoderBudgetBytes = 8192),
        sorterBytes = 4096,
        keySeed = seed,
        log = TestEngines.LOG,
        backgroundFlush = false,
        segmentBlockBytes = 512,
        typeQueryPlan = TypeQueryPlan.entries[Math.floorMod(seed shr 2, 3)],
        descendingBufferHits = listOf(1, 3, 1000)[Math.floorMod(seed shr 5, 3)],
        compaction = if ((seed shr 4) and 1L == 0L) CompactionOptions(enabled = false) else CompactionOptions(minMergeSegments = 2, bytesPerSecond = 0, maxRunBytes = 64, listMemoryBytes = 1024),
    )

    private fun fill(store: Engine, random: Random, documents: Int) {
        val live = ArrayList<Long>()
        repeat(documents) { i ->
            val count = random.nextInt(0, 6)
            val types = if (count >= 3 && random.nextBoolean()) mapOf("$.items[0]" to "Item", "$.items[2]" to "Item") else emptyMap()
            live += store.insert(doc(random, i, count), types).raw
            if (!store.options.compaction.enabled && i % 12 == 11) store.flush()
            when (random.nextInt(40)) {
                0, 1 -> store.flush()
                2 -> if (store.options.compaction.enabled) store.compact()
                in 3..6 -> if (live.isNotEmpty()) store.delete(DocId(live.removeAt(random.nextInt(live.size))))
            }
        }
    }

    private fun searches(store: Engine): List<Search> {
        val searches = ArrayList<Search>()
        for (key in listOf("k", "tags")) for (value in 0..3) for (mode in ResultMode.entries) for (nested in NestedHits.entries) for (type in listOf(null, "Item")) {
            searches += Search("$key == $value $mode $nested type=$type") { order, after, limit, maxBytes, n ->
                store.search(KeyValueQuery(key, JsonQueryValue.Num(value.toLong()), mode, nested, limit, maxBytes, after, type, order).withMaxDocuments(n))
            }
        }
        val filters = listOf(null, DocumentFilter.compile("@.g == 1"), DocumentFilter.compile("@.all == true && @.k < 2"))
        for (text in listOf("$..[?@.k == 1]", "$.items[?@.k >= 2]", "$..[?@.k == 0 || @.tags[0] == 3]", "$..child")) {
            val path = JsonPath.compile(text)
            for (filter in filters) for (mode in ResultMode.entries) for (type in listOf(null, "Item")) {
                searches += Search("$text where $filter $mode type=$type") { order, after, limit, maxBytes, n ->
                    store.search(JsonPathQuery(path, mode, NestedHits.ALL, limit, maxBytes, after, type, ScanPolicy.ALLOW_SCAN, order, filter).withMaxDocuments(n))
                }
            }
        }
        return searches
    }

    private fun check(store: Engine, searches: List<Search>, random: Random) {
        var bounded = 0
        for (search in searches) for (order in SearchOrder.entries) {
            val all = nodes(search.run(order, null, 1000, Long.MAX_VALUE, null))
            val n = random.nextInt(1, 5)
            val label = "${search.label} $order maxDocuments=$n"
            val expected = firstDocuments(all, n)
            bounded += expected.size
            assertEquals(expected, nodes(search.run(order, null, 1000, Long.MAX_VALUE, n)), label)
            assertEquals(expected.size.toLong(), (search.run(order, null, 1000, Long.MAX_VALUE, n) as EngineSearchResult).use { it.countHits(Long.MAX_VALUE) }, "$label: count")
            if (all.isEmpty()) continue

            // Pages cut by a random limit and byte budget hold the same hits; a cursor resumes with the next documents.
            val limit = random.nextInt(1, expected.size + 2)
            val maxBytes = if (random.nextBoolean()) Long.MAX_VALUE else random.nextLong(1, 400)
            val paged = ArrayList<NodeRef>()
            var last: Cursor? = null
            search.run(order, null, limit, maxBytes, n).use { result ->
                while (true) {
                    val page = result.nextPage() ?: break
                    assertTrue(page.hits.size in 1..limit, label)
                    page.hits.mapTo(paged) { it.node }
                    last = page.cursor
                    val cursor = page.cursor ?: continue
                    if (random.nextInt(3) == 0) {
                        val resumed = nodes(search.run(order, cursor, 1000, Long.MAX_VALUE, n))
                        assertEquals(firstDocuments(past(all, cursor, order), n), resumed, "$label resumed after ${paged.size}")
                    }
                }
            }
            assertEquals(expected, paged, "$label limit $limit maxBytes $maxBytes")
            // The last page has a cursor when documents with hits follow, and resuming from it gives the next ones.
            if (all.size > expected.size) assertNotNull(last, "$label: the last page's cursor")
            val cursor = last ?: continue
            assertEquals(firstDocuments(past(all, cursor, order), n), nodes(search.run(order, cursor, 1000, Long.MAX_VALUE, n)), "$label: after the last page")
        }
        assertTrue(bounded > 0)
    }

    @Test
    fun `a bounded result is the hits of the first documents of the unbounded one`() {
        forAll("maxDocuments", Gen { random, size -> random.nextLong() to 10 + random.nextInt(size, 2 * size + 1) }, iterations = 30, maxSize = 120) { (seed, documents) ->
            TestEngines.open(options(seed)).use { store ->
                val random = Random(seed)
                fill(store, random, documents)
                check(store, searches(store), random)
            }
        }
    }

    // [0]{"g":[1]g,"items":[2][[3]{"k":[4]1},[5]{"k":[6]1},[7]{"k":[8]1}]}
    private fun three(g: Int) = """{"g":$g,"items":[{"k":1},{"k":1},{"k":1}]}"""

    private val engineOptions = EngineOptions(CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024), log = TestEngines.LOG)

    @Test
    fun `a bound of one document evaluates exactly one document`() {
        TestEngines.open(engineOptions).use { store ->
            repeat(10) { store.insert(three(it)) }
            val path = JsonPathQuery(JsonPath.compile("$.items[?@.k == 1]"))
            val keyValue = KeyValueQuery("k", JsonQueryValue.Num(1), ResultMode.CONTAINING_ELEMENT)
            for (order in SearchOrder.entries) {
                (store.search(path.copy(order = order).withMaxDocuments(1)) as EngineSearchResult).use { result ->
                    assertEquals(3, result.iterator().asSequence().count())
                    assertEquals(1, (result.source as PathSearch).documentsEvaluated, "$order")
                }
                (store.search(keyValue.copy(order = order).withMaxDocuments(1)) as EngineSearchResult).use { result ->
                    assertEquals(3, result.nextPage()!!.hits.size)
                    assertEquals(1, result.executor.stats.documentsOpened, "$order")
                }
                // count stops at the bound too.
                (store.search(path.copy(order = order).withMaxDocuments(2)) as EngineSearchResult).use { result ->
                    assertEquals(6, result.countHits(Long.MAX_VALUE))
                    assertEquals(2, (result.source as PathSearch).documentsEvaluated, "$order")
                }
            }
            assertEquals(9, store.count(keyValue.withMaxDocuments(3)))
            assertEquals(30, store.count(keyValue))
        }
    }

    @Test
    fun `every hit of the newest matching document, in pages smaller than the document`() {
        TestEngines.open(engineOptions).use { store ->
            val ids = (0 until 4).map { store.insert(three(it % 2)).raw }
            val newestOfG0 = JsonPathQuery(JsonPath.compile("$.items[?@.k == 1]"), limit = 2, order = SearchOrder.DESCENDING)
                .withDocumentFilter(DocumentFilter.compile("@.g == 0"))
                .withMaxDocuments(1)
            store.search(newestOfG0).use { result ->
                val first = result.nextPage()!!
                assertEquals(listOf(NodeRef(DocId(ids[2]), 7), NodeRef(DocId(ids[2]), 5)), first.hits.map { it.node })
                val second = result.nextPage()!!
                assertEquals(listOf(NodeRef(DocId(ids[2]), 3)), second.hits.map { it.node })
                // An older document with g = 0 follows, so the last page has a cursor, which resumes at that document.
                val cursor = assertNotNull(second.cursor)
                assertNull(result.nextPage())
                assertEquals(List(3) { ids[0] }, nodes(store.search(newestOfG0.copy(after = cursor, limit = 10))).map { it.docId.raw })
            }
        }
    }

    @Test
    fun `the last page has no cursor when no candidate document follows`() {
        TestEngines.open(engineOptions).use { store ->
            val ids = (0 until 3).map { store.insert(three(it)).raw }
            val path = JsonPathQuery(JsonPath.compile("$.items[?@.k == 1]"), limit = 10).withMaxDocuments(1)
            val keyValue = KeyValueQuery("k", JsonQueryValue.Num(1), ResultMode.CONTAINING_ELEMENT, limit = 10).withMaxDocuments(1)
            assertNotNull(store.search(path).use { it.nextPage()!!.cursor })
            assertNotNull(store.search(keyValue).use { it.nextPage()!!.cursor })
            // Past the bound only deleted documents are left: they aren't candidates.
            store.delete(DocId(ids[1]))
            store.delete(DocId(ids[2]))
            assertNull(store.search(path).use { it.nextPage()!!.cursor })
            assertNull(store.search(keyValue).use { it.nextPage()!!.cursor })
            assertNull(store.search(keyValue.copy(order = SearchOrder.DESCENDING)).use { it.nextPage()!!.cursor })
            // A bound larger than the documents with hits leaves the result as it was.
            assertNull(store.search(path.withMaxDocuments(5)).use { it.nextPage()!!.cursor })
        }
    }
}
