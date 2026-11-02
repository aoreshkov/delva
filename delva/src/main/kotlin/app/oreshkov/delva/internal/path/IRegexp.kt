package app.oreshkov.delva.internal.path

import app.oreshkov.delva.QueryLimitException
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * I-Regexp (RFC 9485), the regular expressions of the JSONPath functions `match` and `search`: a pattern is checked
 * against the RFC's grammar and translated to a [java.util.regex.Pattern] that means the same, as section 5.3 of the
 * RFC describes: `.` becomes `[^\n\r]`, groups become non-capturing, and characters Java treats specially are escaped.
 * Groups nest at most [MAX_GROUP_DEPTH] deep, an implementation limit the RFC allows; a deeper pattern isn't valid.
 * A pattern that isn't valid is [Invalid], with the reason, which compiling a query turns into a warning.
 *
 * I-Regexp has no backreferences or lookaround, but a backtracking matcher can still take exponential time on a
 * pattern like `(a*)*b`. Matching therefore reads the subject through a [CharSequence] that counts every character
 * read and throws [QueryLimitException] after [MAX_STEPS]. The JDK's matcher also recurses once per repetition of a
 * group, so `(a|b)*` over a long enough subject runs out of stack: that throws [QueryLimitException] too.
 */
internal object IRegexp {
    /** Characters a match may read, counting each re-read: far above what a pattern that doesn't backtrack needs. */
    const val MAX_STEPS: Long = 16_000_000

    /** How deep groups may nest, as deep as filters in a query ([JsonPathParser.MAX_NESTING]). */
    const val MAX_GROUP_DEPTH: Int = JsonPathParser.MAX_NESTING

    /** What [compile] makes of a pattern: [Compiled], or [Invalid] with the reason. */
    sealed interface Result

    /** A pattern that isn't valid I-Regexp: [reason] says what is wrong with the construct at char [index]. */
    class Invalid(val index: Int, val reason: String) : Result {
        override fun toString(): String = "Invalid($index: $reason)"
    }

    class Compiled(private val pattern: Pattern) : Result {
        /** Whether the whole of [subject] matches (`match`). */
        fun matches(subject: String): Boolean = bounded { pattern.matcher(Budget(subject)).matches() }

        /** Whether a substring of [subject] matches (`search`). */
        fun find(subject: String): Boolean = bounded { pattern.matcher(Budget(subject)).find() }

        private inline fun bounded(match: () -> Boolean): Boolean = try {
            match()
        } catch (_: StackOverflowError) {
            // The stack is unwound by now; the JDK turns the same error during Pattern.compile into an exception too.
            throw QueryLimitException("a regular expression ran out of stack on a string of this length")
        }
    }

    /** The translated pattern, or why [regexp] isn't valid I-Regexp. */
    fun compile(regexp: String): Result {
        val java = try {
            Translator(regexp).translate()
        } catch (e: Rejected) {
            return Invalid(e.index, e.reason)
        }
        return try {
            Compiled(Pattern.compile(java))
        } catch (e: PatternSyntaxException) {
            // The translator refuses everything the JDK would refuse in a translated pattern; this is a safety net.
            Invalid(0, "the JDK doesn't accept it: ${e.description}")
        }
    }

    /**
     * Thrown inside the translator only. It carries no stack trace, since patterns read from documents are translated
     * while a query runs, and an invalid one must cost no more than a valid one.
     */
    private class Rejected(val index: Int, val reason: String) : RuntimeException(null, null, false, false)

    private class Translator(private val re: String) {
        private var i = 0
        private var depth = 0
        private val out = StringBuilder(re.length + 16)

        /** Where the character class being read opened. */
        private var classOpen = 0

        /** The Java pattern; throws [Rejected] when [re] isn't I-Regexp. */
        fun translate(): String {
            regexp()
            // regexp() stops only at the end or at a ')' that no group opened.
            if (i != re.length) invalid("')' closes no group; a literal ')' is written \\)")
            return out.toString()
        }

