package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.ControlRecords
import app.oreshkov.delva.internal.log.DeleteRecord
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.log.RecordReader
import app.oreshkov.delva.internal.log.RecordType
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deleting whole documents on the durable engine. The oracle comparisons are in [EngineModelTest]. */
class DeleteEngineTest {
    private val dir = TestEngines.DIR

    private fun options(
        t: Int = 256,
        threshold: Long = EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD,
        memtableBytes: Long = EngineOptions.DEFAULT_MEMTABLE_BYTES,
        maxDeleteRecordDocIds: Int = EngineOptions.DEFAULT_MAX_DELETE_RECORD_DOC_IDS,
        startWriterThread: Boolean = true,
    ) = EngineOptions(
        CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024)),
        sorterBytes = 4096, keyArenaBytes = 1024, smallInsertThreshold = threshold, keySeed = 11, log = TestEngines.LOG,
        memtableBytes = memtableBytes, backgroundFlush = false, maxDeleteRecordDocIds = maxDeleteRecordDocIds, startWriterThread = startWriterThread,
    )

    // [0]{"all":[1]true,"i":[2]i,"items":[3][[4]{"k":[5]1},[6]{"k":[7]2}]}
    private fun doc(i: Int) = """{"all":true,"i":$i,"items":[{"k":1},{"k":2}]}"""

    private val types = mapOf("$" to "Root", "$.items[1]" to "Item")

    private fun node(docId: Long, pre: Long) = NodeRef(DocId(docId), pre)

    private fun Engine.itemHits(type: String? = null): List<Pair<Long, Long>> =
        search(KeyValueQuery("k", JsonQueryValue.Num(2), ResultMode.CONTAINING_ELEMENT, limit = 1000, type = type)).use { result ->
            result.iterator().asSequence().map { it.node.docId.raw to it.node.nodePre }.toList()
        }

    /** Every read shows exactly the documents [live] and none of [deleted]. */
    private fun check(store: Engine, live: List<Long>, deleted: List<Long>, label: String) {
        assertEquals(live, store.documents().keys.sorted(), "$label: documents")
        assertEquals(live.map { it to 6L }, store.itemHits(), "$label: containing hits")
        assertEquals(live.map { it to 6L }, store.itemHits(type = "Item"), "$label: typed hits")
        assertEquals(live.map { node(it, 0) }, store.objectsOfType("Root"), "$label: objectsOfType")
        assertEquals(live.size.toLong(), store.countOfType("Item"), "$label: countOfType")
        for (docId in live) assertEquals("Item", store.typeOf(node(docId, 6)), label)
        for (docId in deleted) {
            assertFailsWith<DocumentNotFoundException>(label) { store.typeOf(node(docId, 6)) }
            assertFailsWith<DocumentNotFoundException>(label) { store.assignType(node(docId, 4), "Other") }
            assertFalse(store.delete(DocId(docId)), "$label: deleting $docId again")
        }
    }

    @Test
    fun `deleted documents disappear from every read at every fragment size, promoted or not, through replay and flush`() {
        for (t in listOf(32, 4096, 64 * 1024)) {
            for (threshold in listOf(0L, EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD)) {
                val label = "T=$t threshold=$threshold"
                val fs = SimulatedFileSystemIo()
                val options = options(t, threshold)
                val docIds = TestEngines.open(options, fs).use { store ->
                    val docIds = (0 until 6).map { store.insert(doc(it), types).raw }
                    assertTrue(store.delete(DocId(docIds[1])), label)
                    assertEquals(2, store.deleteAll(listOf(DocId(docIds[3]), DocId(docIds[4]), DocId(docIds[3]), DocId(docIds[1]))), label)
                    assertEquals(0, store.deleteAll(emptyList()))
                    check(store, listOf(docIds[0], docIds[2], docIds[5]), listOf(docIds[1], docIds[3], docIds[4]), label)
                    docIds
                }
                val live = listOf(docIds[0], docIds[2], docIds[5])
                val deleted = listOf(docIds[1], docIds[3], docIds[4])
                fs.restart()
                TestEngines.open(options, fs).use { store ->
                    check(store, live, deleted, "$label after replay")
                    store.flush()
                }
                assertTrue(fs.files().any { LogFiles.parseBitmap(it.fileName.toString())?.second == LogFiles.BITMAP_DELETED }, "$label: a deleted-docId file")
                fs.restart()
                TestEngines.open(options, fs).use { store ->
                    assertEquals(0, store.recoveryStats.replayedCommits, label)
                    check(store, live, deleted, "$label from the bitmap file")
                    // DocIds are never reused.
                    assertTrue(store.insert(doc(6)).raw > docIds.last(), label)
                }
            }
        }
    }

    @Test
    fun `deletes alone fill the memtable, so a reopen replays only the deletes since the last flush`() {
        val fs = SimulatedFileSystemIo()
        // 16 KiB at 16 bytes a deleted docId: a flush every 1024 deletes, though they add no postings.
        val options = options(memtableBytes = 16L shl 10)
        val docIds = TestEngines.open(options, fs).use { store ->
            val docIds = (0 until 3000).map { store.insert(doc(it)) }
            store.flush()
            val flushes = store.indexes.flushes
            for (docId in docIds.drop(10)) store.delete(docId)
            assertTrue(store.indexes.flushes - flushes >= 2, "${store.indexes.flushes - flushes} flushes during 2990 deletes")
            docIds
        }
        fs.restart()
        TestEngines.open(options, fs).use { store ->
            assertTrue(store.recoveryStats.replayedDeletes < 1024, "${store.recoveryStats.replayedDeletes} deletes replayed")
            assertEquals(docIds.take(10).map { it.raw }, store.documents().keys.sorted())
        }
    }

    @Test
    fun `docIds that never named a stored document fail the whole call`() {
        val fs = SimulatedFileSystemIo()
        TestEngines.open(options(threshold = 256), fs).use { store ->
            val stored = store.insert(doc(0)).raw
            // Rejected before anything was written: the docId is visible but has no data.
            val rejectedSmall = store.registry.peekNextDocId()
            assertFailsWith<DocumentRejectedException> { store.insert("""{"a":1,"a":2}""") }
            // Rejected after promotion: the docId is aborted.
            val rejectedLarge = store.registry.peekNextDocId()
            assertFailsWith<DocumentRejectedException> { store.insert("""{"pad":"${"x".repeat(2000)}","a":1,"a":2}""") }
            assertTrue(store.registry.current().visibility.isAborted(rejectedLarge))
            val unassigned = store.registry.peekNextDocId()
            for (docId in listOf(rejectedSmall, rejectedLarge, unassigned, unassigned + 100, -1L)) {
                assertFailsWith<DocumentNotFoundException>("docId $docId") { store.delete(DocId(docId)) }
                val e = assertFailsWith<DocumentNotFoundException>("docId $docId") { store.deleteAll(listOf(DocId(stored), DocId(docId))) }
                assertEquals(docId, e.docId.raw)
            }
            assertEquals(listOf(stored), store.documents().keys.toList(), "a failing call deletes nothing")
            assertTrue(store.delete(DocId(stored)))
            assertFailsWith<DocumentNotFoundException> { store.deleteAll(listOf(DocId(stored), DocId(unassigned))) }
        }
    }

    @Test
    fun `a document still being inserted can't be deleted`() {
        val fs = SimulatedFileSystemIo()
        TestEngines.open(options(threshold = 256), fs).use { store ->
            val streaming = CountDownLatch(1)
            val release = CountDownLatch(1)
            val docId = store.registry.peekNextDocId()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val bytes = """{"all":true,"pad":"${"x".repeat(4000)}"}""".toByteArray()
                val insert = executor.submit(Callable { store.insert(HalfwayStream(bytes) { streaming.countDown(); release.await() }).raw })
                assertTrue(streaming.await(30, TimeUnit.SECONDS))
                // A later commit publishes a snapshot that holds the streaming insert's docId as in flight.
                val later = store.insert(doc(1)).raw
                assertTrue(later > docId)
                assertEquals(1, store.registry.current().visibility.inFlightCount)
                assertFailsWith<DocumentNotFoundException> { store.delete(DocId(docId)) }
                assertEquals(1, store.deleteAll(listOf(DocId(later))))
                release.countDown()
                assertEquals(docId, insert.get(30, TimeUnit.SECONDS))
                assertTrue(store.delete(DocId(docId)))
                assertEquals(emptyMap(), store.documents())
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
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

    @Test
    fun `an open search keeps reading deleted documents through flushes`() {
        val fs = SimulatedFileSystemIo()
        TestEngines.open(options(t = 64, memtableBytes = 0), fs).use { store ->
            val docIds = (0 until 20).map { store.insert(doc(it), types).raw }
            val expected = docIds.associateWith { doc(docIds.indexOf(it)) }
            store.search(KeyValueQuery("all", JsonQueryValue.Bool(true), limit = 3)).use { open ->
                val first = open.nextPage()!!.hits.associate { it.docId.raw to it.readJsonAsString() }
                assertEquals(docIds.size, store.deleteAll(docIds.map { DocId(it) }))
                store.flush()
                val added = store.insert(doc(99)).raw
                store.flush()
                assertEquals(listOf(added to 6L), store.itemHits(), "a new search doesn't see them")
                val rest = open.iterator().asSequence().associate { it.docId.raw to (it.readJsonAsString() to it.type) }
                assertEquals(expected, first + rest.mapValues { it.value.first })
                assertTrue(rest.values.all { it.second == "Root" }, "hit types come from the result's snapshot")
            }
            assertEquals(1, store.documents().size)
        }
    }

    @Test
    fun `in one batch, an assignment before a delete succeeds and one after it fails`() {
        val fs = SimulatedFileSystemIo()
        val docIds = TestEngines.open(options(), fs).use { store -> (0 until 3).map { store.insert(doc(it)).raw } }
        fs.restart()
        TestEngines.open(options(startWriterThread = false), fs).use { store ->
            val writer = store.writer
            val before = writer.submit(WriteRequest.AssignTypes(longArrayOf(docIds[0]), longArrayOf(4), arrayOf("Before")))
            val delete = writer.submit(WriteRequest.Delete(longArrayOf(docIds[0], docIds[1])))
            val again = writer.submit(WriteRequest.Delete(longArrayOf(docIds[1], docIds[2])))
            val after = writer.submit(WriteRequest.AssignTypes(longArrayOf(docIds[2], docIds[1]), longArrayOf(4, 4), arrayOf("After", "After")))
            writer.runBatch(wait = false)
            assertEquals(writer.lsn, before.done.get())
            delete.done.get()
            again.done.get()
            assertEquals(listOf(docIds[0], docIds[1]), delete.deleting.toList())
            assertEquals(listOf(docIds[2]), again.deleting.toList(), "a docId deleted earlier in the batch is skipped")
            val failure = assertFailsWith<ExecutionException> { after.done.get() }
            assertIs<DocumentNotFoundException>(failure.cause)
            assertEquals(emptyMap(), store.documents())
        }
        fs.restart()
        TestEngines.open(options(), fs).use { store ->
            assertEquals(emptyMap(), store.documents())
            assertEquals(listOf("Before"), store.listTypes().map { it.name }, "the assignment took effect before the delete")
            assertEquals(emptyList(), store.objectsOfType("Before"))
        }
    }

    @Test
    fun `a delete call spans several records, and one cut off by a crash has no effect`() {
        val options = options(maxDeleteRecordDocIds = 3)
        /** Ten documents, then all of them deleted in one call, without a clean close: the MANIFEST doesn't know its LSNs. */
        fun deleteAllWithoutClose(fs: SimulatedFileSystemIo): List<Long> {
            val docIds = TestEngines.open(options, fs).use { store -> (0 until 10).map { store.insert(doc(it)).raw } }
            fs.restart()
            TestEngines.open(options, fs).let { store -> assertEquals(10, store.deleteAll(docIds.map { DocId(it) })) }
            fs.restart()
            return docIds
        }

        val whole = SimulatedFileSystemIo()
        val docIds = deleteAllWithoutClose(whole)
        assertEquals(listOf(docIds.subList(0, 3), docIds.subList(3, 6), docIds.subList(6, 9), docIds.subList(9, 10)), controlRecords(whole).mapNotNull { it.deleted })
        TestEngines.open(options, whole).use { store -> assertEquals(emptyMap(), store.documents(), "replayed whole") }

        // Cut the call's last record off as a torn tail: none of the call's deletes survive.
        val cut = SimulatedFileSystemIo()
        deleteAllWithoutClose(cut)
        val last = controlRecords(cut).last { it.deleted != null }
        cut.open(last.path, OpenMode.READ_WRITE).use { file ->
            val zeros = ByteArray(last.bytes.count())
            file.write(last.bytes.first, zeros, 0, zeros.size)
            file.force(true)
        }
        TestEngines.open(options, cut).use { store ->
            assertEquals(docIds, store.documents().keys.sorted())
            // The reopened store appends after the cut call; its own call must not continue that one.
            assertEquals(2, store.deleteAll(listOf(DocId(docIds[8]), DocId(docIds[9]))))
        }
        cut.restart()
        TestEngines.open(options, cut).use { store -> assertEquals(docIds.take(8), store.documents().keys.sorted()) }
    }

    /** A control record: its file, its bytes (padding included) and, for DELETE records, the docIds. */
    private class ControlRecord(val path: Path, val bytes: LongRange, val deleted: List<Long>?)

    /** Every record of the control files, oldest first. */
    private fun controlRecords(fs: SimulatedFileSystemIo): List<ControlRecord> {
        val records = ArrayList<ControlRecord>()
        val body = ControlRecords.Body("test")
        val delete = DeleteRecord()
        for (path in fs.files().filter { LogFiles.parseControl(it.fileName.toString()) != null }.sortedBy { LogFiles.parseControl(it.fileName.toString()) }) {
            fs.open(path, OpenMode.READ).use { file ->
                val reader = RecordReader(LogFormat.MAX_CONTROL_BODY_BYTES)
                reader.open(file, LogFormat.HEADER_BYTES.toLong(), file.size())
                while (reader.next() == RecordReader.RECORD) {
                    val deleted = if (reader.type != RecordType.DELETE) null else {
                        body.open(reader.bytes, reader.bodyOffset, reader.bodyLength, reader.type)
                        body.readDelete(delete)
                        delete.docIds.copyOf(delete.count).toList()
                    }
                    records += ControlRecord(path, reader.recordStart until reader.position, deleted)
                }
            }
        }
        return records
    }

    @Test
    fun `the suspending store deletes`() = runBlocking {
        val store = JsonStore.open(dir, EngineOptions(CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024), log = TestEngines.LOG), SimulatedFileSystemIo())
        store.use {
            val a = it.insert(doc(0), types)
            val b = it.insert(doc(1), types)
            assertTrue(it.delete(a))
            assertFalse(it.delete(a))
            assertEquals(1, it.deleteAll(listOf(a, b)))
            assertFailsWith<DocumentNotFoundException> { it.typeOf(NodeRef(b, 0)) }
            assertEquals(emptyList(), it.objectsOfType("Root"))
            assertNull(it.listTypes().firstOrNull { t -> t.name == "Missing" })
        }
    }
}
