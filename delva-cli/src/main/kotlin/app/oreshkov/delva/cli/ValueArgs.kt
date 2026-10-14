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
    @OptIn(ExperimentalDelvaApi::class)
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

    private val DOC_ONLY = Regex("\\d+")
    private val NODE = Regex("\\d+:\\d+")
}
