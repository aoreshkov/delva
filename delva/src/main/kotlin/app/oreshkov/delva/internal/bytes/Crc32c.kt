package app.oreshkov.delva.internal.bytes

import java.util.zip.CRC32C

/**
 * CRC32C over fields and byte ranges (JDK intrinsic). One instance per thread or per writer; not thread-safe.
 *
 * Integers are fed little-endian, the byte order they're stored in, so a CRC over written fields equals the CRC over
 * the stored bytes.
 */
internal class Crc32c {
    private val crc = CRC32C()
    private val scratch = ByteArray(8)

    fun reset(): Crc32c {
        crc.reset()
        return this
    }

    fun update(bytes: ByteArray, offset: Int, length: Int): Crc32c {
        crc.update(bytes, offset, length)
        return this
    }

    fun updateByte(value: Int): Crc32c {
        crc.update(value)
        return this
    }

    fun updateIntLE(value: Int): Crc32c {
        LittleEndian.putInt(scratch, 0, value)
        crc.update(scratch, 0, 4)
        return this
    }

    fun updateLongLE(value: Long): Crc32c {
        LittleEndian.putLong(scratch, 0, value)
        crc.update(scratch, 0, 8)
        return this
    }

    /** The CRC as the 32-bit value that's stored. */
    val value: Int get() = crc.value.toInt()

    companion object {
        fun of(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
            val crc = CRC32C()
            crc.update(bytes, offset, length)
            return crc.value.toInt()
        }
    }
}
