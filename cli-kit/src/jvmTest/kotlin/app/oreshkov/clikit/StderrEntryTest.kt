package app.oreshkov.clikit

import app.oreshkov.clikit.testing.RecordingTerminalIo
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Entries on stderr whose message has line breaks: the format a reader sees, that no message can forge an entry, and
 * that entries from several threads stay whole. The property's cases each have their own seed, printed when one fails;
 * `-Ddelva.seed=<seed>` runs just that case again.
 */
class StderrEntryTest {

    private val stamp = "2026-11-01T19:02:13.897Z"

    @Test
    fun `multi-line entries render as the golden file shows`() {
        val io = RecordingTerminalIo(borderStyle = BorderStyle.Unicode)
        val output = Output(io, machineReadable = false, verbose = true)
        output.log(
            LogLevel.DEBUG,
            "REQUEST: https://example.com/game/startup\nMETHOD: POST\nHEADERS\n\tAccept: application/json\n\nBODY\n",
            stamp = stamp,
        )
        // Not verbose, so no stack trace, whose frames differ from one JDK to the next.
        Output(io, machineReadable = false)
            .log(LogLevel.WARN, "retrying", IllegalStateException("upstream said:\r\n503 Service Unavailable"), stamp)
        output.log(LogLevel.INFO, "a carriage return\r$stamp ERROR forged stays a continuation")
        output.notice("2 documents\nnext page: --after k2")
        output.warn("--limit was given twice\nthe last one is used")
        output.error("can't read 'a.json'\n${Char(0x1B)}[2J and the rest of the line")

        val ascii = RecordingTerminalIo(borderStyle = BorderStyle.Ascii)
        Output(ascii, machineReadable = false).error("where the console can't show a box-drawing line\nthe marker is ASCII")

        Golden.assertMatches("log-multiline.txt", io.stderr + ascii.stderr)
    }

    @Test
    fun `no message can forge an entry or reach the terminal raw`() = forEachCase { random ->
        val border = if (random.nextBoolean()) BorderStyle.Unicode else BorderStyle.Ascii
        val styler = Styler(enabled = random.nextBoolean())
        val io = RecordingTerminalIo(styler = styler, borderStyle = border)
        val output = Output(io, machineReadable = false, verbose = true)
        val message = message(random)
        val case = random.nextInt(6)
        when (case) {
            0 -> output.log(LogLevel.entries.random(random), message, stamp = stamp)
            1 -> output.log(LogLevel.entries.random(random), message)
            2 -> output.log(LogLevel.ERROR, "failed", IllegalStateException(message), stamp)
            3 -> output.notice(message)
            4 -> output.warn(message)
            else -> output.error(message)
        }
        val shown = io.stderr.replace(SGR, "")
        assertTrue(shown.endsWith("\n"), shown)
        val lines = shown.removeSuffix("\n").split('\n')
        lines.forEach { line ->
            val raw = line.firstOrNull(::isRawControl)
            assertTrue(raw == null, "U+%04X reached the terminal in '$line'".format(raw?.code))
        }
        // The message's lines, then, when an error was logged under verbose, its stack trace.
        val messageLines = message.trimEnd('\n', '\r').lines().size
        val continuation = Regex("^ *[${border.vertical}]( |$)")
        lines.take(messageLines).drop(1).forEach { line ->
            assertTrue(continuation.containsMatchIn(line), "'$line' is not a continuation:\n$shown")
        }
        val trace = lines.drop(messageLines)
        assertEquals(case == 2, trace.isNotEmpty(), shown)
        if (trace.isNotEmpty()) {
            assertTrue(trace.first().startsWith("java.lang.IllegalStateException: "), shown)
            trace.drop(1).forEach { line -> assertTrue(line.startsWith("    "), "'$line' is not a trace's frame:\n$shown") }
        }
    }

    @Test
    fun `entries logged from several threads come out whole`() {
        val sink = SynchronizedSink()
        val io = SystemTerminalIo(
            interactive = false,
            borderStyle = BorderStyle.Ascii,
            stdout = PrintStream(ByteArrayOutputStream(), false, Charsets.UTF_8),
            stderr = PrintStream(sink, false, Charsets.UTF_8),
            escapesInterpreted = false,
        )
        val output = Output(io, machineReadable = false)
        val start = CountDownLatch(1)
        val threads = List(THREADS) { t ->
            thread {
                start.await()
                repeat(ENTRIES) { e -> output.log(LogLevel.INFO, List(LINES) { "t$t e$e" }.joinToString("\n")) }
            }
        }
        start.countDown()
        threads.forEach(Thread::join)

        val lines = sink.text().removeSuffix("\n").split('\n')
        assertEquals(THREADS * ENTRIES * LINES, lines.size)
        lines.chunked(LINES).forEach { entry ->
            val id = entry.first().removePrefix("INFO  ")
            assertEquals(List(LINES - 1) { "      | $id" }, entry.drop(1), entry.joinToString("\n"))
        }
        assertEquals(THREADS * ENTRIES, lines.chunked(LINES).map { it.first() }.distinct().size)
    }

    /** One write at a time, as a file descriptor takes them, so the test sees what `PrintStream` hands over. */
    private class SynchronizedSink : OutputStream() {
        private val bytes = ByteArrayOutputStream()

        @Synchronized
        override fun write(b: Int) = bytes.write(b)

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) = bytes.write(b, off, len)

        @Synchronized
        fun text(): String = bytes.toString(Charsets.UTF_8)
    }

    /** Text, fake stamps and markers, and every character a terminal could act on, line breaks among them. */
    private fun message(random: Random): String = buildString {
        repeat(random.nextInt(0, 30)) {
            when (random.nextInt(5)) {
                0 -> append(PIECES.random(random))
                1 -> append(Char(random.nextInt(0, 0x20)))
                2 -> append(Char(random.nextInt(0x7F, 0xA0)))
                3 -> append(BIDI.random(random))
                else -> append(if (random.nextBoolean()) '\n' else '\r')
            }
        }
    }

    private fun isRawControl(char: Char): Boolean {
        val code = char.code
        return code < 0x20 || code in 0x7F..0x9F || char in BIDI
    }

    private fun forEachCase(cases: Int = 500, property: (Random) -> Unit) {
        val seeds = System.getProperty("delva.seed")?.toLong()?.let(::listOf)
            ?: Random.nextLong().let { run -> List(cases) { run + it } }
        for (seed in seeds) {
            try {
                property(Random(seed))
            } catch (failure: AssertionError) {
                throw AssertionError("case failed (seed=$seed)\n  reproduce with: -Ddelva.seed=$seed", failure)
            }
        }
    }

    private companion object {
        const val THREADS = 8
        const val ENTRIES = 200
        const val LINES = 3

        /** The kit's own styling, which is the one escape allowed through. */
        val SGR = Regex("\u001B\\[[0-9;]*m")

        val BIDI = ((0x202A..0x202E) + (0x2066..0x2069)).map(::Char)

        val PIECES = listOf("a", "word ", " ", "    ", "│ ", "| ", "2026-11-01T00:00:00.000Z ERROR ", "warning: ", "error: ", "Жана")
    }
}
