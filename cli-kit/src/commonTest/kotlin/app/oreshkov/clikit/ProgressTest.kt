package app.oreshkov.clikit

import app.oreshkov.clikit.testing.RecordingTerminalIo
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class ProgressTest {

    private fun draws(io: RecordingTerminalIo) = io.stderr.count { it == '\r' }

    @Test
    fun `a flood of updates redraws at most every 100 milliseconds`() {
        val io = RecordingTerminalIo(interactive = true)
        val clock = TestTimeSource()
        val progress = Progress(io, "checking", total = 1_000_000, clock = clock)
        repeat(1_000) { progress.update(it.toLong()) }
        assertEquals(1, draws(io))
        clock += 100.milliseconds
        progress.update(1_000, detail = "segments")
        assertEquals(2, draws(io))
        assertContains(io.stderr, "checking 1000/1000000 segments")
        progress.finish("done")
        assertContains(io.stderr, "done\n")
    }

    @Test
    fun `nothing is drawn down a pipe, or when quiet`() {
        val pipe = RecordingTerminalIo(interactive = false)
        Progress(pipe, "x").apply { step(); finish("done") }
        assertEquals("", pipe.stderr)

        val terminal = RecordingTerminalIo(interactive = true)
        Output(terminal, machineReadable = false, quiet = true).progress("x").apply { step(); finish("done") }
        assertEquals("", terminal.stderr)
    }

    @Test
    fun `an interrupt clears the line on screen, and only that one`() = interrupting {
        val io = RecordingTerminalIo(interactive = true)
        val progress = Progress(io, "x")
        progress.step()
        Progress.clearShown()
        assertEquals("\u001B[K", io.stderr.substringAfterLast('\r'))
        val before = io.stderr
        progress.finish()
        Progress.clearShown()
        assertEquals(before, io.stderr, "a finished progress line is cleared once")
    }

    @Test
    fun `after an interrupt, the handler still running draws no more`() = interrupting {
        val io = RecordingTerminalIo(interactive = true)
        val clock = TestTimeSource()
        val progress = Progress(io, "x", total = 10, clock = clock)
        progress.step()
        Progress.clearShown()
        val cleared = io.stderr
        clock += 100.milliseconds
        progress.step()
        Progress(io, "y").step()
        progress.finish("done")
        assertEquals(cleared, io.stderr)
    }

    @Test
    fun `where escapes would show as text, the line is erased with spaces`() {
        val recording = RecordingTerminalIo(interactive = true)
        val io = object : TerminalIo by recording {
            override val escapesInterpreted = false
        }
        val clock = TestTimeSource()
        val progress = Progress(io, "x", clock = clock)
        progress.update(100, detail = "long")
        clock += 100.milliseconds
        progress.update(101)
        progress.finish()
        assertFalse(Styler.hasEscapes(recording.stderr), recording.stderr)
        assertEquals("\r| x 100 long\r/ x 101     \r       \r", recording.stderr)
    }

    /** Runs [test], which interrupts, then lets progress lines draw again for the tests after it. */
    private fun interrupting(test: () -> Unit) {
        try {
            test()
        } finally {
            Progress.resetInterrupted()
        }
    }

    @Test
    fun `quiet silences notices and warnings, not errors or the payload`() {
        val io = RecordingTerminalIo()
        val output = Output(io, machineReadable = false, quiet = true)
        output.notice("n")
        output.warn("w")
        output.text("payload")
        assertEquals("", io.stderr)
        assertEquals("payload\n", io.stdout)
    }
}
