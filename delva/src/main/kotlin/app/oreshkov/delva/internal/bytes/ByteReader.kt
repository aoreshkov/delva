package app.oreshkov.delva.internal.bytes

import app.oreshkov.delva.CorruptionException

/**
 * Bounds-checked reader over stored bytes. Every malformed or truncated structure throws [CorruptionException]
 * naming [source] and the byte position, never an `IndexOutOfBoundsException` (P5).
 *
 * Reusable: [reset] points it at another range without allocating.
 */
internal class ByteReader(
    bytes: ByteArray,
    offset: Int = 0,
    length: Int = bytes.size - offset,
    private var source: String = "buffer",
) {
    var bytes: ByteArray = bytes
        private set

    /** Absolute index into [bytes] of the next byte to read. */
    var position: Int = offset
        private set

    /** Absolute index into [bytes] one past the last readable byte. */
    var limit: Int = offset + length
        private set

    init {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
    }

    val remaining: Int get() = limit - position

    fun reset(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, source: String = this.source) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        this.bytes = bytes
        this.position = offset
        this.limit = offset + length
        this.source = source
    }

    /** Moves to an absolute index within the current range. */
    fun seek(newPosition: Int) {
        if (newPosition < 0 || newPosition > limit) corrupt(position, "seek to $newPosition outside the range ending at $limit")
        position = newPosition
    }

    fun skip(count: Int) {
        require(count >= 0)
        need(count)
        position += count
    }

    fun readByte(): Int {
        need(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun readShortLE(): Int {
        need(2)
        val v = LittleEndian.getShort(bytes, position).toInt() and 0xFFFF
        position += 2
        return v
    }

    fun readIntLE(): Int {
        need(4)
        val v = LittleEndian.getInt(bytes, position)
        position += 4
        return v
    }

    fun readLongLE(): Long {
        need(8)
        val v = LittleEndian.getLong(bytes, position)
        position += 8
        return v
    }

    fun readIntBE(): Int {
        need(4)
        val v = BigEndian.getInt(bytes, position)
        position += 4
        return v
    }

    fun readLongBE(): Long {
        need(8)
        val v = BigEndian.getLong(bytes, position)
        position += 8
        return v
    }

    fun readBytes(destination: ByteArray, offset: Int, length: Int) {
        need(length)
        System.arraycopy(bytes, position, destination, offset, length)
        position += length
    }

    /**
     * Unsigned LEB128 varint of up to 64 bits. Rejects encodings longer than 10 bytes, a 10th byte above
     * `0x01`, and non-minimal encodings (a final byte of zero after the first).
     */
    fun readVarLong(): Long {
        val start = position
        var result = 0L
        var shift = 0
        var p = position
        while (true) {
            if (p >= limit) corrupt(start, "truncated varint")
            val b = bytes[p++].toInt() and 0xFF
            if (shift == 63 && b > 0x01) corrupt(start, "varint exceeds 64 bits")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) {
                if (b == 0 && shift > 0) corrupt(start, "non-minimal varint")
                position = p
                return result
            }
            shift += 7
        }
    }

    /** Varint for a u32 field; values above `0xFFFF_FFFF` are corrupt. */
    fun readVarU32(): Long {
        val start = position
        val v = readVarLong()
        if (v !in 0L..0xFFFF_FFFFL) corrupt(start, "varint ${java.lang.Long.toUnsignedString(v)} exceeds u32")
        return v
    }

    /** Varint for a length, count or index field bounded by [max] (inclusive). */
    fun readVarInt(max: Int = Int.MAX_VALUE): Int {
        require(max >= 0)
        val start = position
        val v = readVarLong()
        if (v !in 0L..max.toLong()) corrupt(start, "varint ${java.lang.Long.toUnsignedString(v)} exceeds $max")
        return v.toInt()
    }

    fun readZigZag(): Long = Varint.zigZagDecode(readVarLong())

    private fun need(count: Int) {
        if (count > limit - position) corrupt(position, "need $count bytes, ${limit - position} left")
    }

    private fun corrupt(at: Int, detail: String): Nothing =
        throw CorruptionException("$source@$at", detail)
}
