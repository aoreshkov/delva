package app.oreshkov.delva.testkit.oracle

import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONull
import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.json.OracleNumbers
import app.oreshkov.delva.testkit.json.canonicalEquals
import app.oreshkov.delva.testkit.json.compareCodePoints

/**
 * RFC 9535 JSONPath queries, the whole grammar, as the oracle evaluates them: over an [OValue] tree, materializing
 * every nodelist. Written independently of the engine: the text is first split into tokens (each knowing whether blank
 * space preceded it), filters are parsed by precedence climbing into one expression tree and type-checked afterwards,
 * and I-Regexp patterns are checked and translated by a scanner of their own.
 *
 * [select] gives the RFC nodelist (order and duplicates included) with Normalized Paths and pre-order numbers;
 * [selectDistinct] gives what a store search returns: each selected node once, in document order.
 */
class JsonPathOracle private constructor(val text: String, private val root: Query) {
    /** One node of a nodelist: its value, its Normalized Path, its pre-order number and its number of descendants. */
    data class Selected(val value: OValue, val path: String, val pre: Long, val size: Long)

    fun select(document: OValue): List<Selected> {
        val tree = Tree.of(document)
        return Evaluator(tree).run(root, tree).map { Selected(it.tree.value, JsonPath.normalized(it.path), it.tree.pre, it.tree.size) }
    }

    fun selectDistinct(document: OValue): List<Selected> = select(document).distinctBy { it.pre }.sortedBy { it.pre }

    override fun toString(): String = text

    companion object {
        /** Parses [text]; throws [IllegalArgumentException] for any syntax or type error. */
        fun parse(text: String): JsonPathOracle = JsonPathOracle(text, QueryParser(text).parse())
    }
}

/**
 * A document filter as the oracle evaluates it: an RFC 9535 logical expression tested once on a document, with both
 * `@` and `$` standing for its root.
 */
class JsonPathFilterOracle private constructor(val text: String, private val expr: Expr) {
    fun test(document: OValue): Boolean {
        val tree = Tree.of(document)
        return Evaluator(tree).test(expr, tree)
    }

    override fun toString(): String = text

    companion object {
        /** Parses [text]; throws [IllegalArgumentException] for any syntax or type error. */
        fun parse(text: String): JsonPathFilterOracle = JsonPathFilterOracle(text, QueryParser(text).parseFilter())
    }
}

// ---- tokens -------------------------------------------------------------------------------------------------------

private enum class T { ROOT, CURRENT, DOT, DOTDOT, LBRACKET, RBRACKET, COMMA, STAR, COLON, QUESTION, LPAREN, RPAREN, NOT, AND, OR, OP, NAME, STRING, NUMBER, EOF }

private class Token(val type: T, val text: String, val spaceBefore: Boolean, val value: String = text)

private object Lexer {
    private val number = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")
    private val name = Regex("""[A-Za-z_\x{80}-\x{10FFFF}][A-Za-z0-9_\x{80}-\x{10FFFF}]*""")
    private val fixed = listOf("..", "==", "!=", "<=", ">=", "&&", "||", "$", "@", ".", "[", "]", ",", "*", ":", "?", "(", ")", "!", "<", ">")

