package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.WriteConflictException
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogOptions
import java.util.TreeMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Crash simulation for document keys: workloads of inserts (which conflict when their key is taken), upserts, deletes
 * and replaces that change a key, with flushes, compactions and space reclamation, and a crash at a random disk call
 * (log appends, syncs, key segments, MANIFESTs, reclamation), then power loss and reopen. After each crash:
 *
 * - every acknowledged document of a key is the one `get` finds, the key scans list, and the only visible one of its key;
 * - the operation in progress took effect completely or not at all: an upsert's document is visible exactly when the
 *   document it replaces is gone, and a crash never leaves two documents with one key, or a key without its document;
 * - `verify` finds nothing, and the key index answers alike from memtables, segments and replay.
 *
 * `-Ddelva.sim.scenarios=N` sets the number of scenarios (200 by default, 3 000 with `-Pdelva.testProfile=long`);
 * `-Ddelva.seed=S` the first seed.
 */
class DocumentKeyCrashSimTest {
    private sealed interface Step {
        /** An insert of [key], which conflicts when the key is taken. */
        class Insert(val key: String, val payload: String) : Step

        class Upsert(val key: String, val payload: String) : Step

        class Delete(val key: String) : Step

        /** Replaces the document of [from] with one whose key is [to]: conflicts when [to] is taken by another document. */
        class Rekey(val from: String, val to: String, val payload: String) : Step

        /** Flush, compact and reclaim lane files. */
        data object Reclaim : Step
    }

    private class Stats {
        var scenarios = 0
        var crashedMidway = 0
        var acknowledged = 0
        var conflicts = 0
        var applied = 0
        var lost = 0
        var keysChecked = 0L
    }

    /** The operation running when the store failed: the documents it would leave by key, and the ones it would delete. */
    private class InProgress(val json: String?, val key: String?, val deletes: List<Long>)

    @Test
    fun `document keys survive a crash at any disk call and stay unique`() {
        val long = System.getProperty("delva.testProfile") == "long"
        val count = System.getProperty("delva.sim.scenarios")?.toInt() ?: if (long) 3_000 else 200
        val base = System.getProperty("delva.seed")?.toLong() ?: 0x4B3L
        val stats = Stats()
        for (s in 0 until count) {
            val seed = base + s
            try {
                scenario(seed, stats)
            } catch (e: Throwable) {
                throw AssertionError("scenario $seed failed; reproduce: ./gradlew :delva:simTest --tests '*DocumentKeyCrashSimTest' -Ddelva.seed=$seed -Ddelva.sim.scenarios=1", e)
            }
        }
        println(
            "document key crash simulation: ${stats.scenarios} scenarios (${stats.crashedMidway} crashes during work), ${stats.acknowledged} writes " +
                "acknowledged, ${stats.conflicts} conflicts, in-progress writes ${stats.applied} applied / ${stats.lost} lost, ${stats.keysChecked} keys checked",
        )
    }

