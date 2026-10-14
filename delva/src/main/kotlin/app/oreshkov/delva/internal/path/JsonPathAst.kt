package app.oreshkov.delva.internal.path

import app.oreshkov.delva.internal.canon.Nfc

/**
 * A parsed and type-checked RFC 9535 query. [absolute] queries start at `$`, relative ones at `@` (inside filters).
 * Immutable.
 */
internal class PathQuery(val absolute: Boolean, val segments: List<PathSegment>) {
    /**
     * Whether the query is an RFC 9535 singular query (section 2.3.5.1): only child segments, each written as one name or
     * index selector without blank space inside its brackets. Such a query selects at most one node.
     */
    val singular: Boolean = segments.all { it.singularForm }

    /** The same query with every name and string literal in NFC (SPEC.md §2.1). */
    fun normalized(): PathQuery = PathQuery(absolute, segments.map { it.normalized() })
}

/** A child segment (`[…]`, `.name`, `.*`) or a descendant segment (`..[…]`, `..name`, `..*`) with its selectors in order. */
internal class PathSegment(val descendant: Boolean, val selectors: List<Selector>, val singularForm: Boolean) {
    fun normalized(): PathSegment = PathSegment(descendant, selectors.map { it.normalized() }, singularForm)
}

internal sealed interface Selector {
    fun normalized(): Selector = this

    class Name(val key: String) : Selector {
        val utf8: ByteArray = key.toByteArray(Charsets.UTF_8)

        override fun normalized(): Selector = Name(Nfc.normalize(key, "JSONPath name"))
    }

    data object Wildcard : Selector

    /** An index; a negative one counts from the end of the array. */
    class Index(val index: Long) : Selector

    /** `start:end:step`; a null bound takes the default of RFC 9535 section 2.3.4.2.2. */
    class Slice(val start: Long?, val end: Long?, val step: Long?) : Selector

    class Filter(val expr: LogicalExpr) : Selector {
        override fun normalized(): Selector = Filter(expr.normalized())
    }
}

/** A filter's logical expression (RFC 9535 section 2.3.5.1). */
internal sealed interface LogicalExpr {
    fun normalized(): LogicalExpr

    class Or(val items: List<LogicalExpr>) : LogicalExpr {
        override fun normalized(): LogicalExpr = Or(items.map { it.normalized() })
    }

    class And(val items: List<LogicalExpr>) : LogicalExpr {
        override fun normalized(): LogicalExpr = And(items.map { it.normalized() })
    }

    class Not(val expr: LogicalExpr) : LogicalExpr {
        override fun normalized(): LogicalExpr = Not(expr.normalized())
    }

    /** A test: true when [query] selects at least one node. */
    class Exists(val query: PathQuery) : LogicalExpr {
        override fun normalized(): LogicalExpr = Exists(query.normalized())
    }

    /** A test on a function whose result is LogicalType, or NodesType (true when it has a node). */
    class Test(val call: FunctionCall) : LogicalExpr {
        override fun normalized(): LogicalExpr = Test(call.normalized())
    }

    class Comparison(val left: ValueExpr, val op: CompareOp, val right: ValueExpr) : LogicalExpr {
        override fun normalized(): LogicalExpr = Comparison(left.normalized(), op, right.normalized())
    }
}

internal enum class CompareOp(val text: String) {
    EQ("=="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">=")
}

/** Something with a ValueType: a literal, a singular query, or a function whose result is ValueType. */
internal sealed interface ValueExpr {
    fun normalized(): ValueExpr

    class Literal(val value: LiteralValue) : ValueExpr {
        override fun normalized(): ValueExpr = Literal(value.normalized())
    }

    class Query(val query: PathQuery) : ValueExpr {
        override fun normalized(): ValueExpr = Query(query.normalized())
    }

    class Call(val call: FunctionCall) : ValueExpr {
        override fun normalized(): ValueExpr = Call(call.normalized())
    }
}

/** A literal of a filter: numbers as NumberKeys and strings as UTF-8, the forms the stored values compare in. */
internal sealed interface LiteralValue {
    fun normalized(): LiteralValue = this

    data object Null : LiteralValue

    class Bool(val value: Boolean) : LiteralValue

    class Num(val text: String, val key: ByteArray) : LiteralValue

    class Str(val value: String) : LiteralValue {
        val utf8: ByteArray = value.toByteArray(Charsets.UTF_8)

        override fun normalized(): LiteralValue = Str(Nfc.normalize(value, "JSONPath string"))
    }
}

/** The declared types of RFC 9535 section 2.4.1. */
internal enum class PathType { VALUE, LOGICAL, NODES }

/** The function extensions of the IANA registry that RFC 9535 section 2.4 creates. */
internal enum class PathFunction(val functionName: String, val result: PathType, val parameters: List<PathType>) {
    LENGTH("length", PathType.VALUE, listOf(PathType.VALUE)),
    COUNT("count", PathType.VALUE, listOf(PathType.NODES)),
    MATCH("match", PathType.LOGICAL, listOf(PathType.VALUE, PathType.VALUE)),
    SEARCH("search", PathType.LOGICAL, listOf(PathType.VALUE, PathType.VALUE)),
    VALUE("value", PathType.VALUE, listOf(PathType.NODES)),
    ;

    companion object {
        fun named(name: String): PathFunction? = entries.firstOrNull { it.functionName == name }
    }
}

/** A function argument, already checked against its parameter's type. */
internal sealed interface FunctionArg {
    fun normalized(): FunctionArg

    /** A ValueType argument. */
    class Value(val expr: ValueExpr) : FunctionArg {
        override fun normalized(): FunctionArg = Value(expr.normalized())
    }

    /** A NodesType argument: a query, singular or not. */
    class Nodes(val query: PathQuery) : FunctionArg {
        override fun normalized(): FunctionArg = Nodes(query.normalized())
    }

    /** A LogicalType argument; no registered function takes one, but the grammar allows it. */
    class Logical(val expr: LogicalExpr) : FunctionArg {
        override fun normalized(): FunctionArg = Logical(expr.normalized())
    }
}

internal class FunctionCall(val function: PathFunction, val args: List<FunctionArg>) {
    /**
     * The regular expression of `match` / `search` when its pattern is a string literal, translated once;
     * null when it isn't valid I-Regexp (the function is then false) or the pattern isn't a literal.
     */
    val literalPattern: IRegexp.Compiled? = if (function == PathFunction.MATCH || function == PathFunction.SEARCH) {
        ((args[1] as? FunctionArg.Value)?.expr as? ValueExpr.Literal)?.value?.let { (it as? LiteralValue.Str)?.value }?.let { IRegexp.compile(it) }
    } else {
        null
    }

    fun normalized(): FunctionCall = FunctionCall(function, args.map { it.normalized() })
}
