package app.oreshkov.delva.internal.vector

import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VectorsTest {
    @Test
    fun `the sum of squares is taken in double precision, where it neither overflows nor underflows`() {
        assertEquals(25.0, Vectors.squares(floatArrayOf(3f, -4f)))
        assertEquals(0.0, Vectors.squares(floatArrayOf(0f, -0f)))
        // Squares of these overflow float32, and those of the subnormals underflow it.
        val max = Float.MAX_VALUE.toDouble()
        assertEquals(2 * max * max, Vectors.squares(floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE)))
        val min = Float.MIN_VALUE.toDouble()
        assertEquals(min * min, Vectors.squares(floatArrayOf(-Float.MIN_VALUE, 0f)))
        assertTrue(min * min > 0)
    }

    @Test
    fun `a vector is zero when every element is zero, of either sign`() {
        assertTrue(Vectors.isZero(floatArrayOf(0f, -0f)))
        assertFalse(Vectors.isZero(floatArrayOf(0f, Float.MIN_VALUE)))
    }

    @Test
    fun `a logged vector must fit the store's vector field`() {
        val cosine = VectorField("$.v", 2, VectorMetric.COSINE)
        val required = VectorField("$.v", 2, VectorMetric.EUCLIDEAN, required = true)
        assertNull(Vectors.commitProblem(null, null))
        assertNull(Vectors.commitProblem(null, cosine))
        assertNull(Vectors.commitProblem(floatArrayOf(1f, -0f), cosine))
        assertNull(Vectors.commitProblem(floatArrayOf(0f, 0f), required))
        val problems = listOf(
            Vectors.commitProblem(null, required),
            Vectors.commitProblem(floatArrayOf(1f, 2f), null),
            Vectors.commitProblem(floatArrayOf(1f), cosine),
            Vectors.commitProblem(floatArrayOf(1f, Float.NaN), required),
            Vectors.commitProblem(floatArrayOf(0f, -0f), cosine),
        )
        for (problem in problems) assertNotNull(problem)
        assertEquals(problems.size, problems.toSet().size, "each problem is described as itself: $problems")
        assertEquals(problems[3], Vectors.commitProblem(floatArrayOf(1f, Float.NEGATIVE_INFINITY), required))
    }
}