        private fun invalid(reason: String, at: Int = i): Nothing = throw Rejected(at, reason)

        private fun peek(): Int = if (i < re.length) re.codePointAt(i) else -1

        private fun take(): Int = re.codePointAt(i).also { i += Character.charCount(it) }

        /** `i-regexp := branch *("|" branch)`. */
        private fun regexp() {
            branch()
            while (peek() == '|'.code) {
                i++
                out.append('|')
                branch()
            }
        }

        /** `branch := *piece`, ending at `|`, `)` or the end; `piece := atom [quantifier]`. */
        private fun branch() {
            var quantified = false
            while (true) {
                val c = peek()
                if (c == -1 || c == '|'.code || c == ')'.code) return
                if (c == '*'.code || c == '+'.code || c == '?'.code || c == '{'.code) {
                    // Reached only when the piece before can't take it: there is none, or it has a quantifier already.
                    if (quantified) invalid("a quantifier can't follow another: I-Regexp has no lazy (*?) or possessive (*+) quantifiers")
                    invalid("'${c.toChar()}' has nothing before it to repeat; a literal '${c.toChar()}' is written \\${c.toChar()}")
                }
                atom()
                quantified = quantifier()
            }
        }

        /** `quantifier := "*" / "+" / "?" / "{" n ["," [m]] "}"`; whether there was one. */
        private fun quantifier(): Boolean {
            when (peek()) {
                '*'.code, '+'.code, '?'.code -> out.appendCodePoint(take())
                '{'.code -> {
                    val open = i
                    i++
                    val min = quantExact(open)
                    var max: String? = min
                    var range = false
                    if (peek() == ','.code) {
                        i++
                        range = true
                        max = if (peek() in '0'.code..'9'.code) quantExact(open) else null
                    }
                    if (peek() != '}'.code) invalid("the repetition has no '}': it is {n}, {n,} or {n,m}", open)
                    i++
                    // The JDK refuses such a range; I-Regexp's grammar doesn't exclude it, and nothing could match it.
                    if (max != null && min.toInt() > max.toInt()) invalid("the repetition ${re.substring(open, i)} has its minimum above its maximum", open)
                    out.append('{').append(min)
                    if (range) out.append(',').append(max.orEmpty())
                    out.append('}')
                }
                else -> return false
            }
            return true
        }

        private fun quantExact(open: Int): String {
            val start = i
            while (peek() in '0'.code..'9'.code) i++
            if (i == start) invalid("a repetition is {n}, {n,} or {n,m}, with n and m in digits; a literal '{' is written \\{", open)
            // Java limits repetition counts to an Int; larger ones make the pattern unusable anyway.
            return re.substring(start, i).also { if (it.length > 9) invalid("a repetition count has at most 9 digits", start) }
        }

        /** `atom := NormalChar / charClass / "(" i-regexp ")"`. */
        private fun atom() {
            when (val c = peek()) {
                '('.code -> {
                    val open = i
                    i++
                    if (peek() == '?'.code) invalid(groupConstruct(open), open)
                    out.append("(?:")
                    if (++depth > MAX_GROUP_DEPTH) invalid("groups nest at most $MAX_GROUP_DEPTH deep", open)
                    regexp()
                    depth--
                    if (peek() != ')'.code) invalid("the group has no ')'", open)
                    i++
                    out.append(')')
                }
                '.'.code -> {
                    i++
                    out.append("[^\\n\\r]")
                }
                '['.code -> classExpr()
                '\\'.code -> escape()
                // The grammar reads '^' and '$' as ordinary characters, but the compliance suite and the
                // implementations it was built from treat them as anchors of the whole string, as ECMAScript does.
                '^'.code -> {
                    i++
                    out.append('^')
                }
                '$'.code -> {
                    i++
                    out.append("\\z")
                }
                else -> {
                    // peek() pairs surrogates into one code point, so one in this range is unpaired.
                    if (c in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) invalid("an unpaired surrogate isn't a character")
                    if (!isNormalChar(c)) invalid("a literal '${c.toChar()}' is written \\${c.toChar()}")
                    literal(take())
                }
            }
        }

