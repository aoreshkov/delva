package app.oreshkov.clikit

import app.oreshkov.clikit.testing.RecordingTerminalIo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Data that reaches the terminal outside the payload is shown, not obeyed. The characters are built from their codes,
 * so none of them sits invisible in this file.
 */
class TerminalTextTest {
    private val esc = Char(0x1B)
    private val bel = Char(0x07)
    private val rlo = Char(0x202E)

    /** Each kind of hostile key, and what it shows as. */
    private val hostile = listOf(
        "$esc]0;x$bel" to "\\u001B]0;x\\u0007",
        "$esc[2J" to "\\u001B[2J",
        "a\rb" to "a\\u000Db",
        "a\nb" to "a\\u000Ab",
        "abc${rlo}fed" to "abc\\u202Efed",
    )

    @Test
    fun `controls, DEL, C1 and the bidi controls are escaped, and nothing else is`() {
        hostile.forEach { (raw, shown) -> assertEquals(shown, TerminalText.escape(raw)) }
        assertEquals("a\\u0009b\\u007F\\u0085\\u2066", TerminalText.escape("a\tb${Char(0x7F)}${Char(0x85)}${Char(0x2066)}"))
        val ordinary = "Жана 東京 🔑 ${Char(0xA0)}${Char(0x200F)}"
        assertSame(ordinary, TerminalText.escape(ordinary))
    }

    @Test
    fun `a hostile key renders escaped, in a column as wide as what is shown`() {
        val columns = listOf(Column("KEY"), Column("N", flexible = false))
        val rows = hostile.map { (raw, _) -> listOf(raw, "1") }
        val rendered = TableRenderer(border = BorderStyle.Ascii).render(columns, rows, 120)
        assertFalse(esc in rendered || '\r' in rendered || rlo in rendered, rendered)
        val lines = rendered.lines()
        assertEquals(4 + rows.size, lines.size, rendered)
        assertEquals(1, lines.map { it.length }.distinct().size, rendered)
        hostile.forEach { (_, shown) -> assertTrue(lines.any { shown in it }, "$shown in\n$rendered") }

        val plain = TableRenderer().renderPlain(columns, rows, 120)
        assertEquals(1 + rows.size, plain.lines().size, plain)
        assertFalse(esc in plain || rlo in plain, plain)
    }

    @Test
    fun `a table's own styling still reaches the terminal`() {
        val rendered = TableRenderer(Styler(enabled = true)).render(listOf(Column("KEY")), listOf(listOf("$esc[2J")), 80)
        assertTrue(Styler.hasEscapes(rendered.lines()[1]), "the bold header")
        assertFalse(Styler.hasEscapes(rendered.lines()[3]), "the cell")
    }

    @Test
    fun `notices, warnings, errors, properties and log lines escape what they are given`() {
        val io = RecordingTerminalIo()
        val output = Output(io, machineReadable = false, verbose = true)
        val key = "$esc]0;x$bel"
        output.notice("no document has the key $key")
        output.warn(key)
        output.error(key)
        output.log(LogLevel.ERROR, key, IllegalStateException(key))
        output.properties(listOf(key to key))
        assertFalse(esc in io.stderr || bel in io.stderr, io.stderr)
        assertFalse(esc in io.stdout, io.stdout)
        assertTrue("    at " in io.stderr && "\\u0009" !in io.stderr, "a trace's tabs are spaces, not escapes: ${io.stderr}")
    }

    @Test
    fun `a failure's message is escaped`() {
        val command = CommandSpec("fail", "Fail")
        val program = Program(
            CommandSpec("demo", subcommands = listOf(command)),
            "demo 1",
            mapOf(command to CommandHandler { throw CommandFailure(ExitStatus.NO_INPUT, "no key $esc[2J") }),
        )
        val io = RecordingTerminalIo()
        assertEquals(ExitStatus.NO_INPUT, program.run(arrayOf("fail"), io))
        assertEquals("error: no key \\u001B[2J\n", io.stderr)
    }

    @Test
    fun `an unterminated ESC bracket is text, measured and cut like any other`() {
        val text = "$esc[31abcdef"
        assertEquals(text.length, Styler.displayWidth(text))
        val cut = Styler.truncate(text, 5)
        assertEquals(5, Styler.displayWidth(cut))
        assertEquals("$esc[31…", cut)
    }
}
