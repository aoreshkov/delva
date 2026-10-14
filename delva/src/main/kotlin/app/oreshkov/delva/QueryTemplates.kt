package app.oreshkov.delva

import app.oreshkov.delva.internal.path.JsonPathParser
import app.oreshkov.delva.internal.path.LogicalExpr
import app.oreshkov.delva.internal.path.PathQuery
import app.oreshkov.delva.internal.path.Placeholders

/**
 * An RFC 9535 JSONPath query with named placeholders, compiled once and bound to values any number of times
 * (SPEC.md §10.6). A placeholder `${name}` stands where the RFC allows a literal: an operand of a comparison, or a
 * value argument of a function such as the pattern of `match`.
 *
 * ```
 * val byCustomer = JsonPathTemplate.compile("\$.orders[?@.customer == \${customer} && @.total >= \${min}]")
 * val path = byCustomer.bind("customer" to JsonQueryValue.Str(name), "min" to JsonQueryValue.Num(100))
 * ```
 *
 * [bind] puts each value into the compiled query as a literal, never as text, so no value can change what the query
 * asks: `x' || true || 'x` is one string the query compares with. The bound [JsonPath] is the one [JsonPath.compile]
 * makes of the template's text with each placeholder replaced by its value's literal, and that text is its
 * [JsonPath.toString]; binding doesn't parse it again.
 *
 * Placeholders are an extension of RFC 9535, which has none, and [JsonPath.compile] refuses them. Immutable and
 * thread-safe; two templates are equal when their texts are.
 */
@ExperimentalDelvaApi
public class JsonPathTemplate private constructor(private val tree: PathQuery, private val placeholders: Placeholders) {
    /** The names of the placeholders, each once, in the order they first appear in the text. */
    public val parameters: Set<String> get() = placeholders.names

    /**
     * The query with each placeholder replaced by the literal of its value in [values], which has a value for every
     * one of [parameters] and for nothing else; a [JsonQueryValue.Json] value has no literal form. Each of these is
     * an [IllegalArgumentException]. A value that makes the query invalid, such as a string with an unpaired
     * surrogate, a number whose exponent is out of range or a string that takes the query beyond its length limit,
     * throws [InvalidJsonPathException] naming the offset in the bound text, as compiling that text would.
     */
    public fun bind(values: Map<String, JsonQueryValue>): JsonPath {
        val binding = placeholders.bind(values)
        return JsonPath(binding.text, binding.query(tree))
    }

    /** [bind] with the values given as pairs: `bind("id" to JsonQueryValue.Str(id))`. */
    public fun bind(vararg values: Pair<String, JsonQueryValue>): JsonPath = bind(mapOf(*values))

    override fun toString(): String = placeholders.toString()

    override fun equals(other: Any?): Boolean = other is JsonPathTemplate && other.toString() == toString()

    override fun hashCode(): Int = toString().hashCode()

    public companion object {
        /**
         * Parses and type-checks [text], a query in which `${name}` can stand for a literal; a name has ASCII letters,
         * digits and `_`, and doesn't start with a digit. Errors and limits are those of [JsonPath.compile], and a
         * placeholder where no literal can stand, such as a name selector or a test of its own, is an error too.
         */
        @JvmStatic
        public fun compile(text: String): JsonPathTemplate {
            val (tree, placeholders) = JsonPathParser.parseTemplate(text)
            return JsonPathTemplate(tree, placeholders)
        }
    }
}

/**
 * A [DocumentFilter] with named placeholders, compiled once and bound to values any number of times (SPEC.md §10.6),
 * as [JsonPathTemplate] is for a query: `@.kind == ${kind} && @.version >= ${version}`.
 *
 * [bind] puts each value into the compiled filter as a literal, never as text, so no value can change what the filter
 * tests. The bound filter is the one [DocumentFilter.compile] makes of the template's text with each placeholder
 * replaced by its value's literal, and that text is its [DocumentFilter.toString].
 *
 * Immutable and thread-safe; two templates are equal when their texts are.
 */
@ExperimentalDelvaApi
public class DocumentFilterTemplate private constructor(private val tree: LogicalExpr, private val placeholders: Placeholders) {
    /** The names of the placeholders, each once, in the order they first appear in the text. */
    public val parameters: Set<String> get() = placeholders.names

    /** The filter with each placeholder replaced by the literal of its value, with the rules of [JsonPathTemplate.bind]. */
    public fun bind(values: Map<String, JsonQueryValue>): DocumentFilter {
        val binding = placeholders.bind(values)
        return DocumentFilter(binding.text, binding.filter(tree))
    }

    /** [bind] with the values given as pairs: `bind("kind" to JsonQueryValue.Str(kind))`. */
    public fun bind(vararg values: Pair<String, JsonQueryValue>): DocumentFilter = bind(mapOf(*values))

    override fun toString(): String = placeholders.toString()

    override fun equals(other: Any?): Boolean = other is DocumentFilterTemplate && other.toString() == toString()

    override fun hashCode(): Int = toString().hashCode()

    public companion object {
        /**
         * Parses and type-checks [text], a filter in which `${name}` can stand for a literal, with the rules of
         * [JsonPathTemplate.compile] and the errors and limits of [DocumentFilter.compile].
         */
        @JvmStatic
        public fun compile(text: String): DocumentFilterTemplate {
            val (tree, placeholders) = JsonPathParser.parseFilterTemplate(text)
            return DocumentFilterTemplate(tree, placeholders)
        }
    }
}