        /** Why `(?` at [open] isn't I-Regexp, whose groups are only `(…)`: the construct, named as other dialects do. */
        private fun groupConstruct(open: Int): String {
            val rest = re.substring(open + 2, minOf(re.length, open + 3))
            return when {
                rest == "=" || rest == "!" -> "(?$rest is lookahead, which I-Regexp doesn't have"
                re.startsWith("(?<=", open) || re.startsWith("(?<!", open) -> "${re.substring(open, open + 4)} is lookbehind, which I-Regexp doesn't have"
                rest == "<" || rest == "P" || rest == "'" -> "(?$rest names a group, which I-Regexp doesn't have; a group is written (…)"
                rest == ":" -> "(?: makes a group non-capturing, which I-Regexp has no syntax for: write the group as (…)"
                rest == ">" -> "(?> is an atomic group, which I-Regexp doesn't have"
                else -> {
                    var end = open + 2
                    while (end < re.length && (re[end] in 'a'..'z' || re[end] in 'A'..'Z' || re[end] == '-')) end++
                    if (end == open + 2 || end == re.length || (re[end] != ')' && re[end] != ':')) {
                        "'?' has nothing before it to repeat; a literal '?' is written \\?"
                    } else {
                        val flags = re.substring(open, end + 1)
                        val hint = if ('i' in flags) "; to match either case, list both in a class, as in [Dd]" else ""
                        "$flags is an inline flag, which I-Regexp doesn't have$hint"
                    }
                }
            }
        }

        /** `SingleCharEsc / charClassEsc` after a backslash at [i]: the code point it stands for, or -1 for a category. */
        private fun escape(): Int {
            val at = i
            i++
            val c = peek()
            return when {
                c == 'p'.code || c == 'P'.code -> {
                    i++
                    if (peek() != '{'.code) invalid("\\${c.toChar()} is followed by a category in braces, such as \\${c.toChar()}{Lu}", at)
                    i++
                    val start = i
                    while (peek() != '}'.code) {
                        if (peek() == -1) invalid("\\${c.toChar()}{ has no '}'", at)
                        i++
                    }
                    val property = re.substring(start, i)
                    i++
                    if (property !in CATEGORIES) {
                        invalid("\\${c.toChar()}{$property} names no Unicode general category; I-Regexp takes one such as L, Lu or Nd, and no block or script", at)
                    }
                    out.append('\\').appendCodePoint(c).append('{').append(property).append('}')
                    -1
                }
                c == 'n'.code -> { i++; out.append("\\n"); '\n'.code }
                c == 'r'.code -> { i++; out.append("\\r"); '\r'.code }
                c == 't'.code -> { i++; out.append("\\t"); '\t'.code }
                c in '('.code..'+'.code || c == '-'.code || c == '.'.code || c == '?'.code || c in '['.code..'^'.code || c in '{'.code..'}'.code -> {
                    i++
                    out.append('\\').appendCodePoint(c)
                    c
                }
                c == -1 -> invalid("the pattern ends in a '\\' that escapes nothing", at)
                else -> invalid(unknownEscape(c), at)
            }
        }

        private fun unknownEscape(c: Int): String {
            val escape = "\\" + String(Character.toChars(c))
            val instead = when (c) {
                'd'.code -> "; for a digit, use [0-9] or \\p{Nd}"
                'D'.code -> "; for a character other than a digit, use [^0-9] or \\P{Nd}"
                'w'.code -> "; for a word character, use a class such as [A-Za-z0-9_]"
                'W'.code -> "; for another character, use a class such as [^A-Za-z0-9_]"
                's'.code -> "; for blank space, use a class such as [ \\t\\n\\r]"
                'S'.code -> "; for a character other than blank space, use [^ \\t\\n\\r]"
                'b'.code, 'B'.code -> ": I-Regexp has no word boundaries"
                'A'.code, 'z'.code, 'Z'.code -> ": '^' and '$' anchor the start and the end of the string"
                'u'.code, 'x'.code -> ": write the character itself, or escape it in the query's string"
                in '0'.code..'9'.code -> ": it is a backreference, which I-Regexp doesn't have"
                else -> ""
            }
            return "$escape isn't an I-Regexp escape$instead"
        }

