package app.oreshkov.delva.internal.types

import app.oreshkov.delva.InvalidTypeNameException
import app.oreshkov.delva.internal.canon.Utf8

/** Type name rules (SPEC.md §6): 1–256 bytes of UTF-8, no control characters, compared exactly. */
internal object TypeNames {
    const val MAX_BYTES: Int = 256

    /** Throws [InvalidTypeNameException] unless [name] is a valid type name. */
    fun validate(name: String) {
        problem(name)?.let { throw InvalidTypeNameException(it) }
    }

    /** Whether stored bytes are a valid type name. */
    fun isValid(bytes: ByteArray, offset: Int, length: Int): Boolean =
        length in 1..MAX_BYTES && Utf8.isValid(bytes, offset, length) && problem(String(bytes, offset, length, Charsets.UTF_8)) == null

    private fun problem(name: String): String? {
        if (hasLoneSurrogate(name)) return "not valid Unicode"
        val bytes = name.toByteArray(Charsets.UTF_8).size
        if (bytes == 0 || bytes > MAX_BYTES) return "length $bytes bytes, allowed 1..$MAX_BYTES"
        if (name.codePoints().anyMatch { Character.isISOControl(it) }) return "contains a control character"
        return null
    }

    fun hasLoneSurrogate(s: CharSequence): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
            } else {
                if (Character.isLowSurrogate(c)) return true
                i++
            }
        }
        return false
    }
}
