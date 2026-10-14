package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogFiles
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * A search or select that fails after it pinned an index version releases the version exactly once: the failure
 * releases it, and nothing the garbage collector finds later releases it again (code review 2026-09-23, finding 1).
 */
class ResultReleaseTest {
    private fun options() = EngineOptions(
        CreationOptions(fragmentBytes = 64), StoreOptions(encoderBudgetBytes = 1024), sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 11,
        log = TestEngines.LOG, backgroundFlush = false, memtableBytes = 16L shl 10, blockCacheBytes = 0,
    )

    /** Fails reads of index segments while [failing] is set. */
    private class FailingIndexReads(private val fs: SimulatedFileSystemIo) : FileSystemIo by fs {
        @Volatile
        var failing = false

        override fun open(path: Path, mode: OpenMode): FileIo {
            val file = fs.open(path, mode)
            if (path.parent?.fileName?.toString() != LogFiles.INDEX_DIR) return file
            return object : FileIo by file {
                override fun read(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
                    if (failing) throw IOException("simulated read failure on $path")
                    return file.read(position, destination, offset, length)
                }
            }
        }
    }

    @Test
    fun `a failed search or select doesn't release its version a second time`() {
        val fs = FailingIndexReads(SimulatedFileSystemIo())
        Engine.open(TestEngines.DIR, options(), fs).use { store ->
            val docId = List(20) { store.insert("""{"a":$it,"b":"x$it"}""") }.first()
            store.flush()
            val version = store.indexes.versions.current()
            val searches = listOf(
                { store.search(JsonPathQuery(JsonPath.compile("$[?@.a == 3]"))).close() },
                { store.search(KeyValueQuery("b", JsonQueryValue.Str("x3"))).use { it.iterator().hasNext() } },
                { store.select(NodeRef(docId, 0), JsonPath.compile("$.a")).close() },
            )
            for (search in searches) {
                fs.failing = true
                try {
                    assertFails { search() }
                } finally {
                    fs.failing = false
                }
            }
            // A result registered with the Cleaner before its constructor failed is garbage collected here.
            repeat(20) {
                System.gc()
                Thread.sleep(10)
            }
            assertEquals(version, store.indexes.versions.current())
            assertTrue(version.tryRetain(), "the current version was released too often")
            version.release()
            assertEquals(20, store.search(JsonPathQuery(JsonPath.compile("$.a"), scan = ScanPolicy.ALLOW_SCAN)).use { it.iterator().asSequence().count() })
        }
    }
}
