package app.oreshkov.clikit

/** Where a column's content sits when it is narrower than the column. */
public enum class Align {
    /** Against the left edge, padded on the right. */
    Left,

    /** Against the right edge, padded on the left. */
    Right,
}

/** One column of a table that [TableRenderer] draws. */
public class Column(
    /** The column's heading. */
    public val header: String,
    /** Where cells narrower than the column sit. */
    public val align: Align = Align.Left,
    /**
     * A fixed column is never shrunk to make the table fit; a flexible one is, widest first.
     *
     * Statuses, timestamps and generations are fixed because a truncated one is worse than useless
     * — `NEEDS_TOK…` reads as a different state. URLs and account names are flexible because the
     * first thirty characters of one are still informative.
     */
    public val flexible: Boolean = true,
    /** Never shrink below this, even when the terminal is absurdly narrow. */
    public val minWidth: Int = 3,
) {
    override fun equals(other: Any?): Boolean =
        other is Column && other.header == header && other.align == align && other.flexible == flexible &&
            other.minWidth == minWidth

    override fun hashCode(): Int = ((31 * header.hashCode() + align.hashCode()) * 31 + flexible.hashCode()) * 31 + minWidth

    override fun toString(): String = "Column(header=$header, align=$align, flexible=$flexible, minWidth=$minWidth)"
}

/**
 * Two border styles.
 *
 * The ASCII fallback is not a nicety: on a Windows console still running a legacy code page,
 * emitting `┌─┐` produces mojibake, and the charset is probed rather than assumed for exactly that
 * reason. Terminal capability decides which of these is used; the renderer takes it as a parameter
 * so both are testable with no terminal at all.
 */
public enum class BorderStyle(
    internal val horizontal: Char,
    internal val vertical: Char,
    internal val topLeft: Char,
    internal val topRight: Char,
    internal val bottomLeft: Char,
    internal val bottomRight: Char,
    internal val teeDown: Char,
    internal val teeUp: Char,
    internal val teeRight: Char,
    internal val teeLeft: Char,
    internal val cross: Char,
) {
    /** Box-drawing characters. */
    Unicode('─', '│', '┌', '┐', '└', '┘', '┬', '┴', '├', '┤', '┼'),

    /** `-`, `|` and `+`, for a terminal that can't show box drawing. */
    Ascii('-', '|', '+', '+', '+', '+', '+', '+', '+', '+', '+'),
}

/**
 * One function, and everything a listing such as `demo session list` needs from a table.
 *
 * The fitting rule: measure the widest cell per column, and while the total is too wide, shrink
 * the widest *flexible* column by one. Truncation carries an ellipsis. It is not clever, and the
 * failure mode when it is wrong is a narrow column, not a broken layout.
 *
 * Headers and cells go through [TerminalText.escape] before they are measured, so a control character in one is shown
 * as `\u001B` rather than acted on.
 */
public class TableRenderer(
    private val styler: Styler = Styler.Plain,
    private val border: BorderStyle = BorderStyle.Unicode,
) {
    /**
     * [rows] under a header of [columns], in a bordered table fitted to [width] columns. Every row has a cell per
     * column.
     */
    public fun render(columns: List<Column>, rows: List<List<String>>, width: Int = DEFAULT_WIDTH): String {
        require(rows.all { it.size == columns.size }) {
            "every row must have ${columns.size} cells; got ${rows.map { it.size }.distinct()}"
        }
        if (columns.isEmpty()) return ""

        val headers = columns.map { TerminalText.escape(it.header) }
        val cells = escaped(rows)
        val widths = fit(columns, headers, cells, width)
        val lines = mutableListOf<String>()

        lines += rule(widths, border.topLeft, border.teeDown, border.topRight)
        lines += row(headers.map { styler.bold(it) }, columns, widths)
        lines += rule(widths, border.teeRight, border.cross, border.teeLeft)
        cells.forEach { lines += row(it, columns, widths) }
        lines += rule(widths, border.bottomLeft, border.teeUp, border.bottomRight)

        return lines.joinToString("\n")
    }

    /** No borders, two spaces between columns — what a `--json`-less list looks like in a pipe. */
    public fun renderPlain(columns: List<Column>, rows: List<List<String>>, width: Int = DEFAULT_WIDTH): String {
        val headers = columns.map { TerminalText.escape(it.header) }
        val cells = escaped(rows)
        val widths = fit(columns, headers, cells, width, decoration = 2 * (columns.size - 1))
        val header = headers.mapIndexed { i, h -> pad(h, widths[i], columns[i].align) }.joinToString("  ")
        val body = cells.map { row ->
            row.mapIndexed { i, cell -> pad(cell, widths[i], columns[i].align) }.joinToString("  ")
        }
        return (listOf(header) + body).joinToString("\n") { it.trimEnd() }
    }

    /**
     * Every cell with the characters a terminal acts on made visible, before anything is measured: a cell holds data,
     * a stored key for one, and the only escapes a table carries are its own styling's.
     */
    private fun escaped(rows: List<List<String>>): List<List<String>> = rows.map { it.map(TerminalText::escape) }

    private fun fit(
        columns: List<Column>,
        headers: List<String>,
        rows: List<List<String>>,
        width: Int,
        // "│ " + " │ " × (n-1) + " │"
        decoration: Int = 3 * columns.size + 1,
    ): IntArray {
        val widths = IntArray(columns.size) { index ->
            val header = Styler.displayWidth(headers[index])
            val widest = rows.maxOfOrNull { Styler.displayWidth(it[index]) } ?: 0
            maxOf(header, widest)
        }

        var total = widths.sum() + decoration
        // Shrink the widest flexible column first, one character at a time. Slow in principle and
        // irrelevant in practice: the tables here have five columns and the loop is bounded by the
        // overflow.
        while (total > width) {
            val candidate = columns.indices
                .filter { columns[it].flexible && widths[it] > columns[it].minWidth }
                .maxByOrNull { widths[it] }
                ?: break
            widths[candidate]--
            total--
        }
        return widths
    }

    private fun rule(widths: IntArray, left: Char, middle: Char, right: Char): String = buildString {
        append(left)
        widths.forEachIndexed { index, w ->
            if (index > 0) append(middle)
            repeat(w + 2) { append(border.horizontal) }
        }
        append(right)
    }

    private fun row(cells: List<String>, columns: List<Column>, widths: IntArray): String = buildString {
        append(border.vertical)
        cells.forEachIndexed { index, cell ->
            if (index > 0) append(border.vertical)
            append(' ')
            append(pad(cell, widths[index], columns[index].align))
            append(' ')
        }
        append(border.vertical)
    }

    private fun pad(text: String, width: Int, align: Align): String {
        val fitted = Styler.truncate(text, width, ellipsis = if (border == BorderStyle.Unicode) "…" else "..")
        val padding = width - Styler.displayWidth(fitted)
        if (padding <= 0) return fitted
        val spaces = " ".repeat(padding)
        return if (align == Align.Right) spaces + fitted else fitted + spaces
    }

    public companion object {
        /**
         * Assumed when nothing better is known.
         *
         * Exact terminal width detection is the one capability deliberately not implemented: an
         * FFM `ioctl(TIOCGWINSZ)` / `GetConsoleScreenBufferInfo` call adds it in about thirty lines
         * if it ever annoys anyone. Until then `COLUMNS` and `--width` are the overrides, and the
         * failure mode is a truncated cell rather than a broken table.
         */
        public const val DEFAULT_WIDTH: Int = 80
    }
}