    fun tokens(text: String): List<Token> {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            require(cp !in 0xD800..0xDFFF) { "unpaired surrogate in $text" }
            i += Character.charCount(cp)
        }
        val out = ArrayList<Token>()
        i = 0
        while (true) {
            val start = i
            while (i < text.length && text[i] in " \t\n\r") i++
            val space = i > start
            if (i == text.length) {
                out += Token(T.EOF, "", space)
                return out
            }
            val c = text[i]
            if (c == '\'' || c == '"') {
                val (value, end) = string(text, i)
                out += Token(T.STRING, text.substring(i, end), space, value)
                i = end
                continue
            }
            number.matchAt(text, i)?.let {
                out += Token(T.NUMBER, it.value, space)
                i = it.range.last + 1
                return@let
            }?.let { continue }
            name.matchAt(text, i)?.let {
                out += Token(T.NAME, it.value, space)
                i = it.range.last + 1
            }?.let { continue }
            val f = fixed.firstOrNull { text.startsWith(it, i) } ?: throw IllegalArgumentException("unexpected '${text[i]}' in $text")
            val type = when (f) {
                ".." -> T.DOTDOT
                "$" -> T.ROOT
                "@" -> T.CURRENT
                "." -> T.DOT
                "[" -> T.LBRACKET
                "]" -> T.RBRACKET
                "," -> T.COMMA
                "*" -> T.STAR
                ":" -> T.COLON
                "?" -> T.QUESTION
                "(" -> T.LPAREN
                ")" -> T.RPAREN
                "!" -> T.NOT
                "&&" -> T.AND
                "||" -> T.OR
                else -> T.OP
            }
            out += Token(type, f, space)
            i += f.length
        }
    }

    /** A quoted string at [start]: its value and the index after its closing quote. */
    private fun string(text: String, start: Int): Pair<String, Int> {
        val quote = text[start]
        val sb = StringBuilder()
        var i = start + 1
        while (true) {
            require(i < text.length) { "unterminated string in $text" }
            val c = text[i]
            if (c == quote) return sb.toString() to i + 1
            require(c >= ' ') { "control character in a string of $text" }
            if (c != '\\') {
                sb.append(c)
                i++
                continue
            }
            require(i + 1 < text.length) { "unterminated escape in $text" }
            val e = text[i + 1]
            i += 2
            when (e) {
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                '/', '\\' -> sb.append(e)
                'u' -> {
                    val unit = hex(text, i)
                    i += 4
                    if (unit in 0xD800..0xDBFF) {
                        require(text.startsWith("\\u", i)) { "lone high surrogate in $text" }
                        val low = hex(text, i + 2)
                        require(low in 0xDC00..0xDFFF) { "lone high surrogate in $text" }
                        sb.append(unit.toChar()).append(low.toChar())
                        i += 6
                    } else {
                        require(unit !in 0xDC00..0xDFFF) { "lone low surrogate in $text" }
                        sb.append(unit.toChar())
                    }
                }
                else -> {
                    require(e == quote) { "invalid escape \\$e in $text" }
                    sb.append(e)
                }
            }
        }
    }

    private fun hex(text: String, at: Int): Int {
        require(at + 4 <= text.length) { "short \\u escape in $text" }
        val digits = text.substring(at, at + 4)
        require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "invalid \\u escape in $text" }
        return digits.toInt(16)
    }
}

// ---- the parsed query ---------------------------------------------------------------------------------------------

private class Query(val absolute: Boolean, val segments: List<Seg>) {
    val singular: Boolean get() = segments.all { it.singular }
}

private class Seg(val descendant: Boolean, val selectors: List<Sel>, val singular: Boolean)

private sealed interface Sel {
    class Name(val name: String) : Sel
    data object All : Sel
    class Idx(val i: Long) : Sel
    class Slice(val start: Long?, val end: Long?, val step: Long?) : Sel
    class Filter(val e: Expr) : Sel
}

/** One tree for every filter expression; which kind may stand where is checked after parsing. */
private sealed interface Expr {
    class Lit(val v: OValue) : Expr
    class Q(val q: Query) : Expr
    class Call(val name: String, val args: List<Expr>) : Expr
    class Cmp(val l: Expr, val op: String, val r: Expr) : Expr
    class Not(val e: Expr) : Expr
    class And(val l: Expr, val r: Expr) : Expr
    class Or(val l: Expr, val r: Expr) : Expr
    class Paren(val e: Expr) : Expr
}

private class QueryParser(private val text: String) {
    private val tokens = Lexer.tokens(text)
    private var k = 0

    private fun peek() = tokens[k]
    private fun take() = tokens[k++]
    private fun expect(type: T): Token = take().also { require(it.type == type) { "expected $type, found '${it.text}' in $text" } }
    private fun bad(): Nothing = throw IllegalArgumentException("invalid query at token '${peek().text}': $text")

    /** A logical expression on its own, blank space allowed around it. */
    fun parseFilter(): Expr {
        val e = logical(or())
        require(peek().type == T.EOF) { "unexpected '${peek().text}' in $text" }
        return e
    }

    fun parse(): Query {
        val first = expect(T.ROOT)
        require(!first.spaceBefore) { "leading blank space in $text" }
        val q = Query(true, segments())
        require(peek().type == T.EOF && !peek().spaceBefore) { "unexpected '${peek().text}' or trailing space in $text" }
        return q
    }

