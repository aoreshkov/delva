package app.oreshkov.delva.internal.path

import app.oreshkov.delva.QueryLimitException
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * I-Regexp (RFC 9485), the regular expressions of the JSONPath functions `match` and `search`: a pattern is checked
 * against the RFC's grammar and translated to a [java.util.regex.Pattern] that means the same, as section 5.3 of the
 * RFC describes: `.` becomes `[^\n\r]`, groups become non-capturing, and characters Java treats specially are escaped.
 * Groups nest at most [MAX_GROUP_DEPTH] deep, an implementation limit the RFC allows; a deeper pattern isn't valid.
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

    class Compiled(private val pattern: Pattern) {
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

    /** The translated pattern, or null when [regexp] isn't valid I-Regexp. */
    fun compile(regexp: String): Compiled? {
        val java = Translator(regexp).translate() ?: return null
        return try {
            Compiled(Pattern.compile(java))
        } catch (_: PatternSyntaxException) {
            // A repetition range whose lower bound exceeds the upper one, which I-Regexp's grammar doesn't exclude.
            null
        }
    }

    private class Translator(private val re: String) {
        private var i = 0
        private var depth = 0
        private val out = StringBuilder(re.length + 16)

        fun translate(): String? = try {
            regexp()
            if (i != re.length) null else out.toString()
        } catch (_: Invalid) {
            null
        }

        /** Thrown inside the translator only; carries no stack trace. */
        private class Invalid : RuntimeException(null, null, false, false)

        private fun invalid(): Nothing = throw Invalid()

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

        /** `branch := *piece`, ending at `|`, `)` or the end. */
        private fun branch() {
            while (true) {
                val c = peek()
                if (c == -1 || c == '|'.code || c == ')'.code) return
                atom()
                quantifier()
            }
        }

        private fun quantifier() {
            when (peek()) {
                '*'.code, '+'.code, '?'.code -> out.appendCodePoint(take())
                '{'.code -> {
                    i++
                    out.append('{').append(quantExact())
                    if (peek() == ','.code) {
                        i++
                        out.append(',')
                        if (peek() in '0'.code..'9'.code) out.append(quantExact())
                    }
                    if (peek() != '}'.code) invalid()
                    i++
                    out.append('}')
                }
            }
        }

        private fun quantExact(): String {
            val start = i
            while (peek() in '0'.code..'9'.code) i++
            if (i == start) invalid()
            // Java limits repetition counts to an Int; larger ones make the pattern unusable anyway.
            return re.substring(start, i).also { if (it.length > 9) invalid() }
        }

        /** `atom := NormalChar / charClass / "(" i-regexp ")"`. */
        private fun atom() {
            when (val c = peek()) {
                '('.code -> {
                    i++
                    out.append("(?:")
                    if (++depth > MAX_GROUP_DEPTH) invalid()
                    regexp()
                    depth--
                    if (peek() != ')'.code) invalid()
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
                    if (!isNormalChar(c)) invalid()
                    literal(take())
                }
            }
        }

        /** `SingleCharEsc / charClassEsc` after a backslash at [i]. */
        private fun escape() {
            i++
            val c = peek()
            when {
                c == 'p'.code || c == 'P'.code -> {
                    i++
                    if (peek() != '{'.code) invalid()
                    i++
                    val start = i
                    while (peek() != '}'.code) {
                        if (peek() == -1) invalid()
                        i++
                    }
                    val property = re.substring(start, i)
                    i++
                    if (property !in CATEGORIES) invalid()
                    out.append('\\').appendCodePoint(c).append('{').append(property).append('}')
                }
                c == 'n'.code -> { i++; out.append("\\n") }
                c == 'r'.code -> { i++; out.append("\\r") }
                c == 't'.code -> { i++; out.append("\\t") }
                c in '('.code..'+'.code || c == '-'.code || c == '.'.code || c == '?'.code || c in '['.code..'^'.code || c in '{'.code..'}'.code -> {
                    i++
                    out.append('\\').appendCodePoint(c)
                }
                else -> invalid()
            }
        }

        /** `charClassExpr := "[" ["^"] ("-" / CCE1) *CCE1 ["-"] "]"`. */
        private fun classExpr() {
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
                    c == -1 -> invalid()
                    c == ']'.code -> {
                        if (first) invalid()
                        i++
                        out.append(']')
                        return
                    }
                    c == '-'.code -> {
                        // A '-' is literal only first or last.
                        i++
                        if (!first && peek() != ']'.code) invalid()
                        out.append("\\-")
                    }
                    c == '\\'.code && (re.startsWith("\\p", i) || re.startsWith("\\P", i)) -> escape()
                    else -> {
                        classChar()
                        if (peek() == '-'.code && i + 1 < re.length && re[i + 1] != ']') {
                            i++
                            out.append('-')
                            if (peek() == '\\'.code && (re.startsWith("\\p", i) || re.startsWith("\\P", i))) invalid()
                            classChar()
                        }
                    }
                }
                first = false
            }
        }

        /** `CCchar := %x00-2C / %x2E-5A / %x5E-D7FF / %xE000-10FFFF / SingleCharEsc`. */
        private fun classChar() {
            val c = peek()
            when {
                c == '\\'.code -> escape()
                c == '['.code || c == ']'.code || c == '-'.code || c == -1 -> invalid()
                else -> literal(take())
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
