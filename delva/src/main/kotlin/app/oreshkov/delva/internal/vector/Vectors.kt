package app.oreshkov.delva.internal.vector

import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import kotlin.math.sqrt

/** Rules about stored vectors (SPEC.md §11.2) that inserts, replay and verify share. */
internal object Vectors {
    /**
     * The Euclidean length of [values] as a float32: the sum of squares is taken in double precision, so it neither
     * overflows nor underflows for any float32 input, and is rounded once.
     */
    fun norm(values: FloatArray): Float {
        var sum = 0.0
        for (v in values) sum += v.toDouble() * v
        return sqrt(sum).toFloat()
    }

    /** Whether every element of [values] is zero, either sign. */
    fun isZero(values: FloatArray): Boolean = values.all { it == 0f }

    /**
     * What is wrong with the [vector] a COMMIT logged in a store with the vector field [field], or null when nothing
     * is: a vector needs a field and has its dimensions, finite elements and, under [VectorMetric.COSINE], a direction;
     * and only a field that isn't required allows a document without one.
     */
    fun commitProblem(vector: FloatArray?, field: VectorField?): String? = when {
        vector == null -> if (field?.required == true) "without a vector, which the store requires" else null
        field == null -> "with a vector, in a store without a vector field"
        vector.size != field.dimensions -> "with a vector of ${vector.size} numbers, not ${field.dimensions}"
        vector.any { !it.isFinite() } -> "with a vector whose numbers aren't all finite"
        field.metric == VectorMetric.COSINE && isZero(vector) -> "with a zero vector, which has no direction under COSINE"
        else -> null
    }
}
