package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.InsertOutcome
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogOptions
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonLimits
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.model.ModelGen
import app.oreshkov.delva.testkit.model.ModelOp
import app.oreshkov.delva.testkit.model.ModelRunner
import app.oreshkov.delva.testkit.oracle.OracleOptions
import app.oreshkov.delva.testkit.oracle.OracleStore
import java.util.TreeMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic crash simulation: seeded workloads on [SimulatedFileSystemIo] with a crash at a random I/O call, then
 * power loss (unsynced writes cut, torn and bit-flipped at random, or in a third of the cycles written back out of
 * order by page), reopen and check:
 *
 * - every acknowledged document is present with its exact JSON;
 * - every other visible document is one whose insert was in progress (or its group), never a rejected one;
 * - no document is damaged, and searches over the survivors match the oracle;
 *
 * then the next cycle continues on the recovered store. Scenarios vary the fragment size, the promotion threshold,
 * log file sizes, preallocation, memtable limits (so crashes also hit flushes: segment files, bitmap files, MANIFESTs)
 * and Linux or Windows directory semantics. Flushes run on the writer thread, so a scenario is deterministic.
 *
 * `-Ddelva.sim.scenarios=N` sets the number of scenarios (400 by default, 10 000 with `-Pdelva.testProfile=long`);
 * `-Ddelva.seed=S` the first seed.
 */
class CrashSimTest {
    private sealed interface Step {
        class One(val json: String) : Step

        class Group(val jsons: List<String>) : Step

        /** A document with a duplicate key, rejected after much of it was split. */
        class Rejected(val json: String) : Step
    }

    private class Stats {
        var scenarios = 0
        var cycles = 0
        var crashedMidway = 0
        var acknowledged = 0
        var committedUnacknowledged = 0
        var searches = 0
        var flushes = 0L
        var outOfOrder = 0
    }

    @Test
    fun `acknowledged inserts survive a crash at any disk call`() {
        val long = System.getProperty("delva.testProfile") == "long"
        val count = System.getProperty("delva.sim.scenarios")?.toInt() ?: if (long) 10_000 else 400
        val base = System.getProperty("delva.seed")?.toLong() ?: 0x5EEDL
        val stats = Stats()
        for (s in 0 until count) {
            val seed = base + s
            try {
                scenario(seed, stats)
            } catch (e: Throwable) {
                throw AssertionError("scenario $seed failed; reproduce: ./gradlew :delva:simTest --tests '*CrashSimTest' -Ddelva.seed=$seed -Ddelva.sim.scenarios=1", e)
            }
        }
        println(
            "crash simulation: ${stats.scenarios} scenarios, ${stats.cycles} crashes (${stats.crashedMidway} during work, ${stats.outOfOrder} with out-of-order writeback), " +
                "${stats.acknowledged} acknowledged inserts, ${stats.committedUnacknowledged} committed without acknowledgement, ${stats.searches} searches, ${stats.flushes} flushes",
        )
    }

