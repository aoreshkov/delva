package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.VectorQuery
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
import java.util.Random
import kotlin.math.sqrt

/**
 * Exact top-10 vector search over [vectors] chunk documents with unit-length Gaussian vectors of [dimensions] numbers
 * under cosine, on a real disk with the LSM settled. [filter] is none, or a document filter on a root member that one
 * document in 100 (`1%`) or in 10 (`10%`) matches, which the index narrows before any vector is read. [cache] is a
 * block cache that holds the vector index or one that doesn't; [vectorBlockBytes] the size of vector segment blocks.
 */
@OptIn(ExperimentalDelvaApi::class)
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
class VectorSearchBenchmark {
    @Param("20000")
    var vectors: Int = 20000

    @Param("768")
    var dimensions: Int = 768

    @Param("none", "1%", "10%")
    var filter: String = "none"

    /** `default`: the block cache the heap gives, smaller than the vector index; `large`: 1 GiB, which holds it. */
    @Param("default", "large")
    var cache: String = "default"

    /** Whether segments are read with `pread` or through a memory mapping (`StoreOptions.mmapReads`). */
    @Param("false", "true")
    var mmap: Boolean = false

    /** Target size of vector segment blocks in bytes, or `default`: the engine's. */
    @Param("default")
    var vectorBlockBytes: String = "default"

    private lateinit var dir: Path
    private lateinit var store: Engine
    private lateinit var query: VectorQuery

    @Setup
    fun setUp() {
        dir = Files.createTempDirectory("delva-vector-bench")
        val field = VectorField("$.embedding", dimensions, VectorMetric.COSINE)
        val creation = CreationOptions(excludedKeys = setOf("embedding")).withVectorField(field)
        val options = EngineOptions(
            creation,
            blockCacheBytes = if (cache == "large") 1L shl 30 else null,
            vectorSegmentBlockBytes = vectorBlockBytes.takeIf { it != "default" }?.toInt(),
        )
        store = Engine.open(dir, options, JdkFileSystemIo(mmapReads = mmap))
        val random = Random(42)
        var next = 0
        while (next < vectors) {
            val batch = List(minOf(1000, vectors - next)) { i -> chunk(next + i, random) }
            store.insertAll(batch.map { InsertSource(ByteArrayInputStream(it)) })
            next += batch.size
        }
        store.flush()
        while (store.compact() > 0) {
            // compact until the policy picks nothing more, so reads see a settled LSM
        }
        val base = VectorQuery(unitGaussian(random), k = 10)
        query = when (filter) {
            "1%" -> base.withDocumentFilter(DocumentFilter.compile("@.hundredth == 7"))
            "10%" -> base.withDocumentFilter(DocumentFilter.compile("@.tenth == 3"))
            else -> base
        }
        println("\n${search()} hits, $vectors vectors of $dimensions, filter $filter")
    }

    @TearDown
    fun tearDown() {
        store.close()
        dir.toFile().deleteRecursively()
    }

    @Benchmark
    fun search(): Int = store.search(query).use { it.hits.size }

    private fun unitGaussian(random: Random): FloatArray {
        val v = FloatArray(dimensions) { random.nextGaussian().toFloat() }
        var squares = 0.0
        for (x in v) squares += x.toDouble() * x
        val scale = (1 / sqrt(squares)).toFloat()
        for (i in v.indices) v[i] *= scale
        return v
    }

    private fun chunk(n: Int, random: Random): ByteArray {
        val json = StringBuilder(dimensions * 12 + 64)
        json.append("""{"n":""").append(n).append(""","hundredth":""").append(n % 100).append(""","tenth":""").append(n % 10)
            .append(""","embedding":[""")
        for ((i, x) in unitGaussian(random).withIndex()) {
            if (i > 0) json.append(',')
            json.append(x)
        }
        return json.append("]}").toString().toByteArray()
    }
}
