package app.oreshkov.delva.internal.hash

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import kotlin.random.Random

/**
 * XXH3 throughput for the term/key case (8–64 bytes), the mid sizes, and long inputs.
 * Allocations (should be zero): run the jmhBenchmarkJar output with `-prof gc`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
class Xxh3Benchmark {
    @Param("8", "16", "32", "64", "200", "1024", "65536")
    var length: Int = 0

    private lateinit var input: ByteArray
    private val out = LongArray(2)
    private val state = Xxh3State()

    @Setup
    fun setUp() {
        input = Random(42).nextBytes(length)
    }

    @Benchmark
    fun hash64(): Long = Xxh3.hash64(input, 0, input.size, 0L)

    @Benchmark
    fun hash128(): Long {
        Xxh3.hash128(input, 0, input.size, 0L, out, 0)
        return out[0]
    }

    @Benchmark
    fun hash128Seeded(): Long {
        Xxh3.hash128(input, 0, input.size, 0x9E3779B185EBCA8DuL.toLong(), out, 0)
        return out[0]
    }

    @Benchmark
    fun streaming128(): Long {
        state.reset().update(input, 0, input.size).digest128(out, 0)
        return out[0]
    }
}
