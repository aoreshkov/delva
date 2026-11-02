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
    fun `a line break in a message starts a continuation, marked, under where the message starts`() {
        val (io, output) = logged(verbose = true)
        val message = "one\ntwo\nthree"
        output.log(LogLevel.DEBUG, message, stamp = stamp)
        output.log(LogLevel.INFO, message)
        output.notice(message)
        output.warn(message)
        output.error(message)
        val under = " ".repeat(stamp.length + 7)
        assertEquals(
            """
            |$stamp DEBUG one
            |$under| two
            |$under| three
            |INFO  one
            |      | two
            |      | three
            |one
            || two
            || three
            |warning: one
            |         | two
            |         | three
            |error: one
            |       | two
            |       | three
            |
            """.trimMargin(),
            io.stderr,
        )
        assertEquals("", io.stdout)
    }

    @Test
    fun `CRLF and a lone CR break a line, breaks at the end are dropped, and an empty line keeps its marker`() {
        val (io, output) = logged()
        output.warn("crlf\r\nlone\rtab\there\n\nafter\r\n\n")
        assertEquals("warning: crlf\n         | lone\n         | tab    here\n         |\n         | after\n", io.stderr)
    }

    @Test
    fun `the marker is a box-drawing line where the terminal can show one`() {
        val io = RecordingTerminalIo(borderStyle = BorderStyle.Unicode)
        Output(io, machineReadable = false).error("a\nb")
        assertEquals("error: a\n       │ b\n", io.stderr)
    }

    @Test
    fun `a styled notice styles each line on its own`() {
        val (io, output) = logged(styler = Styler(enabled = true))
        output.notice("a\nb")
        io.stderr.removeSuffix("\n").lines().forEach { line ->
            assertTrue(line.startsWith("\u001B[") && line.endsWith("\u001B[0m"), "'$line' is not styled on its own")
        }
        assertEquals("a\n| b\n", io.stderr.replace(Regex("\u001B\\[[0-9;]*m"), ""))
    }

    @Test
    fun `quiet silences a multi-line notice and warning, but not an error`() {
        val (io, output) = logged(quiet = true)
        output.notice("a\nb")
        output.warn("a\nb")
        output.log(LogLevel.INFO, "a\nb")
        output.error("a\nb")
        assertEquals("error: a\n       | b\n", io.stderr)
    }

    @Test
    fun `an error's message continues the log line, marked, before its stack trace`() {
        val failure = IllegalStateException("socket closed\nby peer")
        val (plain, output) = logged()
        output.log(LogLevel.ERROR, "lost the upstream", failure, stamp)
        val under = " ".repeat(stamp.length + 7)
        assertEquals("$stamp ERROR lost the upstream: socket closed\n$under| by peer\n", plain.stderr)

        val (verbose, verboseOutput) = logged(verbose = true)
        verboseOutput.log(LogLevel.ERROR, "lost", failure)
        val lines = verbose.stderr.removeSuffix("\n").lines()
        assertEquals(listOf("ERROR lost: socket closed", "      | by peer"), lines.take(2))
        assertEquals("java.lang.IllegalStateException: socket closed\\u000Aby peer", lines[2])
        assertTrue(lines.drop(3).all { it.startsWith("    ") }, verbose.stderr)
    }

    @Test
    fun `every entry is one write`() {
        // Not a delegate of RecordingTerminalIo: delegation would send errLine's default to the delegate's err.
        class Counting : TerminalIo {
            val writes = mutableListOf<String>()
            override fun out(text: String) = Unit
            override fun err(text: String) {
                writes += text
            }
            override val interactive = false
            override val width = 80
            override val styler = Styler.Plain
            override val borderStyle = BorderStyle.Ascii
            override fun readPassword(prompt: String): CharArray? = null
        }
        val io = Counting()
        val output = Output(io, machineReadable = false, verbose = true)
        output.notice("a\nb")
        output.warn("a\nb")
        output.error("a\nb")
        LogLevel.entries.forEach { output.log(it, "a\nb", IllegalStateException("c\nd"), stamp) }
        io.errLine("a")
        assertEquals(3 + LogLevel.entries.size + 1, io.writes.size, io.writes.toString())
        assertTrue(io.writes.all { it.endsWith("\n") }, io.writes.toString())
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
