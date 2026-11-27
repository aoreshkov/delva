package app.oreshkov.delva.cli

import app.oreshkov.clikit.ConversionResult
import app.oreshkov.clikit.Converters
import app.oreshkov.clikit.InputSource
import app.oreshkov.clikit.ReadsStandardInput
import app.oreshkov.clikit.ValueConverter
import app.oreshkov.clikit.inputSource
import app.oreshkov.clikit.map
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.InvalidCursorException
import app.oreshkov.delva.InvalidJsonPathException
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.VectorMetric

/**
 * delva's values as the command line spells them. Each is checked while parsing, so a malformed one is a usage error
 * with the command's usage line, before any store is opened.
 */
internal object ValueArgs {

    /** A document id: `42`. */
    val docId: ValueConverter<DocId> = ValueConverter { raw ->
        val id = raw.toLongOrNull()
        if (id == null || id < 1) ConversionResult.Invalid("'$raw' is not a document id") else ConversionResult.Ok(DocId(id))
    }

    /** A node, as `doc:node` — the form every command prints a node in, so an output can be pasted back as an input. */
    val nodeRef: ValueConverter<NodeRef> = ValueConverter { raw ->
        val doc = raw.substringBefore(':', "").toLongOrNull()
        val node = raw.substringAfter(':', "").toLongOrNull()
        if (doc == null || doc < 1 || node == null || node < 0) {
            ConversionResult.Invalid("'$raw' is not a node; write it as doc:node, such as 42:0")
        } else {
            ConversionResult.Ok(NodeRef(DocId(doc), node))
        }
    }

    /** An RFC 9535 JSONPath query, compiled here so a syntax error is reported where it was typed. */
    val jsonPath: ValueConverter<JsonPath> = ValueConverter { raw ->
        try {
            ConversionResult.Ok(JsonPath.compile(raw))
        } catch (e: InvalidJsonPathException) {
            ConversionResult.Invalid(e.message ?: "'$raw' is not a JSONPath query")
        }
    }

    /** A document filter, an RFC 9535 logical expression whose `@` is the document's root, compiled as [jsonPath] is. */
    val documentFilter: ValueConverter<DocumentFilter> = ValueConverter { raw ->
        try {
            ConversionResult.Ok(DocumentFilter.compile(raw))
        } catch (e: InvalidJsonPathException) {
            ConversionResult.Invalid(e.message ?: "'$raw' is not a document filter")
        }
    }

    /** A cursor as a hit's JSON record carries it, or as the `more: --after …` notice prints it. */
    val cursor: ValueConverter<Cursor> = ValueConverter { raw ->
        try {
            ConversionResult.Ok(Cursor.parse(raw))
        } catch (e: InvalidCursorException) {
            ConversionResult.Invalid(e.message ?: "'$raw' is not a cursor")
        }
    }

    val mode: ValueConverter<ResultMode> = Converters.enum(ResultMode.entries) {
        when (it) {
            ResultMode.WHOLE_DOCUMENT -> "document"
            ResultMode.CONTAINING_ELEMENT -> "element"
        }
    }

    val nested: ValueConverter<NestedHits> = Converters.enum(NestedHits.entries) {
        when (it) {
            NestedHits.ALL -> "all"
            NestedHits.OUTERMOST_ONLY -> "outermost"
        }
    }

    val order: ValueConverter<SearchOrder> = Converters.enum(SearchOrder.entries) {
        when (it) {
            SearchOrder.ASCENDING -> "asc"
            SearchOrder.DESCENDING -> "desc"
        }
    }

    /** `doc:node`, the one way `delva` prints a node. */
    fun text(node: NodeRef): String = "${node.docId.raw}:${node.nodePre}"

    /**
     * `<jsonpath>=<Type>`, a type for the objects an insert's path names, such as `$.customer=Customer`. Split at the
     * last `=`, since a path can quote a key with one in it and a type name is simpler.
     */
    val typeAssignment: ValueConverter<Pair<String, String>> = ValueConverter { raw ->
        val at = raw.lastIndexOf('=')
        if (at <= 0 || at == raw.length - 1 || !raw.startsWith("$")) {
            ConversionResult.Invalid("'$raw' is not <jsonpath>=<Type>, such as \$.customer=Customer")
        } else {
            ConversionResult.Ok(raw.substring(0, at) to raw.substring(at + 1))
        }
    }

    /**
     * One side of a comparison: a stored document or node, a file, or `-` for standard input, which the parser lets
     * one side read at most.
     */
    sealed interface Operand : ReadsStandardInput {
        data class Stored(val node: NodeRef) : Operand {
            override val readsStandardInput: Boolean get() = false
        }

