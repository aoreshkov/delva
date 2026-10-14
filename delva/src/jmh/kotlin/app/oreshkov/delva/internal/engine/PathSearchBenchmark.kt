package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.StoreOptions
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
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a JSONPath search costs on top of the index lookup a key–value search makes, over 20 000 documents of 40
 * orders each on a real disk. 200 documents hold one order with status `lost`; with [misplaced], 5 000 more hold
 * `lost` only under `returns`, where the index term matches but the path doesn't — the documents a path summary in the
 * postings would let a search skip without reading them. [fragmentBytes] 1024 splits every document into many
 * fragments, so the walk to an anchor crosses fragments. [order] reads the hits either way.
 *
 * - [keyValue]: the key–value search for the same term, the floor;
 * - [pathAnchored]: `$..[?@.status == 'lost']`, candidates from the index and a walk down to each anchor;
 * - [pathStructured]: `$.orders[?@.status == 'lost']`, where the misplaced documents are candidates that select nothing.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
class PathSearchBenchmark {
    @Param("false", "true")
    var misplaced: Boolean = false

    @Param("1024", "65536")
    var fragmentBytes: Int = 65536

    @Param("ASCENDING", "DESCENDING")
    var order: SearchOrder = SearchOrder.ASCENDING

    private lateinit var dir: Path
    private lateinit var store: Engine
    private lateinit var keyValue: KeyValueQuery
    private lateinit var anchored: JsonPathQuery
    private lateinit var structured: JsonPathQuery

    @Setup
    fun setUp() {
        keyValue = KeyValueQuery("status", JsonQueryValue.Str("lost"), ResultMode.CONTAINING_ELEMENT, limit = 10_000, order = order)
        anchored = JsonPathQuery(JsonPath.compile("$..[?@.status == 'lost']"), limit = 10_000, order = order)
        structured = JsonPathQuery(JsonPath.compile("$.orders[?@.status == 'lost']"), limit = 10_000, order = order)
        dir = Files.createTempDirectory("delva-path-bench")
        store = Engine.open(dir, EngineOptions(CreationOptions(fragmentBytes = fragmentBytes), StoreOptions(encoderBudgetBytes = maxOf(2 * fragmentBytes, 8 shl 20))), JdkFileSystemIo())
        for (batch in 0 until 20) {
            store.insertAll(
                (0 until 1000).map { i ->
                    val id = batch * 1000 + i
                    val lost = id % 100 == 7
                    val orders = (0 until 40).joinToString(",") { o ->
                        """{"id":$o,"status":"${if (lost && o == 17) "lost" else "shipped"}","items":[{"sku":"S$o","qty":${o % 5}}]}"""
                    }
                    val returns = if (misplaced && id % 4 == 1) """[{"id":1,"status":"lost"}]""" else "[]"
                    InsertSource(ByteArrayInputStream("""{"customer":"c$id","orders":[$orders],"returns":$returns}""".toByteArray()))
                },
            )
        }
        store.flush()
        while (store.compact() > 0) {
            // settle the LSM
        }
        println("\nkeyValue ${keyValue()}, anchored ${pathAnchored()}, structured ${pathStructured()}; misplaced=$misplaced T=$fragmentBytes order=$order")
    }

    @TearDown
    fun tearDown() {
        store.close()
        dir.toFile().deleteRecursively()
    }

    @Benchmark
    fun keyValue(): Int = store.search(keyValue).use { it.iterator().asSequence().count() }

    @Benchmark
    fun pathAnchored(): Int = store.search(anchored).use { it.iterator().asSequence().count() }

    @Benchmark
    fun pathStructured(): Int = store.search(structured).use { it.iterator().asSequence().count() }
}
