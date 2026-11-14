package app.oreshkov.delva.intellij.store

import app.oreshkov.delva.intellij.DelvaBundle

/**
 * The numbers of [text], a JSON array of numbers such as `[0.12, -0.5, 3e-2]`, as floats. Throws
 * [IllegalArgumentException], with a message for a person, for any other text, and for a number too large for a float.
 */
internal fun parseVector(text: String): List<Float> {
    val array = text.trim()
    require(array.startsWith('[') && array.endsWith(']') && array.length >= 2) { DelvaBundle.message("vector.notArray") }
    val elements = array.substring(1, array.length - 1)
    require(elements.isNotBlank()) { DelvaBundle.message("vector.empty") }
    return elements.split(',').mapIndexed { i, element ->
        val number = element.trim()
        require(number.isNotEmpty()) { DelvaBundle.message("vector.missing", i) }
        require(JSON_NUMBER.matches(number)) { DelvaBundle.message("vector.notNumber", i, number.take(MAX_SHOWN)) }
        parseJsonFloat(number) ?: throw IllegalArgumentException(DelvaBundle.message("vector.tooLarge", i, number.take(MAX_SHOWN)))
    }
}

/** Why [text] isn't a vector [parseVector] reads, or null when it is one. */
internal fun checkVector(text: String): String? = try {
    parseVector(text)
    null
} catch (e: IllegalArgumentException) {
    e.message
}

/** [text] as a float when it's a JSON number, such as `-0.5` or `3e-2`, that a float holds, else null. */
internal fun parseJsonFloat(text: String): Float? {
    if (!JSON_NUMBER.matches(text)) return null
    return text.toFloat().takeIf { it.isFinite() }
}

/** A JSON number (RFC 8259 §6); Kotlin's own parsing also takes `NaN`, `1f` and hexadecimal. */
private val JSON_NUMBER = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")

/** Characters of a wrong element a message quotes at most. */
private const val MAX_SHOWN = 40
