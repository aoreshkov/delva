package app.oreshkov.delva.internal.path

import app.oreshkov.delva.InvalidJsonPathException
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.CanonicalNumber

/**
 * The whole RFC 9535 grammar (section 2 and its appendix A, with the errata that order the alternatives of
 * `function-argument` and `comparable`), by recursive descent, with the type checks of section 2.4.3. Every syntax
 * and type error is found here, reported as [app.oreshkov.delva.InvalidJsonPathException] naming the character offset; evaluating a
 * parsed query never fails on the query.
 *
 * Also enforced here, as section 4 of the RFC suggests: at most [MAX_LENGTH] characters, [MAX_NESTING] levels of
 * nested filters, parentheses and function calls, and [MAX_SELECTORS] selectors. Numbers follow SPEC.md §2.2, so
 * a literal whose exponent doesn't fit an `Int` is rejected.
 *
 * A template ([parseTemplate], [parseFilterTemplate]) may have `${name}` wherever the grammar allows a `literal`; the
 * tree then holds a stand-in literal there, which [Placeholders.bind] replaces.
 */
internal object JsonPathParser {
    const val MAX_LENGTH: Int = 65_536
    const val MAX_NESTING: Int = 64
    const val MAX_SELECTORS: Int = 4_096

    fun parse(text: String): PathQuery = Parser(text).query()

    /**
     * A `logical-expr` on its own, as a filter selector holds one after its `?`, with blank space allowed around it.
     * It counts as one level of nesting, as the filter selector would, and the limits are those of a query.
     */
    fun parseFilter(text: String): LogicalExpr = Parser(text).filter()

    /** [parse] with `${name}` placeholders where a literal can stand ([Placeholders]). */
    fun parseTemplate(text: String): Pair<PathQuery, Placeholders> {
        val parser = Parser(text, templates = true)
        return parser.query() to Placeholders(text, parser.placeholders, "query")
    }

    /** [parseFilter] with `${name}` placeholders where a literal can stand ([Placeholders]). */
    fun parseFilterTemplate(text: String): Pair<LogicalExpr, Placeholders> {
        val parser = Parser(text, templates = true)
        return parser.filter() to Placeholders(text, parser.placeholders, "filter")
    }

    private class Parser(text: String, private val templates: Boolean = false) : PathScanner(text) {
        private var nesting = 0
        private var selectors = 0
        val placeholders = ArrayList<Placeholder>()

        fun filter(): LogicalExpr {
            if (text.length > MAX_LENGTH) fail("a filter has at most $MAX_LENGTH characters")
            checkUnicode()
            skipBlank()
            enter()
            val expr = logicalOr()
            nesting--
            skipBlank()
            if (i < text.length) fail("unexpected '${text[i]}'")
            return expr
        }

        fun query(): PathQuery {
            if (text.length > MAX_LENGTH) fail("a query has at most $MAX_LENGTH characters")
            checkUnicode()
            if (peek() != '$') fail("a query starts with '$'")
            if (text.startsWith("\${")) fail("a placeholder stands for a literal in a filter, not for a query")
            i = 1
            val segments = segments()
            if (i < text.length) {
                val at = i
                skipBlank()
                if (i == text.length) {
                    i = at
                    fail("trailing whitespace")
                }
                fail("unexpected '${text[i]}'")
            }
            return PathQuery(absolute = true, segments)
        }

        /** `*(S segment)`: blank space is taken only when a segment follows it. */
        private fun segments(): List<PathSegment> {
            val segments = ArrayList<PathSegment>()
            while (true) {
                val at = i
                skipBlank()
                when (peek()) {
                    '.', '[' -> segments += segment()
                    else -> {
                        i = at
                        return segments
                    }
                }
            }
        }

        private fun segment(): PathSegment {
            if (peek() == '[') return bracketed(descendant = false)
            i++ // '.'
            if (peek() == '.') {
                i++
                return when {
                    peek() == '[' -> bracketed(descendant = true)
                    peek() == '*' -> {
                        i++
                        PathSegment(true, listOf(count(Selector.Wildcard)), singularForm = false)
                    }
                    i < text.length && isNameFirst(text[i]) -> PathSegment(true, listOf(count(Selector.Name(shorthandName()))), singularForm = false)
                    else -> fail("expected '[', '*' or a member name after '..'")
                }
            }
            return when {
                peek() == '*' -> {
                    i++
                    PathSegment(false, listOf(count(Selector.Wildcard)), singularForm = false)
                }
                i < text.length && isNameFirst(text[i]) -> PathSegment(false, listOf(count(Selector.Name(shorthandName()))), singularForm = true)
                i == text.length -> fail("expected a member name or '*' after '.'")
                else -> fail("a member name can't start with '${text[i]}'; use ['…']")
            }
        }

