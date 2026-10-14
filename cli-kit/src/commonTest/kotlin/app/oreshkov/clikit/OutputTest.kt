package app.oreshkov.clikit

import app.oreshkov.clikit.testing.RecordingTerminalIo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutputTest {

    @Test
    fun `properties align their names and never cut a value to fit the width`() {
        val io = RecordingTerminalIo(width = 20)
        val path = "/a/very/long/path/that/is/wider/than/the/terminal"
        Output(io, machineReadable = false).properties(listOf("store" to path, "documents" to "4"))
        assertEquals("store      $path\ndocuments  4\n", io.stdout)
        assertEquals("", io.stderr)
    }

    @Test
    fun `properties are bold at a terminal and plain down a pipe`() {
        val terminal = RecordingTerminalIo(interactive = true, styler = Styler(enabled = true))
        Output(terminal, machineReadable = false).properties(listOf("a" to "1"))
        assertTrue(Styler.hasEscapes(terminal.stdout))

        val pipe = RecordingTerminalIo(interactive = false, styler = Styler(enabled = true))
        Output(pipe, machineReadable = false).properties(listOf("a" to "1"))
        assertFalse(Styler.hasEscapes(pipe.stdout))
    }

    private val stamp = "2026-09-28T05:52:13.897Z"

    private fun logged(quiet: Boolean = false, verbose: Boolean = false, styler: Styler = Styler.Plain): Pair<RecordingTerminalIo, Output> {
        val io = RecordingTerminalIo(styler = styler)
        return io to Output(io, machineReadable = false, quiet = quiet, verbose = verbose)
    }

    private fun Output.everyLevel() = LogLevel.entries.forEach { log(it, it.name.lowercase(), stamp = stamp) }

    @Test
    fun `log lines are stamped, levelled, aligned and on stderr`() {
        val (io, output) = logged(verbose = true)
        output.everyLevel()
        output.log(LogLevel.INFO, "no stamp")
        assertEquals("", io.stdout)
        assertEquals(
            "$stamp ERROR error\n$stamp WARN  warn\n$stamp INFO  info\n$stamp DEBUG debug\nINFO  no stamp\n",
            io.stderr,
        )
    }

    @Test
    fun `quiet keeps only errors, and debug needs verbose`() {
        val (plain, output) = logged()
        output.everyLevel()
        assertEquals("$stamp ERROR error\n$stamp WARN  warn\n$stamp INFO  info\n", plain.stderr)

        val (quiet, quietOutput) = logged(quiet = true, verbose = true)
        quietOutput.everyLevel()
        assertEquals("$stamp ERROR error\n", quiet.stderr)
    }

    @Test
    fun `an error adds its message, and its stack trace only with verbose`() {
        val failure = IllegalStateException("socket closed")
        val (plain, output) = logged()
        output.log(LogLevel.ERROR, "lost the upstream", failure)
        assertEquals("ERROR lost the upstream: socket closed\n", plain.stderr)

        val (verbose, verboseOutput) = logged(verbose = true)
        verboseOutput.log(LogLevel.WARN, "retrying", failure)
        assertTrue(verbose.stderr.startsWith("WARN  retrying: socket closed\njava.lang.IllegalStateException: socket closed\n"), verbose.stderr)
        assertTrue(verbose.stderr.endsWith("\n"))
    }

    @Test
    fun `log colours the level and dims the stamp, and never at a pipe with colour off`() {
        val (coloured, output) = logged(styler = Styler(enabled = true))
        output.log(LogLevel.ERROR, "boom", stamp = stamp)
        assertTrue(Styler.hasEscapes(coloured.stderr))
        assertEquals("$stamp ERROR boom\n", coloured.stderr.replace(Regex("\u001B\\[[0-9;]*m"), ""))
    }

    @Test
    fun `nothing to show is a notice on stderr, and stdout stays empty`() {
        val io = RecordingTerminalIo()
        val output = Output(io, machineReadable = false)
        output.properties(emptyList())
        output.table(listOf(Column("A")), emptyList(), empty = "no rows")
        assertEquals("", io.stdout)
        assertEquals("nothing to show\nno rows\n", io.stderr)
    }
}
