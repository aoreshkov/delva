package app.oreshkov.delva.internal.canon

import app.oreshkov.delva.internal.bytes.GrowableBytes

/**
 * Canonical, order-preserving number keys, computed from the JSON number text without `BigDecimal`
 * and without allocation.
 *
 * A number is normalized to `0.d₁d₂…dₙ × 10^e` with `d₁ ≠ 0` and no trailing zeros (`1` = `1.0` = `10e-1`,
 * `-0` = `0`). The normalized decimal exponent `e` must fit an `Int`; zero is valid with any exponent.
 *
 * NumberKey layout (unsigned byte order equals numeric order):
 * ```
 * zero:      0x02
 * positive:  0x03 | E + 2³¹ : u32 big-endian | pairs | 0x00
 * negative:  0x01 | ~(E + 2³¹) : u32 BE      | ~pairs | 0xFF
 * ```
 * The digits are regrouped in base 100: `value = 0.p₁p₂…pₘ × 100^E` with `p₁ ≠ 0` and `pₘ ≠ 0`. Each pair `p`
 * (0–99) is stored as `2·p + 1`, so every pair byte is above the `0x00` terminator.
 */
internal object CanonicalNumber {
    const val NEGATIVE: Int = 0x01
    const val ZERO: Int = 0x02
    const val POSITIVE: Int = 0x03

    /** Returned when the text isn't a JSON number. Nothing is written. */
    const val MALFORMED: Int = -1

    /** Returned when the normalized exponent doesn't fit an `Int`. Nothing is written. */
    const val OUT_OF_RANGE: Int = -2

    /** Largest decimal exponent magnitude tracked while parsing; anything larger is out of range anyway. */
    private const val EXPONENT_SATURATION = 1L shl 40

    /**
     * Appends the NumberKey of the JSON number text `text[offset, offset + length)` to [out].
     * Returns the key length, or [MALFORMED] / [OUT_OF_RANGE] (then [out] is unchanged).
     */
    fun writeKey(text: ByteArray, offset: Int, length: Int, out: GrowableBytes): Int {
        val end = offset + length
        var p = offset
        if (p == end) return MALFORMED
        val negative = text[p] == '-'.code.toByte()
        if (negative) p++

        // Integer part: 0 | [1-9][0-9]*
        val intStart = p
        if (p == end || !isDigit(text[p])) return MALFORMED
        if (text[p] == '0'.code.toByte()) p++ else while (p < end && isDigit(text[p])) p++
        val intEnd = p

        // Fraction: \.[0-9]+
        var fracStart = p
        var fracEnd = p
        if (p < end && text[p] == '.'.code.toByte()) {
            p++
            fracStart = p
            while (p < end && isDigit(text[p])) p++
            if (p == fracStart) return MALFORMED
            fracEnd = p
        }

        // Exponent: [eE][+-]?[0-9]+, parsed with saturation.
        var exponent = 0L
        if (p < end && (text[p] == 'e'.code.toByte() || text[p] == 'E'.code.toByte())) {
            p++
            var expNegative = false
            if (p < end && (text[p] == '+'.code.toByte() || text[p] == '-'.code.toByte())) {
                expNegative = text[p] == '-'.code.toByte()
                p++
            }
            val digitsStart = p
            while (p < end && isDigit(text[p])) {
                exponent = minOf(exponent * 10 + (text[p] - '0'.code.toByte()), EXPONENT_SATURATION)
                p++
            }
            if (p == digitsStart) return MALFORMED
            if (expNegative) exponent = -exponent
        }
        if (p != end) return MALFORMED

        // Significant digits: the virtual digit string is int digits followed by fraction digits.
        val intDigits = intEnd - intStart
        val first = firstNonZero(text, intStart, intEnd, fracStart, fracEnd)
        if (first < 0) {
            out.writeByte(ZERO)
            return 1
        }
        val last = lastNonZero(text, intStart, intEnd, fracStart, fracEnd)

        // value = 0.D[first..last] × 10^e with e = intDigits - first + exponent
        val e = intDigits - first + exponent
        if (e < Int.MIN_VALUE || e > Int.MAX_VALUE) return OUT_OF_RANGE

        // Base 100: an odd e gets a leading zero digit so that the exponent halves exactly.
        val leadingZero = e and 1L != 0L
        val e100 = (e + (e and 1L)) / 2
        val flip = if (negative) 0xFF else 0x00

        val start = out.mark()
        out.writeByte(if (negative) NEGATIVE else POSITIVE)
        out.writeIntBE((e100 + (1L shl 31)).toInt() xor (if (negative) -1 else 0))

        var pending = -1 // first digit of an incomplete pair
        if (leadingZero) pending = 0
        var i = first
        while (i <= last) {
            val d = digitAt(text, intStart, intDigits, fracStart, i)
            if (pending < 0) {
                pending = d
            } else {
                out.writeByte((2 * (pending * 10 + d) + 1) xor flip)
                pending = -1
            }
            i++
        }
        if (pending >= 0) out.writeByte((2 * (pending * 10) + 1) xor flip)
        out.writeByte(flip)
        return out.size - start
    }

