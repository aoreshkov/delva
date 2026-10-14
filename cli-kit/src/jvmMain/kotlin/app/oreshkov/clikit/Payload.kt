package app.oreshkov.clikit

import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** A [TerminalIo] that takes the payload as bytes as well as text. */
public interface BinaryPayload {
    /**
     * The payload as UTF-8 bytes, interleaved in order with [TerminalIo.out]. Its `flush` flushes only at a terminal;
     * the program's run flushes what is left. Never close it: it is stdout.
     */
    public val payload: OutputStream
}

/**
 * The payload as a byte stream, for output too large to hold as a string: a stored document of gigabytes, written
 * straight from where it is read. A terminal without a byte stream of its own — a recording one, in a test — gets
 * the bytes decoded and passed to [TerminalIo.out], so a test sees exactly what a pipe would.
 */
public fun TerminalIo.payloadStream(): OutputStream = (this as? BinaryPayload)?.payload ?: Utf8TextOutputStream(::out)

/**
 * Decodes UTF-8 bytes as they are written and hands the text on in pieces, a character split across two writes
 * included. Malformed bytes become U+FFFD, as they would on a terminal.
 */
internal class Utf8TextOutputStream(private val text: (String) -> Unit) : OutputStream() {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val bytes = ByteBuffer.allocate(BUFFER_BYTES)
    private val chars = CharBuffer.allocate(BUFFER_BYTES)

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        var remaining = len
        while (remaining > 0) {
            val n = minOf(remaining, bytes.remaining())
            bytes.put(b, offset, n)
            offset += n
            remaining -= n
            decode(endOfInput = false)
        }
    }

    override fun flush() {
        emit()
    }

    override fun close() {
        decode(endOfInput = true)
        decoder.flush(chars)
        emit()
    }

    private fun decode(endOfInput: Boolean) {
        bytes.flip()
        while (true) {
            val result = decoder.decode(bytes, chars, endOfInput)
            if (result.isOverflow) emit() else break
        }
        // What is left is the start of a character whose remaining bytes haven't arrived yet.
        bytes.compact()
        emit()
    }

    private fun emit() {
        if (chars.position() == 0) return
        chars.flip()
        text(chars.toString())
        chars.clear()
    }

    private companion object {
        const val BUFFER_BYTES = 8192
    }
}

/**
 * JSON Lines on [out]: one JSON value per line, each written with a [JsonWriter]. A value that is already JSON and
 * too large to hold — a stored document — goes in with [JsonWriter.rawValue], writing its bytes to [stream]:
 *
 * ```
 * lines.record {
 *     writeObject {
 *         field("doc", hit.docId.raw)
 *         name("value").rawValue { hit.writeJsonTo(lines.stream()) }
 *     }
 * }
 * ```
 *
 * Nothing here flushes [out]: records accumulate in its buffer, and whoever owns [out] flushes it.
 */
public class JsonLines(private val out: OutputStream) {
    private val text = OutputStreamWriter(
        object : FilterOutputStream(out) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
            }

            override fun flush() = Unit
        },
        Charsets.UTF_8,
    )

    /** [out], once every character written so far is in it: where a value's own bytes go. */
    public fun stream(): OutputStream {
        text.flush()
        return out
    }

    /** One record: [write] writes exactly one JSON value, and a newline ends the line. */
    public fun record(write: JsonWriter.() -> Unit) {
        val json = JsonWriter(text)
        json.write()
        check(json.isComplete) { "a JSON Lines record must be one complete value" }
        text.write("\n")
        text.flush()
    }
}
