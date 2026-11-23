package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.query.PathSearch
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A search can read many documents between two hits, so the suspending callers stop it at a checkpoint the engine
 * runs before each candidate document, not only at the hits.
 */
class SearchCheckpointTest {
    private fun options(creation: CreationOptions = CreationOptions()) = EngineOptions(
        creation, StoreOptions(), log = TestEngines.LOG, backgroundFlush = false, memtableBytes = 16L shl 10,
    )

    /** A hit in the first and the last document, and [between] documents without one in between. */
    private fun Engine.insertSparse(between: Int) {
        insert("""{"hit":"first"}""")
        repeat(between) { insert("""{"n":$it}""") }
        insert("""{"hit":"last"}""")
    }

    @Test
    fun `a collector cancelled between hits stops the scan before the next document`() {
        TestEngines.open(options()).use { store ->
            store.insertSparse(500)
            store.flush()
            for (order in SearchOrder.entries) {
                val query = JsonPathQuery(JsonPath.compile("$.hit"), scan = ScanPolicy.ALLOW_SCAN, order = order)
                store.search(query).use { result ->
                    var thrown: Throwable? = null
                    var hits = 0
                    runBlocking {
                        launch {
                            thrown = runCatching {
                                result.hits.collect {
                                    hits++
                                    cancel()
                                }
                            }.exceptionOrNull()
                        }.join()
                    }
                    assertIs<CancellationException>(thrown, "$order")
                    assertEquals(1, hits, "$order")
                    val evaluated = ((result as EngineSearchResult).source as PathSearch).documentsEvaluated
                    assertTrue(evaluated < 5, "$order: the scan read $evaluated documents after its collector was cancelled")
                }
            }
        }
    }

    @Test
    fun `a count runs its checkpoint before each candidate, and stops where it throws`() {
        TestEngines.open(options(CreationOptions().withDocumentKey("$.k"))).use { store ->
            repeat(200) { store.insert("""{"k":"$it","g":${it % 2}}""") }
            store.flush()
            val queries = listOf<Pair<String, (() -> Unit) -> Long>>(
                "JSONPath scan" to { store.count(JsonPathQuery(JsonPath.compile("$.missing"), scan = ScanPolicy.ALLOW_SCAN), Long.MAX_VALUE, it) },
                "key-value" to { store.count(KeyValueQuery("g", JsonQueryValue.Num(BigDecimal.ONE)), Long.MAX_VALUE, it) },
                "key order" to { store.count(DocumentKeyQuery(), Long.MAX_VALUE, it) },
            )
            for ((name, count) in queries) {
                var checks = 0
                val counted = count { checks++ }
                assertTrue(checks >= counted && checks >= 100, "$name: $checks checks for $counted hits")
                checks = 0
                val e = assertFailsWith<IllegalStateException>(name) { count { if (++checks == 10) error("stop") } }
                assertEquals("stop", e.message)
                assertEquals(10, checks, name)
            }
        }
    }
}
