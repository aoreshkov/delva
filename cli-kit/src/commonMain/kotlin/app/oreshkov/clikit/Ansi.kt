package app.oreshkov.clikit

/**
 * The 16 SGR base colours, plus bold and dim. That is the whole palette.
 *
 * No truecolor, no theme detection, no downsampling: a command-line tool colours a status green, a
 * failure red and a header bold, and every terminal made this century agrees on how. A palette that
 * needed capability negotiation would be a palette with a bug in it on somebody's machine.
 */
public enum class Style(internal val code: Int) {
    /** Bold, or increased intensity (SGR 1). */
    Bold(1),

    /** Dim, or decreased intensity (SGR 2). */
    Dim(2),

    /** Red foreground (SGR 31). */
    Red(31),

    /** Green foreground (SGR 32). */
    Green(32),

    /** Yellow foreground (SGR 33). */
    Yellow(33),

    /** Blue foreground (SGR 34). */
    Blue(34),

    /** Magenta foreground (SGR 35). */
    Magenta(35),

    /** Cyan foreground (SGR 36). */
    Cyan(36),

    /** White foreground (SGR 37). */
    White(37),

    /** Bright black, the grey of most palettes (SGR 90). */
    BrightBlack(90),
}

/**
 * Applies styles, or does not.
 *
 * Whether colour is on is decided once, at startup, and this object carries the answer. That is
 * why it is a value passed around rather than a global consulted: the tests assert on the exact
 * byte stream in both states, and a global would make "was colour on for this test" a question
 * about execution order.
 *
 * @property enabled whether styles are applied; when not, every method returns its text unchanged.
 */
public class Styler(public val enabled: Boolean) {

    /** [text] in all of [styles], then a reset; [text] unchanged when styling is off or [styles] is empty. */
    public fun style(text: String, vararg styles: Style): String {
        if (!enabled || styles.isEmpty() || text.isEmpty()) return text
        val codes = styles.joinToString(";") { it.code.toString() }
        return "$ESCAPE[${codes}m$text$RESET"
    }

    /** [text] in [Style.Bold]. */
    public fun bold(text: String): String = style(text, Style.Bold)

    /** [text] in [Style.Dim]. */
    public fun dim(text: String): String = style(text, Style.Dim)

    /** [text] in [Style.Red]. */
    public fun red(text: String): String = style(text, Style.Red)

    /** [text] in [Style.Green]. */
    public fun green(text: String): String = style(text, Style.Green)

    /** [text] in [Style.Yellow]. */
    public fun yellow(text: String): String = style(text, Style.Yellow)


    public companion object {
        /**
         * Written as a unicode escape, never as a literal control character: a raw `0x1B` in a
         * source file is invisible in a diff and the first thing a well-meaning editor strips.
         */
        internal const val ESCAPE_CHAR: Char = '\u001B'
        internal const val ESCAPE: String = "\u001B"
        internal const val RESET: String = "\u001B[0m"

        /** Never emits an escape. The default for anything not proven to be an interactive terminal. */
        public val Plain: Styler = Styler(enabled = false)

        /**
         * Whether [text] carries any escape sequence.
         *
         * Public where the constants are not, because this is the question other modules ask —
         * "did an escape reach a stream that should not have one" — and exposing the answer keeps
         * that assertion available without publishing the encoding.
         */
        public fun hasEscapes(text: String): Boolean = ESCAPE_CHAR in text

        /**
         * The width of [text] as the terminal will show it, ignoring the SGR sequences, such as [style]'s, it carries.
         *
         * Measured in code points with surrogate pairs handled, and that is all — full-width CJK
         * and emoji will mis-measure. A bounded, deliberate acceptance: the columns of a
         * command-line tool's tables are ids, timestamps, status enums and URLs, and machine-readable
         * output such as `--json` exists for anything that is not.
         */
        public fun displayWidth(text: String): Int {
            var width = 0
            var index = 0
            while (index < text.length) {
                val char = text[index]
                val sgrEnd = sgrEnd(text, index)
                if (sgrEnd > 0) {
                    index = sgrEnd
                    continue
                }
                // A surrogate pair is one character on screen, not two.
                if (char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                    index += 2
                } else {
                    index++
                }
                width++
            }
            return width
        }

        /**
         * Truncate to [limit] visible characters, ending with an ellipsis when anything was cut.
         *
         * Escape-aware, because a truncation that lands inside `\u001B[32m` leaves the terminal
         * painting everything after it green — including the next command's prompt.
         */
        public fun truncate(text: String, limit: Int, ellipsis: String = "…"): String {
            if (limit <= 0) return ""
            if (displayWidth(text) <= limit) return text
            val keep = limit - displayWidth(ellipsis)
            if (keep <= 0) return ellipsis.take(limit)

            val builder = StringBuilder()
            var width = 0
            var index = 0
            var open = false
            while (index < text.length && width < keep) {
                val char = text[index]
                val stop = sgrEnd(text, index)
                if (stop > 0) {
                    val sequence = text.substring(index, stop)
                    builder.append(sequence)
                    open = sequence != RESET
                    index = stop
                    continue
                }
                if (char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                    builder.append(char).append(text[index + 1])
                    index += 2
                } else {
                    builder.append(char)
                    index++
                }
                width++
            }
            builder.append(ellipsis)
            if (open) builder.append(RESET)
            return builder.toString()
        }

        /**
         * The end of the SGR sequence, `ESC [ [0-9;]* m`, that starts at [start] in [text], or -1 when none does. Only
         * a complete one is an escape: an `ESC [` that never reaches its `m` would otherwise swallow the rest of the
         * text, measured as nothing and never cut.
         */
        private fun sgrEnd(text: String, start: Int): Int {
            if (text[start] != ESCAPE_CHAR || start + 1 >= text.length || text[start + 1] != '[') return -1
            var index = start + 2
            while (index < text.length && (text[index] in '0'..'9' || text[index] == ';')) index++
            return if (index < text.length && text[index] == 'm') index + 1 else -1
        }
    }
}
