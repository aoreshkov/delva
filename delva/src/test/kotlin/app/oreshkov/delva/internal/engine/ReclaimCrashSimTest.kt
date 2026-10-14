package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo.Platform
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogOptions
import java.util.TreeMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Delete-heavy churn with space reclamation — inserts (small and promoted), delete calls, flushes,
 * compactions and reclamations, some while a search holds an old snapshot — with a crash at a random disk call (copies to
 * the GC lane, its syncs, RELOCATE records, MANIFESTs retiring files, file deletions; with compression, dictionary files and
 * the MANIFESTs listing them), then power loss and reopen.
 * After each crash:
 *
 * - every acknowledged document that wasn't deleted is present with its exact JSON and type; a document whose insert was
 *   in progress is absent or complete; a delete call in progress took effect completely or not at all;
 * - deleted documents never reappear;
 * - no document is damaged, and every log file on disk is listed (reclaimed files never linger or come back);
 * - a search that held its snapshot across reclamations read every document of that snapshot in full.
 *
 * `-Ddelva.sim.scenarios=N` sets the number of scenarios (200 by default, 3 000 with `-Pdelva.testProfile=long`);
 * `-Ddelva.seed=S` the first seed.
 */
class ReclaimCrashSimTest {
    private class Stats {
        var scenarios = 0
        var crashedMidway = 0
        var crashedReclaiming = 0
        var reclaimedFiles = 0L
        var relocatedFragments = 0L
        var pinnedReads = 0L
        var documentsChecked = 0L
        var inflatedFragments = 0L
        var dictionariesLoaded = 0L
        var outOfOrder = 0
    }

    private sealed interface Step {
        class Insert(val jsons: List<String>) : Step

        class Delete(val share: Double, val seed: Long) : Step

        /** Flush, compact and reclaim; with [pinned], a search opened before is read after. */
        class Reclaim(val pinned: Boolean) : Step
    }

    /** What the store must show. */
    private class Expected {
        val docs = TreeMap<Long, String>()
        val deleted = HashSet<Long>()
    }

    private class InProgress {
        var inserts: List<String> = emptyList()
        var deletes: List<Long> = emptyList()
        var reclaiming = false
    }

    @Test
    fun `reclamation survives a crash at any disk call and never loses a live document`() {
        val long = System.getProperty("delva.testProfile") == "long"
        val count = System.getProperty("delva.sim.scenarios")?.toInt() ?: if (long) 3_000 else 200
        val base = System.getProperty("delva.seed")?.toLong() ?: 0xEC1A1L
        val stats = Stats()
        for (s in 0 until count) {
            val seed = base + s
            try {
                scenario(seed, stats)
            } catch (e: Throwable) {
                throw AssertionError("scenario $seed failed; reproduce: ./gradlew :delva:simTest --tests '*ReclaimCrashSimTest' -Ddelva.seed=$seed -Ddelva.sim.scenarios=1", e)
            }
        }
        println(
            "reclamation crash simulation: ${stats.scenarios} scenarios (${stats.crashedMidway} crashes during work, ${stats.crashedReclaiming} while reclaiming, ${stats.outOfOrder} with out-of-order writeback), " +
                "${stats.reclaimedFiles} lane files reclaimed, ${stats.relocatedFragments} fragments relocated, ${stats.pinnedReads} documents read through pinned snapshots, " +
                "${stats.documentsChecked} documents checked, ${stats.inflatedFragments} compressed fragments read, ${stats.dictionariesLoaded} dictionaries loaded",
        )
        assertTrue(stats.reclaimedFiles > count, "only ${stats.reclaimedFiles} lane files reclaimed")
        assertTrue(stats.crashedReclaiming > count / 4, "only ${stats.crashedReclaiming} crashes while reclaiming")
    }