    private fun scenario(seed: Long, stats: Stats) {
        val random = Random(seed)
        val t = listOf(32, 256, 4096)[random.nextInt(3)]
        val options = EngineOptions(
            CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024), maxDepth = 128),
            sorterBytes = 4096,
            keyArenaBytes = 1024,
            smallInsertThreshold = listOf(0L, 1024L, 1L shl 22)[random.nextInt(3)],
            keySeed = seed,
            log = LogOptions(
                laneFileBytes = listOf(4096L, 32_768L, 1L shl 20)[random.nextInt(3)],
                controlFileBytes = listOf(4096L, 1L shl 20)[random.nextInt(2)],
                preallocateBytes = listOf(0L, 512L, 8192L)[random.nextInt(3)],
                writeBufferBytes = 4096,
            ),
            memtableBytes = listOf(0L, 16L shl 10, EngineOptions.DEFAULT_MEMTABLE_BYTES)[random.nextInt(3)],
            backgroundFlush = false,
            segmentBlockBytes = listOf(256, 4096)[random.nextInt(2)],
            // Compaction runs inline after flushes, so crashes also hit its merges and commits.
            compaction = if (random.nextBoolean()) {
                CompactionOptions(minMergeSegments = 2, deletedRatio = 0.01, bytesPerSecond = 0, maxRunBytes = 64, listMemoryBytes = 1024)
            } else {
                CompactionOptions(bytesPerSecond = 0)
            },
            compression = TestEngines.compression(random.nextInt(3)),
        )
        val atom = maxOf(t / 4, 64)
        val oracleOptions = OracleOptions(limits = JsonLimits(maxDepth = 128, maxKeyBytes = atom, maxNumberLength = minOf(4096, atom)))
        val profile = JsonProfile.entries[random.nextInt(JsonProfile.entries.size)]
        val windows = random.nextBoolean()
        val fs = SimulatedFileSystemIo(isWindows = windows)
        val acked = TreeMap<Long, String>()
        stats.scenarios++

        var calls = 0L
        repeat(CYCLES) { cycle ->
            val work = workload(random, JsonGen(profile, t))
            if (cycle == 0) {
                val dry = SimulatedFileSystemIo(isWindows = windows)
                Engine.open(TestEngines.DIR, options, dry).use { store -> run(store, work, TreeMap(), ArrayList(), oracleOptions) }
                calls = dry.calls
            }
            fs.crashAfter(random.nextLong(1, calls + 1))
            val attempted = ArrayList<String>()
            var store: Engine? = null
            try {
                store = Engine.open(TestEngines.DIR, options, fs)
                run(store, work, acked, attempted, oracleOptions)
            } catch (e: Exception) {
                if (!fs.isDown) throw e // anything but the crash is a failure
            } finally {
                runCatching { store?.close() }
                stats.flushes += store?.indexes?.flushes ?: 0L
            }
            if (fs.isDown) stats.crashedMidway++
            val outOfOrder = random.nextInt(3) == 0
            if (outOfOrder) stats.outOfOrder++
            fs.crash(random, pages = outOfOrder)
            stats.cycles++

            Engine.open(TestEngines.DIR, options, fs).use { reopened ->
                val visible = reopened.documents()
                for ((docId, json) in acked) assertEquals(json, visible[docId], "acknowledged document $docId after crash in cycle $cycle")
                for ((docId, json) in visible) {
                    if (docId in acked) continue
                    assertTrue(json in attempted, "document $docId is visible but its insert wasn't in progress: $json")
                    acked[docId] = json
                    stats.committedUnacknowledged++
                }
                assertEquals(0, reopened.damagedDocIds.size, "damaged documents ${reopened.damagedDocIds.toList()}")
                assertEquals(0, reopened.registry.current().visibility.inFlightCount)

                val oracle = OracleStore(oracleOptions)
                for ((docId, json) in acked) {
                    oracle.skipTo(docId)
                    oracle.insert(json)
                }
                oracle.skipTo(reopened.registry.peekNextDocId())
                // Searches only, without their inserts or deletes between pages: every document here must be tracked by the test.
                val searches = ModelGen(t, listOf(profile)).ops().generate(random, 60).mapNotNull { op ->
                    when (op) {
                        is ModelOp.Search -> op.copy(between = emptyList(), deleteBetween = null)
                        is ModelOp.Resume -> op
                        else -> null
                    }
                }
                val runner = ModelRunner(reopened, oracle)
                runner.run(searches)
                stats.searches += runner.searches
            }
            fs.restart()
        }
        stats.acknowledged += acked.size
    }

    /** Applies [work] until an operation fails for another reason than a rejection. */
    private fun run(store: Engine, work: List<Step>, acked: MutableMap<Long, String>, attempted: MutableList<String>, oracleOptions: OracleOptions) {
        for (step in work) {
            when (step) {
                is Step.One -> {
                    val docId = try {
                        store.insert(step.json).raw
                    } catch (_: DocumentRejectedException) {
                        continue
                    } catch (e: Exception) {
                        attempted += step.json
                        throw e
                    }
                    acked[docId] = step.json
                }
                is Step.Group -> {
                    val results = try {
                        store.insertAll(step.jsons.map { InsertSource(it.byteInputStream()) })
                    } catch (e: Exception) {
                        attempted += step.jsons
                        throw e
                    }
                    results.forEachIndexed { i, result -> (result as? InsertOutcome.Inserted)?.let { acked[it.docId.raw] = step.jsons[i] } }
                }
                is Step.Rejected -> try {
                    store.insert(step.json)
                    throw AssertionError("a document with a duplicate key was accepted")
                } catch (_: DocumentRejectedException) {
                    // expected
                }
            }
        }
        // Everything acknowledged must be a valid document for the oracle too.
        for (json in acked.values) OracleStore(oracleOptions).insert(json)
    }

    private fun workload(random: Random, gen: JsonGen): List<Step> = List(random.nextInt(3, 16)) {
        when (random.nextInt(10)) {
            in 0..5 -> Step.One(document(random, gen))
            in 6..8 -> Step.Group(List(random.nextInt(1, 5)) { document(random, gen) })
            else -> Step.Rejected("""{"all":true,"x":[${(0 until random.nextInt(1, 200)).joinToString(",") { """{"all":true,"n":$it}""" }}],"x":0}""")
        }
    }

    private fun document(random: Random, gen: JsonGen): String =
        JsonText.minified(OObj(listOf("all" to OBool(true), "doc" to gen.value().generate(random, random.nextInt(1, 120)))))

    private companion object {
        const val CYCLES = 3
    }
}
