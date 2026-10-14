package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.Durability
import app.oreshkov.delva.InsertOutcome
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.compress.CompressionOptions
import app.oreshkov.delva.internal.facade.CoroutineJsonStore
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogOptions
import app.oreshkov.delva.internal.lsm.TermSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Large lanes, memory budgets and back-pressure, `insertAll` and cancellation. */
class IngestLanesTest {
    private val dir = TestEngines.DIR

    private fun options(
        t: Int = 256,
        threshold: Long = 2048,
        lanes: Int = 2,
        log: LogOptions = TestEngines.LOG,
        memtableBytes: Long = 64L shl 20,
        memtableBudgetBytes: Long? = null,
        ingestBudgetBytes: Long = 64L shl 20,
        background: Boolean = false,
        durability: Durability = Durability.SyncEachBatch,
        maxBatchRequests: Int = 512,
        sorterBytes: Int = 4096,
        compaction: Boolean = true,
        compression: CompressionOptions = CompressionOptions(),
    ) = EngineOptions(
        CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 4096), durability = durability),
        sorterBytes = sorterBytes,
        keyArenaBytes = 4096,
        smallInsertThreshold = threshold,
        ingestBudgetBytes = ingestBudgetBytes,
        largeLanes = lanes,
        keySeed = 5,
        log = log,
        maxBatchRequests = maxBatchRequests,
        memtableBytes = memtableBytes,
        memtableBudgetBytes = memtableBudgetBytes,
        backgroundFlush = background,
        segmentBlockBytes = 512,
        compression = compression,
        compaction = CompactionOptions(enabled = compaction),
    )

    private fun doc(i: Int) = """{"all":true,"i":$i,"hot":"yes","items":[{"k":1,"hot":"yes"},{"k":2}]}"""

    private fun bigDoc(tag: Int, items: Int) =
        """{"all":true,"big":$tag,"items":[${(0 until items).joinToString(",") { """{"k":$it,"hot":"yes","tag":"t${it % 13}","s":"${"y".repeat(it % 40)}"}""" }}]}"""

    private fun Engine.hotHits(): List<Pair<Long, Long>> =
        search(KeyValueQuery("hot", JsonQueryValue.Str("yes"), ResultMode.CONTAINING_ELEMENT, limit = 100_000)).use { result ->
            result.iterator().asSequence().map { it.node.docId.raw to it.node.nodePre }.toList()
        }

    private fun laneFiles(fs: SimulatedFileSystemIo): List<String> =
        fs.files().filter { it.parent?.fileName?.toString() == LogFiles.DATA_DIR }.map { it.fileName.toString() }

    /** A stream of [bytes] in 256-byte reads that runs [atMiddle] once when half of it was read. */
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
            val n = minOf(len, 256, bytes.size - position)
            System.arraycopy(bytes, position, b, off, n)
            position += n
            return n
        }
    }

    @Test
    fun `large inserts stream on lanes of their own, and more wait for a free lane`() {
        val fs = SimulatedFileSystemIo()
        val docs = (1..4).map { bigDoc(it, 400 + it) }
        Engine.open(dir, options(threshold = 0, lanes = 3), fs).use { store ->
            val allStreaming = CountDownLatch(3)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(4)
            try {
                val first = (0 until 3).map { i ->
                    executor.submit(
                        Callable {
                            store.insert(
                                MiddleStream(docs[i].toByteArray()) {
                                    allStreaming.countDown()
                                    release.await()
                                },
                            ).raw
                        },
                    )
                }
                assertTrue(allStreaming.await(30, TimeUnit.SECONDS), "three large inserts stream at once")
                assertEquals(0, store.lanes.freeCount)
                assertEquals(listOf("L1", "L2", "L3"), laneFiles(fs).map { it.substringAfter("lane-").substringBefore('-') }.distinct().sorted())
                val fourth = executor.submit(Callable { store.insert(docs[3]).raw })
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (store.lanes.waits == 0L && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals(1, store.lanes.waits, "the fourth insert waits for a lane")
                assertFalse(fourth.isDone)
                release.countDown()
                val ids = first.map { it.get(30, TimeUnit.SECONDS) } + fourth.get(30, TimeUnit.SECONDS)
                assertEquals(3, store.lanes.freeCount)
                val expected = ids.zip(docs).toMap()
                assertEquals(expected, store.documents())
                val hits = store.hotHits()
                store.close()
                fs.restart()
                Engine.open(dir, options(threshold = 0, lanes = 3), fs).use { reopened ->
                    assertEquals(expected, reopened.documents())
                    assertEquals(hits, reopened.hotHits())
                }
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `small inserts are acknowledged while every lane is held by a blocked client`() {
        val fs = SimulatedFileSystemIo()
        // Small documents stay far below the threshold (their postings count too); the large ones pass it early.
        Engine.open(dir, options(threshold = 8192, lanes = 1), fs).use { store ->
            val blocked = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(3)
            try {
                val large = executor.submit(
                    Callable {
                        store.insert(
                            MiddleStream(bigDoc(1, 300).toByteArray()) {
                                blocked.countDown()
                                release.await()
                            },
                        ).raw
                    },
                )
                assertTrue(blocked.await(30, TimeUnit.SECONDS))
                // A second large insert waits for the lane while holding its buffer.
                val waiting = executor.submit(Callable { store.insert(bigDoc(2, 300)).raw })
                val small = executor.submit(Callable { (0 until 50).map { store.insert(doc(it)).raw } })
                val smallIds = small.get(30, TimeUnit.SECONDS)
                assertFalse(large.isDone)
                assertFalse(waiting.isDone)
                assertEquals(50, store.documents().keys.intersect(smallIds.toSet()).size, "small inserts are visible while the lane is held")
                release.countDown()
                large.get(30, TimeUnit.SECONDS)
                waiting.get(30, TimeUnit.SECONDS)
                assertEquals(52, store.documents().size)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `a large lane syncs every largeLaneSyncBytes, except without durability`() {
        val json = bigDoc(1, 3000)
        for (durability in listOf(Durability.SyncEachBatch, Durability.None)) {
            val fs = SimulatedFileSystemIo()
            val log = LogOptions(laneFileBytes = 1L shl 20, controlFileBytes = 1L shl 20, preallocateBytes = 0, writeBufferBytes = 4096, largeLaneSyncBytes = 16L shl 10)
            Engine.open(dir, options(threshold = 0, log = log, durability = durability), fs).use { store ->
                store.insert(json)
                val laneBytes = laneFiles(fs).sumOf { fs.bytes(TestEngines.DIR.resolve(LogFiles.DATA_DIR).resolve(it)).size }
                if (durability is Durability.None) {
                    assertEquals(0, store.largeLaneSyncs)
                } else {
                    assertTrue(store.largeLaneSyncs >= laneBytes / (16L shl 10) - 1, "${store.largeLaneSyncs} syncs for $laneBytes lane bytes")
                }
            }
        }
    }

    @Test
    fun `a large document spans rolled lane files`() {
        val fs = SimulatedFileSystemIo()
        val log = LogOptions(laneFileBytes = 16L shl 10, controlFileBytes = 1L shl 20, preallocateBytes = 0, writeBufferBytes = 4096)
        val json = bigDoc(1, 2000)
        val hits: List<Pair<Long, Long>>
        val docId: Long
        Engine.open(dir, options(threshold = 0, log = log, compression = CompressionOptions.DISABLED), fs).use { store ->
            docId = store.insert(json).raw
            hits = store.hotHits()
        }
        assertTrue(laneFiles(fs).count { it.startsWith("lane-L1") } > 5, "lane files ${laneFiles(fs)}")
        fs.restart()
        Engine.open(dir, options(threshold = 0, log = log, compression = CompressionOptions.DISABLED), fs).use { store ->
            assertEquals(mapOf(docId to json), store.documents())
            assertEquals(hits, store.hotHits())
            assertEquals(0, store.damagedDocIds.size)
        }
    }

    /** Holds segment writes of flushes until [open] is called. */
    private class GatedFlushes(private val fs: SimulatedFileSystemIo) : FileSystemIo by fs {
        private val gate = CountDownLatch(1)

        @Volatile
        var held = 0

        fun open() = gate.countDown()

        override fun open(path: Path, mode: OpenMode): FileIo {
            if (mode == OpenMode.CREATE_NEW && path.parent?.fileName?.toString() == LogFiles.INDEX_DIR) {
                held++
                gate.await()
            }
            return fs.open(path, mode)
        }
    }

    @Test
    fun `inserts wait for memtable room while flushes can't keep up, and continue after them`() {
        for (large in listOf(false, true)) {
            val fs = SimulatedFileSystemIo()
            val gated = GatedFlushes(fs)
            val options = options(threshold = if (large) 0 else 1L shl 20, memtableBytes = 16L shl 10, memtableBudgetBytes = 64L shl 10, background = true)
            Engine.open(dir, options, gated).use { store ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val expected = HashMap<Long, String>()
                    val work = executor.submit {
                        if (large) {
                            val json = bigDoc(1, 3000)
                            expected[store.insert(json).raw] = json
                        } else {
                            repeat(400) { i -> doc(i).let { expected[store.insert(it).raw] = it } }
                        }
                    }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    while (store.indexes.memtableWaits == 0L && System.nanoTime() < deadline) Thread.sleep(5)
                    assertTrue(store.indexes.memtableWaits > 0, "large=$large: an insert waits for room")
                    Thread.sleep(100)
                    assertFalse(work.isDone, "large=$large: the insert is held back")
                    assertTrue(gated.held > 0)
                    assertTrue(store.indexes.memtableBytes <= options.memtableBudgetBytes + (64L shl 10), "large=$large: ${store.indexes.memtableBytes} memtable bytes")
                    gated.open()
                    work.get(60, TimeUnit.SECONDS)
                    store.flush()
                    assertEquals(expected, store.documents(), "large=$large")
                    assertTrue(store.indexes.peakMemtableBytes <= options.memtableBudgetBytes + (64L shl 10), "large=$large: peak ${store.indexes.peakMemtableBytes}")
                } finally {
                    gated.open()
                    executor.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `back-pressure never deadlocks, whatever the limits and flush mode`() {
        for (background in listOf(false, true)) {
            for (memtable in listOf(0L, 4L shl 10)) {
                val fs = SimulatedFileSystemIo()
                val options = options(threshold = 1024, lanes = 2, memtableBytes = memtable, memtableBudgetBytes = 8L shl 10, background = background)
                Engine.open(dir, options, fs).use { store ->
                    val executor = Executors.newFixedThreadPool(4)
                    try {
                        val futures = (0 until 4).map { w ->
                            executor.submit(
                                Callable {
                                    (0 until 40).associate { i ->
                                        val json = if (i % 10 == 3) bigDoc(w * 100 + i, 200) else doc(w * 100 + i)
                                        store.insert(json).raw to json
                                    }
                                },
                            )
                        }
                        val expected = futures.flatMap { it.get(120, TimeUnit.SECONDS).entries }.associate { it.key to it.value }
                        assertEquals(160, expected.size)
                        assertEquals(expected, store.documents(), "background=$background memtable=$memtable")
                    } finally {
                        executor.shutdownNow()
                    }
                }
            }
        }
    }

    @Test
    fun `the ingest budget bounds how many inserts run at once`() {
        val fs = SimulatedFileSystemIo()
        val probe = options()
        // Room for two inserts' base reservations and a little buffering.
        val budget = 2 * probe.ingestBaseBytes + 8192
        Engine.open(dir, options(ingestBudgetBytes = budget, threshold = 4096), fs).use { store ->
            val started = CountDownLatch(2)
            val release = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(3)
            try {
                val blocked = (0 until 2).map { i ->
                    executor.submit(
                        Callable {
                            store.insert(
                                MiddleStream(doc(i).toByteArray()) {
                                    started.countDown()
                                    release.await()
                                },
                            ).raw
                        },
                    )
                }
                assertTrue(started.await(30, TimeUnit.SECONDS))
                val third = executor.submit(Callable { store.insert(doc(3)).raw })
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (store.budget.waits == 0L && System.nanoTime() < deadline) Thread.sleep(5)
                assertEquals(1, store.budget.waits, "the third insert waits for the budget")
                assertFalse(third.isDone)
                release.countDown()
                (blocked + third).forEach { it.get(30, TimeUnit.SECONDS) }
                assertEquals(3, store.documents().size)
                assertEquals(budget, store.budget.availableBytes, "everything given back")
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `promoted inserts and ingests that grew aren't pooled`() {
        Engine.open(dir, options(threshold = 0), SimulatedFileSystemIo()).use { store ->
            store.insert(bigDoc(1, 2000))
            assertEquals(0, store.pooledIngests)
        }
        Engine.open(dir, options(), SimulatedFileSystemIo()).use { store ->
            repeat(10) { store.insert(doc(it)) }
            assertEquals(1, store.pooledIngests)
        }
    }

    @Test
    fun `insertAll logs small documents in shared group commits and reports each outcome`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(threshold = 2048, maxBatchRequests = 5, background = true), fs).use { store ->
            val jsons = (0 until 23).map { i ->
                when {
                    i == 7 -> """{"all":true,"dup":1,"dup":2}"""
                    i == 11 -> bigDoc(i, 300)
                    else -> doc(i)
                }
            }
            val batchesBefore = store.writer.batches
            val outcomes = store.insertAll(jsons.map { InsertSource(it.byteInputStream()) })
            assertEquals(23, outcomes.size)
            val rejected = assertIs<InsertOutcome.Rejected>(outcomes[7])
            assertEquals(RejectReason.DUPLICATE_KEY, rejected.exception.reason)
            val ids = outcomes.mapNotNull { (it as? InsertOutcome.Inserted)?.docId?.raw }
            assertEquals(22, ids.size)
            assertEquals(ids.sorted(), ids, "docIds follow the source order")
            val expected = outcomes.withIndex().mapNotNull { (i, o) -> (o as? InsertOutcome.Inserted)?.let { it.docId.raw to jsons[i] } }.toMap()
            assertEquals(expected, store.documents())
            // 21 small documents in groups of at most 5, plus BEGIN and COMMIT of the promoted one.
            assertTrue(store.writer.batches - batchesBefore in 5L..9L, "${store.writer.batches - batchesBefore} batches")
        }
    }

    @Test
    fun `insertAll commits early when the ingest budget runs out, and a failing stream leaves the earlier groups`() {
        val fs = SimulatedFileSystemIo()
        val probe = options()
        Engine.open(dir, options(ingestBudgetBytes = 3 * probe.ingestBaseBytes, threshold = 1L shl 20), fs).use { store ->
            val outcomes = store.insertAll((0 until 10).map { InsertSource(doc(it).byteInputStream()) })
            assertEquals(10, outcomes.count { it is InsertOutcome.Inserted })
            assertEquals(probe.ingestBaseBytes * 3, store.budget.availableBytes)

            val before = store.documents().keys
            val sources = (0 until 6).map { i -> if (i == 4) MiddleStream(doc(i).toByteArray()) { throw IOException("gone") } else doc(i).byteInputStream() }
            assertFailsWith<IOException> { store.insertAll(sources.map(::InsertSource)) }
            val after = store.documents().keys - before
            assertTrue(after.size in 2..4, "groups before the failure are visible: $after")
            assertEquals(probe.ingestBaseBytes * 3, store.budget.availableBytes)
            assertEquals(0, store.registry.current().visibility.inFlightCount)
        }
    }

    @Test
    fun `cancelling a suspended insert stops reading and leaves nothing visible`() {
        for (threshold in listOf(1L shl 20, 0L)) {
            val fs = SimulatedFileSystemIo()
            val store = JsonStore.open(dir, options(threshold = threshold), fs)
            store.use {
                val engine = (store as CoroutineJsonStore).core
                val reading = CountDownLatch(1)
                runBlocking {
                    val insert = async(Dispatchers.Default) {
                        store.insert(
                            object : InputStream() {
                                private val head = """{"all":true,"items":[""".toByteArray()
                                private var position = 0L

                                override fun read(): Int = throw UnsupportedOperationException()

                                override fun read(b: ByteArray, off: Int, len: Int): Int {
                                    // An endless array of objects, slowly.
                                    reading.countDown()
                                    Thread.sleep(1)
                                    val chunk = if (position < head.size) head else """{"hot":"yes"},""".toByteArray()
                                    val n = minOf(len, chunk.size)
                                    System.arraycopy(chunk, 0, b, off, n)
                                    position += n
                                    return n
                                }
                            },
                        )
                    }
                    reading.await()
                    Thread.sleep(50)
                    // A promoted insert holds a lane; under load 50 ms may not be enough input to promote it.
                    if (threshold == 0L) {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                        while (engine.lanes.freeCount == engine.lanes.count && System.nanoTime() < deadline) Thread.sleep(5)
                    }
                    insert.cancel()
                    assertFailsWith<CancellationException> { insert.await() }
                }
                val docId = engine.insert(doc(1)).raw
                assertEquals(setOf(docId), engine.documents().keys, "threshold=$threshold")
                assertEquals(0, engine.registry.current().visibility.inFlightCount)
                if (threshold == 0L) assertTrue(engine.registry.current().visibility.isAborted(docId - 1), "the promoted insert was aborted")
            }
        }
    }

    @Test
    fun `searches over many runs and segments stream their postings and read the same hits`() {
        val json = bigDoc(1, 6000)
        val reference = Engine.open(dir, options(threshold = 0, sorterBytes = 1 shl 20), SimulatedFileSystemIo()).use { store ->
            store.insert(json)
            store.insert(doc(2))
            store.hotHits() to store.documents()
        }
        val fs = SimulatedFileSystemIo()
        // Without compaction, which would merge the segments this test needs many of (CompactionEngineTest covers it).
        Engine.open(dir, options(threshold = 0, memtableBytes = 8L shl 10, background = false, compaction = false), fs).use { store ->
            store.insert(json)
            store.insert(doc(2))
            store.flush()
            val version = store.indexes.versions.current()
            assertTrue(version.postingSegments.size > TermSources.RELEASE_ABOVE_SOURCES, "${version.postingSegments.size} posting segments")
            assertTrue(store.indexes.flushes > 2L * TermSources.RELEASE_ABOVE_SOURCES)
            assertTrue(store.indexes.fragmentMerges > 0)
            // O(log n): with flushes of varying sizes the tiering keeps about log₂(flushes) segments, not exactly.
            val bound = 2 * (64 - java.lang.Long.numberOfLeadingZeros(store.indexes.flushes)) + 2
            assertTrue(version.fragmentSegments.size <= bound, "${version.fragmentSegments.size} fragment segments after ${store.indexes.flushes} flushes")
            assertEquals(reference.first, store.hotHits())
            assertEquals(reference.second, store.documents())
            // Merged-away segment files are gone once no version holds them.
            val live = (version.postingSegments + version.fragmentSegments).map { it.fileNo }.toSet()
            val files = fs.files().filter { it.parent?.fileName?.toString() == LogFiles.INDEX_DIR }.mapNotNull { LogFiles.parseSegment(it.fileName.toString())?.first }
            assertEquals(live, files.toSet())
        }
        fs.restart()
        Engine.open(dir, options(threshold = 0, memtableBytes = 8L shl 10, compaction = false), fs).use { store ->
            assertEquals(reference.first, store.hotHits())
            assertEquals(0, store.replayedCommits)
        }
    }

    @Test
    fun `small inserts keep their latency while large inserts stream`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(threshold = 4096, lanes = 2, background = true, memtableBytes = 1L shl 20), fs).use { store ->
            fun smallLatencies(n: Int): LongArray = LongArray(n) { i ->
                val start = System.nanoTime()
                store.insert(doc(i))
                System.nanoTime() - start
            }.also { it.sort() }
            smallLatencies(300) // warm-up
            val quiet = smallLatencies(500)
            val executor = Executors.newFixedThreadPool(2)
            val stop = java.util.concurrent.atomic.AtomicBoolean()
            try {
                val large = (0 until 2).map { w -> executor.submit { var i = 0; while (!stop.get()) store.insert(bigDoc(w * 1000 + i++, 1500)) } }
                Thread.sleep(50)
                val busy = smallLatencies(500)
                stop.set(true)
                large.forEach { it.get(60, TimeUnit.SECONDS) }
                val p99Quiet = quiet[quiet.size * 99 / 100]
                val p99Busy = busy[busy.size * 99 / 100]
                println("small insert p99: ${p99Quiet / 1000} µs alone, ${p99Busy / 1000} µs with 2 large inserts streaming")
                // Generous: catches a small insert waiting for a whole large insert, not scheduler noise.
                assertTrue(p99Busy < maxOf(50 * p99Quiet, TimeUnit.MILLISECONDS.toNanos(250)), "p99 ${p99Busy / 1000} µs vs ${p99Quiet / 1000} µs")
            } finally {
                stop.set(true)
                executor.shutdownNow()
            }
        }
    }
}

