package app.oreshkov.delva

import app.oreshkov.delva.internal.compare.CompareSettings
import java.io.InputStream

/** One side of a comparison (SPEC.md §9): a node of a stored document, or a JSON value given as text. */
public sealed interface CompareOperand {
    /** The value of [node]; its document must be visible when the comparison runs. */
    public class Stored(public val node: NodeRef) : CompareOperand {
        override fun toString(): String = "Stored(${node.docId.raw}, ${node.nodePre})"
    }

    /**
     * A JSON value given as text, [json], read with the rules and limits of an insert (SPEC.md §1); invalid text is
     * rejected the same way.
     */
    public class Json(public val json: String) : CompareOperand {
        override fun toString(): String = "Json(${json.length} chars)"
    }

    /**
     * A JSON value read from [source] when the comparison runs, with the rules of [Json]. The stream isn't closed and
     * can be read only once, so a second run (such as collecting a diff flow again) reads what's left of it.
     */
    public class JsonStream(public val source: InputStream) : CompareOperand {
        override fun toString(): String = "JsonStream"
    }
}

/**
 * How values are compared (SPEC.md §9.2).
 *
 * - [includeTypes]: objects are equal only when they also have the same assigned type (SPEC.md §6); values given as
 *   JSON have no types.
 * - [ignoreKeys]: object members with one of these keys are left out at every depth.
 * - [ignorePaths]: values at these paths (RFC 9535 singular queries as in SPEC.md §6.1, `$` being the compared values)
 *   are left out, with everything inside them.
 *
 * Throws [IllegalArgumentException] for invalid path syntax or keys that aren't valid Unicode.
 */
public class CompareOptions(
    public val includeTypes: Boolean = false,
    public val ignoreKeys: Set<String> = emptySet(),
    public val ignorePaths: Set<String> = emptySet(),
) {
    internal val settings = CompareSettings(includeTypes, ignoreKeys, ignorePaths, maxDifferences = 1, maxValueBytes = 0, verifySkippedSubtrees = true)

    /** These options with the given ones changed; the result is validated like a new instance. */
    public fun copy(
        includeTypes: Boolean = this.includeTypes,
        ignoreKeys: Set<String> = this.ignoreKeys,
        ignorePaths: Set<String> = this.ignorePaths,
    ): CompareOptions = CompareOptions(includeTypes, ignoreKeys, ignorePaths)

    override fun equals(other: Any?): Boolean =
        other is CompareOptions && other.includeTypes == includeTypes && other.ignoreKeys == ignoreKeys &&
            other.ignorePaths == ignorePaths

    override fun hashCode(): Int = listOf(includeTypes, ignoreKeys, ignorePaths).hashCode()

    override fun toString(): String =
        "CompareOptions(includeTypes=$includeTypes, ignoreKeys=${ignoreKeys.size}, ignorePaths=${ignorePaths.size})"
}

/**
 * How a diff is computed and reported (SPEC.md §9.3): [includeTypes], [ignoreKeys] and [ignorePaths] compare values as
 * in [CompareOptions], and
 *
 * - [maxDifferences]: the diff stops after this many differences;
 * - [maxValueBytes]: [DiffValue.json] holds at most this many bytes of UTF-8;
 * - [verifySkippedSubtrees]: compare values whose canonical hashes are equal instead of taking them as equal. The hash
 *   isn't collision-resistant against an adversary (SPEC.md §13).
 */
