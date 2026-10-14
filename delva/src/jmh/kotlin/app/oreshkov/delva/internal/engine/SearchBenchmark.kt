package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.internal.compress.CompressionOptions
import app.oreshkov.delva.internal.io.JdkFileSystemIo
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
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Search latency by posting-list size and whole-document streaming throughput, over a store of 100 000 small
 * documents on a real disk. `hits = 1` is a term of one document, `100`, `10000` and `100000` are terms shared by that
 * many, so the cost of merging runs and materializing hits is visible; `SampleTime` gives p50/p99. [compressed] shows
 * what inflating fragments costs on the read path. [order] reads the hits either way; [firstHit] is the first page of
 * one hit — with `DESCENDING`, the newest copy of a key — which should cost the same whatever the list's length.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
class SearchBenchmark {
    @Param("1", "100", "10000", "100000")
    var hits: Int = 1

    @Param("ASCENDING", "DESCENDING")
    var order: SearchOrder = SearchOrder.ASCENDING

    @Param("true", "false")
    var compressed: Boolean = true

    private lateinit var dir: Path
    private lateinit var store: Engine
    private lateinit var query: KeyValueQuery
    private val sink = OutputStream.nullOutputStream()

    @Setup
    fun setUp() {
        dir = Files.createTempDirectory("delva-search-bench")
        val options = EngineOptions(
            compression = if (compressed) CompressionOptions() else CompressionOptions.DISABLED,
        )
        store = Engine.open(dir, options, JdkFileSystemIo())
        // Every document has a unique sku, a "group" shared by 100 and a "kind" shared by 10 000.
        for (batch in 0 until 100) {
            store.insertAll(
                (0 until 1000).map { i ->
                    val id = batch * 1000 + i
                    val json = """{"sku":"S$id","group":${id % 1000},"kind":"k${id % 10}","name":"item $id","tags":["red","green","blue"],"note":"${"n".repeat(id % 50)}"}"""
                    InsertSource(ByteArrayInputStream(json.toByteArray()))
                },
            )
        }
        store.flush()
        while (store.compact() > 0) {
            // compact until the policy picks nothing more, so reads see a settled LSM
        }
        query = when (hits) {
            1 -> KeyValueQuery("sku", JsonQueryValue.Str("S54321"), ResultMode.CONTAINING_ELEMENT, limit = 1000)
            100 -> KeyValueQuery("group", JsonQueryValue.Num(321), ResultMode.CONTAINING_ELEMENT, limit = 1000)
            10000 -> KeyValueQuery("kind", JsonQueryValue.Str("k3"), ResultMode.CONTAINING_ELEMENT, limit = 1000)
            else -> KeyValueQuery("tags", JsonQueryValue.Str("red"), ResultMode.CONTAINING_ELEMENT, limit = 1000)
        }.copy(order = order)
        val found = search()
        println("\n$hits hits expected, $found found, compressed=$compressed")
    }

    @TearDown
    fun tearDown() {
        store.close()
        dir.toFile().deleteRecursively()
    }

    /** The whole search: term lookup, run merge, visibility and materializing every hit's NodeRef. */
    @Benchmark
    fun search(): Int = store.search(query).use { result -> result.iterator().asSequence().count() }

    /** The first page of one hit: the oldest, or with `DESCENDING` the newest. */
    @Benchmark
    fun firstHit(): Long = store.search(query.copy(limit = 1)).use { result -> result.nextPage()!!.hits.single().docId.raw }

    /** The same hits with their JSON written out (fragment cache, fragment decode, reassembly). */
    @Benchmark
    fun searchAndStream(): Long {
        var bytes = 0L
        store.search(query).use { result ->
            for (hit in result.iterator()) {
                hit.writeJsonTo(sink)
                bytes += hit.encodedSize
            }
        }
        return bytes
    }
}
