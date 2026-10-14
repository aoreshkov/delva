package app.oreshkov.delva.internal.parse

import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.payload.FragmentEncoder
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.PayloadReader
import app.oreshkov.delva.internal.payload.PayloadValidator
import app.oreshkov.delva.internal.payload.PendingEncoder
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
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * Tokenizer and payload decoder throughput over one large document. Run with `-Ddelva.bench.json=<file>` to measure a
 * real document; otherwise a ~46 MB synthetic catalog document is used. Allocations per token and per value should
 * be near zero: run the jmhBenchmarkJar output with `-prof gc`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
class TokenizerBenchmark {
    /** Input bytes the tokenizer holds at a time: a larger window refills less often but touches more cache. */
    @Param("16384", "65536", "262144")
    var windowBytes: Int = Utf8JsonTokenizer.DEFAULT_WINDOW_BYTES

    private lateinit var json: ByteArray
    private lateinit var payload: ByteArray
    private lateinit var tokenizer: Utf8JsonTokenizer
    private val reader = PayloadReader()
    private val validator = PayloadValidator()
    private val encoder = PendingEncoder()
    private val pending = GrowableBytes(1 shl 20)

    @Setup
    fun setUp() {
        json = System.getProperty("delva.bench.json")?.let { Files.readAllBytes(Path.of(it)) } ?: synthetic(46_000_000)
        tokenizer = Utf8JsonTokenizer(ByteArrayInputStream(json), ParseLimits(maxDepth = 100_000), windowBytes)
        pending.clear()
        tokenizer.reset(ByteArrayInputStream(json))
        encoder.encode(tokenizer, pending)
        val out = GrowableBytes(pending.size)
        FragmentEncoder().encodeValue(pending.array, 0, pending.size, out)
        payload = out.toByteArray()
    }

    /** All tokens of the document. */
    @Benchmark
    fun tokenize(): Long {
        tokenizer.reset(ByteArrayInputStream(json))
        var tokens = 0L
        while (tokenizer.next() != JsonToken.EOF) tokens++
        return tokens
    }

    /** Tokens to pending bytes (no fragment re-encode). */
    @Benchmark
    fun tokenizeToPending(): Int {
        tokenizer.reset(ByteArrayInputStream(json))
        pending.clear()
        encoder.encode(tokenizer, pending)
        return pending.size
    }

    /** Every value of the whole-document payload, with structural validation. */
    @Benchmark
    fun decode(): Long {
        reader.open(payload, 0, payload.size, FragmentKind.SUBTREE, 0)
        var events = 0L
        while (reader.next() != PayloadReader.END) events++
        return events
    }

    /** Full validation, including UTF-8 and number checks. */
    @Benchmark
    fun validate(): Long = validator.validate(payload, 0, payload.size, FragmentKind.SUBTREE, 0, 0)

    private fun synthetic(targetBytes: Int): ByteArray {
        val random = Random(42)
        val sb = StringBuilder(targetBytes + 1024)
        sb.append("{\"content\":[")
        var first = true
        while (sb.length < targetBytes) {
            if (!first) sb.append(',')
            first = false
            sb.append("{\"@type\":\"type.googleapis.com/Item\",\"id\":").append(random.nextInt(1_000_000))
            sb.append(",\"name\":\"item-").append(random.nextInt(10_000)).append("\",\"price\":").append(random.nextInt(10_000) / 100.0)
            sb.append(",\"tags\":[\"a\",\"b\",\"hot\"],\"enabled\":").append(random.nextBoolean())
            sb.append(",\"attrs\":{\"weight\":").append(random.nextInt(100)).append(",\"note\":\"").append("x".repeat(random.nextInt(0, 40)))
            sb.append("\\n\"},\"timestamp\":\"2021-05-20T15:57:00Z\"}")
        }
        sb.append("]}")
        return sb.toString().toByteArray()
    }
}
