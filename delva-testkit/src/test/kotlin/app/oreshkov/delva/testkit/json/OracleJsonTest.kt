package app.oreshkov.delva.testkit.json

import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.testkit.property.Gens
import app.oreshkov.delva.testkit.property.forAll
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OracleJsonTest {
    private fun accepted(json: String, limits: JsonLimits = JsonLimits()): OValue =
        assertIs<ParseResult.Accepted>(OracleJson.parse(json, limits), json).value

    private fun rejected(bytes: ByteArray, limits: JsonLimits = JsonLimits()): RejectReason =
        assertIs<ParseResult.Rejected>(OracleJson.parse(bytes, limits), bytes.decodeToString()).reason

    private fun rejected(json: String, limits: JsonLimits = JsonLimits()) = rejected(json.toByteArray(), limits)

    @Test
    fun `generated documents survive random formatting and minified output unchanged`() {
        for (profile in JsonProfile.entries) {
            forAll("round trip $profile", Gens.pair(JsonGen(profile, fragmentSize = 300).value(), Gens.long()), iterations = 60, maxSize = 60) { (value, seed) ->
                val formatted = JsonText.randomlyFormatted(value, Random(seed))
                assertEquals(value, accepted(formatted), formatted)
                val minified = JsonText.minified(value)
                assertEquals(value, accepted(minified))
                assertEquals(minified, JsonText.minified(accepted(formatted)))
            }
        }
    }

    @Test
    fun `minified output escapes only what it must`() {
        val value = OObj("k\"\\" to OStr("a\u0000\u0001\u001f\b\t\n\r" + 12.toChar() + "/\u007f\u00e9😀"))
        assertEquals("""{"k\"\\":"a\u0000\u0001\u001f\b\t\n\r\f/""" + "\u007f\u00e9😀\"}", JsonText.minified(value))
    }

    @Test
    fun `byte order mark only at the start`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(OObj(), assertIs<ParseResult.Accepted>(OracleJson.parse(bom + "{}".toByteArray())).value)
        assertEquals(RejectReason.MALFORMED_JSON, rejected(bom + bom + "{}".toByteArray()))
        assertEquals(RejectReason.MALFORMED_JSON, rejected("[1]".toByteArray() + bom))
        assertEquals(RejectReason.MALFORMED_JSON, rejected(bom))
        // Inside a string it's an ordinary character.
        assertEquals(OStr("\uFEFF"), assertIs<ParseResult.Accepted>(OracleJson.parse("\"".toByteArray() + bom + "\"".toByteArray())).value)
    }

    @Test
    fun `invalid UTF-8 is INVALID_UTF8 inside strings and MALFORMED_JSON elsewhere`() {
        val bad = byteArrayOf(0xC0.toByte(), 0x80.toByte())
        assertEquals(RejectReason.INVALID_UTF8, rejected("[\"a".toByteArray() + bad + "\"]".toByteArray()))
        assertEquals(RejectReason.INVALID_UTF8, rejected("{\"".toByteArray() + bad + "\":1}".toByteArray()))
        assertEquals(RejectReason.MALFORMED_JSON, rejected("[".toByteArray() + bad + "]".toByteArray()))
        // The first problem in document order wins.
        assertEquals(RejectReason.MALFORMED_JSON, rejected("[01, \"".toByteArray() + bad + "\"]".toByteArray()))
    }

    @Test
    fun `escapes and surrogates`() {
        assertEquals(OStr("😀"), accepted("\"\\ud83d\\uDE00\""))
        assertEquals(OStr("/\b" + 12.toChar() + "\n\r\t\"\\"), accepted("\"\\/\\b\\f\\n\\r\\t\\\"\\\\\""))
        assertEquals(RejectReason.INVALID_STRING, rejected("\"\\ud83d\""))
        assertEquals(RejectReason.INVALID_STRING, rejected("\"\\ude00\\ud83d\""))
        assertEquals(RejectReason.INVALID_STRING, rejected("\"\\ud83dx\""))
        assertEquals(RejectReason.MALFORMED_JSON, rejected("\"\\ud83d\\u12\""))
        assertEquals(RejectReason.MALFORMED_JSON, rejected("\"\\x\""))
        assertEquals(RejectReason.MALFORMED_JSON, rejected("\"a\tb\""))
    }

    @Test
    fun `duplicate keys compare after unescaping`() {
        assertEquals(RejectReason.DUPLICATE_KEY, rejected("""{"a":1,"\u0061":2}"""))
        assertEquals(RejectReason.DUPLICATE_KEY, rejected("""[{"x":{"a":1,"b":2,"a":3}}]"""))
        assertEquals(OObj("a" to OObj("a" to ONum("1"))), accepted("""{"a":{"a":1}}"""))
    }

    @Test
    fun `numbers keep their spelling and the exponent range is checked`() {
        assertEquals(OArr(ONum("-0.0e+00"), ONum("1E5"), ONum("0.1e2147483647")), accepted("[-0.0e+00,1E5,0.1e2147483647]"))
        assertEquals(RejectReason.NUMBER_OUT_OF_RANGE, rejected("[0.1e2147483648]"))
        assertEquals(RejectReason.NUMBER_OUT_OF_RANGE, rejected("[1e2147483647]"))
        assertEquals(ONum("0e99999999999999"), accepted("0e99999999999999"))
        for (bad in listOf("[01]", "[1.]", "[.5]", "[+1]", "[1e]", "[-]", "[NaN]", "[0x1]")) {
            assertEquals(RejectReason.MALFORMED_JSON, rejected(bad), bad)
        }
    }

    @Test
    fun `limits`() {
        assertIs<ParseResult.Accepted>(OracleJson.parse("[[[]]]", JsonLimits(maxDepth = 3)))
        assertEquals(RejectReason.LIMIT_EXCEEDED, rejected("[[[[]]]]", JsonLimits(maxDepth = 3)))
        assertEquals(RejectReason.LIMIT_EXCEEDED, rejected("""{"a":{"b":{"c":{}}}}""", JsonLimits(maxDepth = 3)))
        assertIs<ParseResult.Accepted>(OracleJson.parse("7", JsonLimits(maxDepth = 0)))
        assertEquals(RejectReason.LIMIT_EXCEEDED, rejected("""{"ключ":1}""", JsonLimits(maxKeyBytes = 7)))
        assertIs<ParseResult.Accepted>(OracleJson.parse("""{"ключ":1}""", JsonLimits(maxKeyBytes = 8)))
        assertEquals(RejectReason.LIMIT_EXCEEDED, rejected("\"\\u00e9\\u00e9\"", JsonLimits(maxStringBytes = 3)))
        assertEquals(RejectReason.LIMIT_EXCEEDED, rejected("[123456]", JsonLimits(maxNumberLength = 5)))
        assertEquals(RejectReason.LIMIT_EXCEEDED, rejected("[1, 2]", JsonLimits(maxDocumentBytes = 5)))
    }

    @Test
    fun `scalars and arrays are valid roots, and empty input is not`() {
        assertEquals(ONull, accepted(" null "))
        assertEquals(OStr(""), accepted("\"\""))
        assertEquals(OArr(), accepted("[]"))
        assertEquals(RejectReason.MALFORMED_JSON, rejected(""))
        assertEquals(RejectReason.MALFORMED_JSON, rejected("  "))
        assertEquals(RejectReason.MALFORMED_JSON, rejected("1 2"))
    }

    @Test
    fun `canonical equality`() {
        assertTrue(canonicalEquals(accepted("""{"a":1,"b":[1.0,"x"]}"""), accepted("""{"b":[10e-1,"x"],"a":1.00}""")))
        assertFalse(canonicalEquals(accepted("[1,2]"), accepted("[2,1]")))
        assertFalse(canonicalEquals(accepted("\"1\""), accepted("1")))
        assertTrue(canonicalEquals(accepted("-0"), accepted("0.0e7")))
        assertFalse(canonicalEquals(accepted("""{"a":null}"""), accepted("{}")))
    }

    @Test
    fun `assertJsonEquals names the path of the first difference`() {
        val error = runCatching { assertJsonEquals("""{"a":[1,{"b":2}]}""", """{"a":[1,{"b":3}]}""") }.exceptionOrNull()
        assertTrue(error is AssertionError && error.message!!.contains("""$["a"][1]["b"]"""), error?.message)
    }
}