    private fun segments(): List<Seg> {
        val out = ArrayList<Seg>()
        while (true) {
            when (peek().type) {
                T.DOT -> {
                    take()
                    val t = take()
                    require(!t.spaceBefore) { "space after '.' in $text" }
                    out += when (t.type) {
                        T.STAR -> Seg(false, listOf(Sel.All), false)
                        T.NAME -> Seg(false, listOf(Sel.Name(t.text)), true)
                        else -> bad()
                    }
                }
                T.DOTDOT -> {
                    take()
                    require(!peek().spaceBefore) { "space after '..' in $text" }
                    out += when (peek().type) {
                        T.STAR -> Seg(true, listOf(Sel.All).also { take() }, false)
                        T.NAME -> Seg(true, listOf(Sel.Name(take().text)), false)
                        T.LBRACKET -> Seg(true, bracket().first, false)
                        else -> bad()
                    }
                }
                T.LBRACKET -> {
                    val (selectors, compact) = bracket()
                    out += Seg(false, selectors, compact && selectors.size == 1 && (selectors[0] is Sel.Name || selectors[0] is Sel.Idx))
                }
                else -> return out
            }
        }
    }

    /** The selectors of `[…]`, and whether it's written without blank space inside. */
    private fun bracket(): Pair<List<Sel>, Boolean> {
        expect(T.LBRACKET)
        var compact = !peek().spaceBefore
        val out = ArrayList<Sel>()
        while (true) {
            out += selector()
            val t = take()
            if (t.spaceBefore) compact = false
            when (t.type) {
                T.RBRACKET -> return out to compact
                T.COMMA -> continue
                else -> throw IllegalArgumentException("expected ',' or ']' in $text")
            }
        }
    }

    private fun int(t: Token): Long {
        require(t.type == T.NUMBER && Regex("0|-?[1-9][0-9]*").matches(t.text)) { "not an integer: '${t.text}' in $text" }
        val v = t.text.toBigInteger()
        require(v.abs() <= 9007199254740991L.toBigInteger()) { "integer out of range in $text" }
        return v.toLong()
    }

    private fun selector(): Sel = when (peek().type) {
        T.STRING -> Sel.Name(take().value)
        T.STAR -> Sel.All.also { take() }
        T.QUESTION -> {
            take()
            Sel.Filter(logical(or()))
        }
        T.NUMBER, T.COLON -> {
            val start = if (peek().type == T.NUMBER) int(take()) else null
            if (peek().type != T.COLON) {
                Sel.Idx(start ?: bad())
            } else {
                take()
                val end = if (peek().type == T.NUMBER) int(take()) else null
                var step: Long? = null
                if (peek().type == T.COLON) {
                    take()
                    if (peek().type == T.NUMBER) step = int(take())
                }
                Sel.Slice(start, end, step)
            }
        }
        else -> bad()
    }

    // Precedence climbing: || below && below ! and comparisons.
    private fun or(): Expr {
        var e = and()
        while (peek().type == T.OR) {
            take()
            e = Expr.Or(e, and())
        }
        return e
    }

    private fun and(): Expr {
        var e = unary()
        while (peek().type == T.AND) {
            take()
            e = Expr.And(e, unary())
        }
        return e
    }

    private fun unary(): Expr {
        if (peek().type == T.NOT) {
            take()
            val operand = primary()
            require(operand is Expr.Paren || operand is Expr.Q || operand is Expr.Call) { "'!' before something that isn't a test in $text" }
            return Expr.Not(operand)
        }
        val left = primary()
        if (peek().type != T.OP) return left
        val op = take().text
        return Expr.Cmp(left, op, primary())
    }

    private fun primary(): Expr {
        val t = peek()
        return when (t.type) {
            T.LPAREN -> {
                take()
                val e = or()
                expect(T.RPAREN)
                Expr.Paren(e)
            }
            T.ROOT, T.CURRENT -> {
                take()
                Expr.Q(Query(t.type == T.ROOT, segments()))
            }
            T.STRING -> Expr.Lit(OStr(take().value))
            T.NUMBER -> {
                take()
                require(!Regex("-?0[0-9].*").matches(t.text)) { "leading zero in $text" }
                Expr.Lit(ONum(t.text))
            }
            T.NAME -> {
                take()
                if (peek().type == T.LPAREN && !peek().spaceBefore) {
                    take()
                    val args = ArrayList<Expr>()
                    if (peek().type != T.RPAREN) {
                        while (true) {
                            args += or()
                            if (peek().type == T.COMMA) take() else break
                        }
                    }
                    expect(T.RPAREN)
                    checkCall(t.text, args)
                    Expr.Call(t.text, args)
                } else {
                    when (t.text) {
                        "true" -> Expr.Lit(OBool(true))
                        "false" -> Expr.Lit(OBool(false))
                        "null" -> Expr.Lit(ONull)
                        else -> throw IllegalArgumentException("unknown word '${t.text}' in $text")
                    }
                }
            }
            else -> bad()
        }
    }

