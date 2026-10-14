package app.oreshkov.delva.internal.bytes

/**
 * An append buffer over a doubling `ByteArray`. [array] is exposed for zero-copy reads and hashing; it's valid until
 * the next write that grows the buffer.
 */
internal class GrowableBytes(initialCapacity: Int = 64) {
    var array: ByteArray = ByteArray(initialCapacity)
        private set

    var size: Int = 0
        private set

    val capacity: Int get() = array.size

    fun clear() {
        size = 0
    }

    /** Current size, for a later [reset]. */
    fun mark(): Int = size

    /** Drops everything written after [mark]. */
    fun reset(mark: Int) = truncate(mark)

    fun truncate(newSize: Int) {
        require(newSize in 0..size) { "truncate to $newSize, size is $size" }
        size = newSize
    }

    fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > array.size) grow(minCapacity)
    }

    /** Appends [length] bytes (contents undefined) to be patched later; returns their start position. */
    fun reserve(length: Int): Int {
        require(length >= 0)
        val start = size
        ensureCapacity(checkedEnd(start, length))
        size = start + length
        return start
    }

    fun writeByte(value: Int) {
        if (size == array.size) grow(size + 1)
        array[size++] = value.toByte()
    }

    fun writeBytes(source: ByteArray, offset: Int = 0, length: Int = source.size - offset) {
        val start = reserve(length)
        System.arraycopy(source, offset, array, start, length)
    }

    // Reserve first: it may replace `array`.
    fun writeShortLE(value: Int) { val p = reserve(2); LittleEndian.putShort(array, p, value.toShort()) }
    fun writeIntLE(value: Int) { val p = reserve(4); LittleEndian.putInt(array, p, value) }
    fun writeLongLE(value: Long) { val p = reserve(8); LittleEndian.putLong(array, p, value) }
    fun writeShortBE(value: Int) { val p = reserve(2); BigEndian.putShort(array, p, value.toShort()) }
    fun writeIntBE(value: Int) { val p = reserve(4); BigEndian.putInt(array, p, value) }
    fun writeLongBE(value: Long) { val p = reserve(8); BigEndian.putLong(array, p, value) }

    fun putByte(position: Int, value: Int) {
        checkRange(position, 1)
        array[position] = value.toByte()
    }

    fun putIntLE(position: Int, value: Int) {
        checkRange(position, 4)
        LittleEndian.putInt(array, position, value)
    }

    fun putLongLE(position: Int, value: Long) {
        checkRange(position, 8)
        LittleEndian.putLong(array, position, value)
    }

    fun putIntBE(position: Int, value: Int) {
        checkRange(position, 4)
        BigEndian.putInt(array, position, value)
    }

    /** Unsigned LEB128 varint. */
    fun writeVarLong(value: Long) {
        if (array.size - size < Varint.MAX_BYTES) grow(checkedEnd(size, Varint.MAX_BYTES))
        size = Varint.write(array, size, value)
    }

    fun writeZigZag(value: Long) = writeVarLong(Varint.zigZagEncode(value))

    /** Moves [length] bytes from [from] to [to] inside the written range (overlapping ranges allowed). */
    fun moveRange(from: Int, to: Int, length: Int) {
        checkRange(from, length)
        checkRange(to, length)
        System.arraycopy(array, from, array, to, length)
    }

    fun toByteArray(): ByteArray = array.copyOf(size)

    private fun checkRange(position: Int, length: Int) {
        require(position >= 0 && length >= 0 && position <= size - length) {
            "range [$position, +$length) outside [0, $size)"
        }
    }

    private fun grow(minCapacity: Int) {
        val doubled = if (array.size >= MAX_CAPACITY / 2) MAX_CAPACITY else maxOf(array.size * 2, 16)
        val newCapacity = maxOf(doubled, minCapacity)
        check(newCapacity <= MAX_CAPACITY) { "buffer would exceed $MAX_CAPACITY bytes" }
        array = array.copyOf(newCapacity)
    }

    private fun checkedEnd(start: Int, length: Int): Int {
        val end = start.toLong() + length
        check(end <= MAX_CAPACITY) { "buffer would exceed $MAX_CAPACITY bytes" }
        return end.toInt()
    }

    private companion object {
        /** Largest array size all JVMs allocate. */
        const val MAX_CAPACITY = Int.MAX_VALUE - 8
    }
}
