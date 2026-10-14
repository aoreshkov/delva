package app.oreshkov.delva.testkit.oracle

import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.json.OracleJson
import app.oreshkov.delva.testkit.json.ParseResult
import kotlin.random.Random

/**
 * The JSON paths of SPEC.md §6.1: RFC 9535 singular queries (`abs-singular-query`, §2.3.5.1) without negative indexes.
 * ```
 * path          := '$' *(S (name-segment / index-segment))
 * name-segment  := '[' string-literal ']' / '.' member-name-shorthand
 * index-segment := '[' ('0' / [1-9][0-9]*) ']'     ; at most 2^53 - 1
 * S             := [ \t\n\r]*
 * ```
 * A path selects at most one value. Written independently of the engine's parser: a segment is matched by a regex, and
 * a string literal is decoded by rewriting it into a JSON string for [OracleJson].
 */
class JsonPath private constructor(val text: String, val steps: List<Step>) {
    sealed interface Step {
        data class Member(val key: String) : Step
        data class Index(val index: Long) : Step
    }

    fun resolve(root: OValue): OValue? {
        var current = root
        for (step in steps) {
            current = when (step) {
                is Step.Member -> (current as? OObj)?.get(step.key) ?: return null
                is Step.Index -> (current as? OArr)?.items?.let { if (step.index < it.size) it[step.index.toInt()] else null } ?: return null
            }
        }
        return current
    }

    /** This path's RFC 9535 Normalized Path. */
    val normalized: String get() = normalized(steps.map { if (it is Step.Member) it.key else (it as Step.Index).index })

    override fun toString(): String = text

    companion object {
        const val MAX_INDEX: Long = 9007199254740991

        private val shorthand = Regex("""[ \t\n\r]*\.([A-Za-z_\x{80}-\x{10FFFF}][A-Za-z0-9_\x{80}-\x{10FFFF}]*)""")
        private val index = Regex("""[ \t\n\r]*\[(0|[1-9][0-9]*)]""")
        private val doubleQuoted = Regex("""[ \t\n\r]*\["((?:[^"\\]|\\.)*)"]""")
        private val singleQuoted = Regex("""[ \t\n\r]*\['((?:[^'\\]|\\.)*)']""")

        /** Parses [text]; throws [IllegalArgumentException] for invalid syntax. */
        fun parse(text: String): JsonPath {
            require(text.startsWith("$")) { "JSON path must start with '$': $text" }
            require(validUnicode(text)) { "JSON path isn't valid Unicode: $text" }
            val steps = ArrayList<Step>()
            var i = 1
            while (i < text.length) {
                val m = shorthand.matchAt(text, i)
                    ?: index.matchAt(text, i)
                    ?: doubleQuoted.matchAt(text, i)
                    ?: singleQuoted.matchAt(text, i)
                    ?: throw IllegalArgumentException("invalid segment at $i in $text")
                val body = m.groupValues[1]
                steps += when (m.value.trimStart()[0]) {
                    '.' -> Step.Member(body)
                    else -> when (m.value.trimStart()[1]) {
                        '"' -> Step.Member(decode("\"$body\"", text))
                        '\'' -> Step.Member(decode(singleToDouble(body, text), text))
                        else -> Step.Index(body.toLongOrNull()?.takeIf { it <= MAX_INDEX } ?: throw IllegalArgumentException("index too large in $text"))
                    }
                }
                i = m.range.last + 1
            }
            return JsonPath(text, steps)
        }

        /** RFC 9535 Normalized Path (section 2.7) of [steps]: `String` keys and `Long` indexes. */
        fun normalized(steps: List<Any>): String = buildString {
            append('$')
            for (step in steps) {
                if (step is Long) {
                    append('[').append(step).append(']')
                    continue
                }
                append("['")
                for (cp in (step as String).codePoints().toArray()) {
                    append(
                        when (cp) {
                            '\''.code -> "\\'"
                            '\\'.code -> "\\\\"
                            0x08 -> "\\b"
                            0x09 -> "\\t"
                            0x0A -> "\\n"
                            0x0C -> "\\f"
                            0x0D -> "\\r"
                            in 0..0x1F -> "\\u%04x".format(cp)
                            else -> String(Character.toChars(cp))
                        },
                    )
                }
                append("']")
            }
        }

        /**
         * A random spelling of the member step for [key]: now and then after whitespace, as `.key` where the key
         * allows, else as a single- or double-quoted string with random optional escapes (`\/`, `\uXXXX` in either
         * case, surrogate pairs above U+FFFF).
         */
        fun spellMember(key: String, random: Random): String = buildString {
            append(blank(random))
            if (shorthand.matches(".$key") && random.nextBoolean()) {
                append('.').append(key)
                return@buildString
            }
            val quote = if (random.nextBoolean()) '"' else '\''
            append('[').append(quote)
            var escapePair = false
            for (ch in key) {
                // Both halves of a pair are escaped, or neither.
                if (Character.isHighSurrogate(ch)) escapePair = random.nextInt(3) == 0
                if (Character.isSurrogate(ch) && !escapePair) {
                    append(ch)
                    continue
                }
                val escape = when (ch) {
                    quote, '\\' -> "\\$ch"
                    '\b' -> "\\b"
                    '\u000C' -> "\\f"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    '/' -> if (random.nextInt(3) == 0) "\\/" else null
                    else -> null
                }
                when {
                    escape != null && random.nextInt(4) != 0 -> append(escape)
                    ch < ' ' || ch == quote || ch == '\\' || Character.isSurrogate(ch) || random.nextInt(8) == 0 -> {
                        val hex = "%04x".format(ch.code)
                        append("\\u").append(if (random.nextBoolean()) hex else hex.uppercase())
                    }
                    else -> append(ch)
                }
            }
            append(quote).append(']')
        }

        /** A random spelling of the index step for [index]: `[i]`, now and then after whitespace. */
        fun spellIndex(index: Long, random: Random): String = "${blank(random)}[$index]"

        private fun blank(random: Random): String = if (random.nextInt(6) == 0) listOf(" ", "\t", "\n", "\r", " \n ")[random.nextInt(5)] else ""

        /** The body of a single-quoted literal as a double-quoted one: `\'` → `'`, `"` → `\"`; `\"` is invalid in it. */
        private fun singleToDouble(body: String, text: String): String = buildString {
            append('"')
            var i = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    c == '"' -> append("\\\"")
                    c != '\\' -> append(c)
                    body[i + 1] == '\'' -> { append('\''); i++ }
                    body[i + 1] == '"' -> throw IllegalArgumentException("\\\" in a single-quoted string of $text")
                    else -> { append(c).append(body[i + 1]); i++ }
                }
                i++
            }
            append('"')
        }

        /** RFC 9535's double-quoted strings are JSON strings, lone escaped surrogates included (both reject them). */
        private fun decode(json: String, text: String): String {
            val parsed = OracleJson.parse(json)
            require(parsed is ParseResult.Accepted && parsed.value is OStr) { "invalid string step in $text" }
            return parsed.value.value
        }

        private fun validUnicode(s: String): Boolean {
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                if (cp in 0xD800..0xDFFF) return false
                i += Character.charCount(cp)
            }
            return true
        }
    }
}
