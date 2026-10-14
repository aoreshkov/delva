package app.oreshkov.delva.internal.bytes

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.Gens
import app.oreshkov.delva.testkit.property.forAll
import app.oreshkov.delva.testkit.property.map
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32C
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BytesTest {
    private sealed interface Op {
        data class Byte(val v: Int) : Op
        data class Bytes(val v: ByteArray) : Op
        data class IntLE(val v: Int) : Op
        data class LongLE(val v: Long) : Op
        data class IntBE(val v: Int) : Op
        data class LongBE(val v: Long) : Op
    }

    private val ops: Gen<List<Op>> = Gens.list(
        Gens.oneOf(
            Gens.int(0..255).map { Op.Byte(it) },
            Gens.bytes(maxLength = 300).map { Op.Bytes(it) },
            Gens.int(Int.MIN_VALUE..Int.MAX_VALUE).map { Op.IntLE(it) },
            Gens.edgyLong().map { Op.LongLE(it) },
            Gens.int(Int.MIN_VALUE..Int.MAX_VALUE).map { Op.IntBE(it) },
            Gens.edgyLong().map { Op.LongBE(it) },
        ),
    )

    @Test
    fun `GrowableBytes writes match a ByteBuffer model and ByteReader reads them back`() {
        forAll("growable bytes vs model", ops, iterations = 300, maxSize = 200) { list ->
            val buffer = GrowableBytes(1)
            val model = ByteBuffer.allocate(list.size * 300 + 16)
            for (op in list) when (op) {
                is Op.Byte -> { buffer.writeByte(op.v); model.put(op.v.toByte()) }
                is Op.Bytes -> { buffer.writeBytes(op.v); model.put(op.v) }
                is Op.IntLE -> { buffer.writeIntLE(op.v); model.order(ByteOrder.LITTLE_ENDIAN).putInt(op.v) }
                is Op.LongLE -> { buffer.writeLongLE(op.v); model.order(ByteOrder.LITTLE_ENDIAN).putLong(op.v) }
                is Op.IntBE -> { buffer.writeIntBE(op.v); model.order(ByteOrder.BIG_ENDIAN).putInt(op.v) }
                is Op.LongBE -> { buffer.writeLongBE(op.v); model.order(ByteOrder.BIG_ENDIAN).putLong(op.v) }
            }
            assertContentEquals(model.array().copyOf(model.position()), buffer.toByteArray())

            val reader = ByteReader(buffer.array, 0, buffer.size)
            for (op in list) when (op) {
                is Op.Byte -> assertEquals(op.v, reader.readByte())
                is Op.Bytes -> ByteArray(op.v.size).also { reader.readBytes(it, 0, it.size); assertContentEquals(op.v, it) }
                is Op.IntLE -> assertEquals(op.v, reader.readIntLE())
                is Op.LongLE -> assertEquals(op.v, reader.readLongLE())
                is Op.IntBE -> assertEquals(op.v, reader.readIntBE())
                is Op.LongBE -> assertEquals(op.v, reader.readLongBE())
            }
            assertEquals(0, reader.remaining)
        }
    }

    @Test
    fun `reserve, patch, mark, reset and moveRange`() {
        val buffer = GrowableBytes(2)
        buffer.writeByte(0xAA)
        val slot = buffer.reserve(4)
        buffer.writeByte(0xBB)
        buffer.putIntLE(slot, 0x04030201)
        assertEquals(listOf(0xAA, 1, 2, 3, 4, 0xBB), buffer.toByteArray().map { it.toInt() and 0xFF })

        val mark = buffer.mark()
        buffer.writeLongBE(-1)
        buffer.reset(mark)
        assertEquals(6, buffer.size)

        buffer.moveRange(from = 1, to = 2, length = 4)
        assertEquals(listOf(0xAA, 1, 1, 2, 3, 4), buffer.toByteArray().map { it.toInt() and 0xFF })
        assertFailsWith<IllegalArgumentException> { buffer.moveRange(from = 3, to = 0, length = 4) }
        assertFailsWith<IllegalArgumentException> { buffer.putIntLE(3, 0) }
        assertFailsWith<IllegalArgumentException> { buffer.truncate(7) }
    }

    @Test
    fun `ByteReader throws CorruptionException with a location instead of reading past the limit`() {
        val bytes = ByteArray(16) { it.toByte() }
        val reader = ByteReader(bytes, offset = 4, length = 6, source = "segment-7")
        assertEquals(0x07060504, reader.readIntLE())
        val e = assertFailsWith<CorruptionException> { reader.readIntLE() }
        assertEquals("segment-7@8", e.location)
        assertEquals(8, reader.position)
        assertFailsWith<CorruptionException> { reader.skip(3) }
        assertFailsWith<CorruptionException> { reader.seek(11) }
        reader.seek(10)
        assertEquals(0, reader.remaining)
    }

    @Test
    fun `Crc32c over fields equals the JDK CRC over the stored bytes`() {
        forAll("crc32c fields", Gens.pair(Gens.bytes(maxLength = 100), Gens.edgyLong())) { (body, number) ->
            val stored = GrowableBytes()
            stored.writeByte(0x11)
            stored.writeIntLE(body.size)
            stored.writeLongLE(number)
            stored.writeBytes(body)
            val expected = CRC32C().apply { update(stored.array, 0, stored.size) }.value.toInt()

            val fields = Crc32c().reset().updateByte(0x11).updateIntLE(body.size).updateLongLE(number).update(body, 0, body.size)
            assertEquals(expected, fields.value)
            assertEquals(expected, Crc32c.of(stored.array, 0, stored.size))
        }
    }
}
