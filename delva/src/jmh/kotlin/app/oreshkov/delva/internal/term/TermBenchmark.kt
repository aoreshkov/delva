package app.oreshkov.delva.internal.term

import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.CanonicalNumber
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
 * The per-value work of indexing: varints, the order-preserving number key and term encoding. These run
 * once per posting, so they show up directly in ingest throughput. They must not allocate: run the `jmhBenchmarkJar`
 * output with `-prof gc`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
class TermBenchmark {
    /** How the number is spelled: small integers, large integers, decimals and exponents. */
    @Param("7", "1234567890123", "-25.0e-3", "3.14159")
    var number: String = "7"

    @Param("8", "64")
    var stringBytes: Int = 8

    private val out = GrowableBytes(256)
    private val encoder = TermEncoder()
    private val key = GrowableBytes(64)
    private lateinit var numberText: ByteArray
    private lateinit var keyBytes: ByteArray
    private lateinit var stringValue: ByteArray
    private lateinit var varints: ByteArray
    private val reader = ByteReader(ByteArray(0))

    @Setup
    fun setUp() {
        numberText = number.toByteArray(Charsets.US_ASCII)
        keyBytes = "sku".toByteArray()
        stringValue = ByteArray(stringBytes) { ('a' + (it % 26)).code.toByte() }
        val values = GrowableBytes(1024)
        val random = Random(3)
        repeat(128) { values.writeVarLong(random.nextLong(0, 1L shl random.nextInt(1, 62))) }
        varints = values.toByteArray()
    }

    @Benchmark
    fun numberKey(): Int {
        key.clear()
        return CanonicalNumber.writeKey(numberText, 0, numberText.size, key)
    }

    @Benchmark
    fun stringTerm(): Int {
        out.clear()
        encoder.writeKey(out, keyBytes, 0, keyBytes.size)
        encoder.writeString(out, stringValue, 0, stringValue.size)
        return out.size
    }

    @Benchmark
    fun readVarints(): Long {
        reader.reset(varints, 0, varints.size, "benchmark")
        var sum = 0L
        while (reader.remaining > 0) sum += reader.readVarLong()
        return sum
    }

    @Benchmark
    fun writeVarints(): Int {
        out.clear()
        var value = 1L
        repeat(128) {
            out.writeVarLong(value)
            value = value * 31 + 7
        }
        return out.size
    }
}
