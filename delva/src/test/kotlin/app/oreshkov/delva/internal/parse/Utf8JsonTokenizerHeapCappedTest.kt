package app.oreshkov.delva.internal.parse

import app.oreshkov.delva.internal.hash.Xxh3State
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs under `heapCappedTest` (256 MB heap): a 1 GiB string value is tokenized without ever being held whole. */
class Utf8JsonTokenizerHeapCappedTest {
    /** `["<pattern repeated>"]`, generated while it's read. */
    private class HugeStringStream(private val repeats: Long) : InputStream() {
        private val head = "[\"".toByteArray()
        private val tail = "\"]".toByteArray()
        private var index = 0L
        private var inPattern = 0

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            var n = 0
            while (n < len) {
                val source: ByteArray
                val position: Int
                when {
                    index < head.size -> { source = head; position = index.toInt() }
                    index - head.size < repeats * RAW.size -> { source = RAW; position = inPattern }
                    index - head.size - repeats * RAW.size < tail.size -> { source = tail; position = (index - head.size - repeats * RAW.size).toInt() }
                    else -> return if (n == 0) -1 else n
                }
                b[off + n++] = source[position]
                if (source === RAW) inPattern = (inPattern + 1) % RAW.size
                index++
            }
            return n
        }
    }

    @Test
    fun `a 1 GiB string streams through in bounded memory`() {
        val repeats = (1L shl 30) / RAW.size + 1
        val tokenizer = Utf8JsonTokenizer(HugeStringStream(repeats))
        assertEquals(JsonToken.START_ARRAY, tokenizer.next())

        val actual = Xxh3State().reset()
        var total = 0L
        var parts = 0L
        while (true) {
            assertEquals(JsonToken.STRING_PART, tokenizer.next())
            assertTrue(tokenizer.length in 1..Utf8JsonTokenizer.DEFAULT_STRING_CHUNK_BYTES)
            actual.update(tokenizer.bytes, tokenizer.offset, tokenizer.length)
            total += tokenizer.length
            parts++
            if (tokenizer.isLastPart) break
        }
        assertEquals(JsonToken.END_ARRAY, tokenizer.next())
        assertEquals(JsonToken.EOF, tokenizer.next())

        val expected = Xxh3State().reset()
        repeat(repeats.toInt()) { expected.update(DECODED) }
        assertEquals(repeats * DECODED.size, total)
        assertEquals(expected.digest64(), actual.digest64())
        assertTrue(parts > 10_000, "parts $parts")
    }

    private companion object {
        val RAW = "plain ascii text, €uro, \\n\\t\\\"escapes\\\", \\u00e9, \\ud83d\\ude00 and 😀 — ".toByteArray()
        val DECODED = "plain ascii text, €uro, \n\t\"escapes\", é, 😀 and 😀 — ".toByteArray()
    }
}
