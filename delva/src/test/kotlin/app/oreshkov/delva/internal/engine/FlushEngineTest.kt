package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogOptions
import app.oreshkov.delva.internal.lsm.Version
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Memtable flushes, segments, partial replay and index watermarks, on the simulated disk. */
class FlushEngineTest {
    private val dir = TestEngines.DIR

    private fun options(
        t: Int = 256,
        threshold: Long = EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD,
        log: LogOptions = TestEngines.LOG,
        memtableBytes: Long = EngineOptions.DEFAULT_MEMTABLE_BYTES,
        background: Boolean = false,
        writerThread: Boolean = true,
    ) = EngineOptions(
        CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 4096)),
        sorterBytes = 4096,
        smallInsertThreshold = threshold,
        keySeed = 11,
        log = log,
        memtableBytes = memtableBytes,
        backgroundFlush = background,
        segmentBlockBytes = 512,
        startWriterThread = writerThread,
        // Segments as flushes leave them (CompactionEngineTest covers compaction).
        compaction = CompactionOptions(enabled = false),
    )

    private fun doc(i: Int, size: Int = 3) =
        """{"all":true,"i":$i,"hot":"yes","items":[${(0 until size + i % 5).joinToString(",") { """{"k":$it,"hot":"yes","s":"${"x".repeat(it * 7)}"}""" }}]}"""

    private fun bigDoc(items: Int) =
        """{"all":true,"big":true,"items":[${(0 until items).joinToString(",") { """{"k":$it,"hot":"yes","tag":"t${it % 13}","s":"${"y".repeat(it % 40)}"}""" }}]}"""

    /** `(docId, pre)` of every object with `hot = "yes"`. */
    private fun Engine.hotHits(): List<Pair<Long, Long>> =
        search(KeyValueQuery("hot", JsonQueryValue.Str("yes"), ResultMode.CONTAINING_ELEMENT, limit = 100_000)).use { result ->
            result.iterator().asSequence().map { it.node.docId.raw to it.node.nodePre }.toList()
        }

    private fun indexFiles(fs: SimulatedFileSystemIo): List<Path> = fs.files().filter { it.parent?.fileName?.toString() == LogFiles.INDEX_DIR }

    @Test
    fun `flushed documents survive reopening and only the log tail is replayed`() {
        for (windows in listOf(false, true)) {
            val fs = SimulatedFileSystemIo(isWindows = windows)
            val log = LogOptions(laneFileBytes = 1L shl 16, controlFileBytes = 4096, preallocateBytes = 0, writeBufferBytes = 4096)
            val expected = HashMap<Long, String>()
            val hits: List<Pair<Long, Long>>
            Engine.open(dir, options(log = log, threshold = 2048), fs).use { store ->
                repeat(150) { i -> doc(i, if (i % 10 == 0) 60 else 3).let { expected[store.insert(it).raw] = it } }
                store.flush()
                assertEquals(1, store.indexes.flushes)
                assertTrue(store.postings.runCount == 0 && store.fragmentIndex.size == 0, "the active memtable is empty after the flush")
                assertEquals(2, indexFiles(fs).size, "a posting and a fragment segment")
                assertTrue(store.files.deletedControlFiles > 3, "control files before the flush are deleted: ${store.files.deletedControlFiles}")
                assertEquals(expected, store.documents(), "searches read the segments")
                repeat(4) { i -> doc(1000 + i).let { expected[store.insert(it).raw] = it } }
                hits = store.hotHits()
            }
            fs.restart()
            Engine.open(dir, options(log = log, threshold = 2048), fs).use { store ->
                assertEquals(expected, store.documents())
                assertEquals(hits, store.hotHits())
                assertEquals(4, store.replayedCommits, "only the inserts after the flush are replayed")
                assertEquals(0, store.recoveryStats.controlFilesSkipped, "control files before the flush are gone")
                assertTrue(store.recoveryStats.controlRecordsScanned < 150, "${store.recoveryStats.controlRecordsScanned} control records scanned")
            }
        }
    }

    @Test
    fun `recovery reads the same tail however much data was flushed before it`() {
        val scanned = listOf(20, 400).map { count ->
            val fs = SimulatedFileSystemIo()
            val log = LogOptions(laneFileBytes = 1L shl 16, controlFileBytes = 4096, preallocateBytes = 0, writeBufferBytes = 4096)
            Engine.open(dir, options(log = log), fs).use { store ->
                repeat(count) { store.insert(doc(it)) }
                store.flush()
                repeat(3) { store.insert(doc(it)) }
            }
            fs.restart()
            Engine.open(dir, options(log = log), fs).use { store ->
                assertEquals(count + 3, store.documents().size)
                assertEquals(3, store.replayedCommits)
                store.recoveryStats.replayedBytes to store.recoveryStats.controlRecordsScanned
            }
        }
        assertEquals(scanned[0].first, scanned[1].first, "lane bytes replayed")
        assertTrue(scanned[1].second <= scanned[0].second + 4096 / 40, "control records scanned: $scanned")
    }

    @Test
    fun `a crash at any disk call of a flush loses nothing and duplicates nothing`() {
        for (windows in listOf(false, true)) {
            fun workload(store: Engine): Map<Long, String> {
                val docs = HashMap<Long, String>()
                repeat(12) { i -> doc(i, if (i % 4 == 0) 30 else 2).let { docs[store.insert(it).raw] = it } }
                // A promoted insert rejected after its BEGIN: its ABORT goes into the bitmap file.
                assertFailsWith<DocumentRejectedException> { store.insert("""{"all":true,"items":[${(0 until 200).joinToString(",") { """{"hot":"yes","n":$it}""" }}],"all":false}""") }
                repeat(3) { i -> doc(100 + i).let { docs[store.insert(it).raw] = it } }
                return docs
            }

            val dry = SimulatedFileSystemIo(isWindows = windows)
            val flushCalls = Engine.open(dir, options(threshold = 2048), dry).use { store ->
                workload(store)
                val before = dry.calls
                store.flush()
                dry.calls - before
            }
            assertTrue(flushCalls > 10, "$flushCalls disk calls in a flush")
            for (k in 1..flushCalls) {
                val fs = SimulatedFileSystemIo(isWindows = windows)
                val store = Engine.open(dir, options(threshold = 2048), fs)
                val expected = workload(store)
                val hits = store.hotHits()
                fs.crashAfter(k)
                runCatching { store.flush() }
                runCatching { store.close() }
                fs.crash(Random(k))
                val label = "windows=$windows crash at flush call $k of $flushCalls"
                Engine.open(dir, options(threshold = 2048), fs).use { reopened ->
                    assertEquals(expected, reopened.documents(), label)
                    assertEquals(hits, reopened.hotHits(), label)
                    assertEquals(0, reopened.damagedDocIds.size, label)
                    reopened.flush()
                }
                fs.crash(Random(k + 1000))
                Engine.open(dir, options(threshold = 2048), fs).use { reopened ->
                    assertEquals(expected, reopened.documents(), "$label, after a second flush")
                    assertEquals(hits, reopened.hotHits(), "$label, after a second flush")
                    assertEquals(0, reopened.replayedCommits, label)
                    val live = reopened.files.index.segments.map { it.fileNo }.toSet()
                    for (path in indexFiles(fs)) assertTrue(LogFiles.parseSegment(path.fileName.toString())?.first in live, "$label: orphan $path")
                }
            }
        }
    }

    /** A stream of [bytes] that runs [atMiddle] once when half of it was read, in 1 KiB reads. */
    private class MiddleStream(private val bytes: ByteArray, private val atMiddle: () -> Unit) : InputStream() {
        private var position = 0
        private var fired = false

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!fired && position >= bytes.size / 2) {
                fired = true
                atMiddle()
            }
            if (position == bytes.size) return -1
            val n = minOf(len, 1024, bytes.size - position)
            System.arraycopy(bytes, position, b, off, n)
            position += n
            return n
        }
    }

    @Test
    fun `a flush in the middle of a large insert bounds the replay after a crash`() {
        val log = LogOptions(laneFileBytes = 64L shl 20, controlFileBytes = 1L shl 20, preallocateBytes = 0, writeBufferBytes = 4096)
        val json = bigDoc(20_000)
        val reference = Engine.open(dir, options(log = log, threshold = 0), SimulatedFileSystemIo()).use { store ->
            val docId = store.insert(json).raw
            Triple(docId, store.hotHits(), store.documents())
        }
        for (windows in listOf(false, true)) {
            val fs = SimulatedFileSystemIo(isWindows = windows)
            var laneAtFlush = 0L
            lateinit var store: Engine
            store = Engine.open(dir, options(log = log, threshold = 0), fs)
            val lane = { fs.files().first { it.fileName.toString().startsWith("lane-L1") } }
            store.insert(
                MiddleStream(json.toByteArray()) {
                    store.flush()
                    laneAtFlush = fs.bytes(lane()).size.toLong()
                },
            )
            assertEquals(1, store.indexes.flushes)
            val laneBytes = fs.bytes(lane()).size.toLong()
            fs.crash(Random(3))
            Engine.open(dir, options(log = log, threshold = 0), fs).use { reopened ->
                assertEquals(reference.third, reopened.documents())
                assertEquals(reference.second, reopened.hotHits(), "the same hits as without a flush, no duplicates")
                assertEquals(1, reopened.replayedCommits)
                val replayed = reopened.recoveryStats.replayedBytes
                val unflushed = laneBytes - laneAtFlush
                assertTrue(replayed < laneBytes * 3 / 4, "replayed $replayed of $laneBytes lane bytes")
                assertTrue(replayed <= unflushed + 64 * 1024, "replayed $replayed bytes, $unflushed were written after the flush")
                reopened.flush()
            }
            fs.restart()
            Engine.open(dir, options(log = log, threshold = 0), fs).use { reopened ->
                assertEquals(reference.second, reopened.hotHits())
                assertEquals(0, reopened.replayedCommits)
            }
        }
    }

    @Test
    fun `a large insert that ends without COMMIT after a mid-document flush stays hidden`() {
        for (crash in listOf(false, true)) {
            val fs = SimulatedFileSystemIo()
            lateinit var store: Engine
            store = Engine.open(dir, options(threshold = 0), fs)
            val kept = store.insert(doc(1)).raw
            val keptHits = store.hotHits()
            val failing = MiddleStream(bigDoc(3000).toByteArray()) {
                store.flush()
                if (crash) fs.crashAfter(1)
                throw IOException("client went away")
            }
            assertFailsWith<IOException> { store.insert(failing) }
            if (crash) {
                runCatching { store.close() }
                fs.crash(Random(9))
            } else {
                assertEquals(keptHits, store.hotHits(), "the flushed postings of the aborted insert are filtered")
                store.close()
                fs.restart()
            }
            Engine.open(dir, options(threshold = 0), fs).use { reopened ->
                assertEquals(setOf(kept), reopened.documents().keys, "crash=$crash")
                assertEquals(keptHits, reopened.hotHits(), "crash=$crash")
                assertTrue(reopened.registry.current().visibility.isAborted(kept + 1))
                reopened.flush()
            }
            fs.restart()
            Engine.open(dir, options(threshold = 0), fs).use { reopened ->
                assertEquals(keptHits, reopened.hotHits(), "crash=$crash, aborted docIds from the bitmap file")
                assertTrue(reopened.registry.current().visibility.isAborted(kept + 1))
                assertTrue(fs.files().any { it.parent?.fileName?.toString() == LogFiles.BITMAP_DIR })
            }
        }
    }

    @Test
    fun `aborted documents stay hidden when their ABORT is no longer replayed`() {
        val fs = SimulatedFileSystemIo()
        val log = LogOptions(laneFileBytes = 1L shl 16, controlFileBytes = 4096, preallocateBytes = 0, writeBufferBytes = 4096)
        val rejected = """{"all":true,"items":[${(0 until 300).joinToString(",") { """{"n":$it}""" }}],"all":1}"""
        val expected = HashMap<Long, String>()
        Engine.open(dir, options(log = log, threshold = 1024), fs).use { store ->
            expected[store.insert(doc(0)).raw] = doc(0)
            assertFailsWith<DocumentRejectedException> { store.insert(rejected) }
            repeat(200) { i -> doc(i + 1).let { expected[store.insert(it).raw] = it } }
            store.flush()
            assertTrue(store.files.deletedControlFiles > 0, "the control file with the ABORT is deleted")
        }
        fs.restart()
        Engine.open(dir, options(log = log, threshold = 1024), fs).use { store ->
            assertEquals(0, store.recoveryStats.controlFilesSkipped)
            assertEquals(0, store.replayedCommits)
            assertTrue(store.registry.current().visibility.isAborted(2))
            assertEquals(expected, store.documents())
            assertEquals(203, store.insert(doc(999)).raw)
        }
    }

    @Test
    fun `a slow client stream never delays a flush`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(threshold = 0, background = true), fs).use { store ->
            val blocked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val first = store.insert(doc(1)).raw
            val executor = Executors.newFixedThreadPool(2)
            try {
                val insert = executor.submit<Long> {
                    store.insert(
                        MiddleStream(bigDoc(2000).toByteArray()) {
                            blocked.countDown()
                            release.await()
                        },
                    ).raw
                }
                assertTrue(blocked.await(30, TimeUnit.SECONDS))
                val flush = executor.submit { store.flush() }
                flush.get(30, TimeUnit.SECONDS)
                assertFalse(insert.isDone, "the insert is still waiting for its client")
                assertEquals(1, store.indexes.flushes)
                release.countDown()
                val second = insert.get(30, TimeUnit.SECONDS)
                assertEquals(setOf(first, second), store.documents().keys)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `an open search keeps its version while flushes replace memtables, and obsolete segments wait for it`() {
        for (windows in listOf(false, true)) {
            val fs = SimulatedFileSystemIo(isWindows = windows)
            Engine.open(dir, options(memtableBytes = 16L shl 10, background = true), fs).use { store ->
                val expected = HashMap<Long, String>()
                repeat(40) { i -> doc(i).let { expected[store.insert(it).raw] = it } }
                val result = store.search(KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT, limit = 7))
                val first = result.nextPage()!!
                repeat(200) { i -> store.insert(doc(i + 100)) }
                store.flush()
                assertTrue(store.indexes.flushes >= 2)
                val read = (first.hits + result.iterator().asSequence()).associate { it.node.docId.raw to it.readJsonAsString() }
                assertEquals(expected, read, "windows=$windows: the open result reads its snapshot")

                // Retire the newest posting segment while a search pins the version holding it (compaction will).
                val pinned = store.search(KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT, limit = 1))
                val version = store.indexes.versions.current()
                val retired = version.postingSegments.last()
                retired.markObsolete()
                store.indexes.versions.install(Version(version.active, version.frozen, version.postingSegments.dropLast(1), version.fragmentSegments, version.typeSegments, version.keySegments, version.flushedLsn))
                assertTrue(fs.exists(retired.path), "still pinned by the open search")
                assertTrue(pinned.nextPage()!!.hits.isNotEmpty())
                pinned.close()
                result.close()
                assertFalse(fs.exists(retired.path), "deleted once the last version holding it was released")
                assertTrue(retired.deleted)
            }
        }
    }

    @Test
    fun `damaged segments refuse to open or fail the read that needs them`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(), fs).use { store ->
            repeat(30) { store.insert(doc(it, 10)) }
            store.flush()
        }
        val posting = indexFiles(fs).first { it.fileName.toString().startsWith("posting") }
        val bytes = fs.bytes(posting)
        val size = bytes.size.toLong()
        // The restart entry of the term `all = true`: key length 3, "all", tag TRUE.
        val term = byteArrayOf(3, 'a'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 3)
        val at = (0..bytes.size - term.size).first { i -> term.indices.all { bytes[i + it] == term[it] } } + 2L
        fs.flipBit(posting, at, 3)
        fs.restart()
        Engine.open(dir, options(), fs).use { store ->
            val e = runCatching { store.documents() }.exceptionOrNull()
            assertTrue(e is CorruptionException, "a search reading the damaged block fails: $e")
        }
        fs.flipBit(posting, at, 3)
        fs.flipBit(posting, size - 10, 1)
        fs.restart()
        assertFailsWith<CorruptionException> { Engine.open(dir, options(), fs) }
    }

    @Test
    fun `files left by an unfinished flush are deleted on open`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(), fs).use { store ->
            repeat(5) { store.insert(doc(it)) }
            store.flush()
        }
        val orphans = listOf(dir.resolve("index/posting-999999.seg"), dir.resolve("index/frag-000500.seg.tmp"), dir.resolve("bitmap/aborted-999998.dis"), dir.resolve("bitmap/deleted-999997.dis"))
        for (p in orphans) fs.open(p, OpenMode.CREATE_NEW).use { it.write(0, byteArrayOf(1, 2, 3), 0, 3) }
        fs.restart()
        Engine.open(dir, options(), fs).use { store ->
            assertEquals(5, store.documents().size)
            for (p in orphans) assertFalse(fs.exists(p), "$p")
        }
    }

    @Test
    fun `concurrent inserts and searches with background flushes`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(threshold = 4096, memtableBytes = 24L shl 10, background = true), fs).use { store ->
            val executor = Executors.newFixedThreadPool(6)
            val acked = java.util.concurrent.ConcurrentHashMap<Long, String>()
            val stop = java.util.concurrent.atomic.AtomicBoolean()
            try {
                val readers = List(2) {
                    executor.submit {
                        while (!stop.get()) {
                            val before = HashMap(acked)
                            val seen = store.documents()
                            for ((docId, json) in before) assertEquals(json, seen[docId], "acknowledged document $docId")
                        }
                    }
                }
                val writers = List(4) { w ->
                    executor.submit {
                        repeat(150) { i -> doc(w * 1000 + i, if (i % 7 == 0) 40 else 2).let { acked[store.insert(it).raw] = it } }
                    }
                }
                writers.forEach { it.get(120, TimeUnit.SECONDS) }
                stop.set(true)
                readers.forEach { it.get(120, TimeUnit.SECONDS) }
            } finally {
                stop.set(true)
                executor.shutdownNow()
            }
            store.flush()
            assertTrue(store.indexes.flushes > 3, "${store.indexes.flushes} flushes")
            assertEquals(HashMap(acked), store.documents())
        }
        fs.restart()
        Engine.open(dir, options(), fs).use { assertEquals(600, it.documents().size) }
    }
}