    // ---- types (RFC 9535 section 2.4.3) ------------------------------------------------------------------------------

    private val results = mapOf("length" to "V", "count" to "V", "value" to "V", "match" to "L", "search" to "L")
    private val params = mapOf("length" to "V", "count" to "N", "value" to "N", "match" to "VV", "search" to "VV")

    private fun checkCall(name: String, args: List<Expr>) {
        val p = params[name] ?: throw IllegalArgumentException("unknown function $name in $text")
        require(args.size == p.length) { "$name takes ${p.length} arguments in $text" }
        for ((i, a) in args.withIndex()) {
            val ok = when (p[i]) {
                'V' -> isValue(a)
                else -> a is Expr.Q
            }
            require(ok) { "argument ${i + 1} of $name has the wrong type in $text" }
        }
    }

    private fun isValue(e: Expr) = e is Expr.Lit || (e is Expr.Q && e.q.singular) || (e is Expr.Call && results[e.name] == "V")

    /** Checks [e] where a logical expression is required and returns it. */
    private fun logical(e: Expr): Expr {
        when (e) {
            is Expr.Or -> { logical(e.l); logical(e.r) }
            is Expr.And -> { logical(e.l); logical(e.r) }
            is Expr.Not -> logical(e.e)
            is Expr.Paren -> logical(e.e)
            is Expr.Q -> {}
            is Expr.Call -> require(results[e.name] == "L") { "${e.name}() isn't a test in $text" }
            is Expr.Cmp -> require(isValue(e.l) && isValue(e.r)) { "a comparison of something that isn't a value in $text" }
            is Expr.Lit -> throw IllegalArgumentException("a literal isn't a test in $text")
        }
        return e
    }
}

// ---- evaluation ---------------------------------------------------------------------------------------------------

/** The document with pre-order numbers; children keyed by member key (String) or index (Long). */
private class Tree(val value: OValue, val pre: Long, val kids: List<Pair<Any, Tree>>) {
    val size: Long = kids.sumOf { 1 + it.second.size }

    companion object {
        fun of(root: OValue): Tree {
            var next = 0L
            fun build(v: OValue): Tree {
                val pre = next++
                val kids = when (v) {
                    is OObj -> v.members.map { (k, m) -> k to build(m) }
                    is OArr -> v.items.mapIndexed { i, m -> i.toLong() to build(m) }
                    else -> emptyList()
                }
                return Tree(v, pre, kids)
            }
            return build(root)
        }
    }
}

private class Node(val tree: Tree, val path: List<Any>)

private class Evaluator(private val doc: Tree) {
    fun test(e: Expr, current: Tree): Boolean = truth(e, current)

    fun run(q: Query, current: Tree): List<Node> {
        var list = listOf(if (q.absolute) Node(doc, emptyList()) else Node(current, emptyList()))
        for (seg in q.segments) {
            list = list.flatMap { n ->
                val visited = if (seg.descendant) descendantsOrSelf(n) else listOf(n)
                visited.flatMap { v -> seg.selectors.flatMap { s -> apply(s, v) } }
            }
        }
        return list
    }

    private fun descendantsOrSelf(n: Node): List<Node> = listOf(n) + n.tree.kids.flatMap { (k, t) -> descendantsOrSelf(Node(t, n.path + k)) }

