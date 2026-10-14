package app.oreshkov.delva.internal.bytes

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.testkit.property.Gens
import app.oreshkov.delva.testkit.property.forAll
import app.oreshkov.delva.testkit.property.map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VarintTest {
    @Test
    fun `round trip, and size matches the bytes written`() {
        forAll("varint round trip", Gens.edgyLong(), iterations = 2000) { value ->
            val buffer = GrowableBytes(1)
            buffer.writeVarLong(value)
            assertEquals(Varint.size(value), buffer.size)
            val reader = ByteReader(buffer.array, 0, buffer.size)
            assertEquals(value, reader.readVarLong())
            assertEquals(0, reader.remaining)
        }
    }

    @Test
    fun `known encodings`() {
        assertEncoding(0, "00")
        assertEncoding(1, "01")
        assertEncoding(127, "7f")
        assertEncoding(128, "8001")
        assertEncoding(300, "ac02")
        assertEncoding(0xFFFF_FFFFL, "ffffffff0f")
        assertEncoding(Long.MAX_VALUE, "ffffffffffffffff7f")
        assertEncoding(-1, "ffffffffffffffffff01")
        assertEncoding(Long.MIN_VALUE, "80808080808080808001")
    }

    @Test
    fun `non-minimal encodings are rejected`() {
        forAll("padded varint rejected", Gens.edgyLong().map { it ushr 1 }, iterations = 500) { value ->
            val bytes = encode(value)
            // Same value with one extra zero group: set the continuation bit on the last byte and append 0x00.
            val padded = bytes.copyOf(bytes.size + 1)
            padded[bytes.size - 1] = (padded[bytes.size - 1].toInt() or 0x80).toByte()
            padded[bytes.size] = 0
            if (padded.size <= Varint.MAX_BYTES) {
                assertFailsWith<CorruptionException> { ByteReader(padded).readVarLong() }
            }
        }
        assertFailsWith<CorruptionException> { ByteReader(hex("8000")).readVarLong() }
        assertFailsWith<CorruptionException> { ByteReader(hex("ff80808080808080808000")).readVarLong() }
    }

    @Test
    fun `tenth byte above 0x01 and eleven-byte encodings are rejected`() {
        assertFailsWith<CorruptionException> { ByteReader(hex("ffffffffffffffffff02")).readVarLong() }
        assertFailsWith<CorruptionException> { ByteReader(hex("ffffffffffffffffff7f")).readVarLong() }
        assertFailsWith<CorruptionException> { ByteReader(hex("ffffffffffffffffff8101")).readVarLong() }
    }

    @Test
    fun `truncated varint is rejected without moving the position`() {
        val reader = ByteReader(hex("8080"))
        assertFailsWith<CorruptionException> { reader.readVarLong() }
        assertEquals(0, reader.position)
    }

    @Test
    fun `u32 and bounded int fields are range-checked`() {
        assertEquals(0xFFFF_FFFFL, ByteReader(encode(0xFFFF_FFFFL)).readVarU32())
        assertFailsWith<CorruptionException> { ByteReader(encode(0x1_0000_0000L)).readVarU32() }
        assertFailsWith<CorruptionException> { ByteReader(encode(-1)).readVarU32() }
        assertEquals(100, ByteReader(encode(100)).readVarInt(max = 100))
        assertFailsWith<CorruptionException> { ByteReader(encode(101)).readVarInt(max = 100) }
        assertFailsWith<CorruptionException> { ByteReader(encode(Int.MAX_VALUE + 1L)).readVarInt() }
    }

    @Test
    fun `zigzag round trip and known values`() {
        assertEquals(0L, Varint.zigZagEncode(0))
        assertEquals(1L, Varint.zigZagEncode(-1))
        assertEquals(2L, Varint.zigZagEncode(1))
        assertEquals(-1L, Varint.zigZagEncode(Long.MIN_VALUE))
        assertEquals(-2L, Varint.zigZagEncode(Long.MAX_VALUE))
        forAll("zigzag round trip", Gens.edgyLong(), iterations = 2000) { value ->
            assertEquals(value, Varint.zigZagDecode(Varint.zigZagEncode(value)))
            val buffer = GrowableBytes()
            buffer.writeZigZag(value)
            assertEquals(value, ByteReader(buffer.array, 0, buffer.size).readZigZag())
        }
    }

    private fun assertEncoding(value: Long, expectedHex: String) {
        assertEquals(expectedHex, encode(value).joinToString("") { "%02x".format(it) })
    }

    private fun encode(value: Long): ByteArray = GrowableBytes().apply { writeVarLong(value) }.toByteArray()

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
