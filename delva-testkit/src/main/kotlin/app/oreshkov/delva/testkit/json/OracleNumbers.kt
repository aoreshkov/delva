package app.oreshkov.delva.testkit.json

import java.math.BigInteger

/**
 * Number rules written independently of the engine: string and `BigInteger` arithmetic only.
 *
 * A number `±0.d₁…dₙ × 10^e` (d₁ ≠ 0, no trailing zeros) is in range when `e` fits a 32-bit signed integer.
 * Zero is always in range.
 */
object OracleNumbers {
    private val grammar = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private val intMin = BigInteger.valueOf(Int.MIN_VALUE.toLong())
    private val intMax = BigInteger.valueOf(Int.MAX_VALUE.toLong())

    fun isJsonNumber(text: String): Boolean = grammar.matches(text)

    private class Parts(val negative: Boolean, val digits: String, val exponent: BigInteger)

    private fun parts(text: String): Parts {
        val m = checkNotNull(grammar.matchEntire(text)) { "not a JSON number: $text" }
        val intPart = m.groupValues[1]
        val fracPart = m.groupValues[2].removePrefix(".")
        val expPart = m.groupValues[3].drop(1)
        val all = intPart + fracPart
        val first = all.indexOfFirst { it != '0' }
        if (first < 0) return Parts(false, "", BigInteger.ZERO)
        val last = all.indexOfLast { it != '0' }
        val exp = if (expPart.isEmpty()) BigInteger.ZERO else BigInteger(expPart.removePrefix("+"))
        return Parts(text.startsWith("-"), all.substring(first, last + 1), BigInteger.valueOf((intPart.length - first).toLong()) + exp)
    }

    fun inRange(text: String): Boolean {
        val p = parts(text)
        return p.digits.isEmpty() || (p.exponent >= intMin && p.exponent <= intMax)
    }

    /** `0`, or `[-]0.<digits>e<exponent>`: equal strings exactly for equal values. */
    fun canonical(text: String): String {
        val p = parts(text)
        if (p.digits.isEmpty()) return "0"
        return (if (p.negative) "-" else "") + "0." + p.digits + "e" + p.exponent
    }

    /** Numeric comparison of two JSON numbers. */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        val sa = if (pa.digits.isEmpty()) 0 else if (pa.negative) -1 else 1
        val sb = if (pb.digits.isEmpty()) 0 else if (pb.negative) -1 else 1
        if (sa != sb) return sa.compareTo(sb)
        if (sa == 0) return 0
        val magnitude = pa.exponent.compareTo(pb.exponent).takeIf { it != 0 } ?: compareDigits(pa.digits, pb.digits)
        return if (sa < 0) -magnitude else magnitude
    }

    private fun compareDigits(a: String, b: String): Int {
        for (i in 0 until maxOf(a.length, b.length)) {
            val da = if (i < a.length) a[i] else '0'
            val db = if (i < b.length) b[i] else '0'
            if (da != db) return da.compareTo(db)
        }
        return 0
    }
}
