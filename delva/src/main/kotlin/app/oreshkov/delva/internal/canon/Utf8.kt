package app.oreshkov.delva.internal.canon

/**
 * Strict UTF-8 per RFC 3629: rejects overlong forms, UTF-8-encoded surrogates, code points above U+10FFFF,
 * and truncated sequences.
 */
internal object Utf8 {
    /** Returns the index of the first byte of the first invalid sequence, or -1 if the whole range is valid. */
    fun firstInvalid(bytes: ByteArray, offset: Int, length: Int): Int {
        val end = offset + length
        var p = offset
        while (p < end) {
            val b0 = bytes[p].toInt() and 0xFF
            if (b0 < 0x80) {
                p++
                continue
            }
            val sequenceLength = sequenceLength(bytes, p, end)
            if (sequenceLength < 0) return p
            p += sequenceLength
        }
        return -1
    }

    fun isValid(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean =
        firstInvalid(bytes, offset, length) < 0

    /**
     * Length of the valid sequence starting at [index] (1–4), or -1 if the bytes at [index] (before [end]) don't
     * form a complete valid sequence.
     */
    fun sequenceLength(bytes: ByteArray, index: Int, end: Int): Int {
        val b0 = bytes[index].toInt() and 0xFF
        if (b0 < 0x80) return 1
        // Allowed range of the second byte depends on the lead byte (Table 3-7 of the Unicode standard).
        val lower: Int
        val upper: Int
        val length: Int
        when {
            b0 in 0xC2..0xDF -> { length = 2; lower = 0x80; upper = 0xBF }
            b0 == 0xE0 -> { length = 3; lower = 0xA0; upper = 0xBF }
            b0 in 0xE1..0xEC || b0 == 0xEE || b0 == 0xEF -> { length = 3; lower = 0x80; upper = 0xBF }
            b0 == 0xED -> { length = 3; lower = 0x80; upper = 0x9F }
            b0 == 0xF0 -> { length = 4; lower = 0x90; upper = 0xBF }
            b0 in 0xF1..0xF3 -> { length = 4; lower = 0x80; upper = 0xBF }
            b0 == 0xF4 -> { length = 4; lower = 0x80; upper = 0x8F }
            else -> return -1
        }
        if (end - index < length) return -1
        val b1 = bytes[index + 1].toInt() and 0xFF
        if (b1 < lower || b1 > upper) return -1
        for (i in 2 until length) {
            if (bytes[index + i].toInt() and 0xC0 != 0x80) return -1
        }
        return length
    }

    /** Length (2–4) of the sequence a lead byte [b0] (at least `0x80`) starts, or -1 if it can't start one. */
    fun leadLength(b0: Int): Int = when {
        b0 in 0xC2..0xDF -> 2
        b0 in 0xE0..0xEF -> 3
        b0 in 0xF0..0xF4 -> 4
        else -> -1
    }

    /**
     * Whether [b] is valid at position [index] (1–3) of a sequence that starts with the lead byte [b0]. The second
     * byte's range depends on the lead byte; later bytes are plain continuation bytes.
     */
    fun isValidTrailByte(b0: Int, index: Int, b: Int): Boolean {
        if (index > 1) return b and 0xC0 == 0x80
        return when (b0) {
            0xE0 -> b in 0xA0..0xBF
            0xED -> b in 0x80..0x9F
            0xF0 -> b in 0x90..0xBF
            0xF4 -> b in 0x80..0x8F
            else -> b in 0x80..0xBF
        }
    }

    /** True for bytes that continue a multi-byte sequence (`10xxxxxx`); a code point never starts at one. */
    fun isContinuation(b: Byte): Boolean = b.toInt() and 0xC0 == 0x80
}
