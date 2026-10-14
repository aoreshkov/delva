package app.oreshkov.delva.internal.canon

import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.parse.JsonToken
import app.oreshkov.delva.internal.parse.NfcTokenSource
import app.oreshkov.delva.internal.parse.ParseLimits
import app.oreshkov.delva.internal.parse.Utf8JsonTokenizer
import java.io.ByteArrayInputStream
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NFC normalization of UTF-8, and the token source that applies it while a document streams in. */
class NfcTest {
    private val composed = "café" // café with U+00E9
    private val decomposed = "café" // café with e + U+0301

    private fun normalize(text: String): String {
        val utf8 = text.toByteArray(Charsets.UTF_8)
        val out = GrowableBytes(utf8.size + 8)
        val written = Nfc.normalize(utf8, 0, utf8.size, out)
        assertEquals(written, out.size)
        return String(out.array, 0, out.size, Charsets.UTF_8)
    }

    @Test
    fun `ascii is normalized as it stands`() {
        val ascii = "{\"key\":\"value\"}".toByteArray(Charsets.UTF_8)
        assertTrue(Nfc.isAscii(ascii, 0, ascii.size))
        assertTrue(Nfc.isNormalized(ascii, 0, ascii.size))
        assertEquals("{\"key\":\"value\"}", normalize("{\"key\":\"value\"}"))
    }

    @Test
    fun `decomposed text composes and composed text is left alone`() {
        assertEquals(composed, normalize(decomposed))
        assertEquals(composed, normalize(composed))
        val utf8 = decomposed.toByteArray(Charsets.UTF_8)
        assertFalse(Nfc.isAscii(utf8, 0, utf8.size))
        assertFalse(Nfc.isNormalized(utf8, 0, utf8.size))
        assertEquals(composed, Nfc.normalizeOrNull(decomposed))
    }

    @Test
    fun `normalization can lengthen text`() {
        // U+0958 is a composition exclusion: NFC decomposes it to U+0915 U+093C and the UTF-8 grows.
        val text = "क़"
        val normalized = normalize(text)
        assertEquals("क़", normalized)
        assertTrue(normalized.toByteArray(Charsets.UTF_8).size > text.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `a safe starter is anything that cannot compose onto what precedes it`() {
        fun safe(text: String): Boolean {
            val utf8 = text.toByteArray(Charsets.UTF_8)
            return Nfc.safeStarterAt(utf8, 0, utf8.size)
        }
        assertTrue(safe("a")) // ASCII
        assertTrue(safe("é")) // precomposed letter
        assertTrue(safe("中")) // CJK ideograph: text without spaces still breaks
        assertTrue(safe("가")) // Hangul syllable: composes with a following T, never backwards
        assertFalse(safe("́")) // combining acute
        assertFalse(safe("ि")) // spacing combining mark
        assertFalse(safe("ᅡ")) // Hangul V jamo, which composes onto a preceding L
    }

    @Test
    fun `the tail kept back is the part a following code point could still change`() {
        val bytes = "ab́c".toByteArray(Charsets.UTF_8) // a | b + acute | c
        // The last safe starter is "c"; everything before it may still take another mark.
        assertEquals(bytes.size - 1, Nfc.tailStart(bytes, 0, bytes.size))
        val marks = "á̧".toByteArray(Charsets.UTF_8)
        assertEquals(0, Nfc.tailStart(marks, 0, marks.size))
    }

    /** Tokenizes with [chunk]-byte string parts and returns the strings the source hands on, parts joined. */
    private fun strings(json: String, chunk: Int = 8): List<String> {
        val tokenizer = Utf8JsonTokenizer(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)), ParseLimits.DEFAULT, 16, chunk)
        val source = NfcTokenSource(tokenizer, ParseLimits.DEFAULT)
        val values = ArrayList<String>()
        val current = GrowableBytes(64)
        while (true) {
            when (source.next()) {
                JsonToken.EOF -> return values
                JsonToken.KEY, JsonToken.STRING -> values += String(source.bytes, source.offset, source.length, Charsets.UTF_8)
                JsonToken.STRING_PART -> {
                    current.writeBytes(source.bytes, source.offset, source.length)
                    if (source.isLastPart) {
                        values += String(current.array, 0, current.size, Charsets.UTF_8)
                        current.clear()
                    }
                }
            }
        }
    }

