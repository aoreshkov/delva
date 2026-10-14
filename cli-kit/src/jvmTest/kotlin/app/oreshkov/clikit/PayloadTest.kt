package app.oreshkov.clikit

import app.oreshkov.clikit.testing.RecordingTerminalIo
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The payload as bytes: through a pipe as it is, into a test as text, and as JSON Lines. */
class PayloadTest {

    /** One, two, three and four UTF-8 bytes. */
    private val probe = "a—日🔑"

    @Test
    fun `bytes split mid-character across writes decode whole`() {
        val text = StringBuilder()
        val stream = Utf8TextOutputStream { text.append(it) }
        val bytes = probe.toByteArray(Charsets.UTF_8)
        for (b in bytes) stream.write(b.toInt())
        stream.flush()
        assertEquals(probe, text.toString())

        text.clear()
        val big = probe.repeat(5_000).toByteArray(Charsets.UTF_8)
        stream.write(big, 0, 7_001)
        stream.write(big, 7_001, big.size - 7_001)
        stream.close()
        assertEquals(probe.repeat(5_000), text.toString())
    }

    @Test
    fun `malformed bytes become the replacement character`() {
        val text = StringBuilder()
        Utf8TextOutputStream { text.append(it) }.use { it.write(byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte())) }
        assertEquals("a�b", text.toString())
    }

    @Test
    fun `a terminal without a byte stream gets the payload as text`() {
        val io = RecordingTerminalIo()
        io.payloadStream().apply { write(probe.toByteArray(Charsets.UTF_8)); flush() }
        assertEquals(probe, io.stdout)
    }

    @Test
    fun `down a pipe, payload bytes and text share one buffer, in order, until flushed`() {
        val bytes = ByteArrayOutputStream()
        val io = SystemTerminalIo(interactive = false, stdout = PrintStream(bytes, false, Charsets.UTF_8))
        io.out("text ")
        io.payload.write("bytes ".toByteArray())
        io.payload.flush()
        io.out("text")
        // PrintStream over a ByteArrayOutputStream holds nothing back, so order is what's checked here;
        // that nothing forces a flush per write is the next test.
        assertEquals("text bytes text", bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun `down a pipe nothing is flushed per write, and flush delivers it all`() {
        val sink = CountingFlushes()
        val io = SystemTerminalIo(interactive = false, stdout = PrintStream(sink, false, Charsets.UTF_8))
        repeat(1_000) { io.out("line\n"); io.payload.write('x'.code); io.payload.flush() }
        assertEquals(0, sink.flushes)
        io.flush()
        assertEquals(1, sink.flushes)
    }

    @Test
    fun `at a terminal every write is flushed, so output appears as it is made`() {
        val sink = CountingFlushes()
        val io = SystemTerminalIo(interactive = true, stdout = PrintStream(sink, false, Charsets.UTF_8))
        io.out("a")
        io.payload.write('b'.code)
        io.payload.flush()
        assertEquals(2, sink.flushes)
    }

    @Test
    fun `at a terminal with a legacy charset, payload bytes are re-encoded like text`() {
        val cp1252 = Charset.forName("windows-1252")
        val bytes = ByteArrayOutputStream()
        val io = SystemTerminalIo(interactive = true, stdout = PrintStream(bytes, false, cp1252))
        io.payload.write("é—".toByteArray(Charsets.UTF_8))
        io.payload.flush()
        assertEquals("é—", bytes.toString(cp1252))
    }

    @Test
    fun `JSON Lines are one value per line, with a large value streamed in as bytes`() {
        val bytes = ByteArrayOutputStream()
        val lines = JsonLines(bytes)
        for (doc in 1..2) {
            lines.record {
                writeObject {
                    field("doc", doc)
                    name("value").rawValue { lines.stream().write("""{"name":"ада"}""".toByteArray()) }
                }
            }
        }
        assertEquals(
            """{"doc":1,"value":{"name":"ада"}}""" + "\n" + """{"doc":2,"value":{"name":"ада"}}""" + "\n",
            bytes.toString(Charsets.UTF_8),
        )
        assertFailsWith<IllegalStateException> { lines.record { beginObject() } }
    }

    private class CountingFlushes : java.io.OutputStream() {
        var flushes = 0
        override fun write(b: Int) = Unit
        override fun flush() {
            flushes++
        }
    }
}