    /** Appends the NumberKey of [value]; identical to [writeKey] over `value.toString()`. */
    fun writeKey(value: Long, out: GrowableBytes): Int {
        if (value == 0L) {
            out.writeByte(ZERO)
            return 1
        }
        val negative = value < 0
        // Magnitude as an unsigned 64-bit value (covers Long.MIN_VALUE).
        var magnitude = if (negative) -value else value
        var trailingZeros = 0
        while (java.lang.Long.remainderUnsigned(magnitude, 10) == 0L) {
            magnitude = java.lang.Long.divideUnsigned(magnitude, 10)
            trailingZeros++
        }
        val digits = unsignedDigitCount(magnitude)
        val e = digits + trailingZeros
        val flip = if (negative) 0xFF else 0x00

        val start = out.mark()
        out.writeByte(if (negative) NEGATIVE else POSITIVE)
        out.writeIntBE(((e + (e and 1)) / 2 + (1L shl 31)).toInt() xor (if (negative) -1 else 0))

        var pending = if (e and 1 != 0) 0 else -1
        var power = UNSIGNED_POWERS_OF_TEN[digits - 1]
        repeat(digits) {
            val d = java.lang.Long.divideUnsigned(magnitude, power).toInt()
            magnitude -= d * power
            power /= 10
            if (pending < 0) {
                pending = d
            } else {
                out.writeByte((2 * (pending * 10 + d) + 1) xor flip)
                pending = -1
            }
        }
        if (pending >= 0) out.writeByte((2 * (pending * 10) + 1) xor flip)
        out.writeByte(flip)
        return out.size - start
    }

    /**
     * If the text is a canonical JSON integer (`0` or `-?[1-9][0-9]*`, not `-0`) that fits a `Long`, stores it in
     * `out[outIndex]` and returns true (`INT`). Otherwise returns false; the number keeps its spelling (`NUM`).
     */
    fun parseCanonicalLong(text: ByteArray, offset: Int, length: Int, out: LongArray, outIndex: Int): Boolean {
        val end = offset + length
        var p = offset
        if (p == end) return false
        val negative = text[p] == '-'.code.toByte()
        if (negative) p++
        if (p == end || !isDigit(text[p])) return false
        if (text[p] == '0'.code.toByte()) {
            if (p + 1 != end || negative) return false
            out[outIndex] = 0
            return true
        }
        if (end - p > 19) return false
        // Accumulate negatively so that Long.MIN_VALUE fits.
        var acc = 0L
        while (p < end) {
            val b = text[p]
            if (!isDigit(b)) return false
            val d = b - '0'.code.toByte()
            if (acc < (Long.MIN_VALUE + d) / 10) return false
            acc = acc * 10 - d
            p++
        }
        if (!negative) {
            if (acc == Long.MIN_VALUE) return false
            acc = -acc
        }
        out[outIndex] = acc
        return true
    }

    private fun isDigit(b: Byte): Boolean = b >= '0'.code.toByte() && b <= '9'.code.toByte()

    private fun digitAt(text: ByteArray, intStart: Int, intDigits: Int, fracStart: Int, virtualIndex: Int): Int {
        val index = if (virtualIndex < intDigits) intStart + virtualIndex else fracStart + (virtualIndex - intDigits)
        return text[index] - '0'.code.toByte()
    }

    private fun firstNonZero(text: ByteArray, intStart: Int, intEnd: Int, fracStart: Int, fracEnd: Int): Int {
        for (i in intStart until intEnd) if (text[i] != '0'.code.toByte()) return i - intStart
        for (i in fracStart until fracEnd) if (text[i] != '0'.code.toByte()) return (intEnd - intStart) + (i - fracStart)
        return -1
    }

    private fun lastNonZero(text: ByteArray, intStart: Int, intEnd: Int, fracStart: Int, fracEnd: Int): Int {
        for (i in fracEnd - 1 downTo fracStart) if (text[i] != '0'.code.toByte()) return (intEnd - intStart) + (i - fracStart)
        for (i in intEnd - 1 downTo intStart) if (text[i] != '0'.code.toByte()) return i - intStart
        return -1
    }

    /** 10⁰ … 10¹⁹ as unsigned 64-bit values. */
    private val UNSIGNED_POWERS_OF_TEN = LongArray(20).also {
        it[0] = 1
        for (i in 1 until 20) it[i] = it[i - 1] * 10
    }

    private fun unsignedDigitCount(value: Long): Int {
        var count = 1
        while (count < 20 && java.lang.Long.compareUnsigned(value, UNSIGNED_POWERS_OF_TEN[count]) >= 0) count++
        return count
    }
}
