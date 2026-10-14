package app.oreshkov.clikit

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableRendererTest {

    private val columns = listOf(
        Column("ACCOUNT"),
        Column("SITE", flexible = false),
        Column("STATUS", flexible = false),
        Column("GENERATION", flexible = false),
        Column("LAST USED", align = Align.Right, flexible = false),
    )

    private val rows = listOf(
        listOf("alice", "eu", "LIVE", "01JB2K3M4N5P6Q7R8S9T0V1W2X", "2026-08-09T10:00Z"),
        listOf("bob", "eu", "NEEDS_TOKEN", "01JB2K3M4N5P6Q7R8S9T0V1W2Y", "2026-08-09T09:12Z"),
    )

    @Test
    fun `a table has a header rule, a separator and a bottom rule`() {
        val rendered = TableRenderer(border = BorderStyle.Unicode).render(columns, rows, width = 120)
        val lines = rendered.lines()
        assertTrue(lines.first().startsWith("┌"))
        assertTrue(lines[2].startsWith("├"))
        assertTrue(lines.last().startsWith("└"))
        // Four fixed lines — top rule, header, separator, bottom rule — plus one per row.
        assertEquals(4 + rows.size, lines.size)
    }

    @Test
    fun `every line of a table is the same width`() {
        // The property a reader notices instantly when it is false, and the one an off-by-one in
        // the padding breaks.
        listOf(40, 60, 80, 120).forEach { width ->
            val lines = TableRenderer().render(columns, rows, width).lines()
            assertEquals(1, lines.map { Styler.displayWidth(it) }.distinct().size, "at width $width: $lines")
        }
    }

    @Test
    fun `a narrow terminal shrinks the flexible column and leaves the fixed ones alone`() {
        val rendered = TableRenderer().render(columns, rows, width = 60)
        // The generation is a fixed column: a truncated one is not a shorter generation, it is a
        // different one, and `NEEDS_TOK…` reads as a state that does not exist.
        assertContains(rendered, "01JB2K3M4N5P6Q7R8S9T0V1W2X")
        assertContains(rendered, "NEEDS_TOKEN")
    }

    @Test
    fun `truncation carries an ellipsis`() {
        val long = listOf(listOf("a".repeat(100), "eu", "LIVE", "g", "t"))
        val rendered = TableRenderer().render(columns, long, width = 60)
        assertContains(rendered, "…")
        assertFalse("a".repeat(100) in rendered)
    }

    @Test
    fun `the ASCII fallback uses no box drawing at all`() {
        // On a Windows console running a legacy code page, a box-drawing character is mojibake.
        val rendered = TableRenderer(border = BorderStyle.Ascii).render(columns, rows, width = 120)
        assertTrue(rendered.none { it in "─│┌┐└┘┬┴├┤┼…" }, rendered)
        assertContains(rendered, "+--")
        assertContains(rendered, "|")
    }

    @Test
    fun `the ASCII fallback truncates with dots rather than an ellipsis`() {
        val long = listOf(listOf("a".repeat(100), "eu", "LIVE", "g", "t"))
        val rendered = TableRenderer(border = BorderStyle.Ascii).render(columns, long, width = 50)
        assertFalse("…" in rendered)
        assertContains(rendered, "..")
    }

    @Test
    fun `a right-aligned column is padded on the left`() {
        val rendered = TableRenderer(border = BorderStyle.Ascii)
            .render(listOf(Column("N", align = Align.Right)), listOf(listOf("1"), listOf("100")), width = 40)
        assertContains(rendered, "|   1 |")
        assertContains(rendered, "| 100 |")
    }

    @Test
    fun `the plain form has no borders and no trailing whitespace`() {
        val rendered = TableRenderer(border = BorderStyle.Ascii).renderPlain(columns, rows, width = 120)
        assertFalse("|" in rendered)
        assertTrue(rendered.lines().none { it.endsWith(" ") }, rendered)
    }

    @Test
    fun `a header wider than every cell still fits`() {
        val rendered = TableRenderer(border = BorderStyle.Ascii)
            .render(listOf(Column("VERY_LONG_HEADER")), listOf(listOf("x")), width = 120)
        assertContains(rendered, "VERY_LONG_HEADER")
    }

    @Test
    fun `no rows renders a header and the rules, not an exception`() {
        val rendered = TableRenderer(border = BorderStyle.Ascii).render(columns, emptyList(), width = 120)
        assertEquals(4, rendered.lines().size)
    }

    @Test
    fun `a row with the wrong number of cells is a programming error, reported as one`() {
        assertFailsWith<IllegalArgumentException> {
            TableRenderer().render(columns, listOf(listOf("only", "two")))
        }
    }

    @Test
    fun `the bold header does not change the column width`() {
        // Escape sequences are zero-width on screen and must be zero-width to the measurer, or
        // every styled table is misaligned by exactly the length of its escapes.
        val lines = TableRenderer(Styler(enabled = true), BorderStyle.Ascii).render(columns, rows, 120).lines()
        assertTrue(Styler.hasEscapes(lines[1]))
        assertEquals(1, lines.map { Styler.displayWidth(it) }.distinct().size, lines.toString())
    }

    @Test
    fun `a cell's escapes are shown, not obeyed, even a styler's`() {
        // A cell is data, and data can't be told from styling by looking at it: the only escapes
        // a table carries are its own.
        val coloured = listOf(listOf(Styler(enabled = true).green("alice"), "eu", "LIVE", "g", "t"))
        val rendered = TableRenderer(border = BorderStyle.Ascii).render(columns, coloured, 120)
        assertFalse(Styler.hasEscapes(rendered))
        assertContains(rendered, "\\u001B[32malice")
    }
}
