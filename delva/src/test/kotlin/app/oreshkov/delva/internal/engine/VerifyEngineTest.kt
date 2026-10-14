package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.Durability
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.VerifyIssueKind
import app.oreshkov.delva.VerifyPhase
import app.oreshkov.delva.VerifyProgress
import app.oreshkov.delva.VerifyReport
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.compress.CompressionOptions
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.log.LogOptions
import app.oreshkov.delva.internal.lsm.FragmentEntry
import app.oreshkov.delva.internal.store.LogFragmentStore
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The `verify` scrub, on the simulated disk. */
class VerifyEngineTest {
    private val dir = TestEngines.DIR

    private fun options(
        t: Int = 256,
        threshold: Long = EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD,
        durability: Durability = Durability.SyncEachBatch,
        laneFileBytes: Long = 64L shl 10,
        compression: CompressionOptions = CompressionOptions.DISABLED,
    ) = EngineOptions(
        CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 4096), durability = durability),
        sorterBytes = 4096, smallInsertThreshold = threshold, keySeed = 11,
        log = LogOptions(laneFileBytes = laneFileBytes, controlFileBytes = 32L shl 10, preallocateBytes = 4L shl 10, writeBufferBytes = 4096),
        segmentBlockBytes = 512,
        compaction = CompactionOptions(bytesPerSecond = 0),
        reclaim = ReclaimOptions(bytesPerSecond = 0),
        compression = compression,
    )

    private fun doc(i: Int, size: Int = 3) =
        """{"all":true,"i":$i,"items":[${(0 until size + i % 5).joinToString(",") { """{"k":$it,"s":"${"x".repeat(it * 7)}"}""" }}],"tail":"${"t".repeat(i % 50)}"}"""

    private fun laneFile(fs: SimulatedFileSystemIo, prefix: String = "lane-S"): Path =
        fs.files().first { it.fileName.toString().startsWith(prefix) }

    private fun controlFile(fs: SimulatedFileSystemIo): Path =
        fs.files().first { it.parent?.fileName?.toString() == LogFiles.CONTROL_DIR }

    private fun segmentFile(fs: SimulatedFileSystemIo, prefix: String): Path =
        fs.files().first { it.fileName.toString().startsWith(prefix) }

    @Test
    fun `an empty store verifies`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(), fs).use { store ->
            val report = store.verify()
            assertTrue(report.intact, "$report ${report.issues}")
            assertEquals(0, report.damagedDocuments.size)
        }
    }

    @Test
    fun `a store verifies before and after a flush, and after reopening`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(), fs).use { store ->
            repeat(40) { store.insert(doc(it)) }
            // Everything is still in the log and the memtables.
            val logged = store.verify()
            assertTrue(logged.intact, "${logged.issues}")
            // One COMMIT per document, with an implicit BEGIN.
            assertEquals(40, logged.logRecordsChecked)
            assertTrue(logged.fragmentsChecked > 0, "$logged")

            store.flush()
            val flushed = store.verify()
            assertTrue(flushed.intact, "${flushed.issues}")
            assertTrue(flushed.segmentBlocksChecked > 0, "$flushed")
        }
        Engine.open(dir, options(), fs).use { store ->
            val report = store.verify()
            assertTrue(report.intact, "${report.issues}")
            assertTrue(report.filesChecked > 0, "$report")
        }
    }

    @Test
    fun `promoted inserts, types and deletes verify`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(t = 64, threshold = 0), fs).use { store ->
            val ids = (0 until 12).map { store.insert(doc(it, 8), mapOf("$" to "Root")) }
            store.flush()
            repeat(6) { store.insert(doc(100 + it, 8)) }
            store.deleteAll(ids.take(4))
            assertTrue(store.verify().intact)
            store.flush()
            store.compact()
            val report = store.verify()
            assertTrue(report.intact, "${report.issues}")
        }
    }

    @Test
    fun `a compressed store with dictionaries verifies`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(compression = TestEngines.compression(2)), fs).use { store ->
            repeat(60) { store.insert(doc(it, 6)) }
            store.flush()
            val report = store.verify()
            assertTrue(report.intact, "${report.issues}")
        }
    }

    @Test
    fun `a damaged lane record is reported with its document and the extent of the damage`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(t = 64), fs).use { store ->
            val docId = store.insert(doc(1, 6)).raw
            val entry = store.fragmentIndex.entries().first { it.docId == docId && it.pre > 0 }
            fs.flipBit(laneFile(fs), LogFragmentStore.offsetOf(entry.location) + LogFormat.RECORD_HEADER_BYTES + 4, 1)

            val report = store.verify()
            assertTrue(!report.intact, "the damage wasn't found")
            assertEquals(listOf(docId), report.damagedDocuments.map { it.raw })
            assertTrue(report.damagedBytes > 0, "${report.issues}")
            // Found twice over: the COMMIT's extents don't hold it, and the fragment index locates it.
            assertTrue(report.issues.any { it.kind == VerifyIssueKind.LOG_RECORD }, "${report.issues}")
            assertTrue(report.issues.any { it.kind == VerifyIssueKind.FRAGMENT_INDEX }, "${report.issues}")
        }
    }

    @Test
    fun `damage found after a flush is reported by the fragment index alone`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(t = 64), fs).use { store ->
            val docId = store.insert(doc(2, 6)).raw
            val entry = store.fragmentIndex.entries().first { it.docId == docId && it.pre > 0 }
            store.flush()
            fs.flipBit(laneFile(fs), LogFragmentStore.offsetOf(entry.location) + LogFormat.RECORD_HEADER_BYTES + 4, 1)

            val report = store.verify()
            assertEquals(listOf(docId), report.damagedDocuments.map { it.raw })
            val issue = report.issues.first { it.kind == VerifyIssueKind.FRAGMENT_INDEX }
            assertTrue(issue.location.startsWith("lane file "), issue.toString())
        }
    }

    @Test
    fun `a damaged control record is reported with the extent of the damage`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(), fs).use { store ->
            repeat(20) { store.insert(doc(it)) }
            fs.flipBit(controlFile(fs), LogFormat.HEADER_BYTES + 20L, 3)

            val report = store.verify()
            val issue = report.issues.first { it.kind == VerifyIssueKind.LOG_RECORD }
            assertTrue(issue.location.startsWith("control file "), issue.toString())
            assertTrue(issue.damagedBytes > 0, issue.toString())
            // The scan resynchronizes: the records after the damage are read, not written off.
            assertTrue(report.logRecordsChecked > 10, "$report")
        }
    }

    @Test
    fun `a zeroed record header is damage, not the end of the log`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(), fs).use { store ->
            repeat(20) { store.insert(doc(it)) }
            val path = controlFile(fs)
            val bytes = fs.bytes(path)
            var at = LogFormat.HEADER_BYTES
            repeat(5) { at += LogFormat.padded(LogFormat.RECORD_HEADER_BYTES + LittleEndian.getInt(bytes, at + 4)) }
            assertEquals(LogFormat.RECORD_MAGIC, LittleEndian.getShort(bytes, at).toInt() and 0xFFFF)
            for (p in at until at + 2) for (bit in 0 until 8) if (bytes[p].toInt() shr bit and 1 != 0) fs.flipBit(path, p.toLong(), bit)

            val report = store.verify()
            val issue = report.issues.first { it.kind == VerifyIssueKind.LOG_RECORD }
            assertTrue(issue.location.startsWith("control file ") && issue.location.endsWith("@$at"), issue.toString())
            assertTrue(report.logRecordsChecked > 10, "the records after the zeroed header were read: $report")
        }
    }

    @Test
    fun `a damaged segment block is reported and doesn't stop the other segments`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(t = 64), fs).use { store ->
            repeat(60) { store.insert(doc(it, 5)) }
            store.flush()
            fs.flipBit(segmentFile(fs, "posting-"), LogFormat.HEADER_BYTES + 30L, 2)

            val report = store.verify()
            val issue = report.issues.first { it.kind == VerifyIssueKind.SEGMENT }
            assertTrue(issue.location.contains("posting-"), issue.toString())
            // The fragment segment was still walked, so its entries were cross-checked.
            assertTrue(report.fragmentsChecked > 0, "$report")
        }
    }

    /** A fragment-index entry that points nowhere sensible is a report, never an exception out of `verify`. */
    @Test
    fun `an index entry pointing outside a record is reported, not thrown`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(t = 64), fs).use { store ->
            val docId = store.insert(doc(3, 6)).raw
            val good = store.fragmentIndex.entries().first { it.docId == docId && it.pre > 0 }
            // Unaligned, past the end, and a zero length: none of them can name a record.
            store.fragmentIndex.put(FragmentEntry(docId, 900_001, good.kind, 0, good.location + 3, good.length, good.payloadOffset, good.rawLength))
            store.fragmentIndex.put(FragmentEntry(docId, 900_002, good.kind, 0, LogFragmentStore.location(LogFragmentStore.fileNoOf(good.location), 1L shl 30), good.length, good.payloadOffset, good.rawLength))
            store.fragmentIndex.put(FragmentEntry(docId, 900_003, good.kind, 0, good.location, 0, good.payloadOffset, good.rawLength))

            val report = store.verify()
            val found = report.issues.filter { it.kind == VerifyIssueKind.FRAGMENT_INDEX }
            assertEquals(3, found.size, "$found")
            assertTrue(found.all { it.document?.raw == docId }, "$found")
        }
    }

    @Test
    fun `verify reports progress for every phase and counts bytes`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(t = 64), fs).use { store ->
            repeat(40) { store.insert(doc(it, 5)) }
            store.flush()
            val seen = LinkedHashMap<VerifyPhase, Long>()
            val report = store.verify { phase, bytesChecked, bytesTotal ->
                assertTrue(bytesChecked <= bytesTotal, "$phase: $bytesChecked of $bytesTotal")
                seen[phase] = maxOf(seen[phase] ?: 0, bytesChecked)
            }
            assertEquals(listOf(VerifyPhase.LOG, VerifyPhase.INDEX, VerifyPhase.FRAGMENTS), seen.keys.toList())
            assertTrue(report.bytesChecked > 0, "$report")
        }
    }

    @Test
    fun `an exception from the progress callback stops the scrub`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(), fs).use { store ->
            repeat(10) { store.insert(doc(it)) }
            val stop = object : RuntimeException("stop") {}
            assertFailsWith<RuntimeException> {
                store.verify(VerifyProgress { _, _, _ -> throw stop })
            }
            // The store is untouched by the abandoned scrub.
            assertTrue(store.verify().intact)
        }
    }

    @Test
    fun `verify changes nothing and can run while the store is written to`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(t = 64), fs).use { store ->
            repeat(20) { store.insert(doc(it, 4)) }
            val before = store.documents()
            var reports: VerifyReport? = null
            // A scrub reads one snapshot; inserts landing during it neither disturb it nor appear in it.
            val report = store.verify { phase, _, _ ->
                if (phase == VerifyPhase.INDEX && reports == null) {
                    repeat(5) { store.insert(doc(200 + it, 4)) }
                    reports = store.verify()
                }
            }
            assertTrue(report.intact, "${report.issues}")
            assertTrue(assertNotNull(reports).intact)
            assertEquals(before, store.documents().filterKeys { it in before.keys })
            assertEquals(25, store.documents().size)
        }
    }

    @Test
    fun `the entries of a promoted insert rejected at its end aren't damage, though its lane bytes were never written`() {
        TestEngines.open(options(threshold = 0)).use { store ->
            store.insert("""{"all":true,"v":1}""")
            // Rejected once its last fragment was indexed: nothing syncs or flushes the lane after it.
            assertFailsWith<DocumentRejectedException> { store.insert("""{"all":true}""", mapOf("$.missing" to "T")) }
            assertEquals(emptyList(), store.verify().issues.map { it.toString() })
        }
    }

    /** Fails the reads that [failing] picks by the file's path and the bytes the read covers. */
    private class FailingReads(private val fs: SimulatedFileSystemIo) : FileSystemIo by fs {
        @Volatile
        var failing: (Path, LongRange) -> Boolean = { _, _ -> false }

        override fun open(path: Path, mode: OpenMode): FileIo {
            val file = fs.open(path, mode)
            return object : FileIo by file {
                override fun read(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
                    if (length > 0 && failing(path, position until position + length)) throw IOException("simulated read failure on $path at $position")
                    return file.read(position, destination, offset, length)
                }
            }
        }
    }

    @Test
    fun `bytes that can't be read are issues, and verify goes on past them`() {
        val sim = SimulatedFileSystemIo()
        val fs = FailingReads(sim)
        Engine.open(dir, options(t = 64), fs).use { store ->
            repeat(60) { store.insert(doc(it, 5)) }
            store.flush()
            val docId = store.insert(doc(100, 6)).raw
            val entry = store.fragmentIndex.entries().first { it.docId == docId && it.pre > 0 }
            val laneNo = LogFragmentStore.fileNoOf(entry.location)
            val recordAt = LogFragmentStore.offsetOf(entry.location)
            // Damage found before the unreadable bytes.
            sim.flipBit(controlFile(sim), LogFormat.HEADER_BYTES + 20L, 3)
            val blockAt = LogFormat.HEADER_BYTES + 30L
            fs.failing = { path, range ->
                val name = path.fileName.toString()
                when {
                    LogFiles.parseLane(name)?.first == laneNo -> recordAt in range
                    name.startsWith("posting-") || name.startsWith("frag-") -> blockAt in range
                    else -> false
                }
            }

            val report = store.verify()
            val issues = report.issues
            assertTrue(issues.any { it.kind == VerifyIssueKind.LOG_RECORD && it.location.startsWith("control file ") }, "$issues")
            // The log is checked by reading the record through its COMMIT, the fragment index by reading it again.
            for (kind in listOf(VerifyIssueKind.COMMIT, VerifyIssueKind.FRAGMENT_INDEX)) {
                assertTrue(issues.any { it.kind == kind && it.document?.raw == docId && "can't be read" in it.detail }, "$kind: $issues")
            }
            assertTrue(issues.any { it.kind == VerifyIssueKind.SEGMENT && "posting-" in it.location }, "$issues")
            // Checking the fragment index walks the fragment segment again, but its unreadable block is reported once.
            assertEquals(1, issues.count { it.kind == VerifyIssueKind.SEGMENT && "frag-" in it.location }, "$issues")
        }
    }
}