    @Test
    fun `keys and short strings are normalized whole`() {
        assertEquals(listOf(composed, composed), strings("""{"$decomposed":"$decomposed"}"""))
    }

    @Test
    fun `a string in parts normalizes as if it had arrived whole`() {
        // Marks land on every chunk boundary the tokenizer could pick, so the parts have to be re-cut.
        for (chunk in 4..24) {
            for (repeat in listOf(1, 3, 17)) {
                val text = ("éäô" + "中" + "é").repeat(repeat)
                assertEquals(
                    listOf(Normalizer.normalize(text, Normalizer.Form.NFC)),
                    strings("[\"$text\"]", chunk),
                    "chunk $chunk, $repeat repeats",
                )
            }
        }
    }

    private fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    private val chunks = listOf(4, 7, 16, 4096)

    @Test
    fun `a run of combining marks longer than the stream-safe bound is rejected, in whichever part it ends`() {
        val over = "x" + "̖".repeat(Nfc.MAX_COMBINING_RUN + 1)
        val plain = "x".repeat(50)
        // A key, a whole string, and strings in parts whose run ends in the last part or before a later starter.
        for (json in listOf("""{"$over":1}""", """["$over"]""", """["$plain$over"]""", """["$over$plain"]""")) {
            for (chunk in chunks) {
                val e = assertFailsWith<DocumentRejectedException>("$json, chunk $chunk") { strings(json, chunk) }
                assertEquals(RejectReason.LIMIT_EXCEEDED, e.reason)
                assertContains(e.message!!, "more than ${Nfc.MAX_COMBINING_RUN} combining marks in a row")
            }
        }
    }

    @Test
    fun `a run of exactly the bound is accepted, and any starter ends a run`() {
        val atBound = "x" + "̖̀".repeat(Nfc.MAX_COMBINING_RUN / 2)
        val text = atBound + "b" + atBound + "中" + atBound + "가" + atBound
        for (chunk in chunks) {
            assertEquals(listOf(nfc(text)), strings("[\"$text\"]", chunk), "chunk $chunk")
            assertEquals(listOf(nfc(atBound)), strings("{\"$atBound\":0}", chunk), "chunk $chunk")
        }
    }

    @Test
    fun `the run is counted in the normalized form, so every spelling of a text gets the same verdict`() {
        fun accepted(json: String, chunk: Int): Boolean = try {
            strings(json, chunk)
            true
        } catch (e: DocumentRejectedException) {
            assertEquals(RejectReason.LIMIT_EXCEEDED, e.reason)
            false
        }
        val bound = Nfc.MAX_COMBINING_RUN
        val spellings = { text: String -> listOf(text, nfc(text), Normalizer.normalize(text, Normalizer.Form.NFD)) }
        val texts = listOf(
            // U+0344 normalizes to two marks, and U+FB2C to a letter and two marks: short as written, long in NFC.
            "\u4E2D" + "\u0344".repeat(bound / 2),
            "\uFB2C" + "\u0316".repeat(bound - 2),
            // A Tamil O is written as two vowel signs and a Kannada OO as three, and each composes to one: long as
            // written, short in NFC. The second is the longest run as written that normalizes within the bound: three
            // marks that compose onto the alpha in front, then three signs for each code point of the run.
            "\u0B95" + "\u0BC6\u0BBE".repeat(bound),
            "\u03B1\u0313\u0300\u0345" + "\u0CC6\u0CC2\u0CD5".repeat(bound),
        )
        assertEquals(3 + 3 * bound, Nfc.MAX_RAW_COMBINING_RUN)
        for (text in texts) {
            for (chunk in chunks) {
                for (spelling in spellings(text)) {
                    assertEquals(listOf(nfc(text)), strings("[\"$spelling\"]", chunk), "chunk $chunk")
                    assertEquals(listOf(nfc(text)), strings("{\"$spelling\":0}", chunk), "chunk $chunk")
                }
                for (spelling in spellings(text + "\u0316")) {
                    val name = "${spelling.codePoints().count()} code points, chunk $chunk"
                    assertFalse(accepted("[\"$spelling\"]", chunk), name)
                    assertFalse(accepted("{\"$spelling\":0}", chunk), name)
                }
            }
        }
    }

