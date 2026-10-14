package app.oreshkov.delva.internal.payload

import app.oreshkov.delva.JsonFormat
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.parse.ParseLimits
import app.oreshkov.delva.internal.parse.Utf8JsonTokenizer
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.Warmup
import java.io.ByteArrayInputStream
import java.io.OutputStream
import kotlin.random.Random

/**
 * JSON output of one decoded ~16 MB catalog payload: the compact form, and formatted layouts where most containers go
 * on one line (the default) or none do (width 0). The compact form doesn't pass through the layout; the formatted ones
 * add the width bookkeeping and the replay of held events. Allocations per event should stay zero on every path: run
 * the jmhBenchmarkJar output with `-prof gc`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
class OutputBenchmark {
    private lateinit var payload: ByteArray
    private val reader = PayloadReader()
    private val output = JsonOutput(OutputStream.nullOutputStream(), 16 * 1024)
    private val pretty = PrettyJsonOutput(output)
    private val broken = JsonFormat(lineWidth = 0)

    @Setup
    fun setUp() {
        val json = synthetic(16_000_000)
        val tokenizer = Utf8JsonTokenizer(ByteArrayInputStream(json), ParseLimits(maxDepth = 1000))
        val pending = GrowableBytes(1 shl 20)
        PendingEncoder().encode(tokenizer, pending)
        val out = GrowableBytes(pending.size)
        FragmentEncoder().encodeValue(pending.array, 0, pending.size, out)
        payload = out.toByteArray()
    }

    @Benchmark
    fun compact(): Long {
        output.reset(OutputStream.nullOutputStream())
        return write(output)
    }

    @Benchmark
    fun pretty(): Long {
        output.reset(OutputStream.nullOutputStream())
        pretty.reset(JsonFormat.PRETTY)
        return write(pretty)
    }

    @Benchmark
    fun prettyWidth0(): Long {
        output.reset(OutputStream.nullOutputStream())
        pretty.reset(broken)
        return write(pretty)
    }

    private fun write(events: JsonEvents): Long {
        reader.open(payload, 0, payload.size, FragmentKind.SUBTREE, 0)
        PayloadJson.write(reader, events)
        output.flush()
        return output.bytesWritten
    }

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
