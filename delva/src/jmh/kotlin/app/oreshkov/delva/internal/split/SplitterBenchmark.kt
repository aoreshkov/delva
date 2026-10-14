package app.oreshkov.delva.internal.split

import app.oreshkov.delva.internal.parse.ParseLimits
import app.oreshkov.delva.internal.parse.Utf8JsonTokenizer
import app.oreshkov.delva.internal.term.SortedTermRun
import app.oreshkov.delva.internal.term.TermSink
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

/**
 * Splitter throughput over one large document: tokenizing, splitting, re-encoding fragments, canonical hashes,
 * duplicate-key checks, indexing every fragment and sorting the postings into runs. Run with
 * `-Ddelva.bench.json=<file>` to measure a real document; otherwise a synthetic ~46 MB catalog is used.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
class SplitterBenchmark {
    @Param("65536")
    var fragmentBytes: Int = 65536

    @Param("true", "false")
    var hashTerms: Boolean = true

    private lateinit var json: ByteArray
    private lateinit var splitter: Splitter
    private lateinit var tokenizer: Utf8JsonTokenizer
    private var fragments = 0L
    private var postings = 0L

    @Setup
    fun setUp() {
        json = System.getProperty("delva.bench.json")?.let { Files.readAllBytes(Path.of(it)) } ?: synthetic(46_000_000)
        val options = SplitOptions(fragmentBytes, hashTerms = hashTerms)
        val sink = FragmentSink { _, _, _, _, _, _, _, _ -> fragments++ }
        val terms = TermSink { _, _, run: SortedTermRun -> postings += run.size }
        splitter = Splitter(options, sink, terms, { _, _, _ -> }, MemorySpillFiles())
        tokenizer = Utf8JsonTokenizer(ByteArrayInputStream(json), options.parseLimits(ParseLimits(maxDepth = 100_000)))
        split()
        println(
            "\n${json.size} bytes, T=$fragmentBytes hashTerms=$hashTerms: ${splitter.nodeCount} values, ${splitter.fragmentCount} fragments, " +
                "${splitter.payloadBytes} payload bytes, ${splitter.postingCount} postings in ${splitter.runCount} runs, peak record ${splitter.peakRecordBytes}",
        )
    }

    @Benchmark
    fun split(): Long {
        tokenizer.reset(ByteArrayInputStream(json))
        splitter.split(1, tokenizer, keySeed = 1)
        return splitter.fragmentCount
    }

    private fun synthetic(targetBytes: Int): ByteArray {
        val random = kotlin.random.Random(42)
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
