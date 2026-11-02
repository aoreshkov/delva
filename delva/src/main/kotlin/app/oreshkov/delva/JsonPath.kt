package app.oreshkov.delva

import app.oreshkov.delva.internal.path.JsonPathParser
import app.oreshkov.delva.internal.path.PathQuery
import kotlinx.coroutines.flow.Flow
import java.io.OutputStream

/**
 * A compiled RFC 9535 JSONPath query (SPEC.md §10): the whole grammar, with the functions `length`, `count`, `match`,
 * `search` and `value`. Compile once, then use it for any number of [JsonStore.select] and [JsonStore.search] calls.
 * Immutable and thread-safe; two queries are equal when their texts are.
 */
public class JsonPath internal constructor(
    private val text: String,
    internal val query: PathQuery,
    warnings: List<QueryWarning>,
) {
    /**
     * What in the query is valid RFC 9535 but can't be what was meant, in the order of the text; empty for nearly every
     * query. Today that is a `match` or `search` whose pattern is a literal that makes it false for every value: a
     * string that isn't I-Regexp, such as `'(?i)abc'`, or a number, `true`, `false` or `null` (SPEC.md §10.1). The query
     * still runs as RFC 9535 says; a tool shows these to whoever wrote it. They follow from the text alone, so two equal
     * queries have the same warnings. A pattern that NFC makes valid or invalid warns too, and the reason says whether
     * it is false in a store with normalization or in one without.
     */
    @ExperimentalDelvaApi
    public val warnings: List<QueryWarning> = warnings.frozen()

    override fun toString(): String = text

    override fun equals(other: Any?): Boolean = other is JsonPath && other.text == text

    override fun hashCode(): Int = text.hashCode()

    public companion object {
        /**
         * Parses and type-checks [text]. Every syntax and type error is found here and throws [InvalidJsonPathException]
         * naming the character offset, as does a query beyond the limits of SPEC.md §10.1. Evaluating a compiled query
         * fails only at the bounds SPEC.md §10.2 sets on `match` and `search`, with [QueryLimitException].
         *
         * What is valid but can't be what was meant doesn't fail; the query's [warnings] say what it is.
         */
        @JvmStatic
        public fun compile(text: String): JsonPath {
            val parsed = JsonPathParser.parse(text)
            return JsonPath(text, parsed.tree, parsed.warnings)
        }
    }
}

/**
 * The nodes one JSONPath query selects from one value, as [JsonStore.select] returns them: an RFC 9535 nodelist,
 * in the RFC's order and with its duplicates, read lazily from one snapshot of the store (SPEC.md §10.3). Nodes can be
 * read until the result is closed. A result is consumed once: [iterator] and [nodes] share one position. Not
 * thread-safe.
 */
@SubclassOptInRequired(DelvaImplementationApi::class)
public interface SelectResult : AutoCloseable {
    /** The snapshot the result reads. */
    public val snapshotLsn: Long

    /** The remaining nodes, in the nodelist's order. */
    public operator fun iterator(): Iterator<SelectedNode>

    /**
     * The remaining nodes as a flow. Collecting it reads the store on the collecting coroutine's thread, so collect on
     * a dispatcher that allows blocking work.
     */
    public val nodes: Flow<SelectedNode>
}

/** One node of a [SelectResult]. Readable while its result is open. */
@SubclassOptInRequired(DelvaImplementationApi::class)
public interface SelectedNode {
    /** The node. */
    public val node: NodeRef

    /**
     * Where the node is, as an RFC 9535 Normalized Path (SPEC.md §9.3) whose `$` is the value the query started at:
     * `$['items'][0]`.
     */
    public val path: String

    /** Writes the node as JSON (SPEC.md §5) with memory independent of its size. Doesn't close [out]. */
    public fun writeJsonTo(out: OutputStream): Unit = writeJsonTo(out, JsonFormat.COMPACT)

    /** Writes the node as JSON laid out in [format] (SPEC.md §5.1) with memory independent of its size. Doesn't close [out]. */
    public fun writeJsonTo(out: OutputStream, format: JsonFormat)

    /** The node as a JSON string; throws [ResultTooLargeException] if it has more than [maxChars] characters. */
    public fun readJsonAsString(maxChars: Int = 1_000_000): String = readJsonAsString(JsonFormat.COMPACT, maxChars)

    /**
     * The node as a JSON string laid out in [format]; throws [ResultTooLargeException] if the text has more than
     * [maxChars] characters, its whitespace included.
     */
    public fun readJsonAsString(format: JsonFormat, maxChars: Int = 1_000_000): String
}
