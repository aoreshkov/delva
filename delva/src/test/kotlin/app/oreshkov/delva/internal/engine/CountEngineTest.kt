package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.StoreClosedException
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.query.PathSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/** Counting hits without reading them. The comparisons with the oracles are in [EngineModelTest] and [JsonPathSearchTest]. */
class CountEngineTest {
    private val options = EngineOptions(CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024), log = TestEngines.LOG)

    // [0]{"all":[1]true,"i":[2]i,"items":[3][[4]{"k":[5]1},[6]{"k":[7]2}]}
    private fun doc(i: Int) = """{"all":true,"i":$i,"items":[{"k":1},{"k":2}]}"""

    private val all = KeyValueQuery("all", JsonQueryValue.Bool(true), limit = 3, maxBytes = 1)
    private val items = JsonPathQuery(JsonPath.compile("$.items[?@.k == 2]"), ResultMode.CONTAINING_ELEMENT, limit = 2)

    @Test
    fun `count is the number of hits whatever the page limits, and honours the cursor, type, mode and nested filter`() {
        TestEngines.open(options, SimulatedFileSystemIo()).use { store ->
            val docIds = (0 until 10).map { store.insert(doc(it), if (it % 2 == 0) mapOf("$.items[1]" to "Even") else emptyMap()).raw }
            store.delete(DocId(docIds[3]))
            assertEquals(9, store.count(all))
            assertEquals(9, store.count(items))
            assertEquals(5, store.count(items.copy(type = "Even")))
            // Each document holds two objects with a "k": one hit each as containing objects, one per document whole.
            val anyItem = JsonPathQuery(JsonPath.compile("$.items[?@.k]"), ResultMode.CONTAINING_ELEMENT, scan = ScanPolicy.ALLOW_SCAN)
            assertEquals(18, store.count(anyItem))
            assertEquals(9, store.count(anyItem.copy(mode = ResultMode.WHOLE_DOCUMENT)))
            // The items aren't nested in one another: keeping the outermost keeps them all.
            assertEquals(18, store.count(anyItem.copy(nested = NestedHits.OUTERMOST_ONLY)))
            // Pages of three, without the byte budget that cuts every page to one hit.
            val cursor = store.search(all.copy(maxBytes = Long.MAX_VALUE)).use { it.nextPage()!!.cursor!! }
            assertEquals(6, store.count(all.copy(after = cursor)))
            assertEquals(9, store.count(all.copy(order = SearchOrder.DESCENDING)))
            for (atMost in 0L..10L) assertEquals(minOf(atMost, 9), store.count(items, atMost), "at most $atMost")
            assertFailsWith<IllegalArgumentException> { store.count(all, -1) }
        }
    }

    @Test
    fun `count stops at atMost without opening the documents after it`() {
        TestEngines.open(options, SimulatedFileSystemIo()).use { store ->
            repeat(20) { store.insert(doc(it)) }
            (store.search(all) as EngineSearchResult).use { result ->
                assertEquals(3, result.countHits(3))
                assertEquals(3, result.executor.stats.documentsOpened)
                // The count continues where it stopped.
                assertEquals(17, result.countHits(Long.MAX_VALUE))
            }
            (store.search(items) as EngineSearchResult).use { result ->
                assertEquals(4, result.countHits(4))
                assertEquals(4, (result.source as PathSearch).documentsEvaluated)
            }
            // A page taken first counts its lookahead hit once.
            (store.search(all.copy(maxBytes = Long.MAX_VALUE)) as EngineSearchResult).use { result ->
                assertEquals(3, result.nextPage()!!.hits.size)
                assertEquals(17, result.countHits(Long.MAX_VALUE))
            }
        }
    }

    @Test
    fun `count refuses what a search refuses`() {
        TestEngines.open(options, SimulatedFileSystemIo()).use { store ->
            store.insert(doc(0))
            val e = assertFailsWith<QueryRejectedException> { store.count(JsonPathQuery(JsonPath.compile("$..k"))) }
            assertEquals(QueryRejectedException.Reason.NEEDS_SCAN, e.reason)
            store.close()
            assertFailsWith<StoreClosedException> { store.count(all) }
        }
    }

    @Test
    fun `the suspending store counts`() = runBlocking {
        JsonStore.open(TestEngines.DIR, options, SimulatedFileSystemIo()).use { store ->
            repeat(5) { store.insert(doc(it)) }
            assertEquals(5, store.count(all))
            assertEquals(2, store.count(items, atMost = 2))
        }
    }
}
