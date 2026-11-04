package app.oreshkov.delva.internal.vector

import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.internal.query.TopK
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Distances under each metric (SPEC.md §11.3), at the edges of float32, and the top-k heap that orders them. */
class VectorDistanceTest {
    private fun distance(metric: VectorMetric, q: FloatArray, v: FloatArray): Float = VectorDistance(metric, q).to(v)

    @Test
    fun `each metric gives the distance SPEC defines`() {
        val q = floatArrayOf(1f, 2f, 3f, 4f, 5f)
        val v = floatArrayOf(-1f, 0.5f, 2f, 0f, 1f)
        val dot = -1.0 + 1.0 + 6.0 + 0.0 + 5.0
        assertEquals((-dot).toFloat(), distance(VectorMetric.DOT_PRODUCT, q, v))
        assertEquals(sqrt(4.0 + 2.25 + 1.0 + 16.0 + 16.0).toFloat(), distance(VectorMetric.EUCLIDEAN, q, v))
        assertEquals((1 - dot / sqrt(55.0 * 6.25)).toFloat(), distance(VectorMetric.COSINE, q, v))
        assertEquals(0f, distance(VectorMetric.EUCLIDEAN, q, q))
        assertEquals(0f, distance(VectorMetric.COSINE, q, floatArrayOf(2f, 4f, 6f, 8f, 10f)))
        assertEquals(2f, distance(VectorMetric.COSINE, q, FloatArray(5) { -q[it] }))
    }

    @Test
    fun `zero is positive zero, and negative zero values are zeros`() {
        val positive = 0f.toRawBits()
        assertEquals(positive, distance(VectorMetric.DOT_PRODUCT, floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)).toRawBits())
        assertEquals(positive, distance(VectorMetric.DOT_PRODUCT, floatArrayOf(-0f), floatArrayOf(1f)).toRawBits())
        // A tiny positive dot product rounds to -0.0 when negated.
        assertEquals(positive, distance(VectorMetric.DOT_PRODUCT, floatArrayOf(Float.MIN_VALUE), floatArrayOf(Float.MIN_VALUE)).toRawBits())
        assertEquals(positive, distance(VectorMetric.EUCLIDEAN, floatArrayOf(-0f, 1f), floatArrayOf(0f, 1f)).toRawBits())
    }

    @Test
    fun `huge vectors give infinite distances, never NaN, and tiny ones keep their direction`() {
        val huge = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE)
        assertEquals(Float.NEGATIVE_INFINITY, distance(VectorMetric.DOT_PRODUCT, huge, huge))
        assertEquals(Float.POSITIVE_INFINITY, distance(VectorMetric.DOT_PRODUCT, huge, floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE)))
        assertEquals(Float.POSITIVE_INFINITY, distance(VectorMetric.EUCLIDEAN, huge, floatArrayOf(-Float.MAX_VALUE, 0f)))
        assertEquals(0f, distance(VectorMetric.COSINE, huge, huge))
        // Opposite signs in different accumulators: a float sum would be inf - inf.
        val mixed = FloatArray(8) { if (it % 4 == 0) Float.MAX_VALUE else if (it % 4 == 1) -Float.MAX_VALUE else 0f }
        assertEquals(0f, distance(VectorMetric.DOT_PRODUCT, mixed, FloatArray(8) { 1f }))
        // Subnormal vectors: a float32 norm of [MIN_VALUE, MIN_VALUE] rounds to MIN_VALUE, 30% off.
        val tiny = floatArrayOf(Float.MIN_VALUE, Float.MIN_VALUE)
        assertEquals(0f, distance(VectorMetric.COSINE, tiny, tiny))
        assertEquals(1f, distance(VectorMetric.COSINE, tiny, floatArrayOf(Float.MIN_VALUE, -Float.MIN_VALUE)))
    }

    @Test
    fun `the unrolled sums agree with plain ones for every length`() {
        val random = Random(29)
        for (n in 1..40) {
            val q = FloatArray(n) { random.nextFloat() * 2 - 1 }
            val v = FloatArray(n) { random.nextFloat() * 2 - 1 }
            val dot = q.indices.sumOf { q[it].toDouble() * v[it] }
            val qq = q.sumOf { it.toDouble() * it }
            val vv = v.sumOf { it.toDouble() * it }
            val diff = q.indices.sumOf { (q[it].toDouble() - v[it]).let { d -> d * d } }
            assertEquals((-dot).toFloat(), distance(VectorMetric.DOT_PRODUCT, q, v), 1e-6f, "n = $n")
            assertEquals(sqrt(diff).toFloat(), distance(VectorMetric.EUCLIDEAN, q, v), 1e-6f, "n = $n")
            assertEquals((1 - dot / sqrt(qq * vv)).toFloat(), distance(VectorMetric.COSINE, q, v), 1e-6f, "n = $n")
        }
    }

    @Test
    fun `the top k are the k smallest by distance and then docId, nearest first`() {
        val random = Random(31)
        for (k in listOf(1, 2, 5, 64, 65, 300)) {
            val top = TopK(k)
            val all = List(500) { random.nextInt(40).toFloat() to it.toLong() }
            for ((d, docId) in all.shuffled(random)) if (top.admits(d, docId)) top.add(d, docId)
            top.sort()
            val expected = all.sortedWith(compareBy({ it.first }, { it.second })).take(k)
            assertEquals(expected, List(top.size) { top.distances[it] to top.docIds[it] }, "k = $k")
        }
        assertTrue(!TopK(1).also { it.add(1f, 5) }.admits(1f, 6))
    }
}
