package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.Durability
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogOptions
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Deterministic simulation of large inserts: they crash at a random disk call — while fragments stream to a lane,
 * around BEGIN, lane syncs and rolls, mid-document flushes (segments, bitmap, MANIFEST), the COMMIT and its sync — then
 * power loss, reopen and check:
 *
 * - every visible document is exactly one that was inserted (never a rejected, failed or cancelled one), complete and
 *   undamaged; with [Durability.SyncEachBatch] every acknowledged one is visible;
 * - the postings of `"hot": "yes"` belong only to visible documents, each exactly as often as its JSON has them — no
 *   posting of an absent document leaks through a mid-document flush, none is lost or doubled by replay;
 * - after a flush and another reopen, nothing changes and nothing is replayed.
 *
 * A second large insert runs from inside the first one's stream, so two lanes are in flight at once on one thread and a
 * scenario stays deterministic. `-Ddelva.sim.scenarios=N` sets the number of scenarios (300 by default, 5 000 with
 * `-Pdelva.testProfile=long`); `-Ddelva.seed=S` the first seed.
 */
class LargeInsertSimTest {
    private sealed interface Step {
        val json: String

        class Large(override val json: String) : Step

        class Small(override val json: String) : Step

        /** [json] runs [inner] when half of it was read. */
        class Nested(override val json: String, val inner: Step) : Step

        /** Rejected at its end (duplicate key), after much of it was written. */
        class Rejected(override val json: String) : Step

        /** Its client stream fails halfway. */
        class Failing(override val json: String) : Step
    }

    private class Stats {
        var scenarios = 0
        var crashedMidway = 0
        var visible = 0
        var acknowledged = 0
        var flushes = 0L
        var syncs = 0L
    }

    @Test
    fun `a crash at any disk call of large inserts leaves each document complete or absent`() {
        val long = System.getProperty("delva.testProfile") == "long"
        val count = System.getProperty("delva.sim.scenarios")?.toInt() ?: if (long) 5_000 else 300
        val base = System.getProperty("delva.seed")?.toLong() ?: 0x1A4E5L
        val stats = Stats()
        for (s in 0 until count) {
            val seed = base + s
            try {
                scenario(seed, stats)
            } catch (e: Throwable) {
                throw AssertionError("scenario $seed failed; reproduce: ./gradlew :delva:simTest --tests '*LargeInsertSimTest' -Ddelva.seed=$seed -Ddelva.sim.scenarios=1", e)
            }
        }
        println(
            "large insert simulation: ${stats.scenarios} scenarios (${stats.crashedMidway} crashed during work), ${stats.visible} documents visible after " +
                "crashes, ${stats.acknowledged} acknowledged, ${stats.flushes} flushes, ${stats.syncs} lane syncs",
        )
    }

