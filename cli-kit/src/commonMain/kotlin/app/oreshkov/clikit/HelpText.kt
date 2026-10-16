package app.oreshkov.clikit

/**
 * The text model of every `help`, description and epilogue, and the one place it is laid out.
 *
 * A blank line starts a paragraph, a single line break is kept, and every other run of whitespace is a place to
 * break the line. A word wider than the room left, a path or a URL, overflows its line rather than being split:
 * half a path can't be copied. Widths are [Styler.displayWidth]s, so a styled word measures what it shows.
 */
internal object HelpText {
    private val SPACE = Regex("\\s+")

    /**
     * [text] in lines of at most [width] columns, one empty string between paragraphs, and none before or after.
     * A line wider than [width] holds a single word.
     *
     * [tail] follows the text's last word, each piece kept whole as though it were one word: `[default: asc]` split
     * after its colon reads as two things.
     */
    fun wrap(text: String, width: Int, tail: List<String> = emptyList()): List<String> {
        val sources = text.lines().map(::words).toMutableList()
        if (tail.isNotEmpty()) {
            val last = sources.indexOfLast { it.isNotEmpty() }
            if (last < 0) sources.add(tail) else sources[last] = sources[last] + tail
        }
        val lines = mutableListOf<String>()
        for (words in sources) {
            if (words.isEmpty()) {
                // Two blank lines are one paragraph break, not two.
                if (lines.isNotEmpty() && lines.last().isNotEmpty()) lines += ""
                continue
            }
            val line = StringBuilder()
            var used = 0
            for (word in words) {
                val wordWidth = Styler.displayWidth(word)
                if (used > 0 && used + 1 + wordWidth > width) {
                    lines += line.toString()
                    line.clear()
                    used = 0
                }
                if (used > 0) {
                    line.append(' ')
                    used++
                }
                line.append(word)
                used += wordWidth
            }
            lines += line.toString()
        }
        if (lines.lastOrNull()?.isEmpty() == true) lines.removeAt(lines.lastIndex)
        return lines
    }

    /**
     * The first line of [text], its whitespace runs made single spaces: what a list of commands and the completion
     * scripts show, since a zsh description or a fish `-d` can't hold a line break.
     */
    fun summary(text: String): String =
        text.lineSequence().map { words(it).joinToString(" ") }.firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun words(line: String): List<String> = line.split(SPACE).filter { it.isNotEmpty() }
}
