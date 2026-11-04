package app.oreshkov.delva

import app.oreshkov.delva.internal.path.SingularPath

/**
 * How the distance between two vectors is measured (SPEC.md §11). Every metric gives a distance where smaller is
 * closer, so results are always ordered by ascending distance.
 */
@ExperimentalDelvaApi
public enum class VectorMetric {
    /**
     * `1 − cos(q, v)`, from 0 for vectors pointing the same way to 2 for opposite ones; the vectors' lengths don't
     * matter. A zero vector has no direction, so it's rejected under this metric.
     */
    COSINE,

    /** `−(q · v)`: the negated dot product, so that a larger dot product is a smaller distance. */
    DOT_PRODUCT,

    /** The Euclidean distance `‖q − v‖`. */
    EUCLIDEAN,
}

/**
 * The vector field of a store: where each document holds its vector, how many numbers the vector has, and how the
 * distance between two vectors is measured (SPEC.md §11). It is a creation option, set with
 * [CreationOptions.withVectorField] and fixed for the store's whole life.
 *
 * In a store with a vector field, a document that has an array of [dimensions] numbers at [path] has that vector, kept
 * as float32 values beside the document; the document itself stays as it was inserted. A document without a value at
 * [path], or with `null` there, has no vector; with [required] it's rejected instead. Anything else at [path] rejects the
 * document.
 *
 * A vector field is a value: two instances with the same settings are equal.
 *
 * Throws [InvalidJsonPathException] when [path] isn't a JSON path that can name a document's vector, and
 * [IllegalArgumentException] when [dimensions] is outside `1..`[MAX_DIMENSIONS].
 */
@ExperimentalDelvaApi
public class VectorField @JvmOverloads constructor(
    /**
     * The JSON path of the array that holds each document's vector: an RFC 9535 singular query without negative
     * indexes, such as `$.embedding`, as type paths are (SPEC.md §6.1), and not `$` itself.
     */
    public val path: String,
    /** How many numbers every vector has, from 1 to [MAX_DIMENSIONS]. */
    public val dimensions: Int,
    /** How the distance between two vectors is measured. */
    public val metric: VectorMetric,
    /** Whether every document must have a vector: then one without a value at [path], or with `null` there, is rejected. */
    public val required: Boolean = false,
) {
    init {
        if (SingularPath.parse(path).isEmpty()) throw InvalidJsonPathException(path, 0, "the vector can't be the whole document")
        require(dimensions in 1..MAX_DIMENSIONS) { "dimensions $dimensions outside 1..$MAX_DIMENSIONS" }
    }

    override fun equals(other: Any?): Boolean =
        other is VectorField && other.path == path && other.dimensions == dimensions && other.metric == metric &&
            other.required == required

    override fun hashCode(): Int = ((path.hashCode() * 31 + dimensions) * 31 + metric.hashCode()) * 31 + required.hashCode()

    override fun toString(): String = buildString {
        append("VectorField(path=").append(path)
        append(", dimensions=").append(dimensions)
        append(", metric=").append(metric)
        if (required) append(", required=true")
        append(')')
    }

    public companion object {
        /**
         * The most numbers a vector may have. At 4 bytes each, a vector takes at most 16 KiB of the index, which keeps
         * the record that makes a document visible small.
         */
        public const val MAX_DIMENSIONS: Int = 4096
    }
}
