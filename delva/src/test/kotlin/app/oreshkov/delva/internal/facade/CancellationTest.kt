package app.oreshkov.delva.internal.facade

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A cancelled suspending write reports what happened: it either returns and its change is visible, or throws and changed nothing. */
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
}