    private fun apply(s: Sel, n: Node): List<Node> {
        val v = n.tree.value
        fun child(k: Any, t: Tree) = Node(t, n.path + k)
        return when (s) {
            is Sel.Name -> n.tree.kids.filter { v is OObj && it.first == s.name }.map { child(it.first, it.second) }
            Sel.All -> n.tree.kids.map { child(it.first, it.second) }
            is Sel.Idx -> {
                if (v !is OArr) return emptyList()
                val i = if (s.i < 0) v.items.size + s.i else s.i
                if (i < 0 || i >= v.items.size) emptyList() else listOf(child(i, n.tree.kids[i.toInt()].second))
            }
            is Sel.Slice -> {
                if (v !is OArr) return emptyList()
                sliceIndexes(s, v.items.size.toLong()).map { child(it, n.tree.kids[it.toInt()].second) }
            }
            is Sel.Filter -> if (v is OObj || v is OArr) n.tree.kids.filter { truth(s.e, it.second) }.map { child(it.first, it.second) } else emptyList()
        }
    }

    private fun sliceIndexes(s: Sel.Slice, len: Long): List<Long> {
        val step = s.step ?: 1L
        if (step == 0L) return emptyList()
        fun norm(i: Long) = if (i >= 0) i else len + i
        val out = ArrayList<Long>()
        if (step > 0) {
            val lo = norm(s.start ?: 0).coerceIn(0, len)
            val hi = norm(s.end ?: len).coerceIn(0, len)
            var i = lo
            while (i < hi) { out += i; i += step }
        } else {
            val hi = norm(s.start ?: (len - 1)).coerceIn(-1, len - 1)
            val lo = norm(s.end ?: (-len - 1)).coerceIn(-1, len - 1)
            var i = hi
            while (lo < i) { out += i; i += step }
        }
        return out
    }

    private fun truth(e: Expr, current: Tree): Boolean = when (e) {
        is Expr.Or -> truth(e.l, current) || truth(e.r, current)
        is Expr.And -> truth(e.l, current) && truth(e.r, current)
        is Expr.Not -> !truth(e.e, current)
        is Expr.Paren -> truth(e.e, current)
        is Expr.Q -> run(e.q, current).isNotEmpty()
        is Expr.Call -> {
            val subject = (value(e.args[0], current) as? OStr)?.value
            val pattern = (value(e.args[1], current) as? OStr)?.value
            if (subject == null || pattern == null) false else OracleRegex.test(pattern, subject, whole = e.name == "match")
        }
        is Expr.Cmp -> compare(value(e.l, current), e.op, value(e.r, current))
        is Expr.Lit -> error("literal as a test")
    }

    /** A value, or null for Nothing. */
    private fun value(e: Expr, current: Tree): OValue? = when (e) {
        is Expr.Lit -> e.v
        is Expr.Q -> run(e.q, current).singleOrNull()?.tree?.value
        is Expr.Call -> when (e.name) {
            "length" -> when (val v = value(e.args[0], current)) {
                is OStr -> ONum(v.value.codePointCount(0, v.value.length).toString())
                is OArr -> ONum(v.items.size.toString())
                is OObj -> ONum(v.members.size.toString())
                else -> null
            }
            "count" -> ONum(run((e.args[0] as Expr.Q).q, current).size.toString())
            "value" -> run((e.args[0] as Expr.Q).q, current).singleOrNull()?.tree?.value
            else -> error("${e.name} isn't a value")
        }
        else -> error("not a value: $e")
    }

    private fun compare(a: OValue?, op: String, b: OValue?): Boolean = when (op) {
        "==" -> eq(a, b)
        "!=" -> !eq(a, b)
        "<" -> lt(a, b)
        "<=" -> lt(a, b) || eq(a, b)
        ">" -> lt(b, a)
        ">=" -> lt(b, a) || eq(a, b)
        else -> error(op)
    }

    private fun eq(a: OValue?, b: OValue?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return canonicalEquals(a, b)
    }

    private fun lt(a: OValue?, b: OValue?): Boolean = when {
        a is ONum && b is ONum -> OracleNumbers.compare(a.text, b.text) < 0
        a is OStr && b is OStr -> compareCodePoints(a.value, b.value) < 0
        else -> false
    }
}

/**
 * I-Regexp (RFC 9485) for the oracle: a pattern is scanned once, rejecting what the RFC's grammar doesn't allow, and
 * rewritten for `java.util.regex` (`.` as `[^\n\r]`, `^` and `$` as anchors of the whole string, as the compliance
 * suite reads them).
 */
private object OracleRegex {
    private val categories = Regex("[LMNPZSC]|L[lmotu]|M[cen]|N[dlo]|P[cdefios]|Z[lps]|S[ckmo]|C[cfno]")
    private const val SINGLE_ESCAPES = "()*+-.?[\\]^nrt{|}"

