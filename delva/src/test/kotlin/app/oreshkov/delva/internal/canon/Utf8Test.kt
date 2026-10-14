package app.oreshkov.delva.internal.canon

import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Utf8Test {
    private fun jdkAccepts(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /** Mostly valid text with occasional corruption: random bytes alone are almost never valid UTF-8. */
    private val utf8ish = Gen { random, size ->
        val out = java.io.ByteArrayOutputStream()
        repeat(random.nextInt(0, size + 1)) {
            when (random.nextInt(10)) {
                0 -> out.write(random.nextInt(256))
                1 -> {
                    // A lead byte followed by a random, possibly short, continuation.
                    out.write(0xC0 + random.nextInt(0x40))
                    repeat(random.nextInt(0, 4)) { out.write(0x80 + random.nextInt(0x40)) }
                }
                else -> {
                    val cp = when (random.nextInt(4)) {
                        0 -> random.nextInt(0x80)
                        1 -> random.nextInt(0x80, 0x800)
                        2 -> random.nextInt(0x800, 0x10000).let { if (it in 0xD800..0xDFFF) 0xE000 else it }
                        else -> random.nextInt(0x10000, 0x110000)
                    }
                    out.write(String(Character.toChars(cp)).toByteArray())
                }
            }
        }
        out.toByteArray()
    }

    @Test
    fun `agrees with the strict JDK decoder`() {
        forAll("utf8 validation vs JDK", utf8ish, iterations = 3000, maxSize = 40) { bytes ->
            assertEquals(jdkAccepts(bytes), Utf8.isValid(bytes), "bytes ${bytes.joinToString(" ") { "%02x".format(it) }}")
        }
    }

    @Test
    fun `boundary sequences`() {
        fun valid(vararg b: Int) = Utf8.isValid(ByteArray(b.size) { b[it].toByte() })
        assertTrue(valid(0x7F))
        assertTrue(valid(0xC2, 0x80))
        assertTrue(valid(0xDF, 0xBF))
        assertTrue(valid(0xE0, 0xA0, 0x80))
        assertTrue(valid(0xED, 0x9F, 0xBF)) // U+D7FF
        assertTrue(valid(0xEE, 0x80, 0x80)) // U+E000
        assertTrue(valid(0xF0, 0x90, 0x80, 0x80)) // U+10000
        assertTrue(valid(0xF4, 0x8F, 0xBF, 0xBF)) // U+10FFFF

        assertFalse(valid(0x80)) // lone continuation
        assertFalse(valid(0xC0, 0x80)) // overlong NUL
        assertFalse(valid(0xC1, 0xBF)) // overlong
        assertFalse(valid(0xE0, 0x9F, 0xBF)) // overlong
        assertFalse(valid(0xED, 0xA0, 0x80)) // U+D800 surrogate
        assertFalse(valid(0xED, 0xBF, 0xBF)) // U+DFFF surrogate
        assertFalse(valid(0xF0, 0x8F, 0xBF, 0xBF)) // overlong
        assertFalse(valid(0xF4, 0x90, 0x80, 0x80)) // U+110000
        assertFalse(valid(0xF5, 0x80, 0x80, 0x80))
        assertFalse(valid(0xFF))
        assertFalse(valid(0xE2, 0x82)) // truncated
        assertFalse(valid(0xE2, 0x82, 0x41)) // bad continuation
    }

    @Test
    fun `first invalid index points at the start of the bad sequence and respects the range`() {
        val bytes = byteArrayOf(0x41, 0xE2.toByte(), 0x82.toByte(), 0xAC.toByte(), 0x42, 0xE2.toByte(), 0x82.toByte(), 0x43)
        assertEquals(5, Utf8.firstInvalid(bytes, 0, bytes.size))
        assertEquals(-1, Utf8.firstInvalid(bytes, 0, 5))
        assertEquals(-1, Utf8.firstInvalid(bytes, 1, 3))
        assertEquals(2, Utf8.firstInvalid(bytes, 2, 3)) // starts on a continuation byte
    }
}
