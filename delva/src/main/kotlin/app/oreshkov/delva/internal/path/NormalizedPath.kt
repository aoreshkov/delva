package app.oreshkov.delva.internal.path

/**
 * RFC 9535 Normalized Paths (section 2.7), the one spelling of a location that differences report: `$`, then `['key']` for a
 * member and `[i]` for an index. A key escapes `'` and `\`, writes `\b \f \n \r \t` as such and other control characters
 * as `\u00xx` in lowercase hex, and keeps every other character as is. A Normalized Path is also a valid [SingularPath].
 */
internal object NormalizedPath {
    fun appendName(sb: StringBuilder, key: CharSequence) {
        sb.append('[')
        appendString(sb, key)
        sb.append(']')
    }

    /** [value] as a single-quoted RFC 9535 string literal, escaped as a Normalized Path escapes a key. */
    fun appendString(sb: StringBuilder, value: CharSequence) {
        sb.append('\'')
        for (ch in value) {
            when {
                ch == '\'' -> sb.append("\\'")
                ch == '\\' -> sb.append("\\\\")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append("\\u00").append(HEX[ch.code shr 4]).append(HEX[ch.code and 0xF])
                else -> sb.append(ch)
            }
        }
        sb.append('\'')
    }

    fun appendName(sb: StringBuilder, utf8: ByteArray, length: Int) = appendName(sb, String(utf8, 0, length, Charsets.UTF_8))

    fun appendIndex(sb: StringBuilder, index: Long) {
        sb.append('[').append(index).append(']')
    }

    private const val HEX = "0123456789abcdef"
}