    fun test(pattern: String, subject: String, whole: Boolean): Boolean {
        val translated = translate(pattern) ?: return false
        val regex = runCatching { java.util.regex.Pattern.compile(translated) }.getOrNull() ?: return false
        val m = regex.matcher(subject)
        return if (whole) m.matches() else m.find()
    }

    fun translate(p: String): String? {
        val out = StringBuilder()
        val cps = p.codePoints().toArray()
        var i = 0
        var depth = 0
        var atom = false
        fun esc(cp: Int) = if (cp < 128 && !Character.isLetterOrDigit(cp)) "\\" + String(Character.toChars(cp)) else String(Character.toChars(cp))
        fun escape(): String? {
            if (i + 1 >= cps.size) return null
            val e = cps[i + 1]
            if (e == 'p'.code || e == 'P'.code) {
                val close = (i + 2 until cps.size).firstOrNull { cps[it] == '}'.code } ?: return null
                if (cps[i + 2] != '{'.code) return null
                val name = String(cps, i + 3, close - i - 3)
                if (!categories.matches(name)) return null
                i = close + 1
                return "\\" + e.toChar() + "{" + name + "}"
            }
            if (e > 127 || SINGLE_ESCAPES.indexOf(e.toChar()) < 0) return null
            i += 2
            return when (e.toChar()) {
                'n' -> "\\n"
                'r' -> "\\r"
                't' -> "\\t"
                else -> "\\" + e.toChar()
            }
        }
        while (i < cps.size) {
            val c = cps[i]
            when (c) {
                '('.code -> { depth++; out.append("(?:"); i++; atom = false; continue }
                ')'.code -> { if (depth == 0) return null; depth--; out.append(')'); i++; atom = true; continue }
                '|'.code -> { out.append('|'); i++; atom = false; continue }
                '*'.code, '+'.code, '?'.code -> { if (!atom) return null; out.appendCodePoint(c); i++; atom = false; continue }
                '{'.code -> {
                    if (!atom) return null
                    val close = (i + 1 until cps.size).firstOrNull { cps[it] == '}'.code } ?: return null
                    val body = String(cps, i + 1, close - i - 1)
                    if (!Regex("[0-9]+(,[0-9]*)?").matches(body)) return null
                    out.append('{').append(body).append('}')
                    i = close + 1
                    atom = false
                    continue
                }
                '.'.code -> { out.append("[^\\n\\r]"); i++ }
                '^'.code -> { out.append('^'); i++ }
                '$'.code -> { out.append("\\z"); i++ }
                '\\'.code -> out.append(escape() ?: return null)
                '['.code -> {
                    i++
                    out.append('[')
                    if (i < cps.size && cps[i] == '^'.code) { out.append('^'); i++ }
                    var n = 0
                    while (true) {
                        if (i >= cps.size) return null
                        val d = cps[i]
                        if (d == ']'.code) { if (n == 0) return null; out.append(']'); i++; break }
                        if (d == '['.code) return null
                        if (d == '-'.code) {
                            // Literal only first or last; ranges are handled after their first character.
                            if (n != 0 && (i + 1 >= cps.size || cps[i + 1] != ']'.code)) return null
                            out.append("\\-"); i++; n++; continue
                        }
                        val isEscape = d == '\\'.code
                        val isCategory = isEscape && i + 1 < cps.size && (cps[i + 1] == 'p'.code || cps[i + 1] == 'P'.code)
                        out.append(if (isEscape) escape() ?: return null else esc(d).also { i++ })
                        n++
                        if (!isCategory && i + 1 < cps.size && cps[i] == '-'.code && cps[i + 1] != ']'.code) {
                            i++
                            out.append('-')
                            val e = cps[i]
                            if (e == '['.code || e == ']'.code) return null
                            if (e == '\\'.code) {
                                if (i + 1 < cps.size && (cps[i + 1] == 'p'.code || cps[i + 1] == 'P'.code)) return null
                                out.append(escape() ?: return null)
                            } else {
                                out.append(esc(e)); i++
                            }
                        }
                    }
                }
                ']'.code, '}'.code -> return null
                else -> { out.append(esc(c)); i++ }
            }
            atom = true
        }
        return if (depth == 0) out.toString() else null
    }
}
