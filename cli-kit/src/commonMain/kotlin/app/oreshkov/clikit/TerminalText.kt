package app.oreshkov.clikit

/**
 * Text from outside the program, made safe to show at a terminal.
 *
 * A stored key, a file name or an exception message can hold any character, and a terminal acts on what it is sent:
 * `ESC ] 0 ; … BEL` retitles the window, `ESC [ 2 J` clears the screen, a carriage return or a line break forges a row
 * of a table or an entry on stderr, and the bidirectional overrides show text in an order other than the one it is
 * stored in. So the only escapes cli-kit writes are its own styling's, and the text of a table cell, a property, a
 * notice, a warning, an error, a log line or a progress line goes through [escape] first. The payload never does: what
 * `--json` writes, or a command prints as its answer, is data, and a pipe receives it as it is.
 *
 * On stderr a line break in a message is still shown as one, because a multi-line message, such as a dump of a
 * request, is unreadable on one line: each line after the first is a continuation that starts with spaces and a
 * marker, `│` or `|`, so it can't pass for an entry of its own. A row of a table or a property really is one line, and
 * keeps the line break escaped.
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
     * One entry on stderr, without its final line break: [head], then [message]'s first line, then each further line
     * of it on a line of its own, [column] spaces in, after [marker] and a space. Line breaks at the end are dropped, so
     * a message that ends in one leaves no empty continuation; CRLF, LF and a lone CR each break a line, so a carriage
     * return can't take the cursor back to column 0. Each line is escaped, with tabs as four spaces, so what follows the
     * marker can't act on the terminal either.
     *
     * [head] is the caller's own text, styled and not escaped, and [column] is how wide it shows. [style] applies to
     * each line on its own, marker included, because a pager such as `less -R` ends styling at a line break.
     */
    internal fun entry(
        head: String,
        message: String,
        column: Int,
        marker: Char,
        style: (String) -> String = { it },
    ): String {
        val lines = message.trimEnd('\n', '\r').lines()
        return buildString {
            append(head).append(style(shown(lines.first())))
            for (line in lines.drop(1)) {
                append('\n').append(" ".repeat(column))
                // An empty line keeps its marker but no trailing space.
                append(style(if (line.isEmpty()) marker.toString() else "$marker ${shown(line)}"))
            }
        }
    }

    /**
     * [error]'s stack trace, each line escaped, with tabs as four spaces rather than `\u0009`, and lines ending in LF,
     * not the platform's CRLF on Windows.
     *
     * The header of each throwable in the chain, the trace's first line and each `Caused by:` and `Suppressed:` line, is
     * its `toString()`, which carries its message, and a message can hold anything. A line break in it would start a
     * line at column 0 with outside text, such as a forged log entry, so a header that holds one is escaped whole
     * before the trace is split into lines, and every header stays one line.
     */
    internal fun stackTrace(error: Throwable): String {
        var trace = error.stackTraceToString()
        // Longest first: an outer message can quote an inner header, and once the outer one is escaped it holds no line
        // break for the inner one to match.
        chain(error).map { it.toString() }
            .filter { '\n' in it || '\r' in it }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { header -> trace = trace.replace(header, shown(header)) }
        return trace.trimEnd().lines().joinToString("\n", transform = ::shown)
    }

    /** [error], its causes and the suppressed exceptions of each, once each however the chain loops back. */
    private fun chain(error: Throwable): List<Throwable> {
        // By identity, which common code has no set for; a chain is a handful of throwables.
        val seen = mutableListOf<Throwable>()
        val pending = ArrayDeque(listOf(error))
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (seen.any { it === next }) continue
            seen += next
            next.cause?.let(pending::addLast)
            pending.addAll(next.suppressedExceptions)
        }
        return seen
    }

    /** [line] as a terminal may show it: tabs as spaces, and every other control escaped. */
    private fun shown(line: String): String = escape(line.replace("\t", TAB))

    private fun isControl(char: Char): Boolean {
        val code = char.code
        return code < 0x20 || code in 0x7F..0x9F || code in 0x202A..0x202E || code in 0x2066..0x2069
    }

    private const val HEX = 16

    /** A tab on stderr: in a dump or a stack trace, `\u0009` would only make the text harder to read. */
    private const val TAB = "    "

    /** What one escaped character adds: `\uXXXX` in place of one. */
    private const val ESCAPE_LENGTH = 5
}
