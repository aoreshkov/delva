package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.ResultTooLargeException
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.StoreClosedException
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineTest {
    private val example = """{"order":{"id":7,"items":[{"sku":"A1","qty":2},{"sku":"B2","qty":1.0}],"tags":["x","y"]}}"""

    private fun engine(fragmentBytes: Int = 64 * 1024, threshold: Long = EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD, hashTerms: Boolean = true) =
        TestEngines.open(EngineOptions(CreationOptions(fragmentBytes = fragmentBytes, hashTerms = hashTerms), StoreOptions(encoderBudgetBytes = maxOf(2 * fragmentBytes, 4096)), smallInsertThreshold = threshold, keySeed = 1, log = TestEngines.LOG))

    /** `(docId, pre) → JSON` of every hit, page by page. */
    private fun SearchResult.all(): List<Triple<Long, Long, String>> = use {
        buildList {
            while (true) {
                val page = nextPage() ?: break
                for (hit in page.hits) add(Triple(hit.node.docId.raw, hit.node.nodePre, hit.readJsonAsString()))
            }
        }
    }

    private fun query(key: String, value: JsonQueryValue, mode: ResultMode = ResultMode.CONTAINING_ELEMENT, nested: NestedHits = NestedHits.ALL, limit: Int = 100) =
        KeyValueQuery(key, value, mode, nested, limit)

    @Test
    fun `the specification examples match at every fragment size`() {
        for (t in listOf(32, 64, 256, 64 * 1024)) {
            for (threshold in listOf(0L, EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD)) {
                engine(t, threshold).use { store ->
                    val docId = store.insert(example).raw
                    assertEquals(1, docId)
                    val order = """{"id":7,"items":[{"sku":"A1","qty":2},{"sku":"B2","qty":1.0}],"tags":["x","y"]}"""
                    assertEquals(listOf(Triple(1L, 1L, order)), store.search(query("id", JsonQueryValue.Num(7))).all(), "T=$t")
                    assertEquals(listOf(Triple(1L, 7L, """{"sku":"B2","qty":1.0}""")), store.search(query("qty", JsonQueryValue.Json("1e0"))).all())
                    assertEquals(listOf(1L), store.search(query("tags", JsonQueryValue.Str("x"))).all().map { it.second })
                    assertEquals(listOf(1L), store.search(query("items", JsonQueryValue.Json("""{"qty":2,"sku":"A1"}"""))).all().map { it.second })
                    assertEquals(listOf(1L), store.search(query("tags", JsonQueryValue.Json("""["x","y"]"""))).all().map { it.second })
                    assertEquals(emptyList(), store.search(query("tags", JsonQueryValue.Json("""["y","x"]"""))).all())
                    assertEquals(emptyList(), store.search(query("order.id", JsonQueryValue.Num(7))).all())
                    assertEquals(emptyList(), store.search(query("id", JsonQueryValue.Str("7"))).all())
                    assertEquals(listOf(Triple(1L, 0L, example)), store.search(query("sku", JsonQueryValue.Str("A1"), ResultMode.WHOLE_DOCUMENT)).all())
                }
            }
        }
    }

    @Test
    fun `hits come in docId and pre order, pages resume exactly, and cursors resume in new searches`() {
        engine(64).use { store ->
            repeat(5) { i -> store.insert("""{"k":"v","n":$i,"c":[{"k":"v"},{"k":"w"},{"k":"v","d":{"k":"v"}}]}""") }
            val expected = store.search(query("k", JsonQueryValue.Str("v"), limit = 1000)).all().map { it.first to it.second }
            assertEquals(20, expected.size)
            assertEquals(expected.sortedWith(compareBy({ it.first }, { it.second })), expected)

            store.search(query("k", JsonQueryValue.Str("v"), limit = 3)).use { result ->
                val seen = ArrayList<Pair<Long, Long>>()
                while (true) {
                    val page = result.nextPage() ?: break
                    assertTrue(page.hits.size <= 3)
                    seen += page.hits.map { it.node.docId.raw to it.node.nodePre }
                    if (page.cursor == null) assertNull(result.nextPage())
                }
                assertEquals(expected, seen)
            }

            var after: Cursor? = null
            val resumed = ArrayList<Pair<Long, Long>>()
            do {
                store.search(query("k", JsonQueryValue.Str("v"), limit = 7).copy(after = after?.let { Cursor.parse(it.toString()) })).use { result ->
                    val page = assertNotNull(result.nextPage())
                    resumed += page.hits.map { it.node.docId.raw to it.node.nodePre }
                    after = page.cursor
                }
            } while (after != null)
            assertEquals(expected, resumed)

            val outermost = store.search(query("k", JsonQueryValue.Str("v"), nested = NestedHits.OUTERMOST_ONLY)).all().map { it.first to it.second }
            assertEquals(expected.filter { it.second == 0L }, outermost)
            assertEquals((1L..5L).map { it to 0L }, store.search(query("k", JsonQueryValue.Str("v"), ResultMode.WHOLE_DOCUMENT)).all().map { it.first to it.second })
        }
    }

    @Test
    fun `an open result doesn't see later inserts, and a new one does`() {
        engine().use { store ->
            store.insert("""{"a":1}""")
            val result = store.search(query("a", JsonQueryValue.Num(1), limit = 1))
            store.insert("""{"a":1}""")
            assertEquals(1, result.all().size)
            assertEquals(2, store.search(query("a", JsonQueryValue.Num(1))).all().size)
        }
    }

    @Test
    fun `the byte budget ends pages early but never returns an empty page`() {
        engine(256).use { store ->
            repeat(6) { i -> store.insert("""{"k":1,"pad":"${"x".repeat(100 * i)}"}""") }
            val sizes = store.search(query("k", JsonQueryValue.Num(1), ResultMode.WHOLE_DOCUMENT)).use { r -> r.iterator().asSequence().map { it.encodedSize }.toList() }
            assertEquals(6, sizes.size)
            assertTrue(sizes.zipWithNext().all { (a, b) -> a < b }, "sizes $sizes")
            val budget = sizes[1] + sizes[2]
            store.search(query("k", JsonQueryValue.Num(1), ResultMode.WHOLE_DOCUMENT).copy(maxBytes = budget)).use { result ->
                val pages = generateSequence { result.nextPage() }.map { page -> page.hits.map { it.encodedSize } }.toList()
                assertEquals(sizes, pages.flatten())
                for (page in pages) assertTrue(page.size == 1 || page.sum() <= budget, "page $page, budget $budget")
                assertEquals(listOf(sizes[0], sizes[1]), pages[0])
            }
        }
    }

    @Test
    fun `hits stream JSON, guard string reads and stop working after close`() {
        val store = engine(64)
        val big = """{"k":true,"s":"${"é".repeat(500)}","a":[${(1..300).joinToString(",")}]}"""
        store.insert(big)
        val result = store.search(query("k", JsonQueryValue.Bool(true)))
        val hit = result.nextPage()!!.hits.single()
        val out = ByteArrayOutputStream()
        hit.writeJsonTo(out)
        assertEquals(big, out.toString(Charsets.UTF_8))
        assertFailsWith<ResultTooLargeException> { hit.readJsonAsString(big.length - 1) }
        assertEquals(big, hit.readJsonAsString(big.length))
        result.close()
        assertFailsWith<IllegalStateException> { hit.readJsonAsString() }
        store.close()
        assertFailsWith<StoreClosedException> { store.insert("{}") }
        assertFailsWith<StoreClosedException> { store.search(query("k", JsonQueryValue.Null)) }
    }

    @Test
    fun `rejected inserts leave nothing visible, before and after promotion`() {
        for (threshold in listOf(0L, EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD)) {
            engine(32, threshold).use { store ->
                store.insert("""{"k":1}""")
                val bad = """{"k":1,"items":[${(1..50).joinToString(",") { """{"k":1}""" }}],"k":2}"""
                val e = assertFailsWith<DocumentRejectedException> { store.insert(bad) }
                assertEquals(RejectReason.DUPLICATE_KEY, e.reason)
                assertFailsWith<DocumentRejectedException> { store.insert("""{"k":1""") }
                assertEquals(4, store.insert("""{"k":1}""").raw)
                assertEquals(listOf(1L, 4L), store.search(query("k", JsonQueryValue.Num(1), ResultMode.WHOLE_DOCUMENT)).all().map { it.first })
                assertEquals(0, store.registry.current().visibility.inFlightCount)
                if (threshold == 0L) assertTrue(store.fragmentIndex.size > 2, "promoted fragments stay, hidden")
            }
        }
    }

    @Test
    fun `query values are validated like documents`() {
        engine(hashTerms = false).use { store ->
            assertFailsWith<DocumentRejectedException> { store.search(query("k", JsonQueryValue.Json("1e2147483648"))) }.also { assertEquals(RejectReason.NUMBER_OUT_OF_RANGE, it.reason) }
            assertFailsWith<DocumentRejectedException> { store.search(query("k", JsonQueryValue.Str("\uD800"))) }.also { assertEquals(RejectReason.INVALID_STRING, it.reason) }
            assertFailsWith<DocumentRejectedException> { store.search(query("k", JsonQueryValue.Json("[1,"))) }
            assertEquals(QueryRejectedException.Reason.NOT_INDEXED, assertFailsWith<QueryRejectedException> { store.search(query("k", JsonQueryValue.Json("[1]"))) }.reason)
            store.insert("""{"k":0}""")
            assertEquals(1, store.search(query("k", JsonQueryValue.Json("0e3000000000"))).all().size)
            assertEquals(1, store.search(query("k", JsonQueryValue.Num(BigDecimal("-0.000")))).all().size)
        }
    }

    @Test
    fun `long strings and numbers are rechecked`() {
        for (t in listOf(1024, 64 * 1024)) {
            engine(t).use { store ->
                val long = "p".repeat(16) + "q".repeat(100)
                val longNumber = "1" + "2".repeat(200)
                store.insert("""{"s":"$long","n":$longNumber}""")
                assertEquals(1, store.search(query("s", JsonQueryValue.Str(long))).all().size)
                assertEquals(0, store.search(query("s", JsonQueryValue.Str(long + "q"))).all().size)
                assertEquals(1, store.search(query("n", JsonQueryValue.Json("$longNumber.000"))).all().size)
                val stringChunks = "z".repeat(3 * t + 7)
                store.insert("""[{"s":"$stringChunks"}]""")
                assertEquals(listOf(2L to 1L), store.search(query("s", JsonQueryValue.Str(stringChunks))).all().map { it.first to it.second })
            }
        }
    }

    @Test
    fun `the suspending facade and flows return the same hits`() = runBlocking {
        JsonStore.open(TestEngines.DIR, EngineOptions(CreationOptions(fragmentBytes = 64), StoreOptions(encoderBudgetBytes = 1024), log = TestEngines.LOG), SimulatedFileSystemIo()).use { store ->
            val first = store.insert("""{"k":[1,2,{"k":1}]}""")
            val second = store.insert(InputStream.nullInputStream().let { """{"k":1}""".byteInputStream() })
            assertEquals(first.raw + 1, second.raw)
            val hits = store.search(query("k", JsonQueryValue.Num(1))).use { it.hits.toList().map { h -> h.node.docId.raw to h.node.nodePre } }
            assertEquals(listOf(1L to 0L, 1L to 4L, 2L to 0L), hits)
            assertFalse(hits.isEmpty())
        }
    }
}
