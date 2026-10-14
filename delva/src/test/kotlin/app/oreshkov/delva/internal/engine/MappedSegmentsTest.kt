package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.DISCARDED_SUFFIX
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.log.LogFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Segment files read through a read-only mapping. On a real disk, because mappings are what the
 * simulated file system can't model — and because deleting a mapped file is what Windows refuses unless the mapping was
 * released first.
 */
class MappedSegmentsTest {
    private fun doc(i: Int) = """{"all":true,"sku":"S$i","qty":${i % 50},"note":"${"n".repeat(i % 30)}"}"""

    @Test
    fun `a store reads, compacts and reopens with mapped segments`() {
        val dir = Files.createTempDirectory("delva-mmap")
        try {
            val io = JdkFileSystemIo(mmapReads = true)
            val options = EngineOptions(
                CreationOptions(fragmentBytes = 1024), StoreOptions(encoderBudgetBytes = 4096), keySeed = 5, log = TestEngines.LOG,
                memtableBytes = 16L shl 10, backgroundFlush = false, segmentBlockBytes = 512,
                compaction = CompactionOptions(minMergeSegments = 2, bytesPerSecond = 0),
            )
            val documents: Map<Long, String>
            val live: Set<Path>
            Engine.open(dir, options, io).use { store ->
                for (i in 0 until 600) store.insert(doc(i))
                store.flush()
                assertTrue(store.indexes.flushes > 1, "${store.indexes.flushes} flushes")
                documents = store.documents()
                assertEquals(600, documents.size)
                // Compaction deletes segment files that reads have mapped; on Windows that needs the mapping released.
                store.compact()
                assertTrue(store.indexes.compactor.compactions > 0, "nothing was compacted")
                assertEquals(documents, store.documents())
                assertEquals(12, store.search(KeyValueQuery("qty", JsonQueryValue.Num(7), ResultMode.CONTAINING_ELEMENT, limit = 1000)).use { it.iterator().asSequence().count() })
                live = store.indexes.versions.current().let { it.postingSegments + it.fragmentSegments + it.typeSegments + it.keySegments }
                    .map { it.path.fileName }.toSet()
            }
            // Before the reopen sweeps them: a mapped segment that couldn't be deleted stays in the index directory, or
            // in the graveyard under its discarded name.
            val segments = Files.list(dir.resolve(LogFiles.INDEX_DIR)).use { files -> files.map { it.fileName }.toList() }
            assertEquals(live, segments.toSet(), "the index directory holds just the current segments")
            val discarded = Files.list(dir.resolve(LogFiles.TMP_DIR)).use { files -> files.filter { it.toString().endsWith(DISCARDED_SUFFIX) }.toList() }
            assertEquals(emptyList(), discarded, "segment files left in the graveyard")
            Engine.open(dir, options, io).use { store -> assertEquals(documents, store.documents()) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `mapped reads return the same bytes as ordinary reads`() {
        val dir = Files.createTempDirectory("delva-mmap-io")
        try {
            val path = dir.resolve("f")
            val bytes = ByteArray(5000) { (it * 31).toByte() }
            JdkFileSystemIo().open(path, OpenMode.CREATE_NEW).use { it.write(0, bytes, 0, bytes.size) }
            JdkFileSystemIo(mmapReads = true).open(path, OpenMode.READ).use { file ->
                assertEquals(bytes.size.toLong(), file.size())
                val read = ByteArray(bytes.size)
                assertEquals(bytes.size, file.read(0, read, 0, read.size))
                assertContentEquals(bytes, read)
                val part = ByteArray(100)
                assertEquals(100, file.read(4000, part, 0, 100))
                assertContentEquals(bytes.copyOfRange(4000, 4100), part)
                // Reads are clamped to the file, as positional reads are.
                assertEquals(0, file.read(bytes.size.toLong(), part, 0, 10))
                assertEquals(10, file.read(bytes.size - 10L, part, 0, 100))
            }
            // An empty file has nothing to map and is read the ordinary way.
            val empty = dir.resolve("empty")
            JdkFileSystemIo().open(empty, OpenMode.CREATE_NEW).close()
            JdkFileSystemIo(mmapReads = true).open(empty, OpenMode.READ).use { assertEquals(0L, it.size()) }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** The mapping is reachable from the public API, not only from tests. */
    @Test
    fun `a public store opens with mapped reads`() {
        val dir = Files.createTempDirectory("delva-mmap-public")
        try {
            val options = StoreOptions(encoderBudgetBytes = 4096, mmapReads = true)
            val ids = JsonStore.create(dir, CreationOptions(fragmentBytes = 1024), options).use { store ->
                val blocking = store.blocking()
                val ids = (0 until 200).map { blocking.insert(doc(it)) }
                assertEquals(4, blocking.search(KeyValueQuery("qty", JsonQueryValue.Num(7), ResultMode.CONTAINING_ELEMENT, limit = 100)).use { it.iterator().asSequence().count() })
                ids
            }
            // Reopening reads the sealed files the first store left behind, through mappings again.
            JsonStore.open(dir, options).use { store ->
                assertEquals(doc(3), store.blocking().search(KeyValueQuery("sku", JsonQueryValue.Str("S3"))).use { it.iterator().next().readJsonAsString() })
                assertTrue(store.blocking().delete(ids[3]))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
