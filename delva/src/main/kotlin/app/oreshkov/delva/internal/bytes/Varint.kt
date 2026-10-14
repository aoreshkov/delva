package app.oreshkov.delva.internal.bytes

/**
 * Unsigned LEB128 varints of up to 64 bits: at most 10 bytes, and the 10th byte is at most `0x01`.
 * Decoders reject non-minimal encodings, so every value has exactly one byte form (see [ByteReader.readVarLong]).
 */
internal object Varint {
    const val MAX_BYTES: Int = 10

    /** Encoded size of [value], read as unsigned. */
    fun size(value: Long): Int {
        // 1 byte per started group of 7 significant bits; zero takes one byte.
        val bits = 64 - java.lang.Long.numberOfLeadingZeros(value or 1L)
        return (bits + 6) / 7
    }

    /** Writes [value] (unsigned) at [position]; returns the position after the last byte. */
    fun write(bytes: ByteArray, position: Int, value: Long): Int {
        var p = position
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            bytes[p++] = ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        bytes[p++] = v.toByte()
        return p
    }

    fun zigZagEncode(value: Long): Long = (value shl 1) xor (value shr 63)

    fun zigZagDecode(value: Long): Long = (value ushr 1) xor -(value and 1)
}
