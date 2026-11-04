package app.oreshkov.delva.internal.vector

import app.oreshkov.delva.VectorMetric
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
import java.util.Random

/**
 * The distance kernel alone: one query against [COUNT] vectors of [dimensions] numbers held in memory, under each
 * [metric], with no index or I/O around it. The score is the time for all of them, so dividing by [COUNT] gives one
 * distance.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
class VectorDistanceBenchmark {
    @Param("384", "768", "1536")
    var dimensions: Int = 768

    @Param("COSINE", "DOT_PRODUCT", "EUCLIDEAN")
    var metric: VectorMetric = VectorMetric.COSINE

    private lateinit var distance: VectorDistance
    private lateinit var vectors: Array<FloatArray>
    private lateinit var squares: DoubleArray

    @Setup
    fun setUp() {
        val random = Random(42)
        distance = VectorDistance(metric, FloatArray(dimensions) { random.nextGaussian().toFloat() })
        vectors = Array(COUNT) { FloatArray(dimensions) { random.nextGaussian().toFloat() } }
        squares = DoubleArray(COUNT) { Vectors.squares(vectors[it]) }
    }

    @Benchmark
    fun distances(): Float {
        var sum = 0f
        for (i in vectors.indices) sum += distance.to(vectors[i], squares[i])
        return sum
    }

    private companion object {
        const val COUNT = 256
    }
}
