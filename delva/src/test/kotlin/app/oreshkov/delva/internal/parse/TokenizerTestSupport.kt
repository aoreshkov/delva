package app.oreshkov.delva.internal.parse

import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.testkit.json.JsonLimits
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONull
import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.random.Random

/** Result of tokenizing a whole document, with string parts joined. */
internal sealed interface Tokenized {
    data class Accepted(val tokens: List<String>) : Tokenized

    data class Rejected(val reason: RejectReason, val byteOffset: Long) : Tokenized
}

internal fun tokenize(
    input: ByteArray,
    limits: ParseLimits = ParseLimits.DEFAULT,
    window: Int = Utf8JsonTokenizer.DEFAULT_WINDOW_BYTES,
    chunk: Int = Utf8JsonTokenizer.DEFAULT_STRING_CHUNK_BYTES,
    stream: InputStream = ByteArrayInputStream(input),
): Tokenized {
    val tokenizer = Utf8JsonTokenizer(stream, limits, window, chunk)
    return try {
        Tokenized.Accepted(drain(tokenizer, chunk))
    } catch (e: DocumentRejectedException) {
        Tokenized.Rejected(e.reason, e.byteOffset)
    }
}

/**
 * Token descriptions until EOF: `KIND` or `KIND:content`. Checks the part invariants on the way: parts only for
 * strings longer than [chunk], each non-empty, at most [chunk] bytes, cut on code-point boundaries.
 */
internal fun drain(source: JsonTokenSource, chunk: Int): List<String> {
    val out = ArrayList<String>()
    val parts = java.io.ByteArrayOutputStream()
    while (true) {
        val token = source.next()
        when (token) {
            JsonToken.EOF -> {
                check(parts.size() == 0) { "EOF inside a string" }
                return out
            }
            JsonToken.KEY, JsonToken.STRING, JsonToken.NUMBER -> {
                check(parts.size() == 0) { "${JsonToken.name(token)} inside a string" }
                check(token != JsonToken.STRING || source.length <= chunk) { "STRING longer than the chunk" }
                out += JsonToken.name(token) + ":" + String(source.bytes, source.offset, source.length, Charsets.UTF_8)
            }
            JsonToken.STRING_PART -> {
                check(source.length in 1..chunk) { "part of ${source.length} bytes" }
                check(!isContinuation(source.bytes[source.offset])) { "part starts inside a code point" }
                parts.write(source.bytes, source.offset, source.length)
                if (source.isLastPart) {
                    check(parts.size() > chunk) { "parts for a string of ${parts.size()} bytes" }
                    out += "STRING:" + parts.toString(Charsets.UTF_8)
                    parts.reset()
                }
            }
            else -> {
                check(parts.size() == 0) { "${JsonToken.name(token)} inside a string" }
                check(source.length == 0)
                out += JsonToken.name(token)
            }
        }
    }
}

private fun isContinuation(b: Byte) = b.toInt() and 0xC0 == 0x80

/** The tokens the oracle's tree implies. */
internal fun expectedTokens(value: OValue): List<String> = ArrayList<String>().also { appendTokens(value, it) }

private fun appendTokens(value: OValue, out: MutableList<String>) {
    when (value) {
        ONull -> out += "NULL"
        is OBool -> out += if (value.value) "TRUE" else "FALSE"
        is ONum -> out += "NUMBER:" + value.text
        is OStr -> out += "STRING:" + value.value
        is OArr -> {
            out += "START_ARRAY"
            value.items.forEach { appendTokens(it, out) }
            out += "END_ARRAY"
        }
        is OObj -> {
            out += "START_OBJECT"
            value.members.forEach { (k, v) ->
                out += "KEY:$k"
                appendTokens(v, out)
            }
            out += "END_OBJECT"
        }
    }
}

internal fun ParseLimits.toOracle(): JsonLimits =
    JsonLimits(maxDepth, maxDocumentBytes, maxKeyBytes, maxStringBytes, maxNumberLength)

/** Returns random short reads (1 to [maxRead] bytes), like a slow network stream. */
internal class ShortReadStream(private val data: ByteArray, private val random: Random, private val maxRead: Int = 5) : InputStream() {
    private var position = 0

    override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position == data.size) return -1
        val n = minOf(len, data.size - position, random.nextInt(1, maxRead + 1))
        System.arraycopy(data, position, b, off, n)
        position += n
        return n
    }
}
