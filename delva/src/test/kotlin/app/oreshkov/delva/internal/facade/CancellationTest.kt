package app.oreshkov.delva.internal.facade

import app.oreshkov.delva.CompareOperand
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.VectorQuery
import app.oreshkov.delva.internal.engine.Engine
import app.oreshkov.delva.internal.engine.EngineOptions
import app.oreshkov.delva.internal.engine.TestEngines
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.io.uninterruptibly
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A cancelled suspending write reports what happened: it either returns and its change is visible, or throws and changed
 * nothing. A coroutine without a Job can't be cancelled, and its calls run to the end.
 */
class CancellationTest {
    /** Three keyed documents, the first two untyped and the third of type `S`. */
    private class Fixture(val a: DocId, val b: DocId, val nodeA: NodeRef, val nodeB: NodeRef)

    private class Case(val name: String, val write: suspend JsonStore.(Fixture) -> Any?)

    private val cases = listOf(
        Case("insert(String)") { insert("""{"id":"n","all":true}""") },
        Case("insert(InputStream)") { insert("""{"id":"n","all":true}""".byteInputStream()) },
        Case("replace") { replace(listOf(it.a), """{"id":"a","all":true,"v":2}""") },
        Case("upsert") { upsert("""{"id":"b","all":true,"v":2}""") },
        Case("delete") { delete(it.a) },
        Case("deleteAll") { deleteAll(listOf(it.a, it.b)) },
        Case("assignType") { assignType(it.nodeA, "T") },
        Case("assignTypes") { assignTypes(listOf(it.nodeA to "T", it.nodeB to "U")) },
        Case("clearType") { clearType("S") },
        Case("insertAll") { insertAll(listOf(InsertSource("""{"id":"n","all":true}""".byteInputStream()))) },
    )

    private fun open(fs: SimulatedFileSystemIo): JsonStore =
        JsonStore.open(TestEngines.DIR, EngineOptions(CreationOptions().withDocumentKey("$.id"), log = TestEngines.LOG), fs)

    private fun roots(engine: Engine): List<NodeRef> =
        engine.search(KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT, limit = 100)).use { result ->
            result.iterator().asSequence().map { it.node }.toList()
        }

    /** Every document's JSON and root type. */
    private fun state(engine: Engine): Map<Long, Pair<String, String?>> =
        engine.search(KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT, limit = 100)).use { result ->
            result.iterator().asSequence().associate { it.node.docId.raw to (it.readJsonAsString(Int.MAX_VALUE) to engine.typeOf(it.node)) }
        }

    private fun fixture(store: JsonStore): Fixture {
        val blocking = store.blocking()
        val a = blocking.insert("""{"id":"a","all":true}""")
        val b = blocking.insert("""{"id":"b","all":true}""")
        val c = blocking.insert("""{"id":"c","all":true}""")
        val nodes = roots((store as CoroutineJsonStore).core).associateBy { it.docId }
        blocking.assignType(nodes.getValue(c), "S")
        return Fixture(a, b, nodes.getValue(a), nodes.getValue(b))
    }

    @Test
    fun `a write cancelled while it syncs returns its result, and its change is visible`() {
        for (case in cases) {
            val fs = SimulatedFileSystemIo()
            open(fs).use { store ->
                val engine = (store as CoroutineJsonStore).core
                val fixture = fixture(store)
                val before = state(engine)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                fs.beforeForce { path ->
                    if (path.parent.fileName.toString() == "control" && entered.count > 0) {
                        entered.countDown()
                        uninterruptibly { release.await() }
                    }
                }
                var outcome: Result<Any?>? = null
                runBlocking {
                    val job = launch(Dispatchers.Default) { outcome = runCatching { store.(case.write)(fixture) } }
                    try {
                        assertTrue(entered.await(10, TimeUnit.SECONDS), "${case.name}: the write syncs")
                        job.cancel()
                    } finally {
                        release.countDown()
                    }
                    job.join()
                }
                val result = outcome!!
                assertTrue(result.isSuccess, "${case.name}: the started write returns, but threw ${result.exceptionOrNull()}")
                assertNotEquals(before, state(engine), "${case.name}: and its change is visible")
            }
        }
    }

    @Test
    fun `a write cancelled before it starts throws and changes nothing`() {
        for (case in cases) {
            open(SimulatedFileSystemIo()).use { store ->
                val engine = (store as CoroutineJsonStore).core
                val fixture = fixture(store)
                val before = state(engine)
                var outcome: Result<Any?>? = null
                runBlocking {
                    launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        cancel()
                        outcome = runCatching { store.(case.write)(fixture) }
                    }.join()
                }
                assertIs<CancellationException>(outcome!!.exceptionOrNull(), case.name)
                assertEquals(before, state(engine), case.name)
            }
        }
    }

    /** Runs [block] in a coroutine whose context has no Job, as `suspend fun main` does, and returns its outcome. */
    private fun <T> withoutJob(block: suspend () -> T): Result<T> {
        val done = CountDownLatch(1)
        var outcome: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) {
            outcome = it
            done.countDown()
        })
        assertTrue(done.await(10, TimeUnit.SECONDS), "the coroutine finishes")
        return outcome!!
    }

    @Test
    fun `a write from a coroutine without a Job runs`() {
        for (case in cases) {
            open(SimulatedFileSystemIo()).use { store ->
                val engine = (store as CoroutineJsonStore).core
                val fixture = fixture(store)
                val before = state(engine)
                val result = withoutJob { store.(case.write)(fixture) }
                assertTrue(result.isSuccess, "${case.name}: threw ${result.exceptionOrNull()}")
                assertNotEquals(before, state(engine), "${case.name}: and its change is visible")
            }
        }
    }

    @Test
    fun `reads that check for cancellation run from a coroutine without a Job`() {
        val creation = CreationOptions().withVectorField(VectorField("$.v", 2, VectorMetric.EUCLIDEAN))
        JsonStore.open(TestEngines.DIR, EngineOptions(creation, log = TestEngines.LOG), SimulatedFileSystemIo()).use { store ->
            store.blocking().insert("""{"v":[1,0]}""")
            store.blocking().insert("""{"v":[3,0]}""")
            val hits = withoutJob { store.search(VectorQuery(floatArrayOf(0f, 0f), k = 1)).use { r -> r.hits.map { it.distance } } }
            assertEquals(listOf(1f), hits.getOrThrow(), "vector search")
            val equal = withoutJob { store.isEqual(CompareOperand.JsonStream("[1]".byteInputStream()), CompareOperand.JsonStream("[1]".byteInputStream())) }
            assertTrue(equal.getOrThrow(), "isEqual of two streams")
            assertEquals(emptyList(), withoutJob { store.verify() }.getOrThrow().issues, "verify")
        }
    }
}
