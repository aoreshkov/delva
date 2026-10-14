package app.oreshkov.delva.internal.split

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.payload.FragmentLookup
import app.oreshkov.delva.internal.payload.PayloadSlice
import app.oreshkov.delva.internal.term.FragmentIndexer
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.property.forAll
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Replay and reads see untrusted bytes: damaged fragments through [FragmentIndexer] and the reassembler must end with
 * [CorruptionException] or a successful (different) result — never another exception, never a hang.
 */
class FragmentFuzzTest {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "fuzz").apply { isDaemon = true } }

    @AfterTest
    fun stop() {
        executor.shutdownNow()
    }

    @Test
    fun `damaged fragments give corruption errors or decode`() {
        val options = SplitOptions(64, 512, sorterBytes = 4096)
        forAll("fragment fuzz", JsonGen(JsonProfile.MIXED, fragmentSize = 64).value(), iterations = 300, maxSize = 120) { value ->
            val run = split(JsonText.minifiedBytes(value), options)
            val random = Random(value.hashCode())
            repeat(10) {
                val victim = run.fragments.written[random.nextInt(run.fragments.written.size)]
                val damaged = victim.payload.copyOf()
                when (random.nextInt(3)) {
                    0 -> repeat(random.nextInt(1, 4)) { if (damaged.isNotEmpty()) damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
                    1 -> if (damaged.isNotEmpty()) damaged[random.nextInt(damaged.size)] = (damaged[random.nextInt(damaged.size)].toInt() xor (1 shl random.nextInt(8))).toByte()
                    else -> {}
                }
                val bytes = if (random.nextInt(3) == 0) damaged.copyOf(random.nextInt(0, damaged.size + 1)) else damaged
                val lookup = FragmentLookup { pre, kind, seq ->
                    if (FragmentKey(pre, kind, seq) == victim.key) PayloadSlice(bytes, 0, bytes.size) else run.fragments.find(pre, kind, seq)
                }
                withWatchdog {
                    expectCorruptionOrSuccess { FragmentIndexer(true).index(victim.key.pre, victim.key.kind, victim.key.seq, victim.context, bytes, 0, bytes.size, CollectingPostings()) }
                    expectCorruptionOrSuccess { reassemble(lookup) }
                }
            }
        }
    }

    private fun expectCorruptionOrSuccess(block: () -> Unit) {
        try {
            block()
        } catch (_: CorruptionException) {
        }
    }

    private fun withWatchdog(block: () -> Unit) {
        val future = executor.submit(block)
        try {
            future.get(10, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause!!
        }
    }
}