    @Test
    fun `text whose run as written passes the raw bound can't normalize within the bound`() {
        // MAX_RAW_COMBINING_RUN rests on these facts about canonical decompositions, which NFC and the text as written
        // share: a run as written is no longer than in the decomposition, and the decomposition's run is at most 3 for
        // the safe starter in front plus 3 for each unsafe code point of the NFC run.
        fun safe(cp: Int): Boolean {
            val utf8 = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            return Nfc.safeStarterAt(utf8, 0, utf8.size)
        }
        for (cp in 0..0x10FFFF) {
            if (cp in 0xD800..0xDFFF) continue
            val text = String(Character.toChars(cp))
            val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).codePoints().toArray()
            val name = "U+${cp.toString(16).uppercase()}"
            if (safe(cp)) {
                assertTrue(safe(decomposed.first()), "$name decomposes to an unsafe code point first")
                assertTrue(decomposed.takeLastWhile { !safe(it) }.size <= 3, "$name decomposes to more than 3 trailing marks")
                // Canonical reordering moves only code points with a nonzero combining class, so a safe starter must
                // have none: U+0300 (class 230) in front of it would move behind it, or U+0334 (class 1) behind it in
                // front.
                if (decomposed.contentEquals(intArrayOf(cp))) {
                    assertEquals("\u0300$text", Normalizer.normalize("\u0300$text", Normalizer.Form.NFD), name)
                    assertEquals("$text\u0334", Normalizer.normalize("$text\u0334", Normalizer.Form.NFD), name)
                }
            } else {
                assertTrue(decomposed.size <= 3 && decomposed.none(::safe), "$name decomposes to ${decomposed.size} code points")
            }
        }
    }

    @Test
    fun `text outside a document is held to the same run, and a huge run is refused without being reordered`() {
        val stored = "\u4E2D" + "\u0344".repeat(Nfc.MAX_COMBINING_RUN / 2)
        val shortened = "\u0B95" + "\u0BC6\u0BBE".repeat(Nfc.MAX_COMBINING_RUN)
        for (text in listOf(stored, shortened)) {
            for (spelling in listOf(text, nfc(text), Normalizer.normalize(text, Normalizer.Form.NFD))) {
                assertEquals(nfc(text), Nfc.normalizeOrNull(spelling))
                assertNull(Nfc.normalizeOrNull(spelling + "\u0316"))
            }
        }
        val e = assertFailsWith<DocumentRejectedException> { Nfc.normalize(stored + "\u0316", "query key") }
        assertEquals(RejectReason.LIMIT_EXCEEDED, e.reason)
        assertContains(e.message!!, "query key has more than ${Nfc.MAX_COMBINING_RUN} combining marks in a row")

        val huge = "a" + "\u0300".repeat(250_000) + "\u0316".repeat(250_000)
        val started = System.nanoTime()
        assertNull(Nfc.normalizeOrNull(huge))
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(seconds < 1, "took $seconds s")
    }

    @Test
    fun `a huge run in reverse canonical order fails fast instead of being reordered`() {
        // Canonical reordering of this run is quadratic: about 6e10 steps.
        val marks = "̀".repeat(250_000) + "̖".repeat(250_000)
        val started = System.nanoTime()
        val e = assertFailsWith<DocumentRejectedException> { strings("[\"a$marks\"]", 4096) }
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(RejectReason.LIMIT_EXCEEDED, e.reason)
        assertTrue(seconds < 1, "took $seconds s")
    }

    @Test
    fun `NFC shrinks UTF-8 less than fourfold`() {
        fun utf8(cp: Int) = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
        val codePoints = (0..0x10FFFF).filter { it !in 0xD800..0xDFFF }
        // The longest spelling of each code point on its own: itself, or a singleton such as U+2126 OHM SIGN that
        // decomposes to it.
        val longest = HashMap<Int, Int>()
        for (cp in codePoints) {
            longest.merge(cp, utf8(cp), ::maxOf)
            val decomposed = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
            if (decomposed.codePointCount(0, decomposed.length) == 1) longest.merge(decomposed.codePointAt(0), utf8(cp), ::maxOf)
        }
        // A composed character's longest spelling is its decomposition with every code point spelled longest.
        for (cp in codePoints) {
            val decomposed = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
            val raw = decomposed.codePoints().map { longest.getValue(it) }.sum()
            val normalized = nfc(decomposed).toByteArray(Charsets.UTF_8).size
            assertTrue(
                raw <= ParseLimits.NFC_MAX_SHRINK * normalized,
                "U+${cp.toString(16).uppercase()}: $raw bytes normalize to $normalized",
            )
        }
    }
}