        /** `charClassExpr := "[" ["^"] ("-" / CCE1) *CCE1 ["-"] "]"`. */
        private fun classExpr() {
            classOpen = i
            i++
            out.append('[')
            if (peek() == '^'.code) {
                i++
                out.append('^')
            }
            var first = true
            while (true) {
                val c = peek()
                when {
                    c == -1 -> invalid("the class has no ']'", classOpen)
                    c == ']'.code -> {
                        if (first) invalid("a class has at least one character; a literal ']' is written \\]")
                        i++
                        out.append(']')
                        return
                    }
                    c == '-'.code -> {
                        // A '-' is literal only first or last.
                        i++
                        if (peek() == -1) invalid("the class has no ']'", classOpen)
                        if (!first && peek() != ']'.code) invalid("a '-' that isn't first or last in a class, or between the two ends of a range, is written \\-", i - 1)
                        out.append("\\-")
                    }
                    c == '\\'.code && (re.startsWith("\\p", i) || re.startsWith("\\P", i)) -> escape()
                    else -> {
                        val low = i
                        val from = classChar()
                        if (peek() == '-'.code && i + 1 < re.length && re[i + 1] != ']') {
                            i++
                            out.append('-')
                            if (peek() == '\\'.code && (re.startsWith("\\p", i) || re.startsWith("\\P", i))) {
                                invalid("a range ends at a character, not at a category", i)
                            }
                            val to = classChar()
                            // The JDK refuses such a range; nothing could match it.
                            if (from > to) invalid("the range ${re.substring(low, i)} runs backwards", low)
                        }
                    }
                }
                first = false
            }
        }

        /** `CCchar := %x00-2C / %x2E-5A / %x5E-D7FF / %xE000-10FFFF / SingleCharEsc`: the code point. */
        private fun classChar(): Int {
            val c = peek()
            return when {
                c == '\\'.code -> escape()
                c == -1 -> invalid("the class has no ']'", classOpen)
                c == '['.code || c == ']'.code || c == '-'.code -> invalid("a literal '${c.toChar()}' in a class is written \\${c.toChar()}")
                c in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code -> invalid("an unpaired surrogate isn't a character")
                else -> take().also { literal(it) }
            }
        }

        /** A literal code point, escaped when Java's syntax would read it as something else. */
        private fun literal(c: Int) {
            if (c < 0x80 && !Character.isLetterOrDigit(c) && c > ' '.code) out.append('\\')
            out.appendCodePoint(c)
        }

        private fun isNormalChar(c: Int): Boolean = when (c) {
            in 0x00..0x27, ','.code, '-'.code, in 0x2F..0x3E, in 0x40..0x5A, in 0x5E..0x7A, in 0x7E..0xD7FF, in 0xE000..0x10FFFF -> true
            else -> false
        }
    }

    private val CATEGORIES = setOf(
        "L", "Ll", "Lm", "Lo", "Lt", "Lu",
        "M", "Mc", "Me", "Mn",
        "N", "Nd", "Nl", "No",
        "P", "Pc", "Pd", "Pe", "Pf", "Pi", "Po", "Ps",
        "Z", "Zl", "Zp", "Zs",
        "S", "Sc", "Sk", "Sm", "So",
        "C", "Cc", "Cf", "Cn", "Co",
    )

    /** The subject of a match, charging one step per character read. */
    private class Budget(private val text: String) : CharSequence {
        private var steps = 0L

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            if (++steps > MAX_STEPS) throw QueryLimitException("a regular expression took more than $MAX_STEPS steps")
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = text.subSequence(startIndex, endIndex)

        override fun toString(): String = text
    }
}
