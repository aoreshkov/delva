package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.DocId
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.lsm.FragmentSegments
import app.oreshkov.delva.internal.lsm.SegmentFormat
import app.oreshkov.delva.internal.lsm.SegmentHandle
import app.oreshkov.delva.internal.lsm.SegmentTermSource
import app.oreshkov.delva.internal.lsm.TypeEntry
import app.oreshkov.delva.internal.lsm.TypeSegments
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Compaction on the durable engine. Merges and the policy alone are in `CompactionTest`. */
class CompactionEngineTest {
    private val dir = TestEngines.DIR

    private fun options(
        t: Int = 256,
        threshold: Long = EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD,
        memtableBytes: Long = 0,
        memtableBudget: Long? = null,
        background: Boolean = false,
        compaction: CompactionOptions = CompactionOptions(bytesPerSecond = 0),
    ) = EngineOptions(
        CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024)),
        sorterBytes = 4096, keyArenaBytes = 1024, smallInsertThreshold = threshold, keySeed = 13, log = TestEngines.LOG,
        memtableBytes = memtableBytes, memtableBudgetBytes = memtableBudget, backgroundFlush = background,
        segmentBlockBytes = 512, compaction = compaction,
    )

    // [0]{"all":[1]true,"i":[2]i,"hot":[3]"yes","items":[4][[5]{"k":[6]1,"hot":[7]"yes"},[8]{"k":[9]2,"s":[10]"x…"}]}
    private fun doc(i: Int) = """{"all":true,"i":$i,"hot":"yes","items":[{"k":1,"hot":"yes"},{"k":2,"s":"${"x".repeat(i % 50)}"}]}"""

    private val types = mapOf("$" to "Root", "$.items[1]" to "Item")

    private fun Engine.hits(key: String, value: JsonQueryValue, type: String? = null): List<Pair<Long, Long>> =
        search(KeyValueQuery(key, value, ResultMode.CONTAINING_ELEMENT, limit = 100_000, type = type)).use { result ->
            result.iterator().asSequence().map { it.node.docId.raw to it.node.nodePre }.toList()
        }

    /** Everything a client can read, for comparing stores. */
    private fun reads(store: Engine): List<Any> = listOf(
        store.documents(),
        store.hits("hot", JsonQueryValue.Str("yes")),
        store.hits("k", JsonQueryValue.Num(2)),
        store.hits("k", JsonQueryValue.Num(2), type = "Item"),
        store.hits("k", JsonQueryValue.Num(1), type = "Other"),
        store.objectsOfType("Item", limit = 100_000),
        store.objectsOfType("Other", limit = 100_000),
        store.countOfType("Root"),
        store.listTypes().map { it.name },
    )

    private fun segmentCount(store: Engine): Int = store.indexes.versions.current().let { it.postingSegments.size + it.fragmentSegments.size + it.typeSegments.size }

    /** DocIds with an entry in any segment of [store]. */
    private fun indexedDocIds(store: Engine): Set<Long> {
        val docIds = HashSet<Long>()
        val version = store.indexes.versions.current()
        for (s in version.postingSegments) {
            val cursor = s.reader.cursor()
            var ok = cursor.first()
            while (ok) {
                val source = SegmentTermSource.at(cursor)
                while (source.docId != Long.MAX_VALUE) docIds += source.docId.also { source.skipDocument() }
                ok = cursor.next()
            }
        }
        for (s in version.fragmentSegments) FragmentSegments.entries(s.reader).forEach { docIds += it.docId }
        for (s in version.typeSegments) TypeSegments.entries(s.reader, s.types!!.baseLsn).forEach { docIds += it.docId }
        return docIds
    }

    private fun indexFiles(fs: SimulatedFileSystemIo): List<Path> = fs.files().filter { it.parent?.fileName?.toString() == LogFiles.INDEX_DIR }

    /** A workload of inserts with types, reassignments, removals and deletes, flushed after every batch. */
    private fun workload(store: Engine, random: Random, rounds: Int, perRound: Int = 12) {
        val live = ArrayList<Long>()
        repeat(rounds) { round ->
            repeat(perRound) { i -> live += store.insert(doc(round * 100 + i), types).raw }
            val picks = List(4) { live[random.nextInt(live.size)] }.distinct()
            store.assignTypes(picks.map { NodeRef(DocId(it), 8) to if (random.nextInt(3) == 0) null else listOf("Item", "Other")[random.nextInt(2)] })
            if (round % 3 == 2) {
                val gone = List(3) { live[random.nextInt(live.size)] }.distinct()
                store.deleteAll(gone.map { DocId(it) })
                live.removeAll(gone.toSet())
            }
        }
    }

    @Test
    fun `reads are the same with and without compaction at every fragment size, and segments stay logarithmic`() {
        for (t in listOf(32, 4096, 64 * 1024)) {
            for (threshold in listOf(0L, EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD)) {
                val label = "T=$t threshold=$threshold"
                val reference = Engine.open(dir, options(t, threshold, compaction = CompactionOptions(enabled = false)), SimulatedFileSystemIo()).use { store ->
                    workload(store, Random(t + threshold), rounds = 30)
                    store.flush()
                    reads(store)
                }
                val fs = SimulatedFileSystemIo()
                Engine.open(dir, options(t, threshold), fs).use { store ->
                    workload(store, Random(t + threshold), rounds = 30)
                    store.flush()
                    val flushes = store.indexes.flushes
                    val compactor = store.indexes.compactor
                    assertTrue(compactor.compactions > 0, "$label: compacted")
                    // Three indexes, each O(log n): the policy leaves fewer than minMergeSegments per size class.
                    val bound = 3 * 4 * (64 - java.lang.Long.numberOfLeadingZeros(flushes))
                    assertTrue(segmentCount(store) <= bound, "$label: ${segmentCount(store)} segments after $flushes flushes")
                    assertEquals(reference, reads(store), label)
                    assertTrue(compactor.writeAmplification > 1.0, "$label: write amplification ${compactor.writeAmplification}")
                    val live = store.files.index.segments.map { it.fileNo }.toSet()
                    assertEquals(live, indexFiles(fs).mapNotNull { LogFiles.parseSegment(it.fileName.toString())?.first }.toSet(), "$label: inputs deleted, no leftovers")
                    println("$label: $flushes flushes, ${compactor.compactions} compactions (${compactor.byReason.toList()}), ${segmentCount(store)} segments, write amplification ${"%.2f".format(compactor.writeAmplification)}")
                }
                fs.restart()
                Engine.open(dir, options(t, threshold), fs).use { store ->
                    assertEquals(reference, reads(store), "$label: after reopening")
                    assertEquals(0, store.replayedCommits, label)
                }
            }
        }
    }

    @Test
    fun `deleted documents' entries are purged, their docIds stay deleted, and the index shrinks`() {
        val fs = SimulatedFileSystemIo()
        val docIds: List<Long>
        Engine.open(dir, options(threshold = 0, memtableBytes = EngineOptions.DEFAULT_MEMTABLE_BYTES, compaction = CompactionOptions(bytesPerSecond = 0, deletedRatio = 0.5)), fs).use { store ->
            docIds = (0 until 200).map { store.insert(doc(it), types).raw }
            store.flush()
            val before = store.indexes.segmentBytes
            assertEquals(docIds.toSet(), indexedDocIds(store))
            val deleted = docIds.filter { it % 4 != 0L }
            store.deleteAll(deleted.map { DocId(it) })
            // The deletes reach a bitmap file with the next flush; only then may compaction purge them.
            assertEquals(0, store.compact())
            store.flush()
            assertTrue(store.indexes.compactor.byReason[CompactionPolicy.Reason.DELETED.ordinal] >= 3, "posting, fragment and type segments rewritten")
            assertEquals(docIds.filter { it % 4 == 0L }.toSet(), indexedDocIds(store))
            assertTrue(store.indexes.segmentBytes < before / 2, "index ${store.indexes.segmentBytes} bytes, ${before} before")
            for (docId in deleted.take(20)) assertFalse(store.delete(DocId(docId)), "deleting $docId again")
            assertEquals(50, store.documents().size)
            assertEquals(0, store.compact(), "nothing left to do")
        }
        fs.restart()
        Engine.open(dir, options(threshold = 0), fs).use { store ->
            assertEquals(docIds.filter { it % 4 == 0L }, store.documents().keys.sorted())
            assertEquals(50L, store.countOfType("Item"))
            assertFalse(store.delete(DocId(docIds[1])))
        }
    }

    @Test
    fun `an open search keeps reading what compaction purged, and the input files go when it closes`() {
        for (windows in listOf(false, true)) {
            val fs = SimulatedFileSystemIo(isWindows = windows)
            Engine.open(dir, options(compaction = CompactionOptions(bytesPerSecond = 0, deletedRatio = 0.1, enabled = false)), fs).use { store ->
                val docIds = (0 until 40).map { store.insert(doc(it), types).raw }
                // An insert returns before the writer flushes its memtable; without this flush the search could pin
                // that memtable instead of the segment flushed from it, and the segment would be an unpinned input.
                store.flush()
                val expected = store.documents()
                val search = store.search(KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT, limit = 5))
                val first = search.nextPage()!!
                store.deleteAll(docIds.drop(5).map { DocId(it) })
                store.flush()
                val inputs = store.indexes.versions.current().let { it.postingSegments + it.fragmentSegments + it.typeSegments }.map(SegmentHandle::path)
                assertTrue(store.compact() > 0)
                assertTrue(indexedDocIds(store).all { it in docIds.take(5) }, "windows=$windows: purged")
                for (path in inputs) assertTrue(fs.exists(path), "windows=$windows: $path is pinned by the open search")
                val read = (first.hits + search.iterator().asSequence()).associate { it.node.docId.raw to it.readJsonAsString() }
                assertEquals(expected, read, "windows=$windows: the open search reads its snapshot")
                search.close()
                for (path in inputs) assertFalse(fs.exists(path), "windows=$windows: $path deleted once unpinned")
                assertEquals(docIds.take(5), store.documents().keys.sorted())
            }
        }
    }

    @Test
    fun `reassignments leave no tombstones once the type segments are compacted`() {
        Engine.open(dir, options(compaction = CompactionOptions(bytesPerSecond = 0, minMergeSegments = 2, tombstoneRatio = 0.05)), SimulatedFileSystemIo()).use { store ->
            val docIds = (0 until 30).map { store.insert(doc(it), types).raw }
            val random = Random(5)
            repeat(40) { round ->
                store.assignTypes(docIds.shuffled(random).take(8).map { NodeRef(DocId(it), 8) to listOf("Item", "Other", null)[(round + it.toInt()) % 3] })
            }
            store.flush()
            store.compact()
            val segments = store.indexes.versions.current().typeSegments
            assertEquals(1, segments.size, "one type segment left")
            val info = segments[0].types!!
            val entries = TypeSegments.entries(segments[0].reader, info.baseLsn).asSequence().toList()
            assertTrue(entries.none { it.space == TypeEntry.INVERTED && it.value == TypeEntry.TOMBSTONE }, "no tombstones")
            assertTrue(entries.none { it.space == TypeEntry.FORWARD && it.value == 0 }, "no 'none' entries")
            // With no tombstones and no deletes, the approximate counts are exact.
            for (info2 in store.listTypes()) assertEquals(store.countOfType(info2.name), info2.approximateCount, info2.name)
        }
    }

    @Test
    fun `the index size stays bounded under insert and delete churn`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(memtableBytes = 16L shl 10), fs).use { store ->
            var previous = emptyList<Long>()
            val sizes = ArrayList<Long>()
            repeat(40) { round ->
                val inserted = (0 until 60).map { store.insert(doc(round * 1000 + it), types).raw }
                if (previous.isNotEmpty()) store.deleteAll(previous.map { DocId(it) })
                previous = inserted
                store.flush()
                sizes += store.indexes.segmentBytes
            }
            val steady = sizes.subList(5, 10).max()
            assertTrue(sizes.takeLast(10).all { it <= 2 * steady }, "index bytes by round: $sizes")
            assertEquals(60, store.documents().size)
            val c = store.indexes.compactor
            println("churn: index bytes $sizes, ${c.compactions} compactions (${c.byReason.toList()}), write amplification ${"%.2f".format(c.writeAmplification)}")
        }
    }

    @Test
    fun `a damaged input abandons only its compaction, and searches still report the damage`() {
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(compaction = CompactionOptions(enabled = false)), fs).use { store ->
            repeat(8) { store.insert(doc(it), types) }
            store.flush()
        }
        val posting = indexFiles(fs).filter { it.fileName.toString().startsWith("posting") }.minBy { it.toString() }
        fs.flipBit(posting, 20, 2)
        fs.restart()
        Engine.open(dir, options(compaction = CompactionOptions(bytesPerSecond = 0, minMergeSegments = 2)), fs).use { store ->
            assertTrue(store.indexes.compactor.failedJobs >= 1)
            assertTrue(store.indexes.versions.current().postingSegments.any { it.path == posting }, "the damaged segment stays")
            assertTrue(store.indexes.compactor.compactions > 0, "other indexes were compacted")
            assertFailsWith<CorruptionException> { store.hits("hot", JsonQueryValue.Str("yes")) + store.hits("k", JsonQueryValue.Num(1)) + store.hits("all", JsonQueryValue.Bool(true)) }
            store.insert(doc(100))
            store.flush()
            assertEquals(0, store.compact(), "the damaged segment isn't picked again")
        }
    }

    @Test
    fun `closing stops a throttled background compaction, and the store opens cleanly`() {
        val fs = SimulatedFileSystemIo()
        val expected: Map<Long, String>
        Engine.open(dir, options(background = true, compaction = CompactionOptions(bytesPerSecond = 1, minMergeSegments = 2)), fs).use { store ->
            repeat(30) { store.insert(doc(it), types) }
            store.flush()
            expected = store.documents()
        }
        fs.restart()
        val start = System.nanoTime()
        Engine.open(dir, options(compaction = CompactionOptions(enabled = false)), fs).use { store ->
            assertEquals(expected, store.documents())
            assertTrue(fs.files().none { it.fileName.toString().endsWith(".tmp") }, "no temporary files: ${fs.files()}")
        }
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(30))
    }

    @Test
    fun `a large document's runs across flushes merge into bounded runs, spilling long lists`() {
        val json = """{"all":true,"items":[${(0 until 8000).joinToString(",") { """{"k":$it,"hot":"yes","tag":"t${it % 7}"}""" }}]}"""
        val reference = Engine.open(dir, options(t = 256, threshold = 0, memtableBytes = EngineOptions.DEFAULT_MEMTABLE_BYTES), SimulatedFileSystemIo()).use { store ->
            store.insert(json)
            store.hits("hot", JsonQueryValue.Str("yes"))
        }
        val fs = SimulatedFileSystemIo()
        // The memtable budget is the flush size, so the insert can't add its next run until a flush has made room:
        // back-pressure, not the writer's timing, decides that the document's runs span hundreds of flushes.
        Engine.open(dir, options(t = 256, threshold = 0, memtableBytes = 8L shl 10, memtableBudget = 8L shl 10, compaction = CompactionOptions(bytesPerSecond = 0, maxRunBytes = 1024, listMemoryBytes = 2048)), fs).use { store ->
            store.insert(json)
            store.flush()
            store.compact()
            assertTrue(store.indexes.memtableWaits > 100, "${store.indexes.memtableWaits} waits for memtable room")
            // How many flushes the writer made on top of those still depends on timing, so the bound is relative to
            // them: merging must leave far fewer segments than flushes, not an exact count.
            val segments = store.indexes.versions.current().postingSegments.size.toLong()
            assertTrue(segments < maxOf(12L, store.indexes.flushes / 3), "$segments posting segments after ${store.indexes.flushes} flushes")
            assertEquals(reference, store.hits("hot", JsonQueryValue.Str("yes")))
            assertTrue(fs.files().none { it.fileName.toString().endsWith(".tmp") })
        }
    }

    @Test
    fun `a crash at any disk call of a compaction loses nothing and leaves no orphans`() {
        for (windows in listOf(false, true)) {
            fun build(fs: SimulatedFileSystemIo): List<Any> =
                Engine.open(dir, options(compaction = CompactionOptions(enabled = false)), fs).use { store ->
                    workload(store, Random(7), rounds = 8)
                    store.flush()
                    reads(store)
                }
            val compactOptions = options(compaction = CompactionOptions(bytesPerSecond = 0, minMergeSegments = 2, deletedRatio = 0.05))
            val calls = SimulatedFileSystemIo(isWindows = windows).let { dry ->
                build(dry)
                dry.restart()
                Engine.open(dir, options(compaction = CompactionOptions(enabled = false)), dry).use { store ->
                    val before = dry.calls
                    assertTrue(store.compact() > 0)
                    dry.calls - before
                }
            }
            assertTrue(calls > 20, "$calls disk calls")
            for (k in 1..calls step maxOf(1L, calls / 60)) {
                val fs = SimulatedFileSystemIo(isWindows = windows)
                val expected = build(fs)
                fs.restart()
                val label = "windows=$windows crash at compaction call $k of $calls"
                val store = Engine.open(dir, options(compaction = CompactionOptions(enabled = false)), fs)
                fs.crashAfter(k)
                runCatching { store.compact() }
                runCatching { store.close() }
                fs.crash(Random(k))
                Engine.open(dir, compactOptions, fs).use { store ->
                    assertEquals(expected, reads(store), label)
                    val live = store.files.index.segments.map { it.fileNo }.toSet()
                    for (path in indexFiles(fs)) {
                        val no = LogFiles.parseSegment(path.fileName.toString())?.first
                        assertTrue(no == null || no in live, "$label: orphan $path")
                        assertFalse(path.fileName.toString().endsWith(".tmp"), "$label: leftover $path")
                    }
                }
            }
        }
    }
}