        /** `"[" S selector *(S "," S selector) S "]"`, the '[' at [i]. */
        private fun bracketed(descendant: Boolean): PathSegment {
            i++
            val open = i
            skipBlank()
            val list = ArrayList<Selector>()
            list += count(selector())
            while (true) {
                skipBlank()
                when (peek()) {
                    ',' -> {
                        i++
                        skipBlank()
                        list += count(selector())
                    }
                    ']' -> {
                        // A singular query's segments have no blank space inside their brackets.
                        val compact = list.size == 1 && (list[0] is Selector.Name || list[0] is Selector.Index) &&
                            !isBlank(text[open]) && !isBlank(text[i - 1])
                        i++
                        return PathSegment(descendant, list, singularForm = compact && !descendant)
                    }
                    END -> fail("expected ']'")
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun count(selector: Selector): Selector {
            if (++selectors > MAX_SELECTORS) fail("a query has at most $MAX_SELECTORS selectors")
            return selector
        }

        private fun selector(): Selector = when (val c = peek()) {
            '\'', '"' -> Selector.Name(string(c))
            '*' -> {
                i++
                Selector.Wildcard
            }
            '?' -> {
                i++
                skipBlank()
                enter()
                Selector.Filter(logicalOr()).also { nesting-- }
            }
            '$' if text.startsWith("\${", i) -> fail("a placeholder stands for a literal in a filter, not for a selector")
            else -> indexOrSlice()
        }

        /** `index-selector` or `slice-selector`: `[start S] ":" S [end S] [":" [S step]]`. */
        private fun indexOrSlice(): Selector {
            val start = if (startsInt()) int() else null
            val afterStart = i
            skipBlank()
            if (peek() != ':') {
                i = afterStart
                if (start == null) fail("expected a selector")
                return Selector.Index(start)
            }
            i++
            skipBlank()
            val end = if (startsInt()) int() else null
            val afterEnd = i
            skipBlank()
            if (peek() != ':') {
                i = afterEnd
                return Selector.Slice(start, end, null)
            }
            i++
            val afterColon = i
            skipBlank()
            val step = if (startsInt()) {
                int()
            } else {
                i = afterColon
                null
            }
            return Selector.Slice(start, end, step)
        }

        private fun startsInt(): Boolean = peek() in '0'..'9' || peek() == '-'

        // ---- filters ----------------------------------------------------------------------------------------------

        private fun enter() {
            if (++nesting > MAX_NESTING) fail("filters, parentheses and function calls nest at most $MAX_NESTING deep")
        }

        private fun logicalOr(): LogicalExpr {
            val items = arrayListOf(logicalAnd())
            while (true) {
                val at = i
                skipBlank()
                if (!text.startsWith("||", i)) {
                    i = at
                    break
                }
                i += 2
                skipBlank()
                items += logicalAnd()
            }
            return if (items.size == 1) items[0] else LogicalExpr.Or(items)
        }

        private fun logicalAnd(): LogicalExpr {
            val items = arrayListOf(basic())
            while (true) {
                val at = i
                skipBlank()
                if (!text.startsWith("&&", i)) {
                    i = at
                    break
                }
                i += 2
                skipBlank()
                items += basic()
            }
            return if (items.size == 1) items[0] else LogicalExpr.And(items)
        }

        /** `paren-expr / comparison-expr / test-expr`. */
        private fun basic(): LogicalExpr {
            if (peek() == '!') {
                i++
                skipBlank()
                if (peek() == '(') return LogicalExpr.Not(parenthesized())
                val at = i
                // A comparison can't be negated without parentheses: what follows the test is left to the caller.
                return when (val operand = operand()) {
                    is Operand.Query -> LogicalExpr.Not(LogicalExpr.Exists(operand.query))
                    is Operand.Call -> LogicalExpr.Not(test(operand.call, at))
                    is Operand.Literal -> {
                        i = at
                        fail("'!' applies to a query, a function or a parenthesized expression, not a literal")
                    }
                }
            }
            if (peek() == '(') return parenthesized()
            val at = i
            val left = operand()
            val afterLeft = i
            skipBlank()
            val op = compareOp()
            if (op == null) {
                i = afterLeft
                return when (left) {
                    is Operand.Query -> LogicalExpr.Exists(left.query)
                    is Operand.Call -> test(left.call, at)
                    is Operand.Literal -> {
                        i = at
                        fail("a literal alone isn't a test; compare it with something")
                    }
                }
            }
            val leftValue = comparable(left, at)
            skipBlank()
            val rightAt = i
            val right = comparable(operand(), rightAt)
            return LogicalExpr.Comparison(leftValue, op, right)
        }

        private fun parenthesized(): LogicalExpr {
            i++
            enter()
            skipBlank()
            val expr = logicalOr()
            skipBlank()
            if (peek() != ')') fail("expected ')'")
            i++
            nesting--
            return expr
        }

        private fun test(call: FunctionCall, at: Int): LogicalExpr {
            if (call.function.result == PathType.VALUE) {
                i = at
                fail("${call.function.functionName}() gives a value, which can't be a test; compare it with something")
            }
            return LogicalExpr.Test(call)
        }

        private fun comparable(operand: Operand, at: Int): ValueExpr = when (operand) {
            is Operand.Literal -> ValueExpr.Literal(operand.value)
            is Operand.Query -> {
                if (!operand.query.singular) {
                    i = at
                    fail("only a singular query can be compared: one name or index per segment, no '..'")
                }
                ValueExpr.Query(operand.query)
            }
            is Operand.Call -> {
                if (operand.call.function.result != PathType.VALUE) {
                    i = at
                    fail("${operand.call.function.functionName}() doesn't give a value, so it can't be compared")
                }
                ValueExpr.Call(operand.call)
            }
        }

        private fun compareOp(): CompareOp? {
            val op = when {
                text.startsWith("==", i) -> CompareOp.EQ
                text.startsWith("!=", i) -> CompareOp.NE
                text.startsWith("<=", i) -> CompareOp.LE
                text.startsWith(">=", i) -> CompareOp.GE
                peek() == '<' -> CompareOp.LT
                peek() == '>' -> CompareOp.GT
                else -> return null
            }
            i += op.text.length
            return op
        }

        private sealed interface Operand {
            class Literal(val value: LiteralValue) : Operand
            class Query(val query: PathQuery) : Operand
            class Call(val call: FunctionCall) : Operand
        }

        /** A literal, a filter query (`@…` or `$…`) or a function call, whichever starts at [i]. */
        private fun operand(): Operand {
            val c = peek()
            return when {
                c == '$' && text.startsWith("\${", i) -> Operand.Literal(placeholder())
                c == '@' || c == '$' -> Operand.Query(filterQuery())
                c == '\'' || c == '"' -> Operand.Literal(LiteralValue.Str(string(c)))
                c == '-' || c in '0'..'9' -> Operand.Literal(number())
                c in 'a'..'z' -> {
                    val start = i
                    while (i < text.length && (text[i] in 'a'..'z' || text[i] in '0'..'9' || text[i] == '_')) i++
                    val name = text.substring(start, i)
                    if (peek() == '(') return Operand.Call(functionCall(name, start))
                    when (name) {
                        "true" -> Operand.Literal(LiteralValue.Bool(true))
                        "false" -> Operand.Literal(LiteralValue.Bool(false))
                        "null" -> Operand.Literal(LiteralValue.Null)
                        else -> {
                            i = start
                            fail("expected a literal, a query or a function call")
                        }
                    }
                }
                c == END -> fail("unexpected end of the query")
                else -> fail("expected a literal, a query or a function call")
            }
        }

        /**
         * `"${" name "}"` at [i], which only a template takes: a stand-in literal of its own, which [Placeholders.bind]
         * replaces with the bound value. `${` is no RFC 9535 syntax outside a string, so a placeholder is never a query.
         */
        private fun placeholder(): LiteralValue {
            if (!templates) fail("'\${' starts a placeholder, which only a template takes (JsonPathTemplate, DocumentFilterTemplate)")
            val start = i
            i += 2
            if (i == text.length || !Placeholders.isNameFirst(text[i])) fail("a placeholder's name starts with an ASCII letter or '_'")
            while (i < text.length && Placeholders.isNameChar(text[i])) i++
            if (peek() != '}') fail("expected '}': a placeholder's name has ASCII letters, digits and '_'")
            i++
            // A fresh instance, so that binding finds this occurrence by identity.
            val standIn = LiteralValue.Str("")
            placeholders += Placeholder(text.substring(start + 2, i - 1), start, i, standIn)
            return standIn
        }

        private fun filterQuery(): PathQuery {
            val absolute = peek() == '$'
            i++
            return PathQuery(absolute, segments())
        }

        /** `number := (int / "-0") [frac] [exp]`. */
        private fun number(): LiteralValue.Num {
            val start = i
            if (peek() == '-') i++
            val digits = digits()
            if (digits.length > 1 && digits.startsWith("0")) {
                i = start
                fail("a number has no leading zeros")
            }
            if (peek() == '.') {
                i++
                digits()
            }
            if (peek() == 'e' || peek() == 'E') {
                i++
                if (peek() == '+' || peek() == '-') i++
                digits()
            }
            val numberText = text.substring(start, i)
            val bytes = numberText.toByteArray(Charsets.US_ASCII)
            val key = GrowableBytes(32)
            if (CanonicalNumber.writeKey(bytes, 0, bytes.size, key) < 0) {
                i = start
                fail("the number's exponent is out of range")
            }
            return LiteralValue.Num(numberText, key.toByteArray())
        }

        /** `function-name "(" S [function-argument *(S "," S function-argument)] S ")"`, [i] at the '('. */
        private fun functionCall(name: String, start: Int): FunctionCall {
            val function = PathFunction.named(name) ?: run {
                i = start
                fail("unknown function '$name'")
            }
            i++
            enter()
            skipBlank()
            val args = ArrayList<FunctionArg>()
            if (peek() != ')') {
                while (true) {
                    val at = i
                    if (args.size == function.parameters.size) fail("$name() takes ${function.parameters.size} argument(s)")
                    args += argument(function.parameters[args.size], name, at)
                    skipBlank()
                    if (peek() == ')') break
                    if (peek() != ',') fail("expected ',' or ')'")
                    i++
                    skipBlank()
                }
            }
            if (args.size != function.parameters.size) fail("$name() takes ${function.parameters.size} argument(s)")
            i++
            nesting--
            return FunctionCall(function, args)
        }

        /**
         * `function-argument := literal / filter-query / function-expr / logical-expr`, checked against [type]: an
         * operand followed by a comparison or `&&` / `||` is a logical expression.
         */
        private fun argument(type: PathType, name: String, at: Int): FunctionArg {
            val expr: Any = if (peek() == '(' || peek() == '!') {
                logicalOr()
            } else {
                val operand = operand()
                val afterOperand = i
                skipBlank()
                if (compareOp() != null || text.startsWith("&&", i) || text.startsWith("||", i)) {
                    i = at
                    logicalOr()
                } else {
                    i = afterOperand
                    operand
                }
            }
            fun wrong(what: String): Nothing {
                i = at
                fail("an argument of $name() must be ${typeName(type)}, not $what")
            }
            return when (type) {
                PathType.VALUE -> when (expr) {
                    is Operand.Literal -> FunctionArg.Value(ValueExpr.Literal(expr.value))
                    is Operand.Query -> if (expr.query.singular) FunctionArg.Value(ValueExpr.Query(expr.query)) else wrong("a query that isn't singular")
                    is Operand.Call -> if (expr.call.function.result == PathType.VALUE) FunctionArg.Value(ValueExpr.Call(expr.call)) else wrong("${expr.call.function.functionName}()")
                    else -> wrong("a logical expression")
                }
                // No registered function gives NodesType, so only a query fits.
                PathType.NODES -> when (expr) {
                    is Operand.Query -> FunctionArg.Nodes(expr.query)
                    is Operand.Call -> wrong("${expr.call.function.functionName}()")
                    is Operand.Literal -> wrong("a literal")
                    else -> wrong("a logical expression")
                }
                PathType.LOGICAL -> when (expr) {
                    is Operand.Query -> FunctionArg.Logical(LogicalExpr.Exists(expr.query))
                    is Operand.Call -> if (expr.call.function.result != PathType.VALUE) FunctionArg.Logical(LogicalExpr.Test(expr.call)) else wrong("${expr.call.function.functionName}()")
                    is Operand.Literal -> wrong("a literal")
                    else -> FunctionArg.Logical(expr as LogicalExpr)
                }
            }
        }

        private fun typeName(type: PathType) = when (type) {
            PathType.VALUE -> "a value (a literal, a singular query or a function giving a value)"
            PathType.NODES -> "a query"
            PathType.LOGICAL -> "a logical expression"
        }

        override fun fail(reason: String): Nothing = throw InvalidJsonPathException(text, i, reason)
    }
}
