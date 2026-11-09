package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogOptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Crash simulation for the vector index: workloads of inserts, replaces and deletes of documents with and without a
 * vector, small and in large lanes, with flushes (also forced by a small memtable), compactions and space reclamation,
 * and a crash at a random disk call (log appends, syncs, vector segments, MANIFESTs), then power loss and reopen. After
 * each crash:
 *
 * - every visible document has exactly the vector it was inserted with, or none, whether the vector comes back from a
 *   segment or from replaying the log;
 * - no memtable or segment holds a vector of a document that was never committed;
 * - the operation in progress took effect completely or not at all, and `verify` finds nothing.
 *
 * `-Ddelva.sim.scenarios=N` sets the number of scenarios (200 by default, 3 000 with `-Pdelva.testProfile=long`);
 * `-Ddelva.seed=S` the first seed.
 */
class VectorIndexSimTest {
    private sealed interface Step {
        /** An insert of document [n]. */
        class Insert(val n: Int) : Step

        /** A replace of the [target]-th visible document, by docId, with document [n]. */
        class Replace(val target: Int, val n: Int) : Step

        class Delete(val target: Int) : Step

        /** Flush, compact and reclaim lane files. */
        data object Reclaim : Step
    }

    /** The operation running when the store failed: the document it inserts, if any, and the ones it deletes. */
    private class InProgress(val n: Int?, val deletes: List<Long>)

    private class Stats {
        var scenarios = 0
        var crashedMidway = 0
        var applied = 0
        var lost = 0
        var vectorsChecked = 0L
        var reopenedWithSegments = 0
    }

    @Test
    fun `after a crash at any disk call, the vector index holds the vectors of the visible documents`() {
        val long = System.getProperty("delva.testProfile") == "long"
        val count = System.getProperty("delva.sim.scenarios")?.toInt() ?: if (long) 3_000 else 200
        val base = System.getProperty("delva.seed")?.toLong() ?: 0x7EC5L
        val stats = Stats()
        for (s in 0 until count) {
            val seed = base + s
            try {
                scenario(seed, stats)
            } catch (e: Throwable) {
                throw AssertionError("scenario $seed failed; reproduce: ./gradlew :delva:simTest --tests '*VectorIndexSimTest' -Ddelva.seed=$seed -Ddelva.sim.scenarios=1", e)
            }
        }
        println(
            "vector index simulation: ${stats.scenarios} scenarios (${stats.crashedMidway} crashes during work), in-progress writes " +
                "${stats.applied} applied / ${stats.lost} lost, ${stats.vectorsChecked} vectors checked, ${stats.reopenedWithSegments} reopenings with vector segments",
        )
    }

