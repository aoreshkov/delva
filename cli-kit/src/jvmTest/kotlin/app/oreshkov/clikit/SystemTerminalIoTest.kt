package app.oreshkov.clikit

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The payload survives the stream it is written to.
 *
 * This is the regression for a one-line change that passed the whole build: dropping the explicit
 * `PrintStream` wrappers let stdout inherit `stdout.encoding`, which on Windows is the ANSI code
 * page even when stdout is a pipe. `PrintStream` reports nothing when a character will not encode —
 * it writes `?` — so `demo store get --json > out.json` would have lost every non-Latin-1 character
 * in the payload, irreversibly and in silence.
 *
 * The constructor already takes both streams as parameters, so the contract is testable without a
 * console and without touching `System.out`.
 */
class SystemTerminalIoTest {

    private class Capture(val charset: Charset) {
        val bytes = ByteArrayOutputStream()
        val stream = PrintStream(bytes, false, charset)
    }

    /** An em-dash, Cyrillic, CJK and an astral-plane emoji: one, two, three and four UTF-8 bytes. */
    private val probe = "—Кэш日本🔑"

    @Test
    fun `text written to a UTF-8 stream arrives as UTF-8 bytes`() {
        val out = Capture(Charsets.UTF_8)
        val err = Capture(Charsets.UTF_8)
        val io = SystemTerminalIo(stdout = out.stream, stderr = err.stream)

        io.out("—")

        assertContentEquals(byteArrayOf(0xE2.toByte(), 0x80.toByte(), 0x94.toByte()), out.bytes.toByteArray())
        assertEquals(0, err.bytes.size(), "the payload must not reach stderr")
    }

    @Test
    fun `text written down a pipe arrives whole once flushed`() {
        // Down a pipe stdout is block-buffered; Program.run flushes when it returns, however the
        // command ended (ProgramTest), which is what keeps a payload from being lost on a failing path.
        val out = Capture(Charsets.UTF_8)
        val io = SystemTerminalIo(interactive = false, stdout = out.stream, stderr = Capture(Charsets.UTF_8).stream)

        io.out(probe)
        io.flush()

        assertEquals(probe, out.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `decoration goes to stderr and nothing else does`() {
        val out = Capture(Charsets.UTF_8)
        val err = Capture(Charsets.UTF_8)
        val io = SystemTerminalIo(stdout = out.stream, stderr = err.stream)

        io.err(probe)

        assertEquals(0, out.bytes.size(), "stdout carries the payload only")
        assertEquals(probe, err.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `a stdout that failed is told apart from a pipe whose reader went away`() {
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException("No space left on device")
        }
        fun io() = SystemTerminalIo(interactive = false, stdout = PrintStream(failing, false, Charsets.UTF_8), stderr = Capture(Charsets.UTF_8).stream)

        val file = io()
        file.out("x")
        file.flush()
        assertEquals(StdoutState.Failed, file.stdoutState(), "a stream the caller gave counts as a file")

        val pipe = io().apply { stdoutIsPipe = { true } }
        pipe.out("x")
        pipe.flush()
        assertEquals(StdoutState.ReaderGone, pipe.stdoutState())

        val written = SystemTerminalIo(stdout = Capture(Charsets.UTF_8).stream, stderr = Capture(Charsets.UTF_8).stream)
        written.out("x")
        assertEquals(StdoutState.Written, written.stdoutState())
    }

    /** `System.out` after a protocol's own writer on it failed, such as when its client hung up. */
    private fun sharedFailedStream() = object : PrintStream(ByteArrayOutputStream(), false, Charsets.UTF_8) {
        override fun checkError() = true
    }

    @Test
    fun `a terminal that wrote nothing is not blamed for another writer's failure`() {
        fun io() = SystemTerminalIo(interactive = false, stdout = sharedFailedStream(), stderr = Capture(Charsets.UTF_8).stream)

        val silent = io()
        silent.flush()
        assertEquals(StdoutState.Written, silent.stdoutState())

        val text = io()
        text.out("x")
        assertEquals(StdoutState.Failed, text.stdoutState(), "what it wrote may be lost")

        val bytes = io()
        bytes.payload.write(byteArrayOf(1), 0, 1)
        assertEquals(StdoutState.Failed, bytes.stdoutState(), "the payload counts as written")
    }

    @Test
    fun `a command that writes nothing exits 0 on a stream another writer broke`() {
        val serve = CommandSpec("serve", "Run until stopped")
        val program = Program(serve, "serve 1", mapOf(serve to CommandHandler { ExitStatus.OK }))
        val err = Capture(Charsets.UTF_8)

        val status = program.run(emptyArray(), SystemTerminalIo(interactive = false, stdout = sharedFailedStream(), stderr = err.stream))

        assertEquals(ExitStatus.OK, status)
        assertEquals("", err.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `the failure this guards against, spelled out`() {
        // Not a test of this module — a test of the platform behaviour the charset decision exists for, so
        // that the reason survives the next person who wonders why the wrappers are there.
        // `PrintStream` swallows the unmappable character and substitutes `?`; nothing throws, and
        // no return value says it happened.
        val ansiCodePage = Capture(Charset.forName("windows-1252"))
        ansiCodePage.stream.print("日本")
        ansiCodePage.stream.flush()

        assertEquals("??", ansiCodePage.bytes.toString(Charset.forName("windows-1252")))
    }
}
