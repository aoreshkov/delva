package app.oreshkov.delva.internal.vector

import app.oreshkov.delva.VectorMetric
import kotlin.math.sqrt

/**
 * Distances from one query vector under one metric (SPEC.md §11.3). The values are float32, but the sums are taken in
 * double precision: a product of two floats is exact in a double, and no sum of up to 4,096 of them overflows or
 * underflows there, so no distance is NaN and none loses a tiny or huge vector's precision. Each distance is rounded
 * to a float once, at the end, and zero is always positive zero. Four independent accumulators let the loop run without
 * waiting on one sum. Thread-safe, as long as nothing changes [query].
 */
internal class VectorDistance(private val metric: VectorMetric, private val query: FloatArray) {
    private val querySquares: Double = run {
        var sum = 0.0
        for (q in query) sum += q.toDouble() * q
        sum
    }

    /** The distance from the query to [values], of the query's length. */
    fun to(values: FloatArray): Float {
        val d = when (metric) {
            VectorMetric.COSINE -> cosine(values)
            VectorMetric.DOT_PRODUCT -> (0.0 - dot(values)).toFloat()
            VectorMetric.EUCLIDEAN -> euclidean(values)
        }
        // -0.0 and 0.0 are one distance; a negative tiny dot product rounds to -0.0.
        return if (d == 0f) 0f else d
    }

    private fun dot(v: FloatArray): Double {
        val q = query
        val n = q.size
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var s3 = 0.0
        var i = 0
        while (i + 3 < n) {
            s0 += q[i].toDouble() * v[i]
            s1 += q[i + 1].toDouble() * v[i + 1]
            s2 += q[i + 2].toDouble() * v[i + 2]
            s3 += q[i + 3].toDouble() * v[i + 3]
            i += 4
        }
        while (i < n) {
            s0 += q[i].toDouble() * v[i]
            i++
        }
        return (s0 + s1) + (s2 + s3)
    }

    /** `1 − q·v / (‖q‖‖v‖)`, with `‖v‖` from [v] itself: a stored float32 norm loses precision on subnormal vectors. */
    private fun cosine(v: FloatArray): Float {
        val q = query
        val n = q.size
        var d0 = 0.0
        var d1 = 0.0
        var v0 = 0.0
        var v1 = 0.0
        var i = 0
        while (i + 1 < n) {
            val a = v[i].toDouble()
            val b = v[i + 1].toDouble()
            d0 += q[i] * a
            d1 += q[i + 1] * b
            v0 += a * a
            v1 += b * b
            i += 2
        }
        if (i < n) {
            val a = v[i].toDouble()
            d0 += q[i] * a
            v0 += a * a
        }
        val cos = (d0 + d1) / sqrt(querySquares * (v0 + v1))
        // Rounding can take |cos| a hair past 1.
        return (1.0 - cos).coerceIn(0.0, 2.0).toFloat()
    }

    private fun euclidean(v: FloatArray): Float {
        val q = query
        val n = q.size
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var s3 = 0.0
        var i = 0
        while (i + 3 < n) {
            val a = q[i].toDouble() - v[i]
            val b = q[i + 1].toDouble() - v[i + 1]
            val c = q[i + 2].toDouble() - v[i + 2]
            val e = q[i + 3].toDouble() - v[i + 3]
            s0 += a * a
            s1 += b * b
            s2 += c * c
            s3 += e * e
            i += 4
        }
        while (i < n) {
            val a = q[i].toDouble() - v[i]
            s0 += a * a
            i++
        }
        return sqrt((s0 + s1) + (s2 + s3)).toFloat()
    }
}
