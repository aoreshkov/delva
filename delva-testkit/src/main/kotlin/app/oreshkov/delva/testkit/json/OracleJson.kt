package app.oreshkov.delva.testkit.json

import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.RejectReason
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Limits checked while parsing. Byte lengths are UTF-8 lengths after unescaping. */
data class JsonLimits(
    val maxDepth: Int = 1024,
    val maxDocumentBytes: Long = Long.MAX_VALUE,
    val maxKeyBytes: Int = Int.MAX_VALUE,
    val maxStringBytes: Long = Long.MAX_VALUE,
    val maxNumberLength: Int = Int.MAX_VALUE,
)

sealed interface ParseResult {
    data class Accepted(val value: OValue) : ParseResult

    data class Rejected(val reason: RejectReason, val detail: String) : ParseResult
}

/**
 * Independent strict JSON parser (RFC 8259 plus Delva's rules), the test oracle for the engine's tokenizer.
 * Deliberately built differently from the engine: the input is first decoded to code points by the JDK decoder,
 * then parsed by recursive descent over them.
 *
 * Rules beyond RFC 8259:
 * - One leading byte-order mark is skipped.
 * - Bytes that aren't valid UTF-8 inside a string or key are [RejectReason.INVALID_UTF8]; anywhere else they're
 *   [RejectReason.MALFORMED_JSON], like any other unexpected byte.
 * - A `\u` escape of a surrogate that isn't part of a high-low pair is [RejectReason.INVALID_STRING].
 * - A repeated key in one object is [RejectReason.DUPLICATE_KEY] (keys compare by code points after unescaping).
 * - A number whose normalized decimal exponent doesn't fit an `Int` is [RejectReason.NUMBER_OUT_OF_RANGE].
 * - Exceeding a [JsonLimits] value is [RejectReason.LIMIT_EXCEEDED]; depth counts open containers.
 *
 * The first problem in document order is reported. With `allowDuplicateKeys`, a repeated key is not a problem and its
 * first value is kept, which tells whether a document rejected for one has another problem too.
 */
object OracleJson {
    fun parse(bytes: ByteArray, limits: JsonLimits = JsonLimits(), allowDuplicateKeys: Boolean = false): ParseResult {
        if (bytes.size > limits.maxDocumentBytes) {
            return ParseResult.Rejected(RejectReason.LIMIT_EXCEEDED, "document has ${bytes.size} bytes")
        }
        val codePoints = decode(bytes)
        val start = if (codePoints.isNotEmpty() && codePoints[0] == BOM) 1 else 0
        return try {
            ParseResult.Accepted(Parser(codePoints, start, limits, allowDuplicateKeys).document())
        } catch (e: Reject) {
            ParseResult.Rejected(e.reason, e.message ?: "")
        }
    }

    fun parse(text: String, limits: JsonLimits = JsonLimits(), allowDuplicateKeys: Boolean = false): ParseResult =
        parse(text.toByteArray(), limits, allowDuplicateKeys)

    /** Parses or throws [DocumentRejectedException] (byte offset unknown: -1). */
    fun parseOrThrow(bytes: ByteArray, limits: JsonLimits = JsonLimits()): OValue =
        when (val result = parse(bytes, limits)) {
            is ParseResult.Accepted -> result.value
            is ParseResult.Rejected -> throw DocumentRejectedException(result.reason, -1, detail = result.detail)
        }

    fun parseOrThrow(text: String, limits: JsonLimits = JsonLimits()): OValue = parseOrThrow(text.toByteArray(), limits)

    private const val BOM = 0xFEFF
    private const val INVALID = -1

