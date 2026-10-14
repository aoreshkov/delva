package app.oreshkov.delva.internal.path

import app.oreshkov.delva.InvalidJsonPathException

/** A step of a [SingularPath]: a member key or an array index. */
internal sealed interface PathStep {
    data class Name(val key: String) : PathStep
    data class Index(val index: Long) : PathStep
}

/**
 * JSON paths as SPEC.md §6.1 defines them: an RFC 9535 singular query (`abs-singular-query`, §2.3.5.1) without
 * negative indexes, which the streaming splitter can't resolve.
 * ```
 * path          := '$' *(S (name-segment / index-segment))
 * name-segment  := '[' string-literal ']' / '.' member-name-shorthand
 * index-segment := '[' ('0' / DIGIT1 *DIGIT) ']'          ; at most 2^53 - 1
 * S             := *(' ' / '\t' / '\n' / '\r')
 * ```
 * `string-literal` is single or double quoted with the escapes of RFC 9535 section 2.3.1.1, and `member-name-shorthand` is
 * `(ALPHA / '_' / U+0080…) *(ALPHA / '_' / U+0080… / DIGIT)`. Each accepted path is a valid RFC 9535 query
 * that selects the same value. Queries of the whole RFC 9535 grammar are [JsonPathParser]'s.
 */
internal object SingularPath {
    /** The largest index RFC 9535 allows: the I-JSON range of exact integers. */
    const val MAX_INDEX: Long = PathScanner.MAX_INT

    /** Parses [text]; throws [app.oreshkov.delva.InvalidJsonPathException] naming the character offset for invalid syntax. */
    fun parse(text: String): List<PathStep> = Parser(text).path()

    private class Parser(text: String) : PathScanner(text) {
        private val steps = ArrayList<PathStep>()

        fun path(): List<PathStep> {
            if (text.isEmpty() || text[0] != '$') fail("a JSON path starts with '$'")
            checkUnicode()
            i = 1
            while (i < text.length) {
                val blank = i
                skipBlank()
                if (i == text.length) {
                    i = blank
                    fail("trailing whitespace")
                }
                when (text[i]) {
                    '.' -> shorthand()
                    '[' -> bracket()
                    else -> fail("expected '.' or '['")
                }
            }
            return steps
        }

        private fun shorthand() {
            i++
            if (i == text.length) fail("expected a member name after '.'")
            when (text[i]) {
                '.' -> unsupported("descendant segments ('..')")
                '*' -> unsupported("wildcards")
            }
            if (!isNameFirst(text[i])) fail("a member name can't start with '${text[i]}'; use ['…']")
            steps += PathStep.Name(shorthandName())
        }

        private fun bracket() {
            i++
            if (i == text.length) fail("unterminated '['")
            when (val c = text[i]) {
                '\'', '"' -> steps += PathStep.Name(string(c))
                '-' -> {
                    val start = i
                    i++
                    val digits = digits()
                    if (digits.startsWith("0")) {
                        i = start
                        fail("invalid index")
                    }
                    i = start
                    fail("negative indexes aren't supported")
                }
                in '0'..'9' -> {
                    val start = i
                    val digits = digits()
                    if (digits.length > 1 && digits.startsWith("0")) {
                        i = start
                        fail("an index has no leading zeros")
                    }
                    val index = digits.toLongOrNull()?.takeIf { it <= MAX_INDEX }
                    if (index == null) {
                        i = start
                        fail("an index is at most $MAX_INDEX")
                    }
                    steps += PathStep.Index(index)
                }
                '*' -> unsupported("wildcards")
                '?' -> unsupported("filters")
                ':' -> unsupported("slices")
                else -> if (isBlank(c)) fail("no whitespace inside '[…]' of a singular query") else fail("expected a quoted name or an index")
            }
            if (i == text.length) fail("expected ']'")
            when (text[i]) {
                ']' -> i++
                ':' -> unsupported("slices")
                ',' -> unsupported("several selectors in one segment")
                else -> fail("expected ']'")
            }
        }

        private fun unsupported(what: String): Nothing = fail("$what aren't supported; a path selects at most one value")

        override fun fail(reason: String): Nothing = throw InvalidJsonPathException(text, i, reason)
    }
}
