package app.oreshkov.delva.testkit.json

import kotlin.test.fail

/**
 * Asserts that [actual] is exactly [expected] (byte-for-byte output comparison). On mismatch the message names the
 * JSON path of the first difference, when both sides parse, and shows the text around it.
 */
fun assertJsonEquals(expected: String, actual: String, message: String? = null) {
    if (expected == actual) return
    val prefix = if (message != null) "$message: " else ""
    val expectedValue = (OracleJson.parse(expected) as? ParseResult.Accepted)?.value
    val actualValue = (OracleJson.parse(actual) as? ParseResult.Accepted)?.value
    val where = if (expectedValue != null && actualValue != null) firstDifference(expectedValue, actualValue, "$") else null
    val offset = expected.zip(actual).indexOfFirst { (a, b) -> a != b }.let { if (it < 0) minOf(expected.length, actual.length) else it }
    fail(
        buildString {
            append(prefix).append("JSON differs")
            if (where != null) append(" at ").append(where)
            append(" (character ").append(offset).append(")")
            append("\n  expected: ").append(excerpt(expected, offset))
            append("\n  actual:   ").append(excerpt(actual, offset))
        },
    )
}

fun assertJsonEquals(expected: OValue, actual: String, message: String? = null) =
    assertJsonEquals(JsonText.minified(expected), actual, message)

private fun firstDifference(a: OValue, b: OValue, path: String): String? {
    if (a == b) return null
    return when {
        a is OObj && b is OObj -> {
            for (i in 0 until maxOf(a.members.size, b.members.size)) {
                val ma = a.members.getOrNull(i)
                val mb = b.members.getOrNull(i)
                if (ma == null || mb == null || ma.first != mb.first) return "$path (member ${i}: key ${ma?.first.quoted()} vs ${mb?.first.quoted()})"
                firstDifference(ma.second, mb.second, "$path[${ma.first.quoted()}]")?.let { return it }
            }
            path
        }
        a is OArr && b is OArr -> {
            for (i in 0 until maxOf(a.items.size, b.items.size)) {
                val ea = a.items.getOrNull(i) ?: return "$path[$i] (missing in expected)"
                val eb = b.items.getOrNull(i) ?: return "$path[$i] (missing in actual)"
                firstDifference(ea, eb, "$path[$i]")?.let { return it }
            }
            path
        }
        else -> path
    }
}

private fun String?.quoted(): String = if (this == null) "none" else StringBuilder().also { JsonText.writeMinifiedString(it, this) }.toString()

private fun excerpt(text: String, offset: Int): String {
    val start = maxOf(0, offset - 40)
    val end = minOf(text.length, offset + 40)
    return (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
}
