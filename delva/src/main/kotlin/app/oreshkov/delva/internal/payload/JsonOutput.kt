package app.oreshkov.delva.internal.payload

import java.io.OutputStream

/**
 * A JSON value as a sequence of events, in the call order a well-formed JSON text needs: [key] before each object
 * member's value; a string either whole ([string]) or in parts ([beginString], [stringPart]s cut on code-point
 * boundaries, [endString]). Bytes are valid UTF-8 and valid only during the call.
 */
internal interface JsonEvents {
    fun beginObject()

    fun endObject()

    fun beginArray()

    fun endArray()

    fun key(utf8: ByteArray, offset: Int, length: Int)

    fun string(utf8: ByteArray, offset: Int, length: Int)

    fun beginString()

    fun stringPart(utf8: ByteArray, offset: Int, length: Int)

    fun endString()

    /** A number in its stored spelling. */
    fun number(ascii: ByteArray, offset: Int, length: Int)

    /** A number stored as an integer (its canonical spelling). */
    fun number(value: Long)

    fun booleanValue(value: Boolean)

    fun nullValue()

    /** True once the receiver needs no more events; producers may then stop early. */
    val isDone: Boolean get() = false
}

/**
 * Streaming UTF-8 JSON writer for the output form of SPEC.md §5: no whitespace, numbers as given, strings
 * escaped minimally — `\"`, `\`, `\b`, `\f`, `\n`, `\r`, `\t`, `\u00XX` (lowercase hex) for other control characters,
 * everything else as the UTF-8 bytes it's given. No byte-order mark.
 *
 * O(1) state besides the buffer: the caller supplies a well-formed sequence of calls, and the writer only tracks
 * whether the next value needs a comma. String input must already be valid UTF-8. Reusable through [reset]; not
 * thread-safe.
 */
internal class JsonOutput(private var out: OutputStream, bufferBytes: Int = 8192) : JsonEvents {
    private val buffer = ByteArray(maxOf(bufferBytes, MIN_BUFFER))
    private var position = 0
    private var flushed = 0L
    private var needsComma = false
    private val digits = ByteArray(20)

    /** Bytes written so far, including buffered ones. */
    val bytesWritten: Long get() = flushed + position

    fun reset(out: OutputStream) {
        this.out = out
        position = 0
        flushed = 0
        needsComma = false
    }

    override fun beginObject() = open('{'.code)

    override fun endObject() = close('}'.code)

    override fun beginArray() = open('['.code)

    override fun endArray() = close(']'.code)

    override fun key(utf8: ByteArray, offset: Int, length: Int) {
        separator()
        put('"'.code)
        escaped(utf8, offset, length)
        put('"'.code)
        put(':'.code)
        needsComma = false
    }

    override fun string(utf8: ByteArray, offset: Int, length: Int) {
        separator()
        put('"'.code)
        escaped(utf8, offset, length)
        put('"'.code)
        needsComma = true
    }

    override fun beginString() {
        separator()
        put('"'.code)
    }

    override fun stringPart(utf8: ByteArray, offset: Int, length: Int) = escaped(utf8, offset, length)

    override fun endString() {
        put('"'.code)
        needsComma = true
    }

    override fun number(ascii: ByteArray, offset: Int, length: Int) {
        separator()
        raw(ascii, offset, length)
        needsComma = true
    }

    override fun number(value: Long) {
        separator()
        var p = digits.size
        var v = value
        if (v == 0L) digits[--p] = '0'.code.toByte()
        // Work with the negative magnitude, so Long.MIN_VALUE needs no special case.
        if (v > 0) v = -v
        while (v != 0L) {
            digits[--p] = ('0'.code - (v % 10).toInt()).toByte()
            v /= 10
        }
        if (value < 0) put('-'.code)
        raw(digits, p, digits.size - p)
        needsComma = true
    }

