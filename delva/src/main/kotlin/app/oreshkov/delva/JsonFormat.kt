package app.oreshkov.delva

/**
 * How a node's JSON is laid out when it's read ([SearchHit.writeJsonTo], [SelectedNode.writeJsonTo] and their
 * `readJsonAsString`). [COMPACT], the default, is the exact output form of SPEC.md §5, with no whitespace. Any other
 * format writes the same tokens with whitespace between them (SPEC.md §5.1), so removing the whitespace outside strings
 * gives the compact text back byte for byte, unless [maxLevels] cuts nested containers out. Formatting is applied as
 * the node is read; it's never stored.
 *
 * A formatted container is written on one line — `[1, 2, 3]`, `{"id": 7, "tags": ["a", "b"]}` — when that line fits
 * [lineWidth] and the container passes [maxInlineElements] and [maxInlineDepth]; otherwise each of its elements starts a
 * line of its own, indented one step deeper than the container, and the closing bracket goes on a line of its own at
 * the container's indentation. With `JsonFormat(lineWidth = 32)`:
 *
 * ```
 * {
 *   "name": "ada",
 *   "tags": ["math", "engines"],
 *   "scores": [
 *     {"round": 1, "points": 40},
 *     {"round": 2, "points": 44}
 *   ]
 * }
 * ```
 *
 * - [indent]: one indentation step, empty or up to [MAX_INDENT_SPACES] spaces, or one tab.
 * - [lineWidth]: the columns a line with a container on it may take: its indentation, the key before the container,
 *   the container and the comma after it. A column is a code point, and a tab counts as [TAB_COLUMNS]. 0 puts no
 *   non-empty container on one line — the layout of kotlinx.serialization's `prettyPrint`. At most [MAX_LINE_WIDTH].
 * - [maxInlineElements]: a container with more elements (members, or array elements) never goes on one line.
 * - [maxInlineDepth]: a container that holds a non-empty container nested deeper than this never goes on one line; 0
 *   puts only containers of scalars on one line.
 * - [maxLevels]: how many levels of nested containers are written in full. A non-empty container nested deeper than
 *   this below the node is written as `{...}` or `[...]` and laid out like a scalar, so 0 writes only the node's own
 *   elements: `{"name": "ada", "tags": [...], "address": {...}}`. Text cut this way isn't JSON.
 *
 * Empty containers are always `{}` and `[]`. A key is followed by `": "`, the elements of a one-line container are
 * separated by `", "`, lines end with `\n`, and the text doesn't end with one. Scalars are never broken, so a long
 * string makes a line longer than [lineWidth].
 *
 * Memory stays independent of the node's size: the writer holds at most one line's worth of the node while it decides.
 * Formats are values: two instances with the same settings are equal.
 */
public class JsonFormat private constructor(
    /** True for [COMPACT]; the layout properties then have no effect. */
    public val isCompact: Boolean,
    public val indent: String,
    public val lineWidth: Int,
    public val maxInlineElements: Int,
    public val maxInlineDepth: Int,
    public val maxLevels: Int,
) {
    /** A formatted layout; the defaults put a container on one line whenever it fits 80 columns. */
    @JvmOverloads
    public constructor(
        indent: String = "  ",
        lineWidth: Int = 80,
        maxInlineElements: Int = Int.MAX_VALUE,
        maxInlineDepth: Int = Int.MAX_VALUE,
        maxLevels: Int = Int.MAX_VALUE,
    ) : this(false, indent, lineWidth, maxInlineElements, maxInlineDepth, maxLevels)

    init {
        if (!isCompact) {
            require(indent == "\t" || (indent.length <= MAX_INDENT_SPACES && indent.all { it == ' ' })) {
                "indent must be empty, up to $MAX_INDENT_SPACES spaces or one tab"
            }
            require(lineWidth in 0..MAX_LINE_WIDTH) { "lineWidth $lineWidth outside 0..$MAX_LINE_WIDTH" }
            require(maxInlineElements >= 0) { "maxInlineElements must not be negative, was $maxInlineElements" }
            require(maxInlineDepth >= 0) { "maxInlineDepth must not be negative, was $maxInlineDepth" }
            require(maxLevels >= 0) { "maxLevels must not be negative, was $maxLevels" }
        }
    }

    /** Columns one indentation step takes: [TAB_COLUMNS] for a tab. */
    public val indentColumns: Int get() = if (indent == "\t") TAB_COLUMNS else indent.length

    /** True when [maxLevels] cuts nested containers out, so the text isn't JSON; known before anything is read. */
    public val isOutline: Boolean get() = maxLevels != Int.MAX_VALUE

    override fun equals(other: Any?): Boolean =
        other is JsonFormat && other.isCompact == isCompact && other.indent == indent && other.lineWidth == lineWidth &&
            other.maxInlineElements == maxInlineElements && other.maxInlineDepth == maxInlineDepth && other.maxLevels == maxLevels

    override fun hashCode(): Int = listOf(isCompact, indent, lineWidth, maxInlineElements, maxInlineDepth, maxLevels).hashCode()

    override fun toString(): String = if (isCompact) {
        "JsonFormat.COMPACT"
    } else {
        buildString {
            append("JsonFormat(indent=").append(if (indent == "\t") "tab" else "${indent.length} spaces")
            append(", lineWidth=").append(lineWidth)
            if (maxInlineElements != Int.MAX_VALUE) append(", maxInlineElements=").append(maxInlineElements)
            if (maxInlineDepth != Int.MAX_VALUE) append(", maxInlineDepth=").append(maxInlineDepth)
            if (maxLevels != Int.MAX_VALUE) append(", maxLevels=").append(maxLevels)
            append(')')
        }
    }

    public companion object {
        /** The widest [lineWidth]. */
        public const val MAX_LINE_WIDTH: Int = 10_000

        /** The most spaces [indent] may have. */
        public const val MAX_INDENT_SPACES: Int = 16

        /** The columns a tab counts as, in [indent] and in [lineWidth]. */
        public const val TAB_COLUMNS: Int = 8

        /** The output form of SPEC.md §5: no whitespace at all. */
        @JvmField
        public val COMPACT: JsonFormat = JsonFormat(true, "", 0, 0, 0, Int.MAX_VALUE)

        /** `JsonFormat()`: two-space indentation, containers on one line when they fit 80 columns. */
        @JvmField
        public val PRETTY: JsonFormat = JsonFormat()
    }
}
