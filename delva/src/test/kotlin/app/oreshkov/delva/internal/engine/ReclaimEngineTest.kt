package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.CompareOperand
import app.oreshkov.delva.DocId
import app.oreshkov.delva.Durability
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.compress.CompressionOptions
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.log.LogOptions
import app.oreshkov.delva.internal.lsm.FragmentEntry
import app.oreshkov.delva.internal.lsm.FragmentSegments
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.store.LogFragmentStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Space reclamation of lane files and deletion of indexed control files. */
class ReclaimEngineTest {
    private val dir = TestEngines.DIR

    private fun options(
        t: Int = 256,
        threshold: Long = EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD,
        durability: Durability = Durability.SyncEachBatch,
        laneFileBytes: Long = 16L shl 10,
        controlFileBytes: Long = 8L shl 10,
        memtableBytes: Long? = null,
        background: Boolean = false,
        reclaim: ReclaimOptions = ReclaimOptions(bytesPerSecond = 0),
        compaction: CompactionOptions = CompactionOptions(bytesPerSecond = 0, deletedRatio = 0.1),
        compression: CompressionOptions = CompressionOptions(),
    ) = EngineOptions(
        CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024), durability = durability),
        sorterBytes = 4096, keyArenaBytes = 1024, smallInsertThreshold = threshold, keySeed = 17,
        log = LogOptions(laneFileBytes = laneFileBytes, controlFileBytes = controlFileBytes, preallocateBytes = 4L shl 10, writeBufferBytes = 4096),
        memtableBytes = memtableBytes, backgroundFlush = background, segmentBlockBytes = 512,
        compaction = compaction, reclaim = reclaim, compression = compression,
    )

    // [0]{"all":[1]true,"i":[2]i,"hot":[3]"yes","items":[4][[5]{"k":[6]1,"hot":[7]"yes"},[8]{"k":[9]2,"s":[10]"x…"}]}
    private fun doc(i: Int) = """{"all":true,"i":$i,"hot":"yes","items":[{"k":1,"hot":"yes"},{"k":2,"s":"${"x".repeat(20 + i % 300)}"}]}"""

    private val types = mapOf("$" to "Root", "$.items[1]" to "Item")

    private fun Engine.hits(key: String, value: JsonQueryValue, type: String? = null): List<Pair<Long, Long>> =
        search(KeyValueQuery(key, value, ResultMode.CONTAINING_ELEMENT, limit = 100_000, type = type)).use { result ->
            result.iterator().asSequence().map { it.node.docId.raw to it.node.nodePre }.toList()
        }

    /** Everything a client can read: documents, searches, types (node lookups) and comparisons with the inserted text. */
    private fun reads(store: Engine): List<Any> {
        val documents = store.documents()
        return listOf(
            documents,
            store.hits("hot", JsonQueryValue.Str("yes")),
            store.hits("k", JsonQueryValue.Num(2), type = "Item"),
            store.objectsOfType("Root", limit = 100_000),
            store.countOfType("Item"),
            documents.keys.map { store.typeOf(NodeRef(DocId(it), 8)) },
            documents.map { (docId, json) -> store.isEqual(CompareOperand.Stored(NodeRef(DocId(docId), 0)), CompareOperand.Json(json)) },
        )
    }

    private fun laneFiles(fs: SimulatedFileSystemIo): List<Path> = fs.files().filter { it.parent?.fileName?.toString() == LogFiles.DATA_DIR }

    private fun laneBytes(fs: SimulatedFileSystemIo): Long = laneFiles(fs).sumOf { fs.bytes(it).size.toLong() }

    /** Every log file on disk is listed: reclaimed files are gone, not orphaned. */
    private fun assertNoUnlistedLogFiles(store: Engine, fs: SimulatedFileSystemIo, label: String) {
        val listed = store.files.listed().map { it.fileNo }.toSet()
        for (path in fs.files()) {
            val name = path.fileName.toString()
            val fileNo = LogFiles.parseLane(name)?.first ?: LogFiles.parseControl(name) ?: continue
            assertTrue(fileNo in listed, "$label: $path isn't listed")
        }
    }

    /** Every fragment of [docIds] is located in a listed lane file. */
    private fun assertLocationsListed(store: Engine, docIds: List<Long>, label: String) {
        val version = store.indexes.versions.acquire()
        try {
            val index = version.fragmentIndex()
            for (docId in docIds) {
                val entries = index.range(docId, 0, 0, Long.MAX_VALUE).toList()
                assertTrue(entries.isNotEmpty(), "$label: document $docId has fragments")
                for (e in entries) assertTrue(store.files.isListedLane(LogFragmentStore.fileNoOf(e.location)), "$label: fragment (${e.docId}, ${e.pre}) in lane file ${LogFragmentStore.fileNoOf(e.location)}")
            }
        } finally {
            version.release()
        }
    }

    /**
     * The fragment index's ranges of [docIds] are what collecting every source's entries, sorting them and keeping the
     * newest of each key gives: for whole documents and for windows of them, with relocated keys in several sources.
     */
    private fun assertRangesMerged(store: Engine, docIds: List<Long>, label: String) {
        fun fields(e: FragmentEntry) = listOf(e.docId, e.pre, e.kind.toLong(), e.seq.toLong(), e.location, e.length.toLong(), e.payloadOffset.toLong(), e.rawLength.toLong())
        val version = store.indexes.versions.acquire()
        try {
            val index = version.fragmentIndex()
            fun collected(docId: Long, fromPre: Long, fromKind: Int, toPre: Long): List<FragmentEntry> {
                val all = ArrayList<FragmentEntry>()
                for (m in version.memtables) all.addAll(m.fragments.range(docId, fromPre, fromKind, toPre))
                val from = FragmentSegments.key(docId, fromPre, fromKind, 0)
                val to = FragmentSegments.key(docId, toPre, FragmentEntry.MAX_KIND, FragmentEntry.MAX_SEQ)
                for (s in version.fragmentSegments.reversed()) {
                    val cursor = s.reader.cursor()
                    var ok = cursor.seekCeiling(from)
                    while (ok && Arrays.compareUnsigned(cursor.key.array, 0, cursor.key.size, to, 0, to.size) <= 0) {
                        all += FragmentSegments.decode(cursor, s.reader.source)
                        ok = cursor.next()
                    }
                }
                all.sort()
                return all.filterIndexed { i, e -> i == 0 || all[i - 1].compareTo(e) != 0 }
            }
            val random = Random(docIds.size.toLong())
            for (docId in docIds) {
                val whole = collected(docId, 0, 0, Long.MAX_VALUE)
                assertEquals(whole.map(::fields), index.range(docId, 0, 0, Long.MAX_VALUE).map(::fields), "$label: document $docId")
                repeat(3) {
                    val fromPre = random.nextLong(0, 12)
                    val toPre = fromPre + random.nextLong(0, 12)
                    val fromKind = if (random.nextBoolean()) 0 else FragmentKind.PART
                    assertEquals(
                        collected(docId, fromPre, fromKind, toPre).map(::fields), index.range(docId, fromPre, fromKind, toPre).map(::fields),
                        "$label: document $docId [$fromPre/$fromKind, $toPre]",
                    )
                }
            }
        } finally {
            version.release()
        }
    }

    @Test
    fun `deleted documents' lane space is reclaimed and every read stays the same, through replay and reopen`() {
        for (windows in listOf(false, true)) {
            for (t in listOf(32, 256, 4096)) {
                for (threshold in listOf(0L, EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD)) {
                    for (compressed in listOf(false, true)) {
                        val label = "windows=$windows T=$t threshold=$threshold compressed=$compressed"
                        val fs = SimulatedFileSystemIo(isWindows = windows)
                        // Compressed, the documents take a fraction of the bytes: smaller files keep the file count comparable.
                        val options = if (compressed) {
                            options(t, threshold, laneFileBytes = 4L shl 10, compression = CompressionOptions(firstTrainingBytes = 8L shl 10, retrainingBytes = 16L shl 10))
                        } else {
                            options(t, threshold, compression = CompressionOptions.DISABLED)
                        }
                        val expected = Engine.open(dir, options, fs).use { store ->
                            val docIds = (0 until 160).map { store.insert(doc(it), types).raw }
                            if (compressed && t >= 256) {
                                assertTrue(store.codecs.compressedFragments.get() > 0 && store.codecs.dictionaryCount > 0, "$label: ${store.codecs.compressedFragments} compressed, ${store.codecs.dictionaryCount} dictionaries")
                            }
                            store.flush()
                            val kept = docIds.filterIndexed { i, _ -> i % 4 == 0 }
                            store.deleteAll((docIds - kept.toSet()).map { DocId(it) })
                            store.flush()
                            store.compact()
                            assertTrue(store.files.index.discarded.values.sum() > 0, "$label: discarded bytes are counted")
                            val expected = reads(store)
                            val before = laneBytes(fs)
                            assertTrue(store.reclaim() > 0, label)
                            assertEquals(expected, reads(store), "$label: after reclaiming")
                            val after = laneBytes(fs)
                            assertTrue(after < before * 6 / 10, "$label: lane bytes $before → $after")
                            assertTrue(store.reclaimer.relocatedFragments > 0, label)
                            assertEquals(store.reclaimer.retiredFiles, store.reclaimer.deletedFiles, "$label: nothing pins the reclaimed files")
                            assertLocationsListed(store, kept, label)
                            assertRangesMerged(store, kept, label)
                            assertNoUnlistedLogFiles(store, fs, label)
                            // Index entries that still locate the retired files are shadowed or hidden, not damage.
                            store.verify().let { assertTrue(it.intact, "$label: ${it.issues.take(3)}") }
                            expected
                        }
                        fs.restart()
                        Engine.open(dir, options, fs).use { store ->
                            assertTrue(store.recoveryStats.replayedRelocations > 0, "$label: the relocations weren't flushed")
                            assertEquals(expected, reads(store), "$label: after replaying the relocations")
                            assertRangesMerged(store, store.documents().keys.toList(), "$label: replayed")
                            store.flush()
                        }
                        fs.restart()
                        Engine.open(dir, options, fs).use { store ->
                            assertEquals(0, store.recoveryStats.replayedRelocations, label)
                            assertEquals(expected, reads(store), "$label: from segments")
                            store.compact()
                            assertEquals(0, store.reclaim(), "$label: nothing left to reclaim")
                            assertEquals(expected, reads(store), "$label: after compacting the relocated entries")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `an open search keeps reading a reclaimed file, which is deleted when the search closes`() {
        for (windows in listOf(false, true)) {
            val fs = SimulatedFileSystemIo(isWindows = windows)
            Engine.open(dir, options(), fs).use { store ->
                val docIds = (0 until 120).map { store.insert(doc(it), types).raw }
                store.flush()
                val expected = store.documents()
                val search = store.search(KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT, limit = 5))
                val first = search.nextPage()!!
                store.deleteAll(docIds.drop(5).map { DocId(it) })
                store.flush()
                store.compact()
                val before = laneFiles(fs)
                assertTrue(store.reclaim() > 0, "windows=$windows")
                val listed = store.files.listed().map { it.fileNo }.toSet()
                val retired = before.filter { LogFiles.parseLane(it.fileName.toString())!!.first !in listed }
                assertTrue(retired.isNotEmpty(), "windows=$windows")
                for (path in retired) assertTrue(fs.exists(path), "windows=$windows: $path is pinned by the open search")
                val read = (first.hits + search.iterator().asSequence()).associate { it.node.docId.raw to it.readJsonAsString() }
                assertEquals(expected, read, "windows=$windows: the open search reads its snapshot")
                search.close()
                for (path in retired) assertFalse(fs.exists(path), "windows=$windows: $path deleted once unpinned")
                assertEquals(docIds.take(5), store.documents().keys.sorted())
                assertEquals(store.reclaimer.retiredFiles, store.reclaimer.deletedFiles)
            }
        }
    }

    @Test
    fun `a crash at any disk call of a reclamation loses nothing and leaves no reclaimed files behind`() {
        for (windows in listOf(false, true)) {
            for (durability in listOf(Durability.SyncEachBatch, Durability.None)) {
                val options = options(t = 64, durability = durability, reclaim = ReclaimOptions(bytesPerSecond = 0, maxRelocateFragments = 7))
                fun build(fs: SimulatedFileSystemIo): List<Any> = Engine.open(dir, options, fs).use { store ->
                    val docIds = (0 until 90).map { store.insert(doc(it), types).raw }
                    store.flush()
                    store.deleteAll(docIds.filterIndexed { i, _ -> i % 3 != 0 }.map { DocId(it) })
                    store.assignTypes(docIds.filterIndexed { i, _ -> i % 6 == 0 }.map { NodeRef(DocId(it), 8) to "Other" })
                    store.flush()
                    store.compact()
                    reads(store)
                }
                val calls = SimulatedFileSystemIo(isWindows = windows).let { dry ->
                    build(dry)
                    dry.restart()
                    Engine.open(dir, options, dry).use { store ->
                        val before = dry.calls
                        assertTrue(store.reclaim() > 0)
                        dry.calls - before
                    }
                }
                assertTrue(calls > 20, "$calls disk calls")
                for (k in 1..calls step maxOf(1L, calls / 50)) {
                    val fs = SimulatedFileSystemIo(isWindows = windows)
                    val expected = build(fs)
                    fs.restart()
                    val label = "windows=$windows $durability crash at reclamation call $k of $calls"
                    val store = Engine.open(dir, options, fs)
                    fs.crashAfter(k)
                    runCatching { store.reclaim() }
                    runCatching { store.close() }
                    fs.crash(Random(k))
                    Engine.open(dir, options, fs).use { reopened ->
                        assertEquals(expected, reads(reopened), label)
                        assertEquals(0, reopened.damagedDocIds.size, label)
                        assertNoUnlistedLogFiles(reopened, fs, label)
                        reopened.reclaim()
                        assertEquals(expected, reads(reopened), "$label: reclaimed after reopening")
                    }
                    fs.restart()
                    Engine.open(dir, options, fs).use { reopened -> assertEquals(expected, reads(reopened), "$label: reopened again") }
                }
            }
        }
    }

    @Test
    fun `discarded bytes persist in the MANIFEST and reclaimed files leave it`() {
        val fs = SimulatedFileSystemIo()
        val options = options(compaction = CompactionOptions(enabled = false, bytesPerSecond = 0, deletedRatio = 0.1), compression = CompressionOptions.DISABLED)
        val discarded = Engine.open(dir, options, fs).use { store ->
            val docIds = (0 until 80).map { store.insert(doc(it), types).raw }
            store.flush()
            store.deleteAll(docIds.drop(10).map { DocId(it) })
            store.flush()
            assertEquals(emptyMap(), store.files.index.discarded, "entries flushed before the deletes stay until compaction")
            assertTrue(store.compact() > 0)
            store.files.index.discarded.also { assertTrue(it.isNotEmpty()) }
        }
        fs.restart()
        Engine.open(dir, options, fs).use { store ->
            assertEquals(discarded, store.files.index.discarded)
            val reclaimed = discarded.keys.filter { store.files.isListedLane(it) }
            assertTrue(store.reclaim() > 0)
            store.flush()
            val left = store.files.listed().map { it.fileNo }
            assertTrue(reclaimed.any { it !in left })
            assertTrue(store.files.index.discarded.keys.all { it in left }, "only listed files have discarded bytes: ${store.files.index.discarded}")
        }
    }

    @Test
    fun `control files whose records are all indexed are deleted, and replay starts after them`() {
        val fs = SimulatedFileSystemIo()
        val options = options()
        val expected = Engine.open(dir, options, fs).use { store ->
            repeat(12) { round ->
                repeat(25) { i -> store.insert(doc(round * 100 + i), types) }
                store.flush()
            }
            assertTrue(store.files.deletedControlFiles > 0, "${store.files.deletedControlFiles} control files deleted")
            assertEquals(1, fs.files().count { LogFiles.parseControl(it.fileName.toString()) != null }, "only the active control file is left")
            reads(store)
        }
        fs.restart()
        Engine.open(dir, options, fs).use { store ->
            assertEquals(expected, reads(store))
            assertEquals(0, store.recoveryStats.replayedCommits)
        }
    }

    @Test
    fun `a damaged record abandons only the reclamation of its file`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(), fs).use { store ->
            val docIds = (0 until 400).map { store.insert(doc(it), types).raw }
            store.flush()
            store.deleteAll(docIds.filterIndexed { i, _ -> i % 5 != 0 }.map { DocId(it) })
            store.flush()
            store.compact()
            val sealed = store.files.listed().filter { !it.control && it.sealed }
            assertTrue(sealed.size >= 3, "${sealed.size} sealed lane files")
            val damaged = sealed.first()
            fs.flipBit(store.files.pathOf(damaged.fileNo, false, damaged.laneId), LogFormat.HEADER_BYTES + 20L, 3)
            assertTrue(store.reclaim() > 0)
            assertEquals(1, store.reclaimer.failedJobs)
            assertTrue(store.files.isListedLane(damaged.fileNo), "the damaged file stays")
            assertTrue(sealed.drop(1).none { store.files.isListedLane(it.fileNo) }, "the others were reclaimed")
        }
    }

    @Test
    fun `zeroed bytes before the SEAL abandon the reclamation of their file instead of ending it early`() {
        // Zeroed: only the magic of a record halfway through, or everything from that record on, the SEAL included.
        for (toTheEnd in listOf(false, true)) {
            val fs = SimulatedFileSystemIo()
            Engine.open(dir, options(), fs).use { store ->
                val docIds = (0 until 400).map { store.insert(doc(it), types).raw }
                store.flush()
                store.deleteAll(docIds.filterIndexed { i, _ -> i % 5 != 0 }.map { DocId(it) })
                store.flush()
                store.compact()
                val damaged = store.files.listed().first { !it.control && it.sealed }
                val path = store.files.pathOf(damaged.fileNo, false, damaged.laneId)
                val bytes = fs.bytes(path)
                val starts = ArrayList<Int>()
                var at = LogFormat.HEADER_BYTES
                while (at + LogFormat.RECORD_HEADER_BYTES <= bytes.size && LittleEndian.getShort(bytes, at).toInt() and 0xFFFF == LogFormat.RECORD_MAGIC) {
                    starts += at
                    at += LogFormat.padded(LogFormat.RECORD_HEADER_BYTES + LittleEndian.getInt(bytes, at + 4))
                }
                assertTrue(starts.size >= 4, "${starts.size} records")
                val from = starts[starts.size / 2]
                val to = if (toTheEnd) bytes.size else from + 2
                for (p in from until to) for (bit in 0 until 8) if (bytes[p].toInt() shr bit and 1 != 0) fs.flipBit(path, p.toLong(), bit)
                store.reclaim()
                assertEquals(1, store.reclaimer.failedJobs, "toTheEnd=$toTheEnd")
                assertTrue(store.files.isListedLane(damaged.fileNo), "toTheEnd=$toTheEnd: the damaged file stays")
                assertTrue(fs.files().contains(path), "toTheEnd=$toTheEnd: the damaged file isn't deleted")
            }
        }
    }

    @Test
    fun `reclamation under concurrent inserts, deletes and readers on the real disk never fails a read`() {
        val root = Files.createTempDirectory("delva-reclaim")
        try {
            val options = options(t = 256, laneFileBytes = 32L shl 10, controlFileBytes = 16L shl 10, memtableBytes = 32L shl 10, background = true)
            val live = Collections.synchronizedList(ArrayList<Long>())
            val deleted = ConcurrentHashMap.newKeySet<Long>()
            val failures = Collections.synchronizedList(ArrayList<Throwable>())
            Engine.open(root, options, JdkFileSystemIo()).use { store ->
                val stop = AtomicBoolean()
                val executor = Executors.newFixedThreadPool(5)
                try {
                    val readers = List(3) { r ->
                        executor.submit {
                            val random = Random(r)
                            try {
                                while (!stop.get()) {
                                    store.search(KeyValueQuery("hot", JsonQueryValue.Str("yes"), ResultMode.WHOLE_DOCUMENT, limit = 20)).use { result ->
                                        for ((n, hit) in result.iterator().withIndex()) {
                                            val json = hit.readJsonAsString()
                                            val i = I.find(json)!!.groupValues[1].toInt()
                                            assertEquals(doc(i), json, "document ${hit.node.docId.raw}")
                                            // Keep old versions pinned while reclamation runs.
                                            if (n % 50 == 0) Thread.sleep(random.nextLong(0, 5))
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                failures += t
                            }
                        }
                    }
                    val writers = List(2) { w ->
                        executor.submit {
                            try {
                                val random = Random(100 + w)
                                repeat(1500) { n ->
                                    live += store.insert(doc(w * 10_000 + n), types).raw
                                    if (n % 3 == 2) {
                                        val gone = synchronized(live) { List(2) { live.removeAt(random.nextInt(live.size)) } }
                                        store.deleteAll(gone.map { DocId(it) })
                                        deleted += gone
                                    }
                                }
                            } catch (t: Throwable) {
                                failures += t
                            }
                        }
                    }
                    writers.forEach { it.get(300, TimeUnit.SECONDS) }
                    stop.set(true)
                    readers.forEach { it.get(300, TimeUnit.SECONDS) }
                } finally {
                    stop.set(true)
                    executor.shutdownNow()
                }
                if (failures.isNotEmpty()) throw AssertionError("${failures.size} failures", failures.first())
                store.flush()
                store.compact()
                store.reclaim()
                assertTrue(store.reclaimer.deletedFiles > 0, "${store.reclaimer.retiredFiles} retired, ${store.reclaimer.deletedFiles} deleted")
                assertEquals(live.sorted(), store.documents().keys.sorted())
            }
            Engine.open(root, options, JdkFileSystemIo()).use { store ->
                val documents = store.documents()
                assertEquals(live.sorted(), documents.keys.sorted())
                for (json in documents.values) assertEquals(doc(I.find(json)!!.groupValues[1].toInt()), json)
                assertTrue(deleted.none { it in documents })
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private companion object {
        val I = Regex("\"i\":(\\d+)")
    }
}
