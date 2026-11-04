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
    fun `the norm is taken in double precision and rounded once`() {
        assertEquals(5f, Vectors.norm(floatArrayOf(3f, -4f)))
        assertEquals(0f, Vectors.norm(floatArrayOf(0f, -0f)))
        // Squares of these overflow float32, and those of the subnormals underflow it.
        assertEquals(Float.MAX_VALUE, Vectors.norm(floatArrayOf(Float.MAX_VALUE, 0f)))
        assertEquals(Float.MIN_VALUE, Vectors.norm(floatArrayOf(-Float.MIN_VALUE)))
        assertEquals(Float.POSITIVE_INFINITY, Vectors.norm(floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE)))
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
