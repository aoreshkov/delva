package app.oreshkov.delva.internal.parse

import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.canon.Utf8
import java.io.InputStream

/**
 * Strict streaming JSON tokenizer over UTF-8 bytes: RFC 8259 exactly, plus Delva's rules (SPEC.md §1).
 *
 * - **Memory:** one fixed input window refilled from the stream, and one scratch buffer for tokens that cross a
 *   refill or need unescaping. The scratch buffer is bounded by the longest key, number or string chunk, so memory
 *   stays O(window + chunk) for any document and any string length.
 * - **Grammar:** an explicit state machine with a bit stack of open containers; no recursion. Whitespace is SP, HT,
 *   LF, CR; a single root value is followed by EOF. One leading byte-order mark is skipped; offsets count from
 *   the start of the stream, including it.
 * - **Strings:** escapes are decoded; an escaped surrogate pair becomes one 4-byte sequence, an unpaired surrogate
 *   escape is [RejectReason.INVALID_STRING]; raw bytes are validated per RFC 3629.
 * - **Numbers:** the grammar is validated while scanning and the raw text is returned; a normalized exponent outside
 *   the `Int` range is [RejectReason.NUMBER_OUT_OF_RANGE].
 *
 * When a document has several problems, the first one in document order is reported. A limit is reported where it's
 * crossed; [ParseLimits.maxDocumentBytes] is checked when bytes are read.
 *
 * Doesn't close [input]. Reusable through [reset]; not thread-safe.
 */