    /** Code points of [bytes]; each malformed UTF-8 sequence becomes one [INVALID] marker. */
    private fun decode(bytes: ByteArray): IntArray {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes)
        val chars = CharBuffer.allocate(bytes.size + 1)
        val out = IntArrayList(bytes.size)
        while (true) {
            val result = decoder.decode(input, chars, true)
            chars.flip()
            appendCodePoints(chars, out)
            chars.clear()
            if (result.isError) {
                out.add(INVALID)
                input.position(input.position() + result.length())
            } else if (result.isUnderflow) {
                break
            }
        }
        decoder.flush(chars)
        chars.flip()
        appendCodePoints(chars, out)
        return out.toArray()
    }

    private fun appendCodePoints(chars: CharBuffer, out: IntArrayList) {
        val s = chars.toString()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            out.add(cp)
            i += Character.charCount(cp)
        }
    }

    private class Reject(val reason: RejectReason, detail: String) : Exception(detail, null, false, false)

    private class Parser(
        private val cp: IntArray,
        private var i: Int,
        private val limits: JsonLimits,
        private val allowDuplicateKeys: Boolean,
    ) {
        fun document(): OValue {
            skipWhitespace()
            val value = value(0)
            skipWhitespace()
            if (i < cp.size) fail(RejectReason.MALFORMED_JSON, "content after the root value")
            return value
        }

        private fun value(depth: Int): OValue {
            if (i >= cp.size) fail(RejectReason.MALFORMED_JSON, "unexpected end of input")
            return when (cp[i]) {
                '{'.code -> obj(depth + 1)
                '['.code -> arr(depth + 1)
                '"'.code -> OStr(string(maxBytes = limits.maxStringBytes))
                't'.code -> literal("true", OBool(true))
                'f'.code -> literal("false", OBool(false))
                'n'.code -> literal("null", ONull)
                else -> if (cp[i] == '-'.code || cp[i] in '0'.code..'9'.code) number() else fail(RejectReason.MALFORMED_JSON, "unexpected character")
            }
        }

        private fun obj(depth: Int): OValue {
            if (depth > limits.maxDepth) fail(RejectReason.LIMIT_EXCEEDED, "depth $depth > ${limits.maxDepth}")
            i++ // {
            val members = ArrayList<Pair<String, OValue>>()
            val keys = HashSet<String>()
            skipWhitespace()
            if (peek() == '}'.code) {
                i++
                return OObj(members)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"'.code) fail(RejectReason.MALFORMED_JSON, "expected a key")
                val key = string(maxBytes = limits.maxKeyBytes.toLong())
                val repeated = !keys.add(key)
                if (repeated && !allowDuplicateKeys) fail(RejectReason.DUPLICATE_KEY, "duplicate key")
                skipWhitespace()
                if (peek() != ':'.code) fail(RejectReason.MALFORMED_JSON, "expected ':'")
                i++
                skipWhitespace()
                val member = value(depth)
                if (!repeated) members += key to member
                skipWhitespace()
                when (peek()) {
                    ','.code -> i++
                    '}'.code -> {
                        i++
                        return OObj(members)
                    }
                    else -> fail(RejectReason.MALFORMED_JSON, "expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): OValue {
            if (depth > limits.maxDepth) fail(RejectReason.LIMIT_EXCEEDED, "depth $depth > ${limits.maxDepth}")
            i++ // [
            val items = ArrayList<OValue>()
            skipWhitespace()
            if (peek() == ']'.code) {
                i++
                return OArr(items)
            }
            while (true) {
                skipWhitespace()
                items += value(depth)
                skipWhitespace()
                when (peek()) {
                    ','.code -> i++
                    ']'.code -> {
                        i++
                        return OArr(items)
                    }
                    else -> fail(RejectReason.MALFORMED_JSON, "expected ',' or ']'")
                }
            }
        }

        private fun string(maxBytes: Long): String {
            i++ // opening quote
            val sb = StringBuilder()
            var utf8Bytes = 0L
            while (true) {
                if (i >= cp.size) fail(RejectReason.MALFORMED_JSON, "unterminated string")
                val c = cp[i++]
                val appended = when {
                    c == '"'.code -> return sb.toString()
                    c == INVALID -> fail(RejectReason.INVALID_UTF8, "invalid UTF-8 in a string")
                    c < 0x20 -> fail(RejectReason.MALFORMED_JSON, "unescaped control character")
                    c == '\\'.code -> escape()
                    else -> c
                }
                sb.appendCodePoint(appended)
                utf8Bytes += utf8Length(appended)
                if (utf8Bytes > maxBytes) fail(RejectReason.LIMIT_EXCEEDED, "string or key longer than $maxBytes bytes")
            }
        }

        /** After a backslash: returns the escaped code point. */
        private fun escape(): Int {
            if (i >= cp.size) fail(RejectReason.MALFORMED_JSON, "unterminated escape")
            return when (cp[i++]) {
                '"'.code -> '"'.code
                '\\'.code -> '\\'.code
                '/'.code -> '/'.code
                'b'.code -> 0x08
                'f'.code -> 0x0C
                'n'.code -> 0x0A
                'r'.code -> 0x0D
                't'.code -> 0x09
                'u'.code -> {
                    val unit = hex4()
                    when (unit) {
                        in 0xD800..0xDBFF -> {
                            if (i + 1 < cp.size && cp[i] == '\\'.code && cp[i + 1] == 'u'.code) {
                                i += 2
                                val low = hex4()
                                if (low !in 0xDC00..0xDFFF) fail(RejectReason.INVALID_STRING, "high surrogate without a low surrogate")
                                Character.toCodePoint(unit.toChar(), low.toChar())
                            } else {
                                fail(RejectReason.INVALID_STRING, "high surrogate without a low surrogate")
                            }
                        }
                        in 0xDC00..0xDFFF -> fail(RejectReason.INVALID_STRING, "lone low surrogate")
                        else -> unit
                    }
                }
                else -> fail(RejectReason.MALFORMED_JSON, "invalid escape")
            }
        }

        private fun hex4(): Int {
            var v = 0
            repeat(4) {
                if (i >= cp.size) fail(RejectReason.MALFORMED_JSON, "truncated \\u escape")
                val d = when (val c = cp[i++]) {
                    in '0'.code..'9'.code -> c - '0'.code
                    in 'a'.code..'f'.code -> c - 'a'.code + 10
                    in 'A'.code..'F'.code -> c - 'A'.code + 10
                    else -> fail(RejectReason.MALFORMED_JSON, "invalid hex digit")
                }
                v = v * 16 + d
            }
            return v
        }

        private fun number(): OValue {
            val start = i
            if (peek() == '-'.code) i++
            if (peek() == '0'.code) {
                i++
            } else if (peek() in '1'.code..'9'.code) {
                while (peek() in '0'.code..'9'.code) i++
            } else {
                fail(RejectReason.MALFORMED_JSON, "invalid number")
            }
            if (peek() == '.'.code) {
                i++
                if (peek() !in '0'.code..'9'.code) fail(RejectReason.MALFORMED_JSON, "invalid number fraction")
                while (peek() in '0'.code..'9'.code) i++
            }
            if (peek() == 'e'.code || peek() == 'E'.code) {
                i++
                if (peek() == '+'.code || peek() == '-'.code) i++
                if (peek() !in '0'.code..'9'.code) fail(RejectReason.MALFORMED_JSON, "invalid number exponent")
                while (peek() in '0'.code..'9'.code) i++
            }
            if (i - start > limits.maxNumberLength) fail(RejectReason.LIMIT_EXCEEDED, "number longer than ${limits.maxNumberLength}")
            val text = String(cp, start, i - start)
            if (!OracleNumbers.inRange(text)) fail(RejectReason.NUMBER_OUT_OF_RANGE, "exponent out of range")
            return ONum(text)
        }

        private fun literal(word: String, value: OValue): OValue {
            for (ch in word) {
                if (i >= cp.size || cp[i] != ch.code) fail(RejectReason.MALFORMED_JSON, "invalid literal")
                i++
            }
            return value
        }

        private fun skipWhitespace() {
            while (i < cp.size && (cp[i] == 0x20 || cp[i] == 0x09 || cp[i] == 0x0A || cp[i] == 0x0D)) i++
        }

        private fun peek(): Int = if (i < cp.size) cp[i] else Int.MIN_VALUE

        private fun fail(reason: RejectReason, detail: String): Nothing = throw Reject(reason, "$detail at code point $i")
    }
}

internal fun utf8Length(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}

private class IntArrayList(capacity: Int) {
    private var data = IntArray(maxOf(capacity, 8))
    private var size = 0

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    fun toArray(): IntArray = data.copyOf(size)
}