    private fun scenario(seed: Long, stats: Stats) {
        val random = Random(seed)
        val t = listOf(32, 256, 4096)[random.nextInt(3)]
        val dimensions = listOf(1, 8, 100)[random.nextInt(3)]
        val options = EngineOptions(
            CreationOptions(fragmentBytes = t).withVectorField(VectorField("$.v", dimensions, VectorMetric.entries[random.nextInt(3)])),
            StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024)),
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
            memtableBytes = listOf(0L, 4L shl 10, EngineOptions.DEFAULT_MEMTABLE_BYTES)[random.nextInt(3)],
            backgroundFlush = false,
            segmentBlockBytes = listOf(256, 4096)[random.nextInt(2)],
            maxDeleteRecordDocIds = listOf(1, EngineOptions.DEFAULT_MAX_DELETE_RECORD_DOC_IDS)[random.nextInt(2)],
            reclaim = ReclaimOptions(maxLiveRatio = 0.95, bytesPerSecond = 0, maxRelocateFragments = listOf(1, 16_384)[random.nextInt(2)]),
            compaction = CompactionOptions(minMergeSegments = 2, deletedRatio = 0.01, tombstoneRatio = 0.01, bytesPerSecond = 0),
            compression = TestEngines.compression(random.nextInt(3)),
            vectorSegmentBlockBytes = listOf(null, 256, 1024)[random.nextInt(3)],
        )
        // Document n's vector, or null when it has none; never zero, so every metric takes it.
        val vectors = ArrayList<FloatArray?>()
        fun newDocument(): Int {
            vectors += if (random.nextInt(4) == 0) null else FloatArray(dimensions) { if (it == 0) 1f + random.nextFloat() else random.nextFloat() * 2 - 1 }
            return vectors.size - 1
        }
        fun json(n: Int): String = buildString {
            append("""{"all":true,"n":""").append(n).append(""","pad":"""").append("p".repeat((n * 37) % 300)).append('"')
            vectors[n]?.let { append(""","v":[""").append(it.joinToString(",")).append(']') }
            append('}')
        }
        val windows = random.nextBoolean()
        val fs = SimulatedFileSystemIo(isWindows = windows)
        // docId → n of every acknowledged document that is visible, and every docId ever committed.
        val expected = LinkedHashMap<Long, Int>()
        val committed = HashSet<Long>()
        stats.scenarios++
        var calls = 0L
        repeat(CYCLES) { cycle ->
            val work = List(random.nextInt(4, 14)) {
                when (random.nextInt(8)) {
                    in 0..2 -> Step.Insert(newDocument())
                    in 3..4 -> Step.Replace(random.nextInt(1000), newDocument())
                    5 -> Step.Delete(random.nextInt(1000))
                    6 -> Step.Reclaim
                    else -> Step.Insert(newDocument())
                }
            }
            if (cycle == 0) {
                val dry = SimulatedFileSystemIo(isWindows = windows)
                Engine.open(TestEngines.DIR, options, dry).use { store -> run(store, work, LinkedHashMap(), HashSet(), null, ::json) }
                calls = dry.calls
            }
            fs.crashAfter(random.nextLong(1, calls + 1))
            val inProgress = arrayOfNulls<InProgress>(1)
            var store: Engine? = null
            try {
                store = Engine.open(TestEngines.DIR, options, fs)
                run(store, work, expected, committed, inProgress, ::json)
            } catch (e: Exception) {
                if (!fs.isDown) throw e // anything but the crash is a failure
            } finally {
                runCatching { store?.close() }
            }
            if (fs.isDown) stats.crashedMidway++
            fs.crash(random)
            Engine.open(TestEngines.DIR, options, fs).use { reopened -> verify(reopened, expected, committed, inProgress[0], vectors, stats, cycle) }
            fs.restart()
        }
    }

    private fun run(
        store: Engine,
        work: List<Step>,
        expected: LinkedHashMap<Long, Int>,
        committed: HashSet<Long>,
        inProgress: Array<InProgress?>?,
        json: (Int) -> String,
    ) {
        for (step in work) {
            when (step) {
                is Step.Insert -> {
                    inProgress?.set(0, InProgress(step.n, emptyList()))
                    val docId = store.insert(json(step.n)).raw
                    expected[docId] = step.n
                    committed += docId
                }
                is Step.Replace -> {
                    if (expected.isEmpty()) continue
                    val old = expected.keys.elementAt(step.target % expected.size)
                    inProgress?.set(0, InProgress(step.n, listOf(old)))
                    val docId = store.replace(listOf(DocId(old)), json(step.n)).raw
                    expected.remove(old)
                    expected[docId] = step.n
                    committed += docId
                }
                is Step.Delete -> {
                    if (expected.isEmpty()) continue
                    val old = expected.keys.elementAt(step.target % expected.size)
                    inProgress?.set(0, InProgress(null, listOf(old)))
                    assertTrue(store.delete(DocId(old)))
                    expected.remove(old)
                }
                Step.Reclaim -> {
                    store.flush()
                    store.compact()
                    store.reclaim()
                }
            }
            inProgress?.set(0, null)
        }
    }

    private fun verify(
        store: Engine,
        expected: LinkedHashMap<Long, Int>,
        committed: HashSet<Long>,
        inProgress: InProgress?,
        vectors: List<FloatArray?>,
        stats: Stats,
        cycle: Int,
    ) {
        val visible = store.documents().mapValues { (_, text) -> N.find(text)!!.groupValues[1].toInt() }
        if (inProgress != null) {
            val inserted = inProgress.n?.let { n -> visible.entries.firstOrNull { (docId, m) -> m == n && docId !in expected }?.key }
            val gone = inProgress.deletes.filter { it !in visible }
            if (inserted != null || (inProgress.n == null && gone.isNotEmpty())) {
                assertEquals(inProgress.deletes, gone, "the in-progress write's document is visible exactly when the ones it replaces are gone")
                for (docId in gone) expected.remove(docId)
                inserted?.let {
                    expected[it] = inProgress.n
                    committed += it
                }
                stats.applied++
            } else {
                assertTrue(gone.isEmpty(), "a write that didn't take effect deleted $gone")
                stats.lost++
            }
        }
        assertEquals(expected, visible, "visible documents after the crash in cycle $cycle")
        for ((docId, n) in visible) {
            assertEquals(vectors[n]?.toList(), store.vectorOf(docId)?.toList(), "vector of document $docId (n = $n) after the crash in cycle $cycle")
            stats.vectorsChecked++
        }
        if (store.indexes.versions.current().vectorSegments.isNotEmpty()) stats.reopenedWithSegments++
        val stray = store.indexedVectorDocIds().filter { it !in committed }
        assertTrue(stray.isEmpty(), "vectors of documents never committed: $stray")
        assertEquals(emptyList(), store.verify().issues.map { it.toString() })
    }

    private companion object {
        const val CYCLES = 3
        val N = Regex(""""n":(\d+)""")
    }
}