internal class Utf8JsonTokenizer(
    input: InputStream,
    private val limits: ParseLimits = ParseLimits.DEFAULT,
    windowBytes: Int = DEFAULT_WINDOW_BYTES,
    private val stringChunkBytes: Int = DEFAULT_STRING_CHUNK_BYTES,
) : JsonTokenSource {
    init {
        require(windowBytes >= 1) { "window must hold at least one byte" }
        require(stringChunkBytes >= 4) { "a string chunk must hold any code point" }
    }

    private var input: InputStream = input
    private val window = ByteArray(windowBytes)
    private var pos = 0
    private var end = 0

    /** Stream offset of `window[0]`. */
    private var windowBase = 0L
    private var eof = false

    private var state = START
    private var containers = LongArray(4) // bit set = object

    /** Tokens that cross a refill, need unescaping or arrive in parts. */
    private val scratch = GrowableBytes(256)

    // Number in progress: window index where its text starts (or -1), and whether earlier text went to scratch.
    private var numberStart = -1
    private var numberCaptured = false

    // String in progress (slow path).
    private var inString = false
    private var stringIsKey = false
    private var partsEmitted = false
    private var stringBytes = 0L
    private var partStart = -1L

    /** A decoded escape or multi-byte character that didn't fit the current part. */
    private val unit = ByteArray(4)
    private var unitLength = 0
    private var unitOffset = 0L

    override var bytes: ByteArray = window
        private set
    override var offset: Int = 0
        private set
    override var length: Int = 0
        private set
    override var isLastPart: Boolean = false
        private set
    override var byteOffset: Long = 0
        private set
    override var depth: Int = 0
        private set

    /** Starts over on a new document. */
    fun reset(input: InputStream) {
        this.input = input
        pos = 0
        end = 0
        windowBase = 0
        eof = false
        state = START
        depth = 0
        numberStart = -1
        numberCaptured = false
        inString = false
        unitLength = 0
        setView(window, 0, 0)
        byteOffset = 0
    }

    override fun close() {
        state = DONE
        inString = false
    }

    override fun next(): Int {
        if (inString) return stringBody()
        while (true) {
            when (state) {
                EXPECT_VALUE -> return value(skipWhitespace())
                EXPECT_VALUE_OR_END -> {
                    val b = skipWhitespace()
                    return if (b == ']'.code) endContainer(JsonToken.END_ARRAY) else value(b)
                }
                EXPECT_KEY_OR_END -> {
                    val b = skipWhitespace()
                    if (b == '}'.code) return endContainer(JsonToken.END_OBJECT)
                    if (b == '"'.code) return string(isKey = true)
                    malformed("expected a key")
                }
                EXPECT_KEY -> {
                    if (skipWhitespace() == '"'.code) return string(isKey = true)
                    malformed("expected a key")
                }
                EXPECT_COLON -> {
                    if (skipWhitespace() != ':'.code) malformed("expected ':'")
                    pos++
                    state = EXPECT_VALUE
                }
                EXPECT_COMMA_OR_END -> {
                    val b = skipWhitespace()
                    val inObject = isObject()
                    when {
                        b == ','.code -> {
                            pos++
                            state = if (inObject) EXPECT_KEY else EXPECT_VALUE
                        }
                        b == '}'.code && inObject -> return endContainer(JsonToken.END_OBJECT)
                        b == ']'.code && !inObject -> return endContainer(JsonToken.END_ARRAY)
                        else -> malformed(if (inObject) "expected ',' or '}'" else "expected ',' or ']'")
                    }
                }
                EXPECT_EOF -> {
                    if (skipWhitespace() >= 0) malformed("content after the root value")
                    state = DONE
                }
                DONE -> return simpleToken(JsonToken.EOF, offset(pos))
                START -> {
                    skipByteOrderMark()
                    state = EXPECT_VALUE
                }
                else -> throw IllegalStateException("the tokenizer failed on an earlier token")
            }
        }
    }

    // ---- structure ----

    private fun value(b: Int): Int = when (b) {
        '{'.code -> startContainer(isObject = true)
        '['.code -> startContainer(isObject = false)
        '"'.code -> string(isKey = false)
        't'.code -> literal(TRUE_BYTES, JsonToken.TRUE)
        'f'.code -> literal(FALSE_BYTES, JsonToken.FALSE)
        'n'.code -> literal(NULL_BYTES, JsonToken.NULL)
        '-'.code, in '0'.code..'9'.code -> number()
        -1 -> malformed("unexpected end of input")
        else -> malformed("unexpected byte")
    }

    private fun startContainer(isObject: Boolean): Int {
        val at = offset(pos)
        if (depth >= limits.maxDepth) fail(RejectReason.LIMIT_EXCEEDED, at, "depth ${depth.toLong() + 1} exceeds ${limits.maxDepth}")
        val word = depth ushr 6
        if (word == containers.size) containers = containers.copyOf(containers.size * 2)
        val bit = 1L shl (depth and 63)
        containers[word] = if (isObject) containers[word] or bit else containers[word] and bit.inv()
        depth++
        pos++
        state = if (isObject) EXPECT_KEY_OR_END else EXPECT_VALUE_OR_END
        return simpleToken(if (isObject) JsonToken.START_OBJECT else JsonToken.START_ARRAY, at)
    }

    private fun endContainer(token: Int): Int {
        val at = offset(pos)
        pos++
        depth--
        afterValue()
        return simpleToken(token, at)
    }

    /** Whether the innermost open container is an object. */
    private fun isObject(): Boolean {
        val d = depth - 1
        return containers[d ushr 6] and (1L shl (d and 63)) != 0L
    }

    private fun afterValue() {
        state = if (depth == 0) EXPECT_EOF else EXPECT_COMMA_OR_END
    }

    private fun literal(word: ByteArray, token: Int): Int {
        val at = offset(pos)
        for (expected in word) {
            if (pos == end && !refill()) malformed("invalid literal")
            if (window[pos] != expected) malformed("invalid literal")
            pos++
        }
        afterValue()
        return simpleToken(token, at)
    }

    // ---- numbers ----

    private fun number(): Int {
        val at = offset(pos)
        numberStart = pos
        numberCaptured = false
        scratch.clear()

        if (window[pos] == '-'.code.toByte()) pos++
        // Virtual digit string = integer digits then fraction digits; e = intDigits - firstNonZero + exponent.
        var digits = 0L
        var firstNonZero = -1L
        var c = peek()
        if (c == '0'.code) {
            pos++
            digits++
        } else if (c in '1'.code..'9'.code) {
            while (c in '0'.code..'9'.code) {
                if (c != '0'.code && firstNonZero < 0) firstNonZero = digits
                digits++
                pos++
                c = peek()
            }
        } else {
            malformed("invalid number")
        }
        val intDigits = digits

        c = peek()
        if (c == '.'.code) {
            pos++
            c = peek()
            if (c !in '0'.code..'9'.code) malformed("invalid number fraction")
            while (c in '0'.code..'9'.code) {
                if (c != '0'.code && firstNonZero < 0) firstNonZero = digits
                digits++
                pos++
                c = peek()
            }
        }

        var exponent = 0L
        c = peek()
        if (c == 'e'.code || c == 'E'.code) {
            pos++
            c = peek()
            var negative = false
            if (c == '+'.code || c == '-'.code) {
                negative = c == '-'.code
                pos++
                c = peek()
            }
            if (c !in '0'.code..'9'.code) malformed("invalid number exponent")
            while (c in '0'.code..'9'.code) {
                exponent = minOf(exponent * 10 + (c - '0'.code), EXPONENT_SATURATION)
                pos++
                c = peek()
            }
            if (negative) exponent = -exponent
        }

        val textLength = offset(pos) - at
        if (numberCaptured) {
            if (scratch.size + (pos - numberStart) <= limits.maxNumberLength) scratch.writeBytes(window, numberStart, pos - numberStart)
            setView(scratch.array, 0, scratch.size)
        } else {
            setView(window, numberStart, pos - numberStart)
        }
        numberStart = -1
        if (textLength > limits.maxNumberLength) {
            fail(RejectReason.LIMIT_EXCEEDED, at, "number longer than ${limits.maxNumberLength} characters")
        }
        if (firstNonZero >= 0) {
            val e = intDigits - firstNonZero + exponent
            if (e < Int.MIN_VALUE || e > Int.MAX_VALUE) fail(RejectReason.NUMBER_OUT_OF_RANGE, at, "normalized exponent $e doesn't fit an Int")
        }
        afterValue()
        byteOffset = at
        isLastPart = false
        return JsonToken.NUMBER
    }

    /** Number text before a refill goes to scratch (bounded: a longer number is rejected anyway). */
    private fun captureNumber() {
        val n = end - numberStart
        if (scratch.size + n <= limits.maxNumberLength) scratch.writeBytes(window, numberStart, n)
        numberStart = 0
        numberCaptured = true
    }

    // ---- strings ----

    private fun string(isKey: Boolean): Int {
        val at = offset(pos)
        pos++
        // Fast path: the whole string is in the window, without escapes, and short enough: return a view.
        val max = if (isKey) limits.maxRawKeyBytes.toLong() else minOf(limits.maxRawStringBytes, stringChunkBytes.toLong())
        val start = pos
        val w = window
        val e = end
        var p = start
        while (p < e) {
            // Eight plain ASCII bytes at a time (SWAR): stop at a quote, backslash, control or non-ASCII byte.
            if (e - p >= 8) {
                val word = LittleEndian.getLong(w, p)
                val special = hasZeroByte(word xor QUOTES) or hasZeroByte(word xor BACKSLASHES) or
                    ((word - SPACES) and word.inv()) or word
                val mask = special and HIGH_BITS
                if (mask == 0L) {
                    p += 8
                    if (p - start > max) break
                    continue
                }
                p += java.lang.Long.numberOfTrailingZeros(mask) ushr 3
            }
            val b = w[p].toInt() and 0xFF
            when (CLASS[b].toInt()) {
                PLAIN -> p++
                QUOTE -> {
                    if (p - start > max) break
                    pos = p + 1
                    setView(w, start, p - start)
                    byteOffset = at
                    isLastPart = false
                    if (isKey) {
                        state = EXPECT_COLON
                        return JsonToken.KEY
                    }
                    afterValue()
                    return JsonToken.STRING
                }
                HIGH -> {
                    val n = Utf8.sequenceLength(w, p, e)
                    if (n < 0) break
                    p += n
                }
                else -> break
            }
            if (p - start > max) break
        }
        inString = true
        stringIsKey = isKey
        partsEmitted = false
        stringBytes = 0
        unitLength = 0
        partStart = at
        return stringBody()
    }

    /** Slow path; resumable after each emitted [JsonToken.STRING_PART]. */
    private fun stringBody(): Int {
        scratch.clear()
        if (partStart < 0) partStart = if (unitLength > 0) unitOffset else offset(pos)
        if (unitLength > 0) {
            scratch.writeBytes(unit, 0, unitLength)
            stringBytes += unitLength
            unitLength = 0
        }
        val max = if (stringIsKey) limits.maxRawKeyBytes.toLong() else limits.maxRawStringBytes
        while (true) {
            if (pos == end && !refill()) malformed("unterminated string")
            val b = window[pos].toInt() and 0xFF
            when (CLASS[b].toInt()) {
                PLAIN -> {
                    val runStart = pos
                    val e = end
                    var p = runStart + 1
                    while (p < e && CLASS[window[p].toInt() and 0xFF] == PLAIN_BYTE) p++
                    var n = p - runStart
                    if (stringBytes + n > max) {
                        fail(RejectReason.LIMIT_EXCEEDED, offset(runStart) + (max - stringBytes), tooLong())
                    }
                    if (!stringIsKey) {
                        val room = stringChunkBytes - scratch.size
                        if (room == 0) return emitPart()
                        if (n > room) n = room
                    }
                    scratch.writeBytes(window, runStart, n)
                    stringBytes += n
                    pos = runStart + n
                }
                QUOTE -> {
                    pos++
                    inString = false
                    setView(scratch.array, 0, scratch.size)
                    byteOffset = partStart
                    if (stringIsKey) {
                        isLastPart = false
                        state = EXPECT_COLON
                        return JsonToken.KEY
                    }
                    afterValue()
                    isLastPart = partsEmitted
                    return if (partsEmitted) JsonToken.STRING_PART else JsonToken.STRING
                }
                BACKSLASH -> {
                    unitOffset = offset(pos)
                    pos++
                    escape()
                    if (!acceptUnit(max)) return emitPart()
                }
                HIGH -> {
                    unitOffset = offset(pos)
                    multiByte(b)
                    if (!acceptUnit(max)) return emitPart()
                }
                else -> malformed("unescaped control character")
            }
        }
    }

    /** Appends the decoded [unit]; false if it must wait for the next part. */
    private fun acceptUnit(max: Long): Boolean {
        if (stringBytes + unitLength > max) fail(RejectReason.LIMIT_EXCEEDED, unitOffset, tooLong())
        if (!stringIsKey && scratch.size + unitLength > stringChunkBytes) return false
        scratch.writeBytes(unit, 0, unitLength)
        stringBytes += unitLength
        unitLength = 0
        return true
    }

    private fun emitPart(): Int {
        partsEmitted = true
        setView(scratch.array, 0, scratch.size)
        byteOffset = partStart
        isLastPart = false
        partStart = -1
        return JsonToken.STRING_PART
    }

    private fun tooLong(): String {
        val what = if (stringIsKey) "key longer than ${limits.maxKeyBytes} bytes" else "string longer than ${limits.maxStringBytes} bytes"
        return if (limits.nfc) "$what after NFC normalization" else what
    }

    /** After a backslash: decodes the escape into [unit]. */
    private fun escape() {
        val b = nextByte()
        if (b < 0) malformed("unterminated escape")
        val decoded = when (b) {
            '"'.code, '\\'.code, '/'.code -> b
            'b'.code -> 0x08
            'f'.code -> 0x0C
            'n'.code -> 0x0A
            'r'.code -> 0x0D
            't'.code -> 0x09
            'u'.code -> -1
            else -> fail(RejectReason.MALFORMED_JSON, offset(pos) - 1, "invalid escape")
        }
        if (decoded >= 0) {
            unit[0] = decoded.toByte()
            unitLength = 1
            return
        }
        val code = hex4()
        val codePoint = when (code) {
            in 0xD800..0xDBFF -> {
                if (nextByte() != '\\'.code || nextByte() != 'u'.code) {
                    fail(RejectReason.INVALID_STRING, unitOffset, "high surrogate without a low surrogate")
                }
                val low = hex4()
                if (low !in 0xDC00..0xDFFF) fail(RejectReason.INVALID_STRING, unitOffset, "high surrogate without a low surrogate")
                0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00)
            }
            in 0xDC00..0xDFFF -> fail(RejectReason.INVALID_STRING, unitOffset, "lone low surrogate")
            else -> code
        }
        unitLength = encodeUtf8(codePoint, unit)
    }

    private fun hex4(): Int {
        var v = 0
        repeat(4) {
            if (pos == end && !refill()) malformed("truncated \\u escape")
            val c = window[pos].toInt() and 0xFF
            val d = when (c) {
                in '0'.code..'9'.code -> c - '0'.code
                in 'a'.code..'f'.code -> c - 'a'.code + 10
                in 'A'.code..'F'.code -> c - 'A'.code + 10
                else -> malformed("invalid hex digit")
            }
            pos++
            v = (v shl 4) or d
        }
        return v
    }

    /** Reads one multi-byte UTF-8 character (lead byte [b0] at [pos]) into [unit], across refills. */
    private fun multiByte(b0: Int) {
        val n = Utf8.leadLength(b0)
        if (n < 0) fail(RejectReason.INVALID_UTF8, unitOffset, "invalid UTF-8 in a string")
        unit[0] = b0.toByte()
        pos++
        for (i in 1 until n) {
            if (pos == end && !refill()) fail(RejectReason.INVALID_UTF8, unitOffset, "truncated UTF-8 sequence")
            val b = window[pos].toInt() and 0xFF
            if (!Utf8.isValidTrailByte(b0, i, b)) fail(RejectReason.INVALID_UTF8, unitOffset, "invalid UTF-8 in a string")
            unit[i] = b.toByte()
            pos++
        }
        unitLength = n
    }

    // ---- input ----

    private fun skipByteOrderMark() {
        if (pos == end && !refill()) return
        if (window[pos] != 0xEF.toByte()) return
        val at = offset(pos)
        pos++
        for (expected in BOM_TAIL) {
            if (pos == end && !refill()) fail(RejectReason.MALFORMED_JSON, at, "unexpected byte")
            if (window[pos] != expected) fail(RejectReason.MALFORMED_JSON, at, "unexpected byte")
            pos++
        }
    }

    /** Next non-whitespace byte without consuming it, or -1 at the end of input. */
    private fun skipWhitespace(): Int {
        val w = window
        var p = pos
        while (true) {
            val e = end
            while (p < e) {
                val b = w[p].toInt() and 0xFF
                if (b > 0x20 || (b != 0x20 && b != 0x0A && b != 0x0D && b != 0x09)) {
                    pos = p
                    return b
                }
                p++
            }
            pos = p
            if (!refill()) return -1
            p = pos
        }
    }

    private fun peek(): Int {
        if (pos == end && !refill()) return -1
        return window[pos].toInt() and 0xFF
    }

    private fun nextByte(): Int {
        if (pos == end && !refill()) return -1
        return window[pos++].toInt() and 0xFF
    }

    /** Refills the window; call only when it's used up. Returns false at the end of input. */
    private fun refill(): Boolean {
        if (eof) return false
        if (numberStart >= 0) captureNumber()
        windowBase += end
        pos = 0
        end = 0
        var n: Int
        do {
            n = input.read(window, 0, window.size)
        } while (n == 0)
        if (n < 0) {
            eof = true
            return false
        }
        end = n
        if (windowBase + n > limits.maxDocumentBytes) {
            fail(RejectReason.LIMIT_EXCEEDED, limits.maxDocumentBytes, "document longer than ${limits.maxDocumentBytes} bytes")
        }
        return true
    }

    private fun offset(windowIndex: Int): Long = windowBase + windowIndex

    private fun setView(array: ByteArray, from: Int, count: Int) {
        bytes = array
        offset = from
        length = count
    }

    private fun simpleToken(token: Int, at: Long): Int {
        setView(window, 0, 0)
        byteOffset = at
        isLastPart = false
        return token
    }

    private fun malformed(detail: String): Nothing = fail(RejectReason.MALFORMED_JSON, offset(pos), detail)

    private fun fail(reason: RejectReason, at: Long, detail: String): Nothing {
        state = FAILED
        inString = false
        throw DocumentRejectedException(reason, at, detail = detail)
    }

    companion object {
        const val DEFAULT_WINDOW_BYTES: Int = 64 * 1024
        const val DEFAULT_STRING_CHUNK_BYTES: Int = 64 * 1024

        private const val START = 0
        private const val EXPECT_VALUE = 1
        private const val EXPECT_VALUE_OR_END = 2
        private const val EXPECT_KEY_OR_END = 3
        private const val EXPECT_KEY = 4
        private const val EXPECT_COLON = 5
        private const val EXPECT_COMMA_OR_END = 6
        private const val EXPECT_EOF = 7
        private const val DONE = 8
        private const val FAILED = 9

        private const val PLAIN = 0
        private const val QUOTE = 1
        private const val BACKSLASH = 2
        private const val CONTROL = 3
        private const val HIGH = 4
        private const val PLAIN_BYTE: Byte = 0

        /** Byte classes inside strings. */
        private val CLASS = ByteArray(256) { b ->
            when {
                b < 0x20 -> CONTROL
                b == '"'.code -> QUOTE
                b == '\\'.code -> BACKSLASH
                b >= 0x80 -> HIGH
                else -> PLAIN
            }.toByte()
        }

        private const val EXPONENT_SATURATION = 1L shl 40

        private const val ONES = 0x0101010101010101L
        private const val HIGH_BITS = -0x7f7f7f7f7f7f7f80L // 0x8080808080808080
        private const val QUOTES = 0x2222222222222222L
        private const val BACKSLASHES = 0x5C5C5C5C5C5C5C5CL
        private const val SPACES = 0x2020202020202020L

        /** Masked with [HIGH_BITS], the lowest set bit marks the lowest zero byte of [x]; higher bits may be borrow noise. */
        private fun hasZeroByte(x: Long): Long = (x - ONES) and x.inv()

        private val TRUE_BYTES = "true".toByteArray()
        private val FALSE_BYTES = "false".toByteArray()
        private val NULL_BYTES = "null".toByteArray()
        private val BOM_TAIL = byteArrayOf(0xBB.toByte(), 0xBF.toByte())

        /** Writes the UTF-8 form of a Unicode scalar value; returns its length. */
        fun encodeUtf8(codePoint: Int, out: ByteArray): Int = when {
            codePoint < 0x80 -> {
                out[0] = codePoint.toByte()
                1
            }
            codePoint < 0x800 -> {
                out[0] = (0xC0 or (codePoint ushr 6)).toByte()
                out[1] = (0x80 or (codePoint and 0x3F)).toByte()
                2
            }
            codePoint < 0x10000 -> {
                out[0] = (0xE0 or (codePoint ushr 12)).toByte()
                out[1] = (0x80 or ((codePoint ushr 6) and 0x3F)).toByte()
                out[2] = (0x80 or (codePoint and 0x3F)).toByte()
                3
            }
            else -> {
                out[0] = (0xF0 or (codePoint ushr 18)).toByte()
                out[1] = (0x80 or ((codePoint ushr 12) and 0x3F)).toByte()
                out[2] = (0x80 or ((codePoint ushr 6) and 0x3F)).toByte()
                out[3] = (0x80 or (codePoint and 0x3F)).toByte()
                4
            }
        }
    }
}