    private fun scenario(seed: Long, stats: Stats) {
        val random = Random(seed)
        val t = listOf(256, 4096)[random.nextInt(2)]
        val lanes = random.nextInt(1, 4)
        val durability = if (random.nextBoolean()) Durability.SyncEachBatch else Durability.Periodic(1.hours)
        val options = EngineOptions(
            CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 4096), durability = durability),
            sorterBytes = 4096,
            keyArenaBytes = 1024,
            smallInsertThreshold = listOf(0L, 2048L)[random.nextInt(2)],
            ingestBudgetBytes = 64L shl 20,
            largeLanes = lanes,
            keySeed = seed,
            log = LogOptions(
                laneFileBytes = listOf(16L shl 10, 256L shl 10)[random.nextInt(2)],
                controlFileBytes = listOf(4096L, 1L shl 20)[random.nextInt(2)],
                preallocateBytes = listOf(0L, 8192L)[random.nextInt(2)],
                writeBufferBytes = 4096,
                largeLaneSyncBytes = listOf(0L, 8192L)[random.nextInt(2)],
            ),
            memtableBytes = listOf(0L, 16L shl 10, 256L shl 10)[random.nextInt(3)],
            backgroundFlush = false,
            segmentBlockBytes = listOf(256, 4096)[random.nextInt(2)],
            compression = TestEngines.compression(random.nextInt(3)),
        )
        val windows = random.nextBoolean()
        val work = workload(random, lanes)
        if (System.getProperty("delva.sim.scenarios") == "1") println("seed $seed: t=$t lanes=$lanes durability=$durability threshold=${options.smallInsertThreshold} lane=${options.log.laneFileBytes} control=${options.log.controlFileBytes} prealloc=${options.log.preallocateBytes} laneSync=${options.log.largeLaneSyncBytes} memtable=${options.memtableBytes} block=${options.segmentBlockBytes} windows=$windows steps=${work.map { it::class.simpleName + "(" + it.json.length + ")" + ((it as? Step.Nested)?.inner?.let { i -> "[" + i::class.simpleName + "]" } ?: "") }}")
        stats.scenarios++

        val dry = SimulatedFileSystemIo(isWindows = windows)
        val calls = Engine.open(TestEngines.DIR, options, dry).use { store ->
            run(store, work, HashMap())
            dry.calls
        }

        val fs = SimulatedFileSystemIo(isWindows = windows)
        val crashAt = random.nextLong(1, calls + 1)
        if (System.getProperty("delva.sim.scenarios") == "1") println("crash at call $crashAt of $calls")
        fs.crashAfter(crashAt)
        val acked = HashMap<Long, String>()
        var store: Engine? = null
        try {
            store = Engine.open(TestEngines.DIR, options, fs)
            run(store, work, acked)
        } catch (_: Exception) {
            // the crash
        } finally {
            runCatching { store?.close() }
            stats.flushes += store?.indexes?.flushes ?: 0L
            stats.syncs += store?.largeLaneSyncs ?: 0L
        }
        if (fs.isDown) stats.crashedMidway++
        fs.crash(random)

        val inserted = work.flatMap { step -> listOfNotNull(step.takeIf { it is Step.Large || it is Step.Small || it is Step.Nested }, (step as? Step.Nested)?.inner) }
            .filter { it !is Step.Rejected && it !is Step.Failing }.map { it.json }.toSet()
        val visible = Engine.open(TestEngines.DIR, options, fs).use { reopened ->
            check(reopened, inserted, acked, durability, "after the crash")
            reopened.flush()
            reopened.documents()
        }
        fs.restart()
        Engine.open(TestEngines.DIR, options, fs).use { reopened ->
            check(reopened, inserted, acked, durability, "after a flush and reopen")
            assertEquals(visible, reopened.documents())
            assertEquals(0, reopened.replayedCommits)
        }
        stats.visible += visible.size
        stats.acknowledged += acked.size
    }

    private fun check(store: Engine, inserted: Set<String>, acked: Map<Long, String>, durability: Durability, label: String) {
        val visible = store.documents()
        for ((docId, json) in visible) assertTrue(json in inserted, "$label: document $docId is visible but wasn't inserted: $json")
        if (durability is Durability.SyncEachBatch) {
            for ((docId, json) in acked) assertEquals(json, visible[docId], "$label: acknowledged document $docId")
        }
        assertEquals(0, store.damagedDocIds.size, "$label: damaged ${store.damagedDocIds.toList()}")
        assertEquals(0, store.registry.current().visibility.inFlightCount, label)
        val hits = store.search(KeyValueQuery("hot", JsonQueryValue.Str("yes"), ResultMode.CONTAINING_ELEMENT, limit = 1_000_000)).use { result ->
            result.iterator().asSequence().groupingBy { it.node.docId.raw }.eachCount()
        }
        val expected = visible.mapValues { (_, json) -> HOT.findAll(json).count() }.filterValues { it > 0 }
        assertEquals(expected, hits, "$label: hits per document")
    }

    /** Runs [work]; records acknowledged documents in [acked]. Throws the first error that isn't expected. */
    private fun run(store: Engine, work: List<Step>, acked: MutableMap<Long, String>) {
        for (step in work) runStep(store, step, acked)
    }

    private fun runStep(store: Engine, step: Step, acked: MutableMap<Long, String>) {
        when (step) {
            is Step.Large, is Step.Small -> acked[store.insert(step.json).raw] = step.json
            is Step.Nested -> {
                val docId = store.insert(HalfwayStream(step.json.toByteArray()) { runStep(store, step.inner, acked) }).raw
                acked[docId] = step.json
            }
            is Step.Rejected -> try {
                store.insert(step.json)
                throw AssertionError("a document with a duplicate key was accepted")
            } catch (_: DocumentRejectedException) {
                // expected
            }
            is Step.Failing -> try {
                store.insert(HalfwayStream(step.json.toByteArray()) { throw ClientGone() })
                throw AssertionError("a failing stream was inserted")
            } catch (_: ClientGone) {
                // expected
            }
        }
    }

    private class ClientGone : IOException("client went away")

    /** [bytes] in reads of at most 512 bytes; runs [atHalf] once when half of it was read. */
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
            val n = minOf(len, 512, bytes.size - position)
            System.arraycopy(bytes, position, b, off, n)
            position += n
            return n
        }
    }

    private fun workload(random: Random, lanes: Int): List<Step> = List(random.nextInt(2, 6)) {
        when (random.nextInt(10)) {
            in 0..2 -> Step.Large(large(random))
            3 -> Step.Small(small(random))
            in 4..5 -> if (lanes >= 2) Step.Nested(large(random), if (random.nextBoolean()) Step.Large(large(random)) else Step.Failing(large(random))) else Step.Large(large(random))
            // With one lane a promoted inner insert would wait for the lane its own thread holds.
            6 -> if (lanes >= 2) Step.Nested(large(random), Step.Small(small(random))) else Step.Small(small(random))
            7 -> Step.Rejected(large(random).dropLast(1) + ""","all":false}""")
            else -> Step.Failing(large(random))
        }
    }

    private fun small(random: Random): String = """{"all":true,"n":${random.nextInt(1000)},"hot":"yes"}"""

    /** 2–60 KiB: an array of records with nested objects, some long strings, `"hot": "yes"` on some of them. */
    private fun large(random: Random): String {
        val items = random.nextInt(20, 700)
        val tag = random.nextInt(1_000_000)
        return buildString {
            append("""{"all":true,"tag":$tag,"items":[""")
            for (i in 0 until items) {
                if (i > 0) append(',')
                append("""{"k":$i""")
                if (random.nextInt(3) != 0) append(""","hot":"yes"""")
                if (random.nextInt(5) == 0) append(""","sub":{"hot":"yes","v":[1,2,{"hot":"no"}]}""")
                if (random.nextInt(20) == 0) append(""","s":"${"z".repeat(random.nextInt(100, 3000))}"""")
                append('}')
            }
            append("]}")
        }
    }

    private companion object {
        val HOT = Regex(""""hot":"yes"""")
    }
}
