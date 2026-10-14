package app.oreshkov.delva.internal.path

/**
 * The lexical pieces that every RFC 9535 grammar in Delva shares: string literals with their escapes (RFC 9535
 * section 2.3.1.1), integers, member-name shorthands, blank space and the check that the text is valid Unicode.
 * Subclasses parse from [i] and report errors through [fail], which names the character offset.
 */
internal abstract class PathScanner(protected val text: String) {
    protected var i: Int = 0

    /** Throws [app.oreshkov.delva.InvalidJsonPathException] naming [i]. */
    protected abstract fun fail(reason: String): Nothing

    protected fun peek(): Char = if (i < text.length) text[i] else END

    protected fun skipBlank() {
        while (i < text.length && isBlank(text[i])) i++
    }

    /** The digits at [i], at least one; a string of them. */
    protected fun digits(): String {
        val start = i
        while (i < text.length && text[i] in '0'..'9') i++
        if (i == start) fail("expected a digit")
        return text.substring(start, i)
    }

    /** RFC 9535 `int` at [i]: `0` or an optional `-` and digits without a leading zero, within ±(2^53 - 1). */
    protected fun int(): Long {
        val start = i
        val negative = peek() == '-'
        if (negative) i++
        val digits = digits()
        if (digits.startsWith("0") && (negative || digits.length > 1)) {
            i = start
            fail(if (negative) "invalid integer (-0 or a leading zero)" else "an integer has no leading zeros")
        }
        val value = digits.toLongOrNull()?.takeIf { it <= MAX_INT }
        if (value == null) {
            i = start
            fail("an integer is at most $MAX_INT in magnitude")
        }
        return if (negative) -value else value
    }

    /** A member-name shorthand at [i], which must start with a name character. */
    protected fun shorthandName(): String {
        val start = i
        while (i < text.length && (isNameFirst(text[i]) || text[i] in '0'..'9')) i++
        return text.substring(start, i)
    }

    /** The string literal opened by [quote] at [i]; leaves [i] after its closing quote. */
    protected fun string(quote: Char): String {
        val sb = StringBuilder()
        i++
        while (true) {
            if (i == text.length) fail("unterminated string")
            val c = text[i]
            when {
                c == quote -> {
                    i++
                    return sb.toString()
                }
                c < ' ' -> fail("a control character must be escaped")
                c != '\\' -> {
                    sb.append(c)
                    i++
                }
                else -> escape(quote, sb)
            }
        }
    }

    private fun escape(quote: Char, sb: StringBuilder) {
        i++
        if (i == text.length) fail("unterminated escape")
        when (val e = text[i]) {
            quote, '\\', '/' -> sb.append(e)
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'u' -> {
                val start = i - 1
                val unit = hex4()
                when {
                    Character.isHighSurrogate(unit) -> {
                        if (!text.startsWith("\\u", i)) {
                            i = start
                            fail("an escaped high surrogate must be followed by an escaped low surrogate")
                        }
                        i++
                        val low = hex4()
                        if (!Character.isLowSurrogate(low)) {
                            i = start
                            fail("an escaped high surrogate must be followed by an escaped low surrogate")
                        }
                        sb.append(unit).append(low)
                    }
                    Character.isLowSurrogate(unit) -> {
                        i = start
                        fail("an unpaired escaped low surrogate")
                    }
                    else -> sb.append(unit)
                }
                return
            }
            else -> fail("invalid escape '\\$e'")
        }
        i++
    }

    /** The four hex digits after the `u` at [i]; leaves [i] after them. */
    private fun hex4(): Char {
        i++
        if (i + 4 > text.length) fail("invalid \\u escape")
        var v = 0
        for (k in 0 until 4) {
            val h = text[i + k]
            // Character.digit also takes non-ASCII digits, which ABNF's HEXDIG doesn't.
            val d = if (h < '\u0080') Character.digit(h, 16) else -1
            if (d < 0) fail("invalid \\u escape")
            v = v shl 4 or d
        }
        i += 4
        return v.toChar()
    }

    /** Fails at the first unpaired surrogate of [text]. */
    protected fun checkUnicode() {
        var k = 0
        while (k < text.length) {
            val c = text[k]
            if (Character.isHighSurrogate(c) && k + 1 < text.length && Character.isLowSurrogate(text[k + 1])) {
                k += 2
            } else if (Character.isSurrogate(c)) {
                i = k
                fail("not valid Unicode (an unpaired surrogate)")
            } else {
                k++
            }
        }
    }

    companion object {
        /** The largest integer RFC 9535 allows in indexes and slices: the I-JSON range of exact integers. */
        const val MAX_INT: Long = (1L shl 53) - 1

        /** What [peek] returns at the end of the text; callers that accept any character check the length first. */
        const val END: Char = '￿'

        fun isBlank(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

        /** RFC 9535 `name-first`; a surrogate is half of a code point above U+FFFF, which is a name character. */
        fun isNameFirst(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c == '_' || c.code >= 0x80
    }
}
