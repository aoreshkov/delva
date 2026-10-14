package app.oreshkov.delva.internal.bytes

/**
 * Bytes read in order from a value that needn't be in memory at once, such as a value of an index segment. Varints
 * follow the rules of [ByteReader.readVarLong]; damage throws [app.oreshkov.delva.CorruptionException].
 */
internal interface ByteInput {
    /** Position within the value. */
    val position: Long

    /** Bytes left after [position]. */
    val remaining: Long

    fun readByte(): Int

    fun readVarLong(): Long

    /** A varint of at most [max]. */
    fun readVarInt(max: Int = Int.MAX_VALUE): Int

    /** Lets go of the bytes held when they can be fetched again, so that many idle inputs hold little memory. */
    fun release()

    /** Throws a [app.oreshkov.delva.CorruptionException] naming the value. */
    fun corrupt(detail: String): Nothing
}