        data class Input(val source: InputSource) : Operand {
            override val readsStandardInput: Boolean get() = source.readsStandardInput
        }
    }

    /**
     * `42` is document 42, `42:7` a node of it, `-` standard input, and anything else a file of JSON. A file whose
     * name is all digits is written `./42`.
     */
    val operand: ValueConverter<Operand> = ValueConverter { raw ->
        when {
            DOC_ONLY.matches(raw) -> docId.convert(raw).map { Operand.Stored(NodeRef(it, 0)) }
            NODE.matches(raw) -> nodeRef.convert(raw).map { Operand.Stored(it) }
            else -> Converters.inputSource.convert(raw).map { Operand.Input(it) }
        }
    }

    /** A document to delete, or `-` for ids read from standard input, which the parser takes once at most. */
    sealed interface DeleteTarget : ReadsStandardInput {
        data class One(val doc: DocId) : DeleteTarget {
            override val readsStandardInput: Boolean get() = false
        }

        data object FromStdin : DeleteTarget {
            override val readsStandardInput: Boolean get() = true
        }
    }

    val deleteTarget: ValueConverter<DeleteTarget> = ValueConverter { raw ->
        if (raw == "-") ConversionResult.Ok(DeleteTarget.FromStdin) else docId.convert(raw).map { DeleteTarget.One(it) }
    }

    /** A vector metric: `cosine`, `dot-product` or `euclidean`. */
    @OptIn(ExperimentalDelvaApi::class)
    val metric: ValueConverter<VectorMetric> = Converters.enum(VectorMetric.entries)

    /** A distance: any number but NaN. It may be negative, since under the dot product a distance is the negated product. */
    val distance: ValueConverter<Float> = ValueConverter { raw ->
        val value = raw.toFloatOrNull()
        if (value == null || value.isNaN()) ConversionResult.Invalid("'$raw' is not a distance") else ConversionResult.Ok(value)
    }

    /** The vector a vector search looks near: given, read from a file or standard input, or the one a stored document has. */
    sealed interface QueryVector : ReadsStandardInput {
        /** The vector as it was written on the command line. */
        class Given(val values: FloatArray) : QueryVector {
            override val readsStandardInput: Boolean get() = false
        }

        /** A JSON array of numbers in a file, or on standard input. */
        data class Input(val source: InputSource) : QueryVector {
            override val readsStandardInput: Boolean get() = source.readsStandardInput
        }

        /** The vector of a document of the store. */
        data class Stored(val doc: DocId) : QueryVector {
            override val readsStandardInput: Boolean get() = false
        }
    }

    /**
     * `42` is the vector of document 42, text that starts with `[` the vector itself, `-` standard input, and anything
     * else a file that holds the vector. A file whose name is all digits is written `./42`.
     */
    val queryVector: ValueConverter<QueryVector> = ValueConverter { raw ->
        when {
            DOC_ONLY.matches(raw) -> docId.convert(raw).map { QueryVector.Stored(it) }
            raw.startsWith('[') -> try {
                ConversionResult.Ok(QueryVector.Given(parseVector(raw)))
            } catch (e: IllegalArgumentException) {
                ConversionResult.Invalid(e.message ?: "not a vector")
            }
            else -> Converters.inputSource.convert(raw).map { QueryVector.Input(it) }
        }
    }

    /**
     * The numbers of [text], a JSON array of numbers such as `[0.12, -0.5, 3e-2]`, as floats. Throws
     * [IllegalArgumentException], naming the element, for any other text and for a number too large for a float.
     */
    fun parseVector(text: String): FloatArray {
        val array = text.trim()
        require(array.length >= 2 && array.startsWith('[') && array.endsWith(']')) { "a vector is a JSON array of numbers, such as [0.12, -0.5]" }
        val elements = array.substring(1, array.length - 1)
        require(elements.isNotBlank()) { "the vector is empty" }
        val numbers = elements.split(',')
        return FloatArray(numbers.size) { i ->
            val number = numbers[i].trim()
            require(JSON_NUMBER.matches(number)) { "element $i of the vector, '${number.take(MAX_SHOWN)}', is not a number" }
            number.toFloat().also { require(it.isFinite()) { "element $i of the vector, ${number.take(MAX_SHOWN)}, is too large" } }
        }
    }

    /** A JSON number (RFC 8259 §6); Kotlin's own parsing also takes `NaN`, `1f` and hexadecimal. */
    private val JSON_NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")

    /** Characters of a wrong element a message quotes at most. */
    private const val MAX_SHOWN = 40

    private val DOC_ONLY = Regex("\\d+")
    private val NODE = Regex("\\d+:\\d+")
}
