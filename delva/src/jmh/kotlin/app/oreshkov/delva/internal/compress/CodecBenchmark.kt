package app.oreshkov.delva.internal.compress

import app.oreshkov.delva.internal.bytes.GrowableBytes
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
import kotlinx.benchmark.TearDown
import kotlinx.benchmark.Warmup
import kotlin.random.Random

/**
 * Fragment payload codecs: deflate and inflate one payload of [payloadBytes] at [level], with and without a
 * preset dictionary built from payloads like it. Prints the compressed size in setup, so the ratio can be read next
 * to the time. Run with `-Ddelva.bench.json=<file>` to use real content instead of a synthetic catalog.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
class CodecBenchmark {
    @Param("1024", "65536")
    var payloadBytes: Int = 65536

    @Param("1", "6")
    var level: Int = 6

    @Param("true", "false")
    var dictionary: Boolean = true

    /** Dictionary size: zlib indexes the whole dictionary on every `setDictionary`, so this is a per-fragment cost. */
    @Param("32768", "8192", "2048")
    var dictionaryBytes: Int = 32768

    private lateinit var codecs: FragmentCodecs
    private lateinit var payload: ByteArray
    private lateinit var compressed: ByteArray
    private var compressedLength = 0
    private val out = GrowableBytes(1 shl 20)
    private val raw = ByteArray(1 shl 21)

    @Setup
    fun setUp() {
        val random = Random(7)
        val samples = List(64) { record(random, payloadBytes) }
        payload = record(random, payloadBytes)
        codecs = FragmentCodecs(CompressionOptions(level = level, minSavingRatio = 0.0))
        if (dictionary) {
            val builder = DictionaryBuilder(256 * 1024)
            for (s in samples) builder.add(s, 0, s.size)
            codecs.register(CompressionDictionary(1, builder.build(dictionaryBytes)), use = true)
        }
        out.clear()
        codecs.writePayload(out, payload, 0, payload.size)
        compressedLength = out.size - FragmentCodecs.CODEC_HEADER_BYTES
        compressed = out.array.copyOfRange(FragmentCodecs.CODEC_HEADER_BYTES, out.size)
        println("\n$payloadBytes bytes at level $level, dictionary $dictionary: $compressedLength bytes (${"%.1f".format(100.0 * compressedLength / payload.size)} %)")
    }

    @TearDown
    fun tearDown() = codecs.close()

    @Benchmark
    fun deflate(): Int {
        out.clear()
        codecs.writePayload(out, payload, 0, payload.size)
        return out.size
    }

    @Benchmark
    fun inflate(): Int {
        codecs.inflate(
            if (dictionary) FragmentCodecs.DEFLATE_DICT else FragmentCodecs.DEFLATE, if (dictionary) 1 else 0,
            compressed, 0, compressedLength, raw, 0, payload.size, "benchmark",
        )
        return payload.size
    }

    /** Payload-like bytes: repeated keys, short strings and numbers, as a fragment's key table and values look. */
    private fun record(random: Random, bytes: Int): ByteArray {
        val sb = StringBuilder(bytes + 256)
        while (sb.length < bytes) {
            sb.append("skunameqtytagsS").append(random.nextInt(10_000))
            sb.append("item-").append(random.nextInt(1000)).append(random.nextInt(100))
            sb.append("red").append("green").append("2021-05-20T15:57:00Z")
        }
        return sb.substring(0, bytes).toByteArray(Charsets.ISO_8859_1)
    }
}
