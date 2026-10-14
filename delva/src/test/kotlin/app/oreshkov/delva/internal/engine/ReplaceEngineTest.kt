package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.InsertOutcome
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.WriteConflictException
import app.oreshkov.delva.internal.facade.CoroutineJsonStore
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.CommitInfo
import app.oreshkov.delva.internal.log.ControlRecords
import app.oreshkov.delva.internal.log.DeleteRecord
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.log.RecordReader
import app.oreshkov.delva.internal.log.RecordType
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Replacing documents: an insert that deletes documents with its COMMIT. The oracle comparisons are in [EngineModelTest]. */
class ReplaceEngineTest {
    private fun options(
        t: Int = 256,
        threshold: Long = EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD,
        maxDeleteRecordDocIds: Int = EngineOptions.DEFAULT_MAX_DELETE_RECORD_DOC_IDS,
        startWriterThread: Boolean = true,
    ) = EngineOptions(
        CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024)),
        sorterBytes = 4096, keyArenaBytes = 1024, smallInsertThreshold = threshold, keySeed = 17, log = TestEngines.LOG,
        backgroundFlush = false, maxDeleteRecordDocIds = maxDeleteRecordDocIds, startWriterThread = startWriterThread,
    )

    // [0]{"all":[1]true,"v":[2]v,"item":[3]{"k":[4]1}}
    private fun doc(v: Int) = """{"all":true,"v":$v,"item":{"k":1}}"""

    private val types = mapOf("$.item" to "Item")

    private fun ids(vararg docIds: Long) = docIds.map { DocId(it) }

    /** Every read shows exactly the documents [live], each with its `Item`, and none of [deleted]. */
    private fun check(store: Engine, live: Map<Long, String>, deleted: List<Long>, label: String) {
        assertEquals(live, store.documents(), "$label: documents")
        assertEquals(live.keys.sorted().map { NodeRef(DocId(it), 3) }, store.objectsOfType("Item"), "$label: objectsOfType")
        for (docId in deleted) {
            assertFailsWith<DocumentNotFoundException>(label) { store.typeOf(NodeRef(DocId(docId), 3)) }
            assertFalse(store.delete(DocId(docId)), "$label: deleting $docId again")
        }
    }

    @Test
    fun `a replace inserts its document and deletes the ones it replaces, promoted or not, through replay and flush`() {
        for (t in listOf(32, 4096)) {
            for (threshold in listOf(0L, EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD)) {
                val label = "T=$t threshold=$threshold"
                val fs = SimulatedFileSystemIo()
                // Two docIds per DELETE record: replacing three spans two.
                val options = options(t, threshold, maxDeleteRecordDocIds = 2)
                val (live, deleted) = TestEngines.open(options, fs).use { store ->
                    val old = (0 until 4).map { store.insert(doc(it), types).raw }
                    val new = store.replace(ids(old[2], old[0], old[1], old[0]), doc(10), types).raw
                    assertTrue(new > old.last(), label)
                    val live = mapOf(old[3] to doc(3), new to doc(10))
                    check(store, live, old.take(3), label)
                    assertEquals(2, store.info().approximateDocumentCount, label)
                    live to old.take(3)
                }
                val records = controlRecords(fs)
                val chain = records.filter { it.delete?.atInsert == true }
                assertEquals(listOf(listOf(deleted[0], deleted[1]), listOf(deleted[2])), chain.map { it.deleted }, "$label: one chain, ascending")
                val newDocId = live.keys.max()
                assertTrue(chain.all { it.delete!!.insertDocId == newDocId }, label)
                assertEquals(listOf(true, false), chain.map { it.delete!!.first }, label)
                assertEquals(listOf(true, false), chain.map { it.delete!!.continued }, label)
                val commits = records.filter { it.commit != null }
                assertEquals(listOf(newDocId), commits.filter { it.commit!!.replaces }.map { it.commit!!.docId }, "$label: only the replace's COMMIT is flagged")
                assertTrue(records.indexOf(chain.last()) < records.indexOf(commits.last()), "$label: the chain comes before the COMMIT")

                fs.restart()
                TestEngines.open(options, fs).use { store ->
                    check(store, live, deleted, "$label after replay")
                    assertEquals(0, store.verify().issues.size, "$label: verify")
                    store.flush()
                }
                fs.restart()
                TestEngines.open(options, fs).use { store ->
                    assertEquals(0, store.recoveryStats.replayedCommits, label)
                    check(store, live, deleted, "$label from the bitmap file")
                }
            }
        }
    }

    @Test
    fun `a replace of a document that isn't visible conflicts before its input is read, and changes nothing`() {
        val fs = SimulatedFileSystemIo()
        TestEngines.open(options(threshold = 256), fs).use { store ->
            val live = store.insert(doc(0)).raw
            val deleted = store.insert(doc(1)).raw
            assertTrue(store.delete(DocId(deleted)))
            val rejected = store.registry.peekNextDocId()
            assertFailsWith<DocumentRejectedException> { store.insert("""{"a":1,"a":2}""") }
            val unassigned = store.registry.peekNextDocId()
            for (docId in listOf(deleted, rejected, unassigned, -1L)) {
                val e = assertFailsWith<WriteConflictException>("docId $docId") { store.replace(ids(live, docId, live), Unreadable, emptyMap()) }
                assertEquals(listOf(docId), e.conflicting.map { it.raw })
                // A conflict wins over a document that would be rejected, and over one given as a string.
                assertFailsWith<WriteConflictException> { store.replace(ids(docId), "not JSON") }
            }
            val all = assertFailsWith<WriteConflictException> { store.replace(ids(unassigned, deleted, rejected, deleted), Unreadable, emptyMap()) }
            assertEquals(listOf(deleted, rejected, unassigned), all.conflicting.map { it.raw }, "ascending, each once")
            assertEquals(unassigned, store.registry.peekNextDocId(), "a conflict found before reading takes no docId")
            assertEquals(mapOf(live to doc(0)), store.documents())
            // Nothing to replace: an insert.
            assertEquals(unassigned, store.replace(emptyList(), doc(2)).raw)
        }
    }

    /** A stream the test must never read. */
    private object Unreadable : InputStream() {
        override fun read(): Int = throw AssertionError("the input was read")
    }

    @Test
    fun `a document deleted while its replace streams in makes the writer abort the replace`() {
        for (threshold in listOf(0L, 1L shl 20)) {
            val fs = SimulatedFileSystemIo()
            val options = options(threshold = threshold)
            val (target, attempted) = TestEngines.open(options, fs).use { store ->
                val target = store.insert(doc(0)).raw
                val attempted = store.registry.peekNextDocId()
                val bytes = """{"all":true,"v":1,"pad":"${"x".repeat(4000)}"}""".toByteArray()
                // The pre-check passes; the delete lands before the replace's COMMIT.
                val input = HalfwayStream(bytes) { assertTrue(store.delete(DocId(target))) }
                val e = assertFailsWith<WriteConflictException>("threshold $threshold") { store.replace(ids(target), input, emptyMap()) }
                assertEquals(listOf(target), e.conflicting.map { it.raw })
                assertEquals(threshold == 0L, store.registry.current().visibility.isAborted(attempted), "threshold $threshold: a promoted replace is aborted")
                assertEquals(emptyMap(), store.documents())
                // The store goes on: the next insert commits.
                val next = store.insert(doc(2)).raw
                // The next batch publishes a snapshot without the docId in flight.
                assertEquals(0, store.registry.current().visibility.inFlightCount, "threshold $threshold")
                assertEquals(mapOf(next to doc(2)), store.documents())
                target to attempted
            }
            fs.restart()
            TestEngines.open(options, fs).use { store ->
                assertEquals(listOf(attempted + 1), store.documents().keys.toList(), "threshold $threshold after replay")
                assertFalse(store.delete(DocId(target)))
                assertEquals(0, store.verify().issues.size)
            }
        }
    }

    @Test
    fun `in one batch, the second replace of a document conflicts`() {
        val fs = SimulatedFileSystemIo()
        val target = TestEngines.open(options(), fs).use { store -> store.insert(doc(0)).raw }
        fs.restart()
        val executor = Executors.newFixedThreadPool(2)
        try {
            TestEngines.open(options(startWriterThread = false), fs).use { store ->
                val replaces = (1..2).map { v -> executor.submit(Callable { store.replace(ids(target), doc(v)).raw }) }
                // Both passed the check before the writer ran; it takes them in one batch.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                while (store.writer.queued < 2) {
                    check(System.nanoTime() < deadline) { "the replaces weren't queued" }
                    Thread.onSpinWait()
                }
                store.writer.runBatch(wait = false)
                val outcomes = replaces.map { runCatching { it.get(30, TimeUnit.SECONDS) } }
                val winner = outcomes.single { it.isSuccess }.getOrThrow()
                val failure = outcomes.single { it.isFailure }.exceptionOrNull()
                assertIs<WriteConflictException>(assertIs<ExecutionException>(failure).cause)
                assertEquals(1, store.writer.batches)
                assertEquals(listOf(winner), store.documents().keys.toList())
                assertEquals(target + 3, store.registry.peekNextDocId(), "each replace took a docId")
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `insertAll gives conflicts as outcomes, and a source can't replace what an earlier one replaces or inserts`() {
        for (threshold in listOf(0L, EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD)) {
            val fs = SimulatedFileSystemIo()
            TestEngines.open(options(threshold = threshold), fs).use { store ->
                val a = store.insert(doc(0)).raw
                val b = store.insert(doc(1)).raw
                val first = store.registry.peekNextDocId()
                fun source(json: String, vararg replaces: Long) = InsertSource(ByteArrayInputStream(json.toByteArray()), types, ids(*replaces))
                val outcomes = store.insertAll(
                    listOf(
                        source(doc(10), a),
                        InsertSource(Unreadable, emptyMap(), ids(a)),
                        // Rejected: its replace of b doesn't happen, so the next source may replace b.
                        source("""{"a":1,"a":2}""", b),
                        source(doc(11), b),
                        InsertSource(Unreadable, emptyMap(), ids(first)),
                        source(doc(12)),
                    ),
                )
                val label = "threshold $threshold"
                assertEquals(first, assertIs<InsertOutcome.Inserted>(outcomes[0], label).docId.raw)
                assertEquals(listOf(a), assertIs<InsertOutcome.Conflicted>(outcomes[1], label).exception.conflicting.map { it.raw })
                assertEquals(RejectReason.DUPLICATE_KEY, assertIs<InsertOutcome.Rejected>(outcomes[2], label).exception.reason)
                val replacedB = assertIs<InsertOutcome.Inserted>(outcomes[3], label).docId.raw
                assertEquals(listOf(first), assertIs<InsertOutcome.Conflicted>(outcomes[4], label).exception.conflicting.map { it.raw }, "$label: inserted by the same call")
                val plain = assertIs<InsertOutcome.Inserted>(outcomes[5], label).docId.raw
                assertEquals(mapOf(first to doc(10), replacedB to doc(11), plain to doc(12)), store.documents(), label)
                assertEquals(0, store.registry.current().visibility.inFlightCount, label)
            }
        }
    }

    @Test
    fun `a replace cut off by a crash before its COMMIT has no effect`() {
        val options = options(maxDeleteRecordDocIds = 1)
        /** Three documents, then a replace of all of them without a clean close: the MANIFEST doesn't know its LSNs. */
        fun replaceWithoutClose(fs: SimulatedFileSystemIo): List<Long> {
            val docIds = TestEngines.open(options, fs).use { store -> (0 until 3).map { store.insert(doc(it)).raw } }
            fs.restart()
            TestEngines.open(options, fs).let { store -> store.replace(docIds.map { DocId(it) }, doc(9)) }
            fs.restart()
            return docIds
        }

        // Cut at the COMMIT, and inside the chain: the records from there on are a torn tail.
        for (cutAt in listOf("COMMIT", "chain")) {
            val fs = SimulatedFileSystemIo()
            val docIds = replaceWithoutClose(fs)
            val records = controlRecords(fs)
            val chain = records.filter { it.delete?.atInsert == true }
            assertEquals(3, chain.size)
            val from = if (cutAt == "COMMIT") records.indexOfLast { it.commit != null } else records.indexOf(chain[1])
            for (record in records.subList(from, records.size)) {
                fs.open(record.path, OpenMode.READ_WRITE).use { file ->
                    val zeros = ByteArray(record.bytes.count())
                    file.write(record.bytes.first, zeros, 0, zeros.size)
                    file.force(true)
                }
            }
            val original = docIds.associateWith { doc(docIds.indexOf(it)) }
            TestEngines.open(options, fs).use { store ->
                assertEquals(original, store.documents(), "cut at the $cutAt")
                assertEquals(0, store.verify().issues.size, "cut at the $cutAt: the dropped chain is no damage")
                // The reopened store appends after the cut chain; its own replace must not continue that one.
                val next = store.replace(ids(docIds[0]), doc(8)).raw
                assertEquals(original - docIds[0] + (next to doc(8)), store.documents())
            }
            fs.restart()
            TestEngines.open(options, fs).use { store ->
                assertEquals(3, store.documents().size, "cut at the $cutAt, reopened")
                assertEquals(0, store.verify().issues.size)
            }
        }
    }

    @Test
    fun `the suspending store replaces documents from a stream, a file and a string`() = runBlocking {
        val fs = SimulatedFileSystemIo()
        val file = Files.createTempFile("delva-replace", ".json")
        try {
            Files.writeString(file, doc(3))
            JsonStore.open(TestEngines.DIR, options(), fs).use { store ->
                val a = store.insert(doc(0), types)
                val b = store.replace(listOf(a), ByteArrayInputStream(doc(1).toByteArray()), types)
                val c = store.replace(listOf(b), doc(2))
                val d = store.replace(listOf(c), file)
                assertFailsWith<WriteConflictException> { store.replace(listOf(a), doc(4)) }
                val outcomes = store.insertAll(listOf(InsertSource(ByteArrayInputStream(doc(5).toByteArray()), types, listOf(d, d))))
                val e = assertIs<InsertOutcome.Inserted>(outcomes.single()).docId
                assertEquals(mapOf(e.raw to doc(5)), (store as CoroutineJsonStore).core.documents())
                assertEquals("Item", store.typeOf(NodeRef(e, 3)))
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** [bytes] in 256-byte reads, running [atHalf] once when half of them were read. */
    private class HalfwayStream(private val bytes: ByteArray, private val atHalf: () -> Unit) : InputStream() {
        private var position = 0
        private var fired = false

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!fired && position >= bytes.size / 2) {
                fired = true
                atHalf()
            }
            if (position == bytes.size) return -1
            val n = minOf(len, 256, bytes.size - position)
            System.arraycopy(bytes, position, b, off, n)
            position += n
            return n
        }
    }

    /** A control record: its file, its bytes (padding included), and what a DELETE or COMMIT record says. */
    private class ControlRecord(val path: Path, val bytes: LongRange, val delete: DeleteRecord?, val commit: CommitInfo?) {
        val deleted: List<Long>? get() = delete?.let { it.docIds.copyOf(it.count).toList() }
    }

    /** Every record of the control files, oldest first. */
    private fun controlRecords(fs: SimulatedFileSystemIo): List<ControlRecord> {
        val records = ArrayList<ControlRecord>()
        val body = ControlRecords.Body("test")
        for (path in fs.files().filter { LogFiles.parseControl(it.fileName.toString()) != null }.sortedBy { LogFiles.parseControl(it.fileName.toString()) }) {
            fs.open(path, OpenMode.READ).use { file ->
                val reader = RecordReader(LogFormat.MAX_CONTROL_BODY_BYTES)
                reader.open(file, LogFormat.HEADER_BYTES.toLong(), file.size())
                while (reader.next() == RecordReader.RECORD) {
                    var delete: DeleteRecord? = null
                    var commit: CommitInfo? = null
                    if (reader.type == RecordType.DELETE || reader.type == RecordType.COMMIT) {
                        body.open(reader.bytes, reader.bodyOffset, reader.bodyLength, reader.type)
                        if (reader.type == RecordType.DELETE) delete = DeleteRecord().also { body.readDelete(it) } else commit = CommitInfo().also { body.readCommit(it) }
                    }
                    records += ControlRecord(path, reader.recordStart until reader.position, delete, commit)
                }
            }
        }
        return records
    }
}