    private fun scenario(seed: Long, stats: Stats) {
        val random = Random(seed)
        val t = listOf(32, 256, 4096)[random.nextInt(3)]
        val options = EngineOptions(
            CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024), maxDepth = 64),
            sorterBytes = 4096,
            keyArenaBytes = 1024,
            smallInsertThreshold = listOf(0L, 2048L, 1L shl 22)[random.nextInt(3)],
            keySeed = seed,
            log = LogOptions(
                laneFileBytes = listOf(4096L, 16_384L)[random.nextInt(2)],
                controlFileBytes = listOf(4096L, 1L shl 20)[random.nextInt(2)],
                preallocateBytes = listOf(0L, 1024L)[random.nextInt(2)],
                writeBufferBytes = 4096,
            ),
            memtableBytes = listOf(0L, 8L shl 10, EngineOptions.DEFAULT_MEMTABLE_BYTES)[random.nextInt(3)],
            backgroundFlush = false,
            segmentBlockBytes = listOf(256, 4096)[random.nextInt(2)],
            compaction = CompactionOptions(minMergeSegments = 2, deletedRatio = listOf(0.01, 0.2)[random.nextInt(2)], bytesPerSecond = 0, maxRunBytes = 64, listMemoryBytes = 1024),
            reclaim = ReclaimOptions(maxLiveRatio = listOf(0.5, 0.9)[random.nextInt(2)], bytesPerSecond = 0, maxRelocateFragments = listOf(1, 3, 16_384)[random.nextInt(3)]),
            compression = TestEngines.compression(random.nextInt(3)),
        )
        val platform = Platform.entries[random.nextInt(Platform.entries.size)]
        val fs = SimulatedFileSystemIo(platform)
        val expected = Expected()
        stats.scenarios++
        var calls = 0L
        repeat(CYCLES) { cycle ->
            val work = workload(random, t)
            if (cycle == 0) {
                val dry = SimulatedFileSystemIo(platform)
                Engine.open(TestEngines.DIR, options, dry).use { store -> run(store, work, Expected(), InProgress(), null) }
                calls = dry.calls
            }
            fs.crashAfter(random.nextLong(1, calls + 1))
            val inProgress = InProgress()
            var store: Engine? = null
            try {
                store = Engine.open(TestEngines.DIR, options, fs)
                run(store, work, expected, inProgress, stats)
            } catch (e: Exception) {
                if (!fs.isDown) throw e // anything but the crash is a failure
            } finally {
                runCatching { store?.close() }
            }
            if (fs.isDown) {
                stats.crashedMidway++
                if (inProgress.reclaiming) stats.crashedReclaiming++
            }
            val outOfOrder = random.nextInt(3) == 0
            if (outOfOrder) stats.outOfOrder++
            fs.crash(random, pages = outOfOrder)
            Engine.open(TestEngines.DIR, options, fs).use { reopened -> verify(reopened, fs, expected, inProgress, cycle, stats) }
            fs.restart()
        }
    }

    private fun workload(random: Random, t: Int): List<Step> = List(random.nextInt(6, 16)) {
        when (random.nextInt(6)) {
            in 0..2 -> Step.Insert(List(random.nextInt(4, 30)) { document(random, t) })
            3 -> Step.Delete(random.nextDouble(0.3, 0.9), random.nextLong())
            else -> Step.Reclaim(pinned = random.nextBoolean())
        }
    }

    private fun document(random: Random, t: Int): String {
        val items = random.nextInt(1, if (random.nextInt(8) == 0) 40 else 4)
        return """{"all":true,"n":${random.nextInt()},"items":[${(0 until items).joinToString(",") { """{"k":$it,"s":"${"z".repeat(random.nextInt(0, 2 * t))}"}""" }}]}"""
    }

    private fun run(store: Engine, work: List<Step>, expected: Expected, inProgress: InProgress, stats: Stats?) {
        for (step in work) {
            when (step) {
                is Step.Insert -> {
                    // Each insert is expected as soon as it returns: a crash later in the step mustn't lose it.
                    for (json in step.jsons) {
                        inProgress.inserts = listOf(json)
                        expected.docs[store.insert(json, TYPES).raw] = json
                    }
                    inProgress.inserts = emptyList()
                }
                is Step.Delete -> {
                    val random = Random(step.seed)
                    val gone = expected.docs.keys.filter { random.nextDouble() < step.share }
                    if (gone.isEmpty()) continue
                    inProgress.deletes = gone
                    assertEquals(gone.size, store.deleteAll(gone.map { DocId(it) }))
                    inProgress.deletes = emptyList()
                    for (docId in gone) expected.docs.remove(docId)
                    expected.deleted += gone
                }
                is Step.Reclaim -> {
                    val snapshot = if (step.pinned) HashMap(expected.docs) else null
                    val search = if (step.pinned) store.search(KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT, limit = 7)) else null
                    try {
                        inProgress.reclaiming = true
                        store.flush()
                        store.compact()
                        val reclaimer = store.reclaimer
                        val before = reclaimer.relocatedFragments
                        val n = store.reclaim()
                        inProgress.reclaiming = false
                        stats?.let {
                            it.reclaimedFiles += n
                            it.relocatedFragments += reclaimer.relocatedFragments - before
                        }
                        if (search != null) {
                            val read = search.iterator().asSequence().associate { it.node.docId.raw to it.readJsonAsString() }
                            assertEquals<Map<Long, String>?>(snapshot, read, "a search pinned across reclamation reads its snapshot")
                            stats?.let { it.pinnedReads += read.size }
                        }
                    } finally {
                        search?.close()
                    }
                }
            }
        }
    }

    private fun verify(store: Engine, fs: SimulatedFileSystemIo, expected: Expected, inProgress: InProgress, cycle: Int, stats: Stats) {
        val visible = store.documents()
        stats.dictionariesLoaded += store.codecs.dictionaryCount
        if (inProgress.deletes.isNotEmpty()) {
            val gone = inProgress.deletes.filter { it !in visible }
            when (gone.size) {
                0 -> {}
                inProgress.deletes.size -> {
                    for (docId in gone) expected.docs.remove(docId)
                    expected.deleted += gone
                }
                else -> throw AssertionError("a delete call was applied in part: ${inProgress.deletes}, gone $gone")
            }
        }
        for ((docId, json) in expected.docs) {
            assertEquals(json, visible[docId], "acknowledged document $docId after the crash in cycle $cycle")
            assertEquals("Root", store.typeOf(NodeRef(DocId(docId), 0)), "type of document $docId")
            stats.documentsChecked++
        }
        for ((docId, json) in visible) {
            if (docId in expected.docs) continue
            assertTrue(json in inProgress.inserts, "document $docId is visible but its insert wasn't in progress")
            expected.docs[docId] = json
        }
        for (docId in expected.deleted) {
            assertFalse(docId in visible, "deleted document $docId reappeared after the crash in cycle $cycle")
            assertFailsWith<DocumentNotFoundException> { store.typeOf(NodeRef(DocId(docId), 0)) }
        }
        assertEquals(0, store.damagedDocIds.size, "damaged documents ${store.damagedDocIds.toList()}")
        val listed = store.files.listed().map { it.fileNo }.toSet()
        for (path in fs.files()) {
            val name = path.fileName.toString()
            val fileNo = LogFiles.parseLane(name)?.first ?: LogFiles.parseControl(name) ?: continue
            assertTrue(fileNo in listed, "$path isn't listed after the crash in cycle $cycle")
        }
        stats.inflatedFragments += store.codecs.inflatedFragments.sum()
    }

    private companion object {
        const val CYCLES = 3
        val TYPES = mapOf("$" to "Root")
    }
}