    private fun scenario(seed: Long, stats: Stats) {
        val random = Random(seed)
        val t = listOf(32, 256, 4096)[random.nextInt(3)]
        val options = EngineOptions(
            CreationOptions(fragmentBytes = t).withDocumentKey("$.id"), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024)),
            sorterBytes = 4096,
            keyArenaBytes = 1024,
            smallInsertThreshold = listOf(0L, 1L shl 22)[random.nextInt(2)],
            keySeed = seed,
            log = LogOptions(
                laneFileBytes = listOf(4096L, 1L shl 20)[random.nextInt(2)],
                controlFileBytes = listOf(4096L, 1L shl 20)[random.nextInt(2)],
                preallocateBytes = listOf(0L, 512L)[random.nextInt(2)],
                writeBufferBytes = 4096,
            ),
            memtableBytes = listOf(0L, 2L shl 10, EngineOptions.DEFAULT_MEMTABLE_BYTES)[random.nextInt(3)],
            backgroundFlush = false,
            segmentBlockBytes = listOf(256, 4096)[random.nextInt(2)],
            maxDeleteRecordDocIds = listOf(1, EngineOptions.DEFAULT_MAX_DELETE_RECORD_DOC_IDS)[random.nextInt(2)],
            reclaim = ReclaimOptions(maxLiveRatio = 0.95, bytesPerSecond = 0, maxRelocateFragments = listOf(1, 16_384)[random.nextInt(2)]),
            compaction = CompactionOptions(minMergeSegments = 2, deletedRatio = 0.01, tombstoneRatio = 0.01, bytesPerSecond = 0),
            compression = TestEngines.compression(random.nextInt(3)),
        )
        val windows = random.nextBoolean()
        val fs = SimulatedFileSystemIo(isWindows = windows)
        // key → (docId, JSON) of every acknowledged document that is visible.
        val expected = TreeMap<String, Pair<Long, String>>()
        stats.scenarios++
        var calls = 0L
        repeat(CYCLES) { cycle ->
            val work = List(random.nextInt(4, 14)) { step(random) }
            if (cycle == 0) {
                val dry = SimulatedFileSystemIo(isWindows = windows)
                Engine.open(TestEngines.DIR, options, dry).use { store -> run(store, work, TreeMap(), null, stats) }
                calls = dry.calls
            }
            fs.crashAfter(random.nextLong(1, calls + 1))
            val inProgress = arrayOfNulls<InProgress>(1)
            var store: Engine? = null
            try {
                store = Engine.open(TestEngines.DIR, options, fs)
                run(store, work, expected, inProgress, stats)
            } catch (e: Exception) {
                if (!fs.isDown) throw e // anything but the crash is a failure
            } finally {
                runCatching { store?.close() }
            }
            if (fs.isDown) stats.crashedMidway++
            fs.crash(random)
            Engine.open(TestEngines.DIR, options, fs).use { reopened -> verify(reopened, expected, inProgress[0], stats, cycle) }
            fs.restart()
        }
    }

    private fun step(random: Random): Step {
        val key = KEYS[random.nextInt(KEYS.size)]
        val payload = "p".repeat(random.nextInt(0, 300)) + random.nextInt(1000)
        return when (random.nextInt(10)) {
            in 0..2 -> Step.Insert(key, payload)
            in 3..5 -> Step.Upsert(key, payload)
            6 -> Step.Delete(key)
            7, 8 -> Step.Rekey(key, KEYS[random.nextInt(KEYS.size)], payload)
            else -> Step.Reclaim
        }
    }

    private fun json(key: String, payload: String) = """{"all":true,"id":"$key","pad":"$payload"}"""

    private fun run(store: Engine, work: List<Step>, expected: TreeMap<String, Pair<Long, String>>, inProgress: Array<InProgress?>?, stats: Stats) {
        for (step in work) {
            when (step) {
                is Step.Insert -> {
                    val json = json(step.key, step.payload)
                    if (step.key in expected) {
                        val e = runCatching { store.insert(json) }.exceptionOrNull()
                        // Any other exception is the crash.
                        if (e !is WriteConflictException) throw e ?: AssertionError("an insert of the taken key ${step.key} didn't conflict")
                        assertEquals(step.key, e.key)
                        assertEquals(listOf(expected.getValue(step.key).first), e.conflicting.map { it.raw })
                        stats.conflicts++
                        continue
                    }
                    inProgress?.set(0, InProgress(json, step.key, emptyList()))
                    expected[step.key] = store.insert(json).raw to json
                }
                is Step.Upsert -> {
                    val json = json(step.key, step.payload)
                    inProgress?.set(0, InProgress(json, step.key, listOfNotNull(expected[step.key]?.first)))
                    expected[step.key] = store.upsert(json).raw to json
                }
                is Step.Delete -> {
                    val docId = expected[step.key]?.first ?: continue
                    inProgress?.set(0, InProgress(null, null, listOf(docId)))
                    assertTrue(store.delete(DocId(docId)))
                    expected.remove(step.key)
                }
                is Step.Rekey -> {
                    val docId = expected[step.from]?.first ?: continue
                    val json = json(step.to, step.payload)
                    if (step.to != step.from && step.to in expected) {
                        val e = runCatching { store.replace(listOf(DocId(docId)), json) }.exceptionOrNull()
                        if (e !is WriteConflictException) throw e ?: AssertionError("a replace onto the taken key ${step.to} didn't conflict")
                        assertEquals(step.to, e.key)
                        stats.conflicts++
                        continue
                    }
                    inProgress?.set(0, InProgress(json, step.to, listOf(docId)))
                    val next = store.replace(listOf(DocId(docId)), json).raw
                    expected.remove(step.from)
                    expected[step.to] = next to json
                }
                Step.Reclaim -> {
                    inProgress?.set(0, null)
                    store.flush()
                    store.compact()
                    store.reclaim()
                }
            }
            inProgress?.set(0, null)
            stats.acknowledged++
        }
    }

    private fun verify(store: Engine, expected: TreeMap<String, Pair<Long, String>>, inProgress: InProgress?, stats: Stats, cycle: Int) {
        val visible = store.documents()
        if (inProgress != null) {
            val committed = inProgress.json?.let { json -> visible.entries.firstOrNull { (docId, text) -> text == json && expected[inProgress.key]?.first != docId }?.key }
            val gone = inProgress.deletes.filter { it !in visible }
            val applied = when {
                inProgress.json == null -> gone.isNotEmpty()
                else -> committed != null
            }
            if (applied) {
                assertEquals(inProgress.deletes, gone, "the in-progress write's document is visible exactly when the ones it replaces are gone")
                expected.entries.removeIf { it.value.first in inProgress.deletes }
                if (committed != null) expected[inProgress.key!!] = committed to inProgress.json
                stats.applied++
            } else {
                assertTrue(gone.isEmpty(), "a write that didn't take effect deleted $gone")
                stats.lost++
            }
        }
        val byDocId = expected.values.associate { it.first to it.second }
        assertEquals(byDocId, visible, "visible documents after the crash in cycle $cycle")
        val keys = store.search(DocumentKeyQuery(limit = 1000)).use { r -> r.iterator().asSequence().map { it.documentKey!! to it.docId.raw }.toList() }
        assertEquals(expected.map { (key, doc) -> key to doc.first }, keys, "keys in order after the crash in cycle $cycle")
        for (key in KEYS) assertEquals(expected[key]?.first, store.get(key)?.raw, "get $key after the crash in cycle $cycle")
        stats.keysChecked += KEYS.size
        assertEquals(emptyList(), store.verify().issues.map { it.toString() })
        // The store goes on: a key that is taken still conflicts.
        expected.keys.firstOrNull()?.let { key -> assertIs<WriteConflictException>(runCatching { store.insert(json(key, "again")) }.exceptionOrNull()) }
        assertFalse(store.damagedDocIds.isNotEmpty())
    }

    private companion object {
        const val CYCLES = 3
        val KEYS = listOf("a", "b", "ab", "é", "😀", "z")
    }
}
