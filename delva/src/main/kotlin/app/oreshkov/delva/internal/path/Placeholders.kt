package app.oreshkov.delva.internal.path

import app.oreshkov.delva.InvalidJsonPathException
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.QueryWarning
import app.oreshkov.delva.frozen
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.CanonicalNumber
import java.util.IdentityHashMap

/** One `${name}` of a template's text, `text[start, end)`, and the stand-in literal the parser put in its place. */
internal class Placeholder(val name: String, val start: Int, val end: Int, val standIn: LiteralValue)

/** A `match` or `search` [call] of a template whose pattern is the placeholder at [index] of the template's list. */
internal class PatternPlaceholder(val call: FunctionCall, val index: Int)

/**
 * The placeholders of a template ([JsonPathParser.parseTemplate]), in the order of the text, and how values bind to
 * them (SPEC.md §10.6). A bound value becomes a literal of the tree, never text that is parsed, so no value can change
 * the query's structure. The bound text, the template's with each placeholder replaced by its value's literal, is what
 * [JsonPathParser] would parse into the same tree: it is the bound query's text, and its equality.
 *
 * The bound query's warnings are those [JsonPathParser] would find in the bound text too: the template's own
 * [warnings], moved to their offsets in the bound text, and those of the [patterns] whose placeholder is bound to a
 * pattern that can't match.
 */
