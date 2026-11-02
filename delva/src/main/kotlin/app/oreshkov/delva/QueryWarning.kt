package app.oreshkov.delva

import java.util.Collections

/**
 * Something in a compiled query or filter that is valid RFC 9535 but almost certainly not what was meant: a `match` or
 * `search` whose pattern is a literal that makes the function false for every value, such as `'(?i)abc'`, which isn't
 * I-Regexp (SPEC.md §10.1). The query still compiles and runs as RFC 9535 says it must; the warning only says that
 * its result can't be the one wanted. [JsonPath.warnings] and [DocumentFilter.warnings] hold them.
 *
 * Two warnings are equal when their offset, length and reason are.
 */
@ExperimentalDelvaApi
public class QueryWarning internal constructor(
    /** The character offset of the problem in the text of the query or filter. */
    public val offset: Int,
    /** The characters the problem spans from [offset], for an editor to underline. */
    public val length: Int,
    /** What is wrong and what it means for the result, without the text and the offset. */
    public val reason: String,
) {
    /** The same warning, [by] characters further on: the offset it has in a bound text. */
    internal fun shifted(by: Int): QueryWarning = if (by == 0) this else QueryWarning(offset + by, length, reason)

    override fun equals(other: Any?): Boolean =
        other is QueryWarning && other.offset == offset && other.length == length && other.reason == reason

    override fun hashCode(): Int = (offset * 31 + length) * 31 + reason.hashCode()

    override fun toString(): String = "QueryWarning(offset=$offset, length=$length, reason=$reason)"
}

/** A copy no caller can change, Java callers included: the warnings of an immutable query are immutable too. */
internal fun List<QueryWarning>.frozen(): List<QueryWarning> =
    if (isEmpty()) emptyList() else Collections.unmodifiableList(ArrayList(this))