    override fun booleanValue(value: Boolean) {
        separator()
        if (value) raw(TRUE, 0, TRUE.size) else raw(FALSE, 0, FALSE.size)
        needsComma = true
    }

    override fun nullValue() {
        separator()
        raw(NULL, 0, NULL.size)
        needsComma = true
    }

    /** Writes `{...}` or `[...]`: a container a formatted layout cuts out (JsonFormat.maxLevels). */
    fun elided(array: Boolean) {
        separator()
        if (array) raw(ELIDED_ARRAY, 0, ELIDED_ARRAY.size) else raw(ELIDED_OBJECT, 0, ELIDED_OBJECT.size)
        needsComma = true
    }

    /**
     * Writes [bytes] as they are — whitespace and separators of a formatted layout — and suppresses the comma the next
     * value would otherwise get: the caller that lays the text out writes its own.
     */
    fun layout(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        raw(bytes, offset, length)
        needsComma = false
    }

    /** Writes buffered bytes to the stream and flushes it. */
    fun flush() {
        drain()
        out.flush()
    }

    private fun open(bracket: Int) {
        separator()
        put(bracket)
        needsComma = false
    }

    private fun close(bracket: Int) {
        put(bracket)
        needsComma = true
    }

    private fun separator() {
        if (needsComma) put(','.code)
    }

    private fun put(b: Int) {
        if (position == buffer.size) drain()
        buffer[position++] = b.toByte()
    }

    private fun raw(bytes: ByteArray, offset: Int, length: Int) {
        if (length > buffer.size - position) {
            drain()
            if (length > buffer.size) {
                out.write(bytes, offset, length)
                flushed += length
                return
            }
        }
        System.arraycopy(bytes, offset, buffer, position, length)
        position += length
    }

    private fun escaped(utf8: ByteArray, offset: Int, length: Int) {
        val end = offset + length
        var runStart = offset
        var p = offset
        while (p < end) {
            val b = utf8[p].toInt() and 0xFF
            val escape = ESCAPE[b]
            if (escape.toInt() == 0) {
                p++
                continue
            }
            raw(utf8, runStart, p - runStart)
            put('\\'.code)
            if (escape.toInt() == 'u'.code) {
                put('u'.code)
                put('0'.code)
                put('0'.code)
                put(HEX[b ushr 4].toInt())
                put(HEX[b and 0xF].toInt())
            } else {
                put(escape.toInt())
            }
            p++
            runStart = p
        }
        raw(utf8, runStart, end - runStart)
    }

    private fun drain() {
        if (position > 0) {
            out.write(buffer, 0, position)
            flushed += position
            position = 0
        }
    }

    companion object {
        private const val MIN_BUFFER = 16
        private val TRUE = "true".toByteArray()
        private val FALSE = "false".toByteArray()
        private val NULL = "null".toByteArray()
        private val ELIDED_OBJECT = "{...}".toByteArray()
        private val ELIDED_ARRAY = "[...]".toByteArray()
        private val HEX = "0123456789abcdef".toByteArray()

        /** Code points of [utf8] once escaped as this writer escapes it. */
        fun escapedWidth(utf8: ByteArray, offset: Int, length: Int): Int {
            var width = 0
            for (p in offset until offset + length) {
                val b = utf8[p].toInt() and 0xFF
                width += when (ESCAPE[b].toInt()) {
                    0 -> if (b and 0xC0 != 0x80) 1 else 0
                    'u'.code -> 6
                    else -> 2
                }
            }
            return width
        }

        /** 0: copy as is; otherwise the character after the backslash (`u` for `\u00XX`). */
        private val ESCAPE = ByteArray(256) { b ->
            when (b) {
                '"'.code -> '"'.code
                '\\'.code -> '\\'.code
                0x08 -> 'b'.code
                0x0C -> 'f'.code
                0x0A -> 'n'.code
                0x0D -> 'r'.code
                0x09 -> 't'.code
                in 0 until 0x20 -> 'u'.code
                else -> 0
            }.toByte()
        }
    }
}