internal class Placeholders(
    private val text: String,
    private val list: List<Placeholder>,
    private val what: String,
    warnings: List<QueryWarning>,
    private val patterns: List<PatternPlaceholder>,
) {
    /** The warnings of the template's own literal patterns, at their offsets in its text. */
    val warnings: List<QueryWarning> = warnings.frozen()

    /** Every name, once, in the order of first appearance. */
    val names: Set<String> = list.mapTo(LinkedHashSet()) { it.name }

    /**
     * Binds [values], one for each of [names] and no others, which is an [IllegalArgumentException] otherwise, as a
     * [JsonQueryValue.Json] is: JSON text has no literal form. A value that makes the bound text an invalid query (a
     * string with an unpaired surrogate, a number whose exponent is out of range, or text beyond the length limit)
     * throws [InvalidJsonPathException] on the bound text, as parsing that text would.
     */
    fun bind(values: Map<String, JsonQueryValue>): Binding {
        val missing = names.filter { it !in values }
        require(missing.isEmpty()) { "no value for ${missing.joinToString { "\${$it}" }} in the $what '$text'" }
        val unknown = values.keys.filter { it !in names }
        require(unknown.isEmpty()) { "the $what '$text' has no placeholder ${unknown.joinToString { "\${$it}" }}" }
        val literals = HashMap<String, Bound>(names.size * 2)
        for (name in names) literals[name] = literal(name, values.getValue(name))
        val sb = StringBuilder(text.length + list.size * 8)
        val boundStarts = IntArray(list.size)
        var from = 0
        var problem: Pair<Int, String>? = null
        for ((k, p) in list.withIndex()) {
            sb.append(text, from, p.start)
            val bound = literals.getValue(p.name)
            if (bound.problem != null && problem == null) problem = sb.length to bound.problem
            boundStarts[k] = sb.length
            sb.append(bound.token)
            from = p.end
        }
        sb.append(text, from, text.length)
        val boundText = sb.toString()
        if (boundText.length > JsonPathParser.MAX_LENGTH) {
            throw InvalidJsonPathException(boundText, 0, "a $what has at most ${JsonPathParser.MAX_LENGTH} characters")
        }
        problem?.let { (offset, reason) -> throw InvalidJsonPathException(boundText, offset, reason) }
        val standIns = IdentityHashMap<LiteralValue, LiteralValue>(list.size)
        for (p in list) standIns[p.standIn] = literals.getValue(p.name).value
        // A warning of the template's own lies outside every placeholder; it moves by what the placeholders before it grew.
        val moved = ArrayList<QueryWarning>(warnings.size)
        var k = 0
        var growth = 0
        for (w in warnings) {
            while (k < list.size && list[k].end <= w.offset) {
                growth = boundStarts[k] + literals.getValue(list[k].name).token.length - list[k].end
                k++
            }
            moved += w.shifted(growth)
        }
        val boundPatterns = patterns.map { BoundPattern(it.call, boundStarts[it.index], literals.getValue(list[it.index].name).token) }
        return Binding(boundText, standIns, moved, boundPatterns)
    }

    /** A value's literal: its RFC 9535 token, its value in the tree, or why it can't be one. */
    private class Bound(val token: String, val value: LiteralValue, val problem: String? = null)

    private fun literal(name: String, value: JsonQueryValue): Bound = when (value) {
        JsonQueryValue.Null -> Bound("null", LiteralValue.Null)
        is JsonQueryValue.Bool -> Bound(value.value.toString(), LiteralValue.Bool(value.value))
        is JsonQueryValue.Str -> {
            val token = StringBuilder(value.value.length + 2).also { NormalizedPath.appendString(it, value.value) }.toString()
            Bound(token, LiteralValue.Str(value.value), if (hasUnpairedSurrogate(value.value)) "not valid Unicode (an unpaired surrogate)" else null)
        }
        is JsonQueryValue.Num -> {
            // BigDecimal's text is an RFC 9535 number: no leading zeros, and an exponent spelled `E+n` or `E-n`.
            val token = value.value.toString()
            val bytes = token.toByteArray(Charsets.US_ASCII)
            val key = GrowableBytes(32)
            if (CanonicalNumber.writeKey(bytes, 0, bytes.size, key) < 0) {
                Bound(token, LiteralValue.Null, "the number's exponent is out of range")
            } else {
                Bound(token, LiteralValue.Num(token, key.toByteArray()))
            }
        }
        is JsonQueryValue.Json -> throw IllegalArgumentException(
            "\${$name} is bound to JSON text, which has no literal form in a $what; bind a Str, Num, Bool or Null",
        )
    }

    private fun hasUnpairedSurrogate(s: String): Boolean {
        var k = 0
        while (k < s.length) {
            val c = s[k]
            if (Character.isHighSurrogate(c) && k + 1 < s.length && Character.isLowSurrogate(s[k + 1])) {
                k += 2
            } else if (Character.isSurrogate(c)) {
                return true
            } else {
                k++
            }
        }
        return false
    }

    override fun toString(): String = text

    companion object {
        /** A placeholder's name: an ASCII letter or `_`, then ASCII letters, digits and `_`. */
        fun isNameFirst(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c == '_'

        fun isNameChar(c: Char): Boolean = isNameFirst(c) || c in '0'..'9'
    }
}

/** A template's `match` or `search` [call] whose pattern placeholder is bound to [token], at [offset] of the bound text. */
internal class BoundPattern(val call: FunctionCall, val offset: Int, val token: String)

/**
 * Bound values, ready to replace the stand-ins of a template's tree: [query] and [filter] copy only the nodes on the way
 * to a stand-in and share the rest, so binding costs the placeholders and their ancestors, not the query.
 */
internal class Binding(
    val text: String,
    private val standIns: IdentityHashMap<LiteralValue, LiteralValue>,
    private val templateWarnings: List<QueryWarning>,
    private val patterns: List<BoundPattern>,
) {
    /** The calls [call] made of a template's calls with a bound pattern, whose translated patterns [warnings] reads. */
    private val boundCalls = IdentityHashMap<FunctionCall, FunctionCall>(patterns.size)

    /**
     * The bound query's warnings, in the order of the bound text; called after [query] or [filter], which translate the
     * bound patterns.
     */
    fun warnings(): List<QueryWarning> {
        if (patterns.isEmpty()) return templateWarnings
        val bound = patterns.mapNotNull { p ->
            val call = checkNotNull(boundCalls[p.call]) { "the tree isn't bound yet" }
            PatternWarnings.of(call, p.offset, p.token)
        }
        return if (bound.isEmpty()) templateWarnings else (templateWarnings + bound).sortedBy { it.offset }
    }

    fun query(q: PathQuery): PathQuery {
        val segments = mapShared(q.segments, ::segment)
        return if (segments === q.segments) q else PathQuery(q.absolute, segments)
    }

    fun filter(e: LogicalExpr): LogicalExpr = logical(e)

    private fun segment(s: PathSegment): PathSegment {
        val selectors = mapShared(s.selectors, ::selector)
        return if (selectors === s.selectors) s else PathSegment(s.descendant, selectors, s.singularForm)
    }

    private fun selector(s: Selector): Selector {
        if (s !is Selector.Filter) return s
        val expr = logical(s.expr)
        return if (expr === s.expr) s else Selector.Filter(expr)
    }

    private fun logical(e: LogicalExpr): LogicalExpr = when (e) {
        is LogicalExpr.Or -> mapShared(e.items, ::logical).let { if (it === e.items) e else LogicalExpr.Or(it) }
        is LogicalExpr.And -> mapShared(e.items, ::logical).let { if (it === e.items) e else LogicalExpr.And(it) }
        is LogicalExpr.Not -> logical(e.expr).let { if (it === e.expr) e else LogicalExpr.Not(it) }
        is LogicalExpr.Exists -> query(e.query).let { if (it === e.query) e else LogicalExpr.Exists(it) }
        is LogicalExpr.Test -> call(e.call).let { if (it === e.call) e else LogicalExpr.Test(it) }
        is LogicalExpr.Comparison -> {
            val left = value(e.left)
            val right = value(e.right)
            if (left === e.left && right === e.right) e else LogicalExpr.Comparison(left, e.op, right)
        }
    }

    private fun value(v: ValueExpr): ValueExpr = when (v) {
        is ValueExpr.Literal -> standIns[v.value]?.let { ValueExpr.Literal(it) } ?: v
        is ValueExpr.Query -> query(v.query).let { if (it === v.query) v else ValueExpr.Query(it) }
        is ValueExpr.Call -> call(v.call).let { if (it === v.call) v else ValueExpr.Call(it) }
    }

    // A new call translates a bound `match` / `search` pattern (FunctionCall.literalPattern) once, here.
    private fun call(c: FunctionCall): FunctionCall {
        val args = mapShared(c.args, ::argument)
        if (args === c.args) return c
        return FunctionCall(c.function, args).also { if (c.patternLiteral != null) boundCalls[c] = it }
    }

    private fun argument(a: FunctionArg): FunctionArg = when (a) {
        is FunctionArg.Value -> value(a.expr).let { if (it === a.expr) a else FunctionArg.Value(it) }
        is FunctionArg.Nodes -> query(a.query).let { if (it === a.query) a else FunctionArg.Nodes(it) }
        is FunctionArg.Logical -> logical(a.expr).let { if (it === a.expr) a else FunctionArg.Logical(it) }
    }

    /** [items] mapped by [f], or [items] itself when [f] returned every item unchanged. */
    private inline fun <T : Any> mapShared(items: List<T>, f: (T) -> T): List<T> {
        var out: ArrayList<T>? = null
        for ((k, item) in items.withIndex()) {
            val mapped = f(item)
            if (out == null && mapped !== item) out = ArrayList<T>(items.size).apply { addAll(items.subList(0, k)) }
            out?.add(mapped)
        }
        return out ?: items
    }
}
