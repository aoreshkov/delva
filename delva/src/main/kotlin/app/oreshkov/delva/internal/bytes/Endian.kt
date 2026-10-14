package app.oreshkov.delva.internal.bytes

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

// Stored integers are little-endian; sortable index keys are big-endian.
// Callers check bounds; the VarHandles still throw IndexOutOfBoundsException on a bad index.

internal object LittleEndian {
    private val SHORT: VarHandle = MethodHandles.byteArrayViewVarHandle(ShortArray::class.java, ByteOrder.LITTLE_ENDIAN)
    private val INT: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)
    private val LONG: VarHandle = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.LITTLE_ENDIAN)

    fun getShort(bytes: ByteArray, index: Int): Short = SHORT.get(bytes, index) as Short
    fun getInt(bytes: ByteArray, index: Int): Int = INT.get(bytes, index) as Int
    fun getLong(bytes: ByteArray, index: Int): Long = LONG.get(bytes, index) as Long
    fun putShort(bytes: ByteArray, index: Int, value: Short) = SHORT.set(bytes, index, value)
    fun putInt(bytes: ByteArray, index: Int, value: Int) = INT.set(bytes, index, value)
    fun putLong(bytes: ByteArray, index: Int, value: Long) = LONG.set(bytes, index, value)
}

internal object BigEndian {
    private val SHORT: VarHandle = MethodHandles.byteArrayViewVarHandle(ShortArray::class.java, ByteOrder.BIG_ENDIAN)
    private val INT: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.BIG_ENDIAN)
    private val LONG: VarHandle = MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.BIG_ENDIAN)

    fun getShort(bytes: ByteArray, index: Int): Short = SHORT.get(bytes, index) as Short
    fun getInt(bytes: ByteArray, index: Int): Int = INT.get(bytes, index) as Int
    fun getLong(bytes: ByteArray, index: Int): Long = LONG.get(bytes, index) as Long
    fun putShort(bytes: ByteArray, index: Int, value: Short) = SHORT.set(bytes, index, value)
    fun putInt(bytes: ByteArray, index: Int, value: Int) = INT.set(bytes, index, value)
    fun putLong(bytes: ByteArray, index: Int, value: Long) = LONG.set(bytes, index, value)
}
