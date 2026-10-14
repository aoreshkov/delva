package app.oreshkov.delva

import app.oreshkov.delva.internal.path.JsonPathParser
import app.oreshkov.delva.internal.path.LogicalExpr

/**
 * A condition on a whole document, which limits a [JsonPathQuery] to the documents that meet it
 * ([JsonPathQuery.documentFilter], SPEC.md §10.5): an RFC 9535 logical expression, what a filter selector holds after
 * its `?`, evaluated once on each document with both `@` and `$` standing for the document's root.
 *
 * `@.kind == 'order' && @.version >= 2` keeps the documents whose root has those members, which a path alone can't
 * say: RFC 9535 filters test the children of a node, never the root itself. The whole filter grammar applies:
 * comparisons, existence tests, `!`, `&&`, `||`, parentheses, queries with nested filters, and the functions of
 * [JsonPath]. A comparison of a root member with a literal narrows the search through the index as the path's own
 * filters do.
 *
 * Compile once, then use it in any number of queries. Immutable and thread-safe; two filters are equal when their texts
 * are.
 */
@ExperimentalDelvaApi
public class DocumentFilter internal constructor(private val text: String, internal val expr: LogicalExpr) {
    override fun toString(): String = text

    override fun equals(other: Any?): Boolean = other is DocumentFilter && other.text == text

    override fun hashCode(): Int = text.hashCode()

    public companion object {
        /**
         * Parses and type-checks [text], with blank space allowed around the expression. As with [JsonPath.compile],
         * every syntax and type error throws [InvalidJsonPathException] naming the character offset, and so does text
         * beyond the limits of SPEC.md §10.1, where the filter counts as one level of nesting.
         */
        @JvmStatic
        public fun compile(text: String): DocumentFilter = DocumentFilter(text, JsonPathParser.parseFilter(text))
    }
}
