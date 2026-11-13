package app.oreshkov.delva.internal.vector

import app.oreshkov.delva.VectorMetric
import kotlin.math.sqrt

/**
 * Distances from one query vector under one metric (SPEC.md §11.3). The values are float32, but the sums are taken in
 * double precision: a product of two floats is exact in a double, and no sum of up to 4,096 of them overflows or
 * underflows there, so no distance is NaN and none loses a tiny or huge vector's precision. Each distance is rounded
 * to a float once, at the end, and zero is always positive zero.
 *
 * Cosine takes the vector's sum of squares as stored with it ([Vectors.squares]), which is as precise as summing them
 * again, so every metric costs one pass over the vector. The loops are unrolled by eight with an accumulator per lane,
 * so eight additions are in flight rather than one chain waiting on itself, and the query is held as doubles, which
 * saves converting it again for every vector. Thread-safe.
 */
internal class VectorDistance(val metric: VectorMetric, query: FloatArray) {
    private val query = DoubleArray(query.size) { query[it].toDouble() }

    private val querySquares: Double = Vectors.squares(query)

    /** The distance from the query to [values], of the query's length, whose sum of squares is [squares]. */
    fun to(values: FloatArray, squares: Double): Float {
        val d = when (metric) {
            VectorMetric.COSINE -> cosine(values, squares)
            VectorMetric.DOT_PRODUCT -> (0.0 - dot(values)).toFloat()
            VectorMetric.EUCLIDEAN -> euclidean(values)
        }
        // -0.0 and 0.0 are one distance; a negative tiny dot product rounds to -0.0.
        return if (d == 0f) 0f else d
    }

    private fun dot(v: FloatArray): Double {
        val q = query
        val n = q.size
        require(v.size == n)
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var s3 = 0.0
        var s4 = 0.0
        var s5 = 0.0
        var s6 = 0.0
        var s7 = 0.0
        var i = 0
        while (i + 7 < n) {
            s0 += q[i] * v[i]
            s1 += q[i + 1] * v[i + 1]
            s2 += q[i + 2] * v[i + 2]
            s3 += q[i + 3] * v[i + 3]
            s4 += q[i + 4] * v[i + 4]
            s5 += q[i + 5] * v[i + 5]
            s6 += q[i + 6] * v[i + 6]
            s7 += q[i + 7] * v[i + 7]
            i += 8
        }
        while (i < n) {
            s0 += q[i] * v[i]
            i++
        }
        return ((s0 + s1) + (s2 + s3)) + ((s4 + s5) + (s6 + s7))
    }

    /**
     * `1 − q·v / (‖q‖‖v‖)`. The product of the two sums of squares stays in a double's range: each is at most
     * 4,096 × (3.4 × 10³⁸)² and at least (1.4 × 10⁻⁴⁵)² for a vector that isn't zero.
     */
    private fun cosine(v: FloatArray, squares: Double): Float {
        val cos = dot(v) / sqrt(querySquares * squares)
        // Rounding can take |cos| a hair past 1.
        return (1.0 - cos).coerceIn(0.0, 2.0).toFloat()
    }

    private fun euclidean(v: FloatArray): Float {
        val q = query
        val n = q.size
        require(v.size == n)
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var s3 = 0.0
        var s4 = 0.0
        var s5 = 0.0
        var s6 = 0.0
        var s7 = 0.0
        var i = 0
        while (i + 7 < n) {
            val a0 = q[i] - v[i]
            val a1 = q[i + 1] - v[i + 1]
            val a2 = q[i + 2] - v[i + 2]
            val a3 = q[i + 3] - v[i + 3]
            val a4 = q[i + 4] - v[i + 4]
            val a5 = q[i + 5] - v[i + 5]
            val a6 = q[i + 6] - v[i + 6]
            val a7 = q[i + 7] - v[i + 7]
            s0 += a0 * a0
            s1 += a1 * a1
            s2 += a2 * a2
            s3 += a3 * a3
            s4 += a4 * a4
            s5 += a5 * a5
            s6 += a6 * a6
            s7 += a7 * a7
            i += 8
        }
        while (i < n) {
            val a = q[i] - v[i]
            s0 += a * a
            i++
        }
        return sqrt(((s0 + s1) + (s2 + s3)) + ((s4 + s5) + (s6 + s7))).toFloat()
    }
}
