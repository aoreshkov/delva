package app.oreshkov.delva.internal.parse

import app.oreshkov.delva.RejectReason
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Utf8JsonTokenizerTest {
    private fun bytes(vararg parts: Any): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (part in parts) {
            when (part) {
                is String -> out.write(part.toByteArray())
                is Int -> out.write(part)
                is ByteArray -> out.write(part)
                else -> error("bad part")
            }
        }
        return out.toByteArray()
    }

    private fun accepted(input: ByteArray, limits: ParseLimits = ParseLimits.DEFAULT, chunk: Int = 64 * 1024): List<String> {
        val results = listOf(1, 2, 7, 64 * 1024).map { tokenize(input, limits, window = it, chunk = chunk) }
        assertTrue(results.all { it == results[0] }, "window sizes disagree: $results")
        val result = results[0]
        assertTrue(result is Tokenized.Accepted, "expected acceptance, got $result")
        return result.tokens
    }

    private fun accepted(text: String, limits: ParseLimits = ParseLimits.DEFAULT) = accepted(text.toByteArray(), limits)

    private fun rejected(input: ByteArray, limits: ParseLimits = ParseLimits.DEFAULT, chunk: Int = 64 * 1024): Tokenized.Rejected {
        val results = listOf(1, 2, 7, 64 * 1024).map { tokenize(input, limits, window = it, chunk = chunk) }
        assertTrue(results.all { it == results[0] }, "window sizes disagree: $results")
        val result = results[0]
        assertTrue(result is Tokenized.Rejected, "expected rejection, got $result")
        return result
    }

    private fun rejected(text: String, limits: ParseLimits = ParseLimits.DEFAULT) = rejected(text.toByteArray(), limits)

    private fun assertRejected(reason: RejectReason, offset: Long, input: ByteArray, limits: ParseLimits = ParseLimits.DEFAULT) {
        assertEquals(Tokenized.Rejected(reason, offset), rejected(input, limits))
    }

    private fun assertRejected(reason: RejectReason, offset: Long, text: String, limits: ParseLimits = ParseLimits.DEFAULT) =
        assertRejected(reason, offset, text.toByteArray(), limits)

    @Test
    fun `tokens and offsets of a small document`() {
        val text = """ {"a": [1, -2.5e3, "x"], "b":{}, "c": true, "d": false, "e": null} """
        val tokenizer = Utf8JsonTokenizer(ByteArrayInputStream(text.toByteArray()))
        val seen = ArrayList<String>()
        while (true) {
            val token = tokenizer.next()
            seen += "${JsonToken.name(token)}@${tokenizer.byteOffset}"
            if (token == JsonToken.EOF) break
        }
        assertEquals(
            listOf(
                "START_OBJECT@1", "KEY@2", "START_ARRAY@7", "NUMBER@8", "NUMBER@11", "STRING@19", "END_ARRAY@22",
                "KEY@25", "START_OBJECT@29", "END_OBJECT@30", "KEY@33", "TRUE@38", "KEY@44", "FALSE@49",
                "KEY@56", "NULL@61", "END_OBJECT@65", "EOF@67",
            ),
            seen,
        )
        assertEquals(JsonToken.EOF, tokenizer.next())
        assertEquals(
            listOf(
                "START_OBJECT", "KEY:a", "START_ARRAY", "NUMBER:1", "NUMBER:-2.5e3", "STRING:x", "END_ARRAY",
                "KEY:b", "START_OBJECT", "END_OBJECT", "KEY:c", "TRUE", "KEY:d", "FALSE", "KEY:e", "NULL", "END_OBJECT",
            ),
            accepted(text),
        )
    }

    @Test
    fun `depth reports open containers`() {
        val tokenizer = Utf8JsonTokenizer(ByteArrayInputStream("[{\"a\":[]}]".toByteArray()))
        val depths = generateSequence { tokenizer.next().takeIf { it != JsonToken.EOF }?.let { tokenizer.depth } }.toList()
        assertEquals(listOf(1, 2, 2, 3, 2, 1, 0), depths)
    }

    @Test
    fun `scalar roots and whitespace`() {
        assertEquals(listOf("NUMBER:0"), accepted("0"))
        assertEquals(listOf("NUMBER:-0.0e+00"), accepted(" \t\r\n-0.0e+00\n"))
        assertEquals(listOf("STRING:"), accepted("\"\""))
        assertEquals(listOf("NULL"), accepted("null"))
        assertRejected(RejectReason.MALFORMED_JSON, 0, "")
        assertRejected(RejectReason.MALFORMED_JSON, 3, "   ")
        assertRejected(RejectReason.MALFORMED_JSON, 2, "1 2")
        assertRejected(RejectReason.MALFORMED_JSON, 1, "01")
        assertRejected(RejectReason.MALFORMED_JSON, 0, "\u000B1")
        assertRejected(RejectReason.MALFORMED_JSON, 3, "{}\n\u000C")
    }

    @Test
    fun `a leading byte-order mark is skipped and counted in offsets`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(listOf("START_OBJECT", "END_OBJECT"), accepted(bytes(bom, "{}")))
        assertRejected(RejectReason.MALFORMED_JSON, 5, bytes(bom, "{}", "x"))
        // A BOM after whitespace, a second BOM, or a partial BOM is an unexpected byte.
        assertRejected(RejectReason.MALFORMED_JSON, 1, bytes(" ", bom, "{}"))
        assertRejected(RejectReason.MALFORMED_JSON, 3, bytes(bom, bom, "{}"))
        assertRejected(RejectReason.MALFORMED_JSON, 0, bytes(0xEF, 0xBB, "{}"))
        assertRejected(RejectReason.MALFORMED_JSON, 0, bytes(0xEF, 0xBB))
        assertRejected(RejectReason.MALFORMED_JSON, 3, bytes(bom))
        assertRejected(RejectReason.MALFORMED_JSON, 2, bytes("[1", bom, "]"))
        // Inside a string it's the character U+FEFF.
        assertEquals(listOf("STRING:\uFEFF"), accepted(bytes("\"", bom, "\"")))
    }

    @Test
    fun `depth limit`() {
        val limits = ParseLimits(maxDepth = 3)
        assertEquals(6, accepted("[[{}]]", limits).size)
        assertRejected(RejectReason.LIMIT_EXCEEDED, 3, "[[[[]]]]", limits)
        assertRejected(RejectReason.LIMIT_EXCEEDED, 11, """{"a":[{"b":[]}]}""", limits)
        assertEquals(listOf("NUMBER:1"), accepted("1", ParseLimits(maxDepth = 0)))
        assertRejected(RejectReason.LIMIT_EXCEEDED, 0, "[]", ParseLimits(maxDepth = 0))

        val deep = "[".repeat(1024) + "]".repeat(1024)
        assertEquals(2048, accepted(deep).size)
        assertRejected(RejectReason.LIMIT_EXCEEDED, 1024, "[".repeat(1025) + "]".repeat(1025))
    }

    @Test
    fun `number length limit and range`() {
        val limits = ParseLimits(maxNumberLength = 5)
        assertEquals(listOf("NUMBER:-1.25"), accepted("-1.25", limits))
        assertRejected(RejectReason.LIMIT_EXCEEDED, 1, "[-1.255]", limits)
        // Grammar problems are found before the length is checked.
        assertRejected(RejectReason.MALFORMED_JSON, 8, "[123456.]", limits)

        assertEquals(listOf("NUMBER:0e3000000000"), accepted("0e3000000000"))
        assertEquals(listOf("NUMBER:0.1e2147483647"), accepted("0.1e2147483647"))
        assertEquals(listOf("NUMBER:1e-2147483647"), accepted("1e-2147483647"))
        assertEquals(listOf("NUMBER:0.01e-2147483646"), accepted("0.01e-2147483646"))
        assertRejected(RejectReason.NUMBER_OUT_OF_RANGE, 1, "[1e2147483647]")
        assertRejected(RejectReason.NUMBER_OUT_OF_RANGE, 0, "0.01e-2147483648")
        assertRejected(RejectReason.NUMBER_OUT_OF_RANGE, 0, "1e000000000000000000000000000009999999999")
        assertEquals(listOf("NUMBER:10000e2147483642"), accepted("10000e2147483642"))
        assertRejected(RejectReason.NUMBER_OUT_OF_RANGE, 0, "100000e2147483642")
    }

    @Test
    fun `number grammar`() {
        for (bad in listOf("-", "-a", "1.", "1.e3", ".5", "1e", "1e+", "+1", "0x1", "1.5e3.2", "--1", "- 1", "Infinity", "NaN")) {
            val result = rejected(bad)
            assertEquals(RejectReason.MALFORMED_JSON, result.reason, bad)
        }
        assertEquals(listOf("START_ARRAY", "NUMBER:1E+2", "NUMBER:-0", "NUMBER:10.01e-0", "END_ARRAY"), accepted("[1E+2,-0,10.01e-0]"))
    }

    @Test
    fun `invalid UTF-8 inside strings and outside`() {
        val cases = listOf(
            byteArrayOf(0xC0.toByte(), 0x80.toByte()) to "overlong 2-byte",
            byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte()) to "overlong 3-byte",
            byteArrayOf(0xF0.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()) to "overlong 4-byte",
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) to "UTF-8 surrogate",
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()) to "above U+10FFFF",
            byteArrayOf(0xF5.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()) to "invalid lead",
            byteArrayOf(0x80.toByte()) to "lone continuation",
            byteArrayOf(0xE2.toByte(), 0x82.toByte()) to "truncated before the quote",
            byteArrayOf(0xFF.toByte()) to "0xFF",
        )
        for ((sequence, name) in cases) {
            assertEquals(Tokenized.Rejected(RejectReason.INVALID_UTF8, 3), rejected(bytes("[\"a", sequence, "\"]")), name)
            assertEquals(Tokenized.Rejected(RejectReason.INVALID_UTF8, 2), rejected(bytes("{\"", sequence, "\":1}")), "$name in a key")
            assertEquals(Tokenized.Rejected(RejectReason.MALFORMED_JSON, 1), rejected(bytes("[", sequence, "]")), "$name outside")
        }
        assertRejected(RejectReason.INVALID_UTF8, 1, bytes("\"", 0xF0, 0x9F, 0x98))
        // Every valid length class, at any window size.
        assertEquals(listOf("STRING:\u007F\u0080\u07FF\u0800\uD7FF\uE000\uFFFF\uD800\uDC00\uDBFF\uDFFF"), accepted("\"\u007F\u0080\u07FF\u0800\uD7FF\uE000\uFFFF\uD800\uDC00\uDBFF\uDFFF\""))
    }

    @Test
    fun `control characters must be escaped`() {
        for (c in 0 until 0x20) {
            assertRejected(RejectReason.MALFORMED_JSON, 2, bytes("[\"", c, "\"]"))
            val escaped = "[\"\\u%04x\",\"\\u%04X\"]".format(c, c)
            val char = c.toChar().toString()
            assertEquals(listOf("START_ARRAY", "STRING:$char", "STRING:$char", "END_ARRAY"), accepted(escaped))
        }
        assertEquals(listOf("STRING:\u007F"), accepted(bytes("\"", 0x7F, "\"")))
    }

    @Test
    fun `escapes`() {
        assertEquals(listOf("STRING:\"\\/\b\u000C\n\r\t"), accepted("""  "\"\\\/\b\f\n\r\t"  """))
        assertEquals(listOf("STRING:é€😀"), accepted("\"\\u00e9\\u20AC\\ud83d\\uDE00\""))
        assertEquals(listOf("KEY:a😀", "NUMBER:1").let { listOf("START_OBJECT") + it + "END_OBJECT" }, accepted("{\"a\\uD83D\\uDE00\":1}"))
        assertRejected(RejectReason.MALFORMED_JSON, 3, """"a\x"""")
        assertRejected(RejectReason.MALFORMED_JSON, 5, """"\u12G4"""")
        assertRejected(RejectReason.MALFORMED_JSON, 5, """"\u12""")
        assertRejected(RejectReason.MALFORMED_JSON, 2, "\"\\")
        assertRejected(RejectReason.INVALID_STRING, 2, """"a\uD800"""")
        assertRejected(RejectReason.INVALID_STRING, 2, """"a\uD800\n"""")
        assertRejected(RejectReason.INVALID_STRING, 2, """"a\uD800\u0041"""")
        assertRejected(RejectReason.INVALID_STRING, 2, """"a\uDC00"""")
        assertRejected(RejectReason.INVALID_STRING, 1, """"\uD800\uD800"""")
        assertRejected(RejectReason.INVALID_STRING, 1, "\"\\uD800\\")
        // Inside the pair, a bad hex digit is a syntax error.
        assertRejected(RejectReason.MALFORMED_JSON, 9, """"\uD800\uZ"""")
        assertRejected(RejectReason.MALFORMED_JSON, 3, "\"ab")
    }

    @Test
    fun `key and string length limits count bytes after unescaping`() {
        val limits = ParseLimits(maxKeyBytes = 4, maxStringBytes = 6)
        assertEquals(listOf("START_OBJECT", "KEY:abcd", "STRING:a\nc€", "END_OBJECT"), accepted("{\"abcd\":\"a\\nc€\"}", limits))
        assertRejected(RejectReason.LIMIT_EXCEEDED, 6, "{\"abcde\":1}", limits)
        assertRejected(RejectReason.LIMIT_EXCEEDED, 5, "{\"abc\\u00e9\":1}", limits)
        assertRejected(RejectReason.LIMIT_EXCEEDED, 8, "[\"abcdefg\"]", limits)
        assertRejected(RejectReason.LIMIT_EXCEEDED, 7, "[\"abcde€\"]", limits)
        // The limit is found before a later problem in the same string.
        assertRejected(RejectReason.LIMIT_EXCEEDED, 8, "[\"abcdefg\\x\"]", limits)
        assertRejected(RejectReason.MALFORMED_JSON, 6, "[\"abc\\x\"]", limits)
        // The key limit and the string limit are separate.
        assertEquals(listOf("STRING:abcdef"), accepted("\"abcdef\"", limits))
        assertEquals(listOf("START_OBJECT", "KEY:", "STRING:", "END_OBJECT"), accepted("{\"\":\"\"}", ParseLimits(maxKeyBytes = 0, maxStringBytes = 0)))
    }

    @Test
    fun `document size limit counts every input byte`() {
        assertEquals(listOf("START_ARRAY", "END_ARRAY"), accepted("[] ", ParseLimits(maxDocumentBytes = 3)))
        assertRejected(RejectReason.LIMIT_EXCEEDED, 3, "[]  ", ParseLimits(maxDocumentBytes = 3))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertRejected(RejectReason.LIMIT_EXCEEDED, 4, bytes(bom, "[]"), ParseLimits(maxDocumentBytes = 4))
    }

    @Test
    fun `long strings arrive in code-point aligned parts`() {
        val chunk = 8
        val value = "abcdefg€h😀😀ij\nkl" + "x".repeat(20)
        val json = "[\"" + value.replace("\n", "\\n") + "\",\"12345678\",\"123456789\"]"
        for (window in listOf(1, 3, 16, 1024)) {
            val tokenizer = Utf8JsonTokenizer(ByteArrayInputStream(json.toByteArray()), windowBytes = window, stringChunkBytes = chunk)
            assertEquals(JsonToken.START_ARRAY, tokenizer.next())
            val parts = ArrayList<String>()
            var firstOffset = -1L
            while (true) {
                assertEquals(JsonToken.STRING_PART, tokenizer.next())
                if (firstOffset < 0) firstOffset = tokenizer.byteOffset
                assertTrue(tokenizer.length in 1..chunk)
                parts += String(tokenizer.bytes, tokenizer.offset, tokenizer.length, Charsets.UTF_8)
                if (tokenizer.isLastPart) break
            }
            assertEquals(1, firstOffset)
            assertEquals(value, parts.joinToString(""))
            // A character that doesn't fit ends the part early.
            assertEquals(listOf("abcdefg", "€h😀", "😀ij\nk", "lxxxxxxx"), parts.take(4), "window $window")
            assertEquals(JsonToken.STRING, tokenizer.next())
            assertEquals("12345678", String(tokenizer.bytes, tokenizer.offset, tokenizer.length))
            assertEquals(JsonToken.STRING_PART, tokenizer.next())
            assertEquals(JsonToken.STRING_PART, tokenizer.next())
            assertTrue(tokenizer.isLastPart)
            assertEquals("9", String(tokenizer.bytes, tokenizer.offset, tokenizer.length))
            assertEquals(JsonToken.END_ARRAY, tokenizer.next())
        }
        // Keys are never split.
        val longKey = "k".repeat(100)
        assertEquals(listOf("START_OBJECT", "KEY:$longKey", "NUMBER:1", "END_OBJECT"), accepted("{\"$longKey\":1}".toByteArray(), chunk = 4))
    }

    @Test
    fun `after EOF next keeps returning EOF, after a rejection it fails`() {
        val done = Utf8JsonTokenizer(ByteArrayInputStream("1".toByteArray()))
        assertEquals(JsonToken.NUMBER, done.next())
        repeat(3) { assertEquals(JsonToken.EOF, done.next()) }

        val failed = Utf8JsonTokenizer(ByteArrayInputStream("[x".toByteArray()))
        assertEquals(JsonToken.START_ARRAY, failed.next())
        assertFailsWith<app.oreshkov.delva.DocumentRejectedException> { failed.next() }
        assertFailsWith<IllegalStateException> { failed.next() }

        failed.reset(ByteArrayInputStream("\"again\"".toByteArray()))
        assertEquals(JsonToken.STRING, failed.next())
        assertEquals("again", String(failed.bytes, failed.offset, failed.length))
        assertEquals(JsonToken.EOF, failed.next())
    }

    @Test
    fun `IO errors propagate unchanged`() {
        val boom = IOException("disk gone")
        val stream = object : InputStream() {
            var calls = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (calls++ > 0) throw boom
                b[off] = '['.code.toByte()
                return 1
            }
        }
        val tokenizer = Utf8JsonTokenizer(stream)
        assertEquals(JsonToken.START_ARRAY, tokenizer.next())
        assertEquals(boom, assertFailsWith<IOException> { tokenizer.next() })
    }

    @Test
    fun `literals`() {
        assertEquals(listOf("START_ARRAY", "TRUE", "FALSE", "NULL", "END_ARRAY"), accepted("[true,false,null]"))
        assertRejected(RejectReason.MALFORMED_JSON, 4, "[tru]")
        assertRejected(RejectReason.MALFORMED_JSON, 5, "[true1]")
        assertRejected(RejectReason.MALFORMED_JSON, 3, "nul")
        assertRejected(RejectReason.MALFORMED_JSON, 0, "True")
    }

    @Test
    fun `structure errors`() {
        assertRejected(RejectReason.MALFORMED_JSON, 3, "[1,]")
        assertRejected(RejectReason.MALFORMED_JSON, 1, "[,1]")
        assertRejected(RejectReason.MALFORMED_JSON, 4, "{\"a\"1}")
        assertRejected(RejectReason.MALFORMED_JSON, 7, "{\"a\":1,}")
        assertRejected(RejectReason.MALFORMED_JSON, 1, "{1:1}")
        assertRejected(RejectReason.MALFORMED_JSON, 2, "[1}")
        assertRejected(RejectReason.MALFORMED_JSON, 6, "{\"a\":1]")
        assertRejected(RejectReason.MALFORMED_JSON, 2, "[1")
        assertRejected(RejectReason.MALFORMED_JSON, 2, "{}}")
        assertRejected(RejectReason.MALFORMED_JSON, 0, "]")
    }
}