public class DiffOptions(
    public val includeTypes: Boolean = false,
    public val ignoreKeys: Set<String> = emptySet(),
    public val ignorePaths: Set<String> = emptySet(),
    public val maxDifferences: Int = 1000,
    public val maxValueBytes: Int = 1024,
    public val verifySkippedSubtrees: Boolean = false,
) {
    init {
        require(maxDifferences > 0) { "maxDifferences must be positive, was $maxDifferences" }
        require(maxValueBytes >= 0) { "maxValueBytes must not be negative, was $maxValueBytes" }
    }

    internal val settings = CompareSettings(includeTypes, ignoreKeys, ignorePaths, maxDifferences, maxValueBytes, verifySkippedSubtrees)

    /** These options with the given ones changed; the result is validated like a new instance. */
    public fun copy(
        includeTypes: Boolean = this.includeTypes,
        ignoreKeys: Set<String> = this.ignoreKeys,
        ignorePaths: Set<String> = this.ignorePaths,
        maxDifferences: Int = this.maxDifferences,
        maxValueBytes: Int = this.maxValueBytes,
        verifySkippedSubtrees: Boolean = this.verifySkippedSubtrees,
    ): DiffOptions = DiffOptions(includeTypes, ignoreKeys, ignorePaths, maxDifferences, maxValueBytes, verifySkippedSubtrees)

    override fun equals(other: Any?): Boolean =
        other is DiffOptions && other.includeTypes == includeTypes && other.ignoreKeys == ignoreKeys &&
            other.ignorePaths == ignorePaths && other.maxDifferences == maxDifferences &&
            other.maxValueBytes == maxValueBytes && other.verifySkippedSubtrees == verifySkippedSubtrees

    override fun hashCode(): Int =
        listOf(includeTypes, ignoreKeys, ignorePaths, maxDifferences, maxValueBytes, verifySkippedSubtrees).hashCode()

    override fun toString(): String =
        "DiffOptions(includeTypes=$includeTypes, ignoreKeys=${ignoreKeys.size}, ignorePaths=${ignorePaths.size}, " +
            "maxDifferences=$maxDifferences, maxValueBytes=$maxValueBytes, verifySkippedSubtrees=$verifySkippedSubtrees)"
}

/** What a [Difference] reports (SPEC.md §9.3). */
public enum class DifferenceKind {
    /** A member or array element exists only in the right value. */
    ADDED,

    /** A member or array element exists only in the left value. */
    REMOVED,

    /** Two values of the same JSON type differ: strings, numbers or booleans. */
    CHANGED,

    /** Two values have different JSON types, such as a string and a number, or an object and an array. */
    VALUE_TYPE_CHANGED,

    /** Two objects have different assigned types; reported only with `includeTypes`. Their members are compared too. */
    ASSIGNED_TYPE_CHANGED,
}

/**
 * One difference between the left and the right value: at [path] (an RFC 9535 Normalized Path such as `$['a'][0]`,
 * SPEC.md §9.3, `$` being the compared values), what [kind] of difference, and the values involved: [left] is null for
 * [DifferenceKind.ADDED], [right] for [DifferenceKind.REMOVED].
 */
public class Difference internal constructor(
    public val path: String,
    public val kind: DifferenceKind,
    public val left: DiffValue?,
    public val right: DiffValue?,
) {
    override fun equals(other: Any?): Boolean =
        other is Difference && other.path == path && other.kind == kind && other.left == left && other.right == right

    override fun hashCode(): Int = ((path.hashCode() * 31 + kind.hashCode()) * 31 + left.hashCode()) * 31 + right.hashCode()

    override fun toString(): String = "Difference($kind at $path: $left → $right)"
}

/** A value a [Difference] involves. */
public class DiffValue internal constructor(
    /** The stored node, or null for a value given as JSON. */
    public val node: NodeRef?,
    /** The value as JSON (SPEC.md §5), cut to its first `maxValueBytes` bytes on a character boundary when [truncated]. */
    public val json: String,
    /** Whether [json] was cut short at `maxValueBytes`. */
    public val truncated: Boolean,
    /** The object's assigned type with `includeTypes`; otherwise null. */
    public val type: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is DiffValue && other.node == node && other.json == json && other.truncated == truncated && other.type == type

    override fun hashCode(): Int = ((node.hashCode() * 31 + json.hashCode()) * 31 + truncated.hashCode()) * 31 + type.hashCode()

    override fun toString(): String = buildString {
        append(node ?: "json").append(' ').append(json)
        if (truncated) append('…')
        if (type != null) append(" as ").append(type)
    }
}
