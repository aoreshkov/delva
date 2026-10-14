package app.oreshkov.clikit

/**
 * Text from outside the program, made safe to show at a terminal.
 *
 * A stored key, a file name or an exception message can hold any character, and a terminal acts on what it is sent:
 * `ESC ] 0 ; … BEL` retitles the window, `ESC [ 2 J` clears the screen, a carriage return or a line break forges a row
 * of a table, and the bidirectional overrides show text in an order other than the one it is stored in. So the only
 * escapes cli-kit writes are its own styling's, and the text of a table cell, a property, a notice, a warning, an
 * error or a progress line goes through [escape] first. The payload never does: what `--json` writes, or a command
 * prints as its answer, is data, and a pipe receives it as it is.
 */
public object TerminalText {
    /**
     * [text] with each character a terminal could act on replaced by a visible `\uXXXX`: the C0 controls, tab and line
     * break included, DEL, the C1 controls (U+0080 – U+009F), and the bidirectional embeddings, overrides and isolates
     * (U+202A – U+202E, U+2066 – U+2069). Text with none of them comes back unchanged.
     */
    public fun escape(text: String): String {
        val first = text.indexOfFirst(::isControl)
        if (first < 0) return text
        return buildString(text.length + ESCAPE_LENGTH) {
            append(text, 0, first)
            for (index in first until text.length) {
                val char = text[index]
                if (isControl(char)) {
                    append('\\').append('u').append(char.code.toString(HEX).uppercase().padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }

    /**
     * [error]'s stack trace, each line escaped: its first line carries the message, which can hold anything. Tabs
     * become four spaces rather than `\u0009`, and lines end in LF, not the platform's CRLF on Windows.
     */
    internal fun stackTrace(error: Throwable): String =
        error.stackTraceToString().trimEnd().lines().joinToString("\n") { escape(it.replace("\t", "    ")) }

    private fun isControl(char: Char): Boolean {
        val code = char.code
        return code < 0x20 || code in 0x7F..0x9F || code in 0x202A..0x202E || code in 0x2066..0x2069
    }

    private const val HEX = 16

    /** What one escaped character adds: `\uXXXX` in place of one. */
    private const val ESCAPE_LENGTH = 5
}
