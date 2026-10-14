package app.oreshkov.delva.internal.canon

import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.testkit.Golden
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.Gens
import app.oreshkov.delva.testkit.property.forAll
import app.oreshkov.delva.testkit.toHex
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CanonicalNumberTest {
    /** A decimal value `±0.digits × 10^exponent` plus a way to spell it as JSON in many equivalent ways. */
    private class Value(val negative: Boolean, val digits: String, val exponent: Long) {
        init {
            require(digits.isEmpty() || (digits.first() != '0' && digits.last() != '0'))
        }

        val isZero get() = digits.isEmpty()

        fun toBigDecimal(): BigDecimal =
            if (isZero) BigDecimal.ZERO
            else BigDecimal(BigInteger(digits), (digits.length - exponent).toInt()).let { if (negative) it.negate() else it }

        /** A random valid JSON spelling of this value. */
        fun spell(random: Random): String {
            if (isZero) {
                val sign = if (random.nextBoolean()) "-" else ""
                val frac = if (random.nextBoolean()) "." + "0".repeat(random.nextInt(1, 4)) else ""
                val exp = if (random.nextBoolean()) "e" + random.nextLong(-3_000_000_000, 3_000_000_000) else ""
                return "${sign}0$frac$exp"
            }
            // Place the decimal point after `pointAt` digits (0 = "0.xxx"), with extra zeros around the digits.
            val padded = "0".repeat(random.nextInt(0, 3)) + digits + "0".repeat(random.nextInt(0, 3))
            val leadingPad = padded.indexOf(digits.first())
            val pointAt = random.nextInt(0, padded.length + 1)
            // value = 0.padded × 10^(exponent + leadingPad) = padded[0, pointAt).padded[pointAt..] × 10^(exp - pointAt)
            val textExponent = exponent + leadingPad - pointAt
            var intPart = padded.substring(0, pointAt).trimStart('0')
            if (intPart.isEmpty()) intPart = "0"
            val fracPart = padded.substring(pointAt)
            val body = if (fracPart.isEmpty()) intPart else "$intPart.$fracPart"
            val expText = when {
                textExponent == 0L && random.nextBoolean() -> ""
                else -> {
                    val e = if (random.nextBoolean()) "e" else "E"
                    val sign = when {
                        textExponent < 0 -> "-"
                        random.nextBoolean() -> "+"
                        else -> ""
                    }
                    e + sign + "0".repeat(random.nextInt(0, 3)) + kotlin.math.abs(textExponent)
                }
            }
            return (if (negative) "-" else "") + body + expText
        }

        override fun toString() = "${if (negative) "-" else ""}0.${digits.ifEmpty { "0" }}e$exponent"
    }

    private val values: Gen<Value> = Gen { random, size ->
        if (random.nextInt(20) == 0) return@Gen Value(false, "", 0)
        val length = random.nextInt(1, 2 + minOf(size, 60))
        val digits = CharArray(length) { '0' + random.nextInt(10) }
        digits[0] = '1' + random.nextInt(9)
        digits[length - 1] = '1' + random.nextInt(9)
        val exponent = when (random.nextInt(3)) {
            0 -> random.nextLong(-5, 6)
            1 -> random.nextLong(-2L * size - 1, 2L * size + 2)
            else -> random.nextLong(-1_000_000_000, 1_000_000_000)
        }
        Value(random.nextBoolean(), String(digits), exponent)
    }

    private fun key(text: String): ByteArray {
        val out = GrowableBytes()
        val bytes = text.toByteArray()
        val result = CanonicalNumber.writeKey(bytes, 0, bytes.size, out)
        check(result == out.size) { "writeKey($text) returned $result" }
        return out.toByteArray()
    }

    private fun result(text: String): Int {
        val bytes = text.toByteArray()
        val out = GrowableBytes()
        out.writeByte(0x77)
        val result = CanonicalNumber.writeKey(bytes, 0, bytes.size, out)
        if (result < 0) assertEquals(1, out.size, "nothing may be written for $text")
        return result
    }

    @Test
    fun `key order equals numeric order, and keys are equal exactly when values are`() {
        forAll("number key order", Gens.pair(Gens.pair(values, values), Gens.long()), iterations = 3000) { (pair, seed) ->
            val (a, b) = pair
            val random = Random(seed)
            val textA = a.spell(random)
            val textB = b.spell(random)
            val expected = a.toBigDecimal().compareTo(b.toBigDecimal()).coerceIn(-1, 1)
            val actual = compareUnsigned(key(textA), key(textB)).coerceIn(-1, 1)
            assertEquals(expected, actual, "compare($textA, $textB)")
        }
    }

    @Test
    fun `every spelling of a value gives the same key, and it decodes to the value`() {
        forAll("number spellings", Gens.pair(values, Gens.long()), iterations = 2000) { (value, seed) ->
            val random = Random(seed)
            val first = key(value.spell(random))
            repeat(5) {
                val text = value.spell(random)
                assertEquals(first.toHex(), key(text).toHex(), "spelling $text of $value")
            }
            assertEquals(0, decode(first).compareTo(value.toBigDecimal()), "decode of $value")
        }
    }

    @Test
    fun `adjacent values that differ only in the last digit pair order correctly`() {
        // Prefix property: 0.12 < 0.121 < 0.13, and the negatives in reverse.
        val ascending = listOf("-0.13", "-0.121", "-0.12", "-0.0000012", "0", "0.0000012", "0.12", "0.121", "0.13", "1", "1.5", "10", "99", "100")
        for (i in 0 until ascending.size - 1) {
            assertTrue(compareUnsigned(key(ascending[i]), key(ascending[i + 1])) < 0, "${ascending[i]} < ${ascending[i + 1]}")
        }
    }

    @Test
    fun `the Long path produces the same bytes as the text path`() {
        forAll("number key from long", Gens.edgyLong(), iterations = 3000) { value ->
            val out = GrowableBytes()
            val length = CanonicalNumber.writeKey(value, out)
            assertEquals(out.size, length)
            assertEquals(key(value.toString()).toHex(), out.toByteArray().toHex(), "value $value")
        }
    }

    @Test
    fun `exponent range is the Int range, and zero accepts any exponent`() {
        // "0.1eX" normalizes to exponent X.
        assertTrue(result("0.1e2147483647") > 0)
        assertEquals(CanonicalNumber.OUT_OF_RANGE, result("0.1e2147483648"))
        assertTrue(result("0.1e-2147483648") > 0)
        assertEquals(CanonicalNumber.OUT_OF_RANGE, result("0.1e-2147483649"))
        // "1eX" normalizes to X + 1.
        assertEquals(CanonicalNumber.OUT_OF_RANGE, result("1e2147483647"))
        assertTrue(result("1e2147483646") > 0)
        assertEquals(CanonicalNumber.OUT_OF_RANGE, result("-1e99999999999999999999999999"))
        assertEquals(CanonicalNumber.OUT_OF_RANGE, result("1e-99999999999999999999999999"))
        // Leading zeros in the exponent are skipped, not counted toward saturation.
        assertEquals(key("1e5").toHex(), key("1e000000000000000000000000000005").toHex())
        assertEquals(key("0.1e2147483647").toHex(), key("1000e0000000000000000002147483643").toHex())
        for (zero in listOf("0", "-0", "0.0", "-0.0e-5", "0e3000000000", "0E-99999999999999999999", "-0.000e+7")) {
            assertEquals("02", key(zero).toHex(), zero)
        }
    }

    @Test
    fun `malformed number text is rejected without writing`() {
        for (text in listOf("", "-", "+1", "01", "-01", "1.", ".5", "1e", "1e+", "1E-", "0x10", "1.5.5", "1e5e5", " 1", "1 ", "NaN", "Infinity", "--1", "1_000")) {
            assertEquals(CanonicalNumber.MALFORMED, result(text), "'$text'")
        }
    }

    @Test
    fun `canonical long detection`() {
        val out = LongArray(1)
        fun canonical(text: String): Long? {
            val bytes = text.toByteArray()
            return if (CanonicalNumber.parseCanonicalLong(bytes, 0, bytes.size, out, 0)) out[0] else null
        }
        assertEquals(0L, canonical("0"))
        assertEquals(7L, canonical("7"))
        assertEquals(-7L, canonical("-7"))
        assertEquals(Long.MAX_VALUE, canonical("9223372036854775807"))
        assertEquals(Long.MIN_VALUE, canonical("-9223372036854775808"))
        for (text in listOf("-0", "00", "07", "1.0", "1e3", "9223372036854775808", "-9223372036854775809", "10000000000000000000", "", "-", "1a")) {
            assertEquals(null, canonical(text), "'$text'")
        }
        forAll("canonical long round trip", Gens.edgyLong(), iterations = 2000) { value ->
            assertEquals(value, canonical(value.toString()))
            assertFalse(CanonicalNumber.parseCanonicalLong("$value.0".toByteArray(), 0, "$value.0".length, out, 0))
        }
    }

    @Test
    fun `golden number keys`() {
        val texts = listOf(
            "0", "-0", "1", "-1", "1.0", "10", "100", "0.5", "0.05", "-0.05", "123", "123.456", "-123.456", "7",
            "1e0", "1E+2", "1e-2", "12345678901234567890", "-9223372036854775808", "3.141592653589793",
            "0.1e2147483647", "0.1e-2147483648", "-0.1e2147483647", "99", "0.99", "1e1", "1e-1",
        )
        Golden.verify(
            "number-keys.txt",
            header = "NumberKey bytes (hex) per JSON number text. Changing any line breaks stored terms and hashes.",
            actual = texts.map { "$it ${key(it).toHex()}" },
        )
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int = java.util.Arrays.compareUnsigned(a, b)

    /** Test-only decoder: NumberKey bytes back to a BigDecimal. */
    private fun decode(key: ByteArray): BigDecimal {
        val signClass = key[0].toInt()
        if (signClass == CanonicalNumber.ZERO) {
            assertEquals(1, key.size)
            return BigDecimal.ZERO
        }
        val negative = signClass == CanonicalNumber.NEGATIVE
        val flip = if (negative) 0xFF else 0
        val biased = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or ((key[1 + i].toInt() xor flip) and 0xFF).toLong() }
        val e100 = biased - (1L shl 31)
        val digits = StringBuilder()
        var i = 5
        while ((key[i].toInt() xor flip) and 0xFF != 0) {
            val pair = (((key[i].toInt() xor flip) and 0xFF) - 1) / 2
            digits.append(pair / 10).append(pair % 10)
            i++
        }
        assertEquals(key.size - 1, i, "terminator must be last")
        // value = 0.digits × 100^e100 = digits × 10^(2·e100 − len)
        val magnitude = BigDecimal(BigInteger(digits.toString()), (digits.length - 2 * e100).toInt())
        return if (negative) magnitude.negate() else magnitude
    }
}
