package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.CreationOptionsMismatchException
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.Durability
import app.oreshkov.delva.EngineFailedException
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreLockedException
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.log.LogOptions
import app.oreshkov.delva.internal.log.ManifestStore
import app.oreshkov.delva.internal.log.RecordReader
import app.oreshkov.delva.internal.lsm.ByteCache
import app.oreshkov.delva.internal.lsm.FragmentEntry
import app.oreshkov.delva.internal.lsm.FragmentIndex
import app.oreshkov.delva.internal.store.LogFragmentStore
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** The log, the writer and recovery by full replay, on the simulated disk. */
class DurableEngineTest {
    private val dir = TestEngines.DIR

    private fun options(
        t: Int = 256,
        threshold: Long = EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD,
        durability: Durability = Durability.SyncEachBatch,
        log: LogOptions = TestEngines.LOG,
        hashTerms: Boolean = true,
    ) = EngineOptions(
        CreationOptions(fragmentBytes = t, hashTerms = hashTerms), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 4096), durability = durability),
        sorterBytes = 4096,
        smallInsertThreshold = threshold,
        keySeed = 7,
        log = log,
    )

    private fun doc(i: Int, size: Int = 3) =
        """{"all":true,"i":$i,"items":[${(0 until size + i % 5).joinToString(",") { """{"k":$it,"s":"${"x".repeat(it * 7)}"}""" }}],"tail":"${"t".repeat(i % 50)}"}"""

    private fun Engine.memtables(): Pair<List<String>, List<String>> {
        val runs = postings.allRuns().map { "${it.term.contentToString()} ${it.docId} ${it.runSeq} ${it.count} ${it.entries.contentToString()}" }
        val entries = fragmentIndex.entries().map { "${it.docId} ${it.pre} ${it.kind} ${it.seq} ${it.location} ${it.length} ${it.payloadOffset}" }
        return runs to entries
    }

    private fun controlFile(fs: SimulatedFileSystemIo): Path = fs.files().first { it.parent?.fileName?.toString() == LogFiles.CONTROL_DIR }

    /** Where the valid records of [file] end; the file itself may be preallocated past that. */
    private fun recordsEnd(fs: SimulatedFileSystemIo, file: Path): Long = fs.open(file, OpenMode.READ).use { handle ->
        val reader = RecordReader(1 shl 20)
        reader.open(handle, LogFormat.HEADER_BYTES.toLong(), handle.size())
        while (reader.next() == RecordReader.RECORD) {
            // next record
        }
        reader.position
    }

    @Test
    fun `documents survive reopening, replay rebuilds identical memtables, and docIds continue`() {
        for (windows in listOf(false, true)) {
            for (t in listOf(32, 256, 64 * 1024)) {
                for (threshold in listOf(0L, 2048L, EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD)) {
                    val label = "windows=$windows T=$t threshold=$threshold"
                    val fs = SimulatedFileSystemIo(isWindows = windows)
                    val expected = HashMap<Long, String>()
                    val live = Engine.open(dir, options(t, threshold), fs).use { store ->
                        repeat(12) { i ->
                            val json = doc(i, if (i % 4 == 0) 40 else 3)
                            expected[store.insert(json).raw] = json
                        }
                        assertFailsWith<DocumentRejectedException> { store.insert("""{"all":true,"a":1,"a":2}""") }
                        assertEquals(expected, store.documents(), label)
                        store.memtables()
                    }
                    fs.restart()
                    Engine.open(dir, options(t, threshold), fs).use { store ->
                        assertEquals(expected, store.documents(), label)
                        assertEquals(live, store.memtables(), "$label: replayed memtables differ")
                        assertEquals(12, store.replayedCommits)
                        assertEquals(14, store.insert(doc(99)).raw, label)
                    }
                }
            }
        }
    }

    @Test
    fun `a crash after acknowledgement loses nothing`() {
        for (seed in 0 until 20) {
            val fs = SimulatedFileSystemIo(isWindows = seed % 2 == 1)
            val expected = HashMap<Long, String>()
            val store = Engine.open(dir, options(64, if (seed % 3 == 0) 0 else 2048), fs)
            repeat(10) { i -> doc(i, seed).let { expected[store.insert(it).raw] = it } }
            fs.crash(Random(seed))
            Engine.open(dir, options(64, 2048), fs).use { reopened ->
                assertEquals(expected, reopened.documents(), "seed $seed")
                assertEquals(0, reopened.damagedDocIds.size)
            }
        }
    }

    @Test
    fun `a failed sync fails the engine and the batch is never acknowledged`() {
        for (target in listOf("lane-S", "ctl-", "lane-L1")) {
            val fs = SimulatedFileSystemIo()
            val store = Engine.open(dir, options(64, if (target == "lane-L1") 0 else 1 shl 20), fs)
            val first = store.insert(doc(1)).raw
            fs.failNextForce { it.fileName.toString().startsWith(target) }
            assertFailsWith<EngineFailedException>(target) { store.insert(doc(2)) }
            assertFailsWith<EngineFailedException> { store.insert(doc(3)) }
            assertFailsWith<EngineFailedException> { store.search(KeyValueQuery("all", JsonQueryValue.Bool(true))) }
            store.close()
            fs.restart()
            Engine.open(dir, options(64, 1 shl 20), fs).use { reopened ->
                assertEquals(setOf(first), reopened.documents().keys, target)
            }
        }
    }

    @Test
    fun `damage in the control log refuses to open, a torn tail is cut off`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(), fs).use { store -> repeat(5) { store.insert(doc(it)) } }
        val control = controlFile(fs)
        val size = fs.bytes(control).size

        // Garbage after the last record, as a torn append leaves it: cut off on open.
        fs.open(control, OpenMode.READ_WRITE).use { it.write(size.toLong(), byteArrayOf(0xA5.toByte(), 0xF7.toByte(), 0x11, 0, 99, 0, 0, 0, 1, 2, 3), 0, 11) }
        fs.restart()
        Engine.open(dir, options(), fs).use { store ->
            assertEquals(5, store.documents().size)
            store.insert(doc(5))
        }
        fs.restart()
        Engine.open(dir, options(), fs).use { store -> assertEquals(6, store.documents().size) }

        // A flipped bit in the first record, with valid records after it: refuse.
        fs.flipBit(control, LogFormat.HEADER_BYTES + 20L, 3)
        fs.restart()
        assertFailsWith<CorruptionException> { Engine.open(dir, options(), fs) }

        // The same without a clean close, so the MANIFEST doesn't know the last LSN: the valid records after the
        // damage still refuse the open.
        val crashed = SimulatedFileSystemIo()
        Engine.open(dir, options(), crashed).let { store -> repeat(5) { store.insert(doc(it)) } }
        crashed.restart()
        crashed.flipBit(controlFile(crashed), LogFormat.HEADER_BYTES + 20L, 3)
        assertFailsWith<CorruptionException> { Engine.open(dir, options(), crashed) }
    }

    @Test
    fun `valid unsynced records after invalid bytes are a torn tail, cut off with the bytes`() {
        // Out-of-order writeback can lose a page of the control log and keep a later one. Nothing is synced after the
        // open, so no record after the damage carries the after-sync flag.
        val fs = SimulatedFileSystemIo()
        val unsynced = options(durability = Durability.None)
        val store = Engine.open(dir, unsynced, fs)
        val kept = (0 until 3).map { store.insert(doc(it)).raw }
        val control = controlFile(fs)
        val damageAt = recordsEnd(fs, control)
        repeat(3) { store.insert(doc(3 + it)) }
        assertTrue(recordsEnd(fs, control) > damageAt + 16)
        fs.open(control, OpenMode.READ_WRITE).use { it.write(damageAt, ByteArray(16) { 0xA5.toByte() }, 0, 16) }
        fs.restart()

        Engine.open(dir, options(), fs).use { reopened ->
            assertEquals(kept.toSet(), reopened.documents().keys)
            reopened.insert(doc(10))
        }
        // The cut-off records past the new ones don't come back, and don't stop the next replay.
        fs.restart()
        Engine.open(dir, options(), fs).use { reopened ->
            assertEquals(4, reopened.documents().size)
            assertEquals(emptyList(), reopened.verify().issues)
        }
    }

    @Test
    fun `damage in a lane file fails only the reads that need the damaged fragment`() {
        val fs = SimulatedFileSystemIo()
        val texts = HashMap<Long, String>()
        var victim = 0L
        var damaged = 0L
        Engine.open(dir, options(64), fs).use { store ->
            repeat(3) { i -> doc(i, 6).let { texts[store.insert(it).raw] = it } }
            victim = texts.keys.sorted()[1]
            val entry = store.fragmentIndex.entries().filter { it.docId == victim }.toList()[1]
            damaged = entry.location
        }
        val lane = fs.files().first { it.fileName.toString().startsWith("lane-S") }
        fs.flipBit(lane, LogFragmentStore.offsetOf(damaged) + LogFormat.RECORD_HEADER_BYTES + 25, 1)
        fs.restart()
        Engine.open(dir, options(64), fs).use { store ->
            assertContentEquals(longArrayOf(victim), store.damagedDocIds)
            store.search(KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT)).use { result ->
                for (hit in result.iterator()) {
                    if (hit.node.docId.raw == victim) {
                        assertFailsWith<CorruptionException> { hit.readJsonAsString() }
                    } else {
                        assertEquals(texts[hit.node.docId.raw], hit.readJsonAsString())
                    }
                }
            }
        }
    }

    @Test
    fun `a bit flip under a running store fails the read with a corruption error`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(64), fs).use { store ->
            val docId = store.insert(doc(1, 5)).raw
            val entry = store.fragmentIndex.entries().first { it.docId == docId }
            val lane = fs.files().first { it.fileName.toString().startsWith("lane-S") }
            fs.flipBit(lane, LogFragmentStore.offsetOf(entry.location) + entry.length - 1, 0)
            store.search(KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT)).use { result ->
                assertFailsWith<CorruptionException> { result.iterator().next().readJsonAsString() }
            }
        }
    }

    @Test
    fun `an index entry with an impossible record length fails the read with a corruption error, not an allocation`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(64), fs).use { store ->
            val docId = store.insert(doc(1, 5)).raw
            val real = store.fragmentIndex.entries().first { it.docId == docId }
            for (length in listOf(0, LogFormat.RECORD_HEADER_BYTES - 1, store.options.split.maxRecordBytes + LogFormat.RECORD_HEADER_BYTES + 1, Int.MAX_VALUE)) {
                val crafted = FragmentEntry(real.docId, real.pre, real.kind, real.seq, real.location, length, real.payloadOffset, real.rawLength)
                val index = object : FragmentIndex {
                    override fun find(docId: Long, pre: Long, kind: Int, seq: Int) = crafted
                    override fun floor(docId: Long, pre: Long, kind: Int, seq: Int) = crafted
                    override fun range(docId: Long, fromPre: Long, fromKind: Int, toPre: Long) = listOf(crafted)
                }
                val fragments = LogFragmentStore(store.files, index, ByteCache(0), store.codecs, store.options.split.maxRecordBytes)
                val e = assertFailsWith<CorruptionException>("length $length") { fragments.find(real.docId, real.pre, real.kind, real.seq, FragmentView()) }
                assertTrue(e.message!!.contains("indexed with $length record bytes"), e.message)
            }
        }
    }

    @Test
    fun `a BEGIN without end is aborted when the store opens`() {
        val fs = SimulatedFileSystemIo()
        var lsn = 0L
        val first: Long
        Engine.open(dir, options(64, threshold = 0), fs).use { store ->
            first = store.insert(doc(1)).raw
            val body = doc(2, 30).toByteArray()
            val failing = object : InputStream() {
                var position = 0

                override fun read(): Int = throw UnsupportedOperationException()

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (position > body.size / 2) {
                        fs.crashAfter(1)
                        throw IOException("client went away")
                    }
                    val n = minOf(len, 64, body.size - position)
                    System.arraycopy(body, position, b, off, n)
                    position += n
                    return n
                }
            }
            assertFailsWith<IOException> { store.insert(failing) }
        }
        fs.restart()
        Engine.open(dir, options(64), fs).use { store ->
            assertEquals(setOf(first), store.documents().keys)
            assertTrue(store.registry.current().visibility.isAborted(first + 1))
            lsn = store.writer.lsn
            assertEquals(first + 2, store.insert(doc(3)).raw)
        }
        fs.restart()
        Engine.open(dir, options(64), fs).use { store ->
            assertEquals(lsn + 1, store.writer.lsn, "the ABORT is written once")
            assertEquals(2, store.documents().size)
        }
    }

    @Test
    fun `log files roll, seal and are found again`() {
        val log = LogOptions(laneFileBytes = 4096, controlFileBytes = 4096, preallocateBytes = 1024, writeBufferBytes = 4096)
        val fs = SimulatedFileSystemIo()
        val expected = HashMap<Long, String>()
        Engine.open(dir, options(256, threshold = 1024, log = log), fs).use { store ->
            repeat(120) { i -> doc(i, i % 7).let { expected[store.insert(it).raw] = it } }
        }
        val controlFiles = fs.files().count { it.parent?.fileName?.toString() == LogFiles.CONTROL_DIR }
        val laneFiles = fs.files().count { it.parent?.fileName?.toString() == LogFiles.DATA_DIR }
        assertTrue(controlFiles >= 3 && laneFiles > 6, "$controlFiles control files, $laneFiles lane files")
        fs.restart()
        Engine.open(dir, options(256, threshold = 1024, log = log), fs).use { store ->
            assertEquals(expected, store.documents())
            repeat(30) { i -> doc(i, 3).let { expected[store.insert(it).raw] = it } }
        }
        fs.crash(Random(1))
        Engine.open(dir, options(256, threshold = 1024, log = log), fs).use { store -> assertEquals(expected, store.documents()) }
    }

    @Test
    fun `creation options can't change, and a store opens only once`() {
        val fs = SimulatedFileSystemIo()
        val store = Engine.open(dir, options(256), fs)
        assertEquals(dir, assertFailsWith<StoreLockedException> { Engine.open(dir, options(256), fs) }.directory)
        store.insert(doc(1))
        store.close()
        assertFailsWith<CreationOptionsMismatchException> { Engine.open(dir, options(512), fs) }
        assertFailsWith<CreationOptionsMismatchException> { Engine.open(dir, options(256, hashTerms = false), fs) }
        Engine.open(dir, EngineOptions(CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1 shl 20), log = TestEngines.LOG), fs).use { assertEquals(1, it.documents().size) }
    }

    @Test
    fun `a damaged MANIFEST falls back to the previous one only if its files exist`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(), fs).use { store -> repeat(3) { store.insert(doc(it)) } }
        val manifests = fs.files().filter { ManifestStore.manifestNo(it) != null }.sortedBy { ManifestStore.manifestNo(it) }
        assertEquals(2, manifests.size, "the current and the previous MANIFEST are kept")
        fs.flipBit(manifests.last(), 30, 2)
        fs.restart()
        Engine.open(dir, options(), fs).use { store -> assertEquals(3, store.documents().size) }

        val current = fs.files().filter { ManifestStore.manifestNo(it) != null }.maxBy { ManifestStore.manifestNo(it)!! }
        fs.flipBit(current, 30, 2)
        fs.files().filter { it.parent?.fileName?.toString() == LogFiles.DATA_DIR }.forEach { fs.delete(it) }
        fs.restart()
        assertFailsWith<CorruptionException> { Engine.open(dir, options(), fs) }
    }

    @Test
    fun `a missing CURRENT refuses to open a store with log files`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(), fs).use { it.insert(doc(1)) }
        fs.delete(dir.resolve(ManifestStore.CURRENT))
        fs.restart()
        assertFailsWith<CorruptionException> { Engine.open(dir, options(), fs) }

        val fresh = SimulatedFileSystemIo()
        fresh.createDirectories(dir)
        fresh.open(dir.resolve("MANIFEST-000001.tmp"), OpenMode.CREATE_NEW).close()
        Engine.open(dir, options(), fresh).use { assertEquals(1, it.insert(doc(1)).raw) }
    }

    @Test
    fun `periodic durability acknowledges before the sync, none never syncs`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(durability = Durability.Periodic(1500.milliseconds)), fs).use { store ->
            store.insert(doc(1))
            assertTrue(store.writer.durableLsn < store.writer.lsn, "acknowledged before the sync")
            val deadline = System.nanoTime() + 10_000_000_000
            while (store.writer.durableLsn < store.writer.lsn && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals(store.writer.lsn, store.writer.durableLsn)
        }

        val none = SimulatedFileSystemIo()
        Engine.open(dir, options(durability = Durability.None), none).use { store ->
            store.insert(doc(0))
            val forces = none.forces
            repeat(20) { store.insert(doc(it)) }
            assertEquals(forces, none.forces)
        }
        none.restart()
        Engine.open(dir, options(durability = Durability.None), none).use { assertEquals(21, it.documents().size) }
    }

    @Test
    fun `an unsynced COMMIT whose fragments were lost is cut off with the tail, not reported as damage`() {
        for (promoted in listOf(false, true)) {
            val fs = SimulatedFileSystemIo()
            val periodic = options(threshold = if (promoted) 0 else EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD, durability = Durability.Periodic(1.hours))
            val store = Engine.open(dir, periodic, fs)
            val synced = (0 until 3).associate { i -> doc(i, 40).let { store.insert(it).raw to it } }
            store.flush()
            // Acknowledged, the control log not synced; a crash keeps the control log's writes and loses the lanes'.
            val unsynced = (0 until 3).associate { i -> doc(10 + i, 40).let { store.insert(it).raw to it } }
            fs.crashKeeping { it.parent?.fileName?.toString() == LogFiles.CONTROL_DIR }
            // Its last sync meets the crashed disk.
            assertFailsWith<EngineFailedException> { store.close() }
            Engine.open(dir, periodic, fs).use { store ->
                assertEquals(0, store.damagedDocIds.size, "promoted=$promoted")
                assertEquals(0, store.registry.current().visibility.inFlightCount)
                if (promoted) {
                    // A large insert syncs its lane before its COMMIT in every mode but None.
                    assertEquals(synced + unsynced, store.documents())
                    assertEquals(0, store.recoveryStats.truncatedCommits)
                } else {
                    assertEquals(synced, store.documents())
                    assertEquals(1, store.recoveryStats.truncatedCommits)
                }
                store.insert(doc(99))
            }
            fs.restart()
            Engine.open(dir, periodic, fs).use { store ->
                assertEquals(if (promoted) 7 else 4, store.documents().size)
                assertEquals(0, store.recoveryStats.truncatedCommits)
            }
        }
    }

    @Test
    fun `a synced COMMIT with a damaged fragment is still reported as damage`() {
        val fs = SimulatedFileSystemIo()
        val damaged: Long
        Engine.open(dir, options(), fs).use { store ->
            damaged = store.insert(doc(1, 40)).raw
            // Later batches mark the log as synced after the damaged document's COMMIT.
            store.insert(doc(2, 40))
            store.insert(doc(3))
        }
        val lane = fs.files().first { it.fileName.toString().startsWith("lane-S") }
        fs.flipBit(lane, LogFormat.HEADER_BYTES + 40L, 3)
        fs.restart()
        Engine.open(dir, options(), fs).use { store ->
            assertEquals(0, store.recoveryStats.truncatedCommits)
            assertEquals(listOf(damaged), store.damagedDocIds.toList())
            for (i in 2..3) {
                store.search(KeyValueQuery("i", JsonQueryValue.Num(i.toBigDecimal()), ResultMode.WHOLE_DOCUMENT)).use { result ->
                    assertEquals(doc(i, if (i == 2) 40 else 3), result.iterator().next().readJsonAsString())
                }
            }
        }
    }
}
