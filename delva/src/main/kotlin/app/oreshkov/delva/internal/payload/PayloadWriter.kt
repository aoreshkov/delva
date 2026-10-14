package app.oreshkov.delva.internal.payload

import app.oreshkov.delva.internal.bytes.GrowableBytes

/**
 * Writes the pieces of the payload encoding ([PayloadFormat]). Callers are responsible for the structure: counts,
 * sizes and `sortPerm` must match what follows.
 */
internal object PayloadWriter {
    fun keyTableCount(out: GrowableBytes, count: Int) = out.writeVarLong(count.toLong())

    fun key(out: GrowableBytes, utf8: ByteArray, offset: Int, length: Int) {
        out.writeVarLong(length.toLong())
        out.writeBytes(utf8, offset, length)
    }

    fun nullValue(out: GrowableBytes) = out.writeByte(PayloadTag.NULL)

    fun booleanValue(out: GrowableBytes, value: Boolean) = out.writeByte(if (value) PayloadTag.TRUE else PayloadTag.FALSE)

    /** A number whose JSON text is canonical and fits a `Long`. */
    fun intValue(out: GrowableBytes, value: Long) {
        out.writeByte(PayloadTag.INT)
        out.writeZigZag(value)
    }

    /** Any other number, in its original spelling. */
    fun numValue(out: GrowableBytes, ascii: ByteArray, offset: Int, length: Int) {
        out.writeByte(PayloadTag.NUM)
        out.writeVarLong(length.toLong())
        out.writeBytes(ascii, offset, length)
    }

    fun strValue(out: GrowableBytes, utf8: ByteArray, offset: Int, length: Int) {
        out.writeByte(PayloadTag.STR)
        out.writeVarLong(length.toLong())
        out.writeBytes(utf8, offset, length)
    }

    /** Object header; follow it with [sortPermEntry] calls when [count] ≥ 2, then the members. */
    fun objectHeader(out: GrowableBytes, count: Int, size: Long) {
        out.writeByte(PayloadTag.OBJ)
        out.writeVarLong(count.toLong())
        out.writeVarLong(size)
    }

    fun arrayHeader(out: GrowableBytes, count: Int, size: Long) {
        out.writeByte(PayloadTag.ARR)
        out.writeVarLong(count.toLong())
        out.writeVarLong(size)
    }

    fun sortPermEntry(out: GrowableBytes, memberPosition: Int) = out.writeVarLong(memberPosition.toLong())

    fun keyRef(out: GrowableBytes, keyRef: Int) = out.writeVarLong(keyRef.toLong())

    /** Stub for a container stored as the SUBTREE fragment at this value's `pre`. */
    fun ref(out: GrowableBytes, containerKind: Int, size: Long, hashLow: Long, hashHigh: Long, encodedBytes: Long) {
        out.writeByte(PayloadTag.REF)
        out.writeByte(containerKind)
        out.writeVarLong(size)
        hash(out, hashLow, hashHigh)
        out.writeVarLong(encodedBytes)
    }

    /** Stub for a container stored as HEADER and PART fragments. */
    fun split(out: GrowableBytes, containerKind: Int, count: Long, size: Long, hashLow: Long, hashHigh: Long) {
        out.writeByte(PayloadTag.SPLIT)
        out.writeByte(containerKind)
        out.writeVarLong(count)
        out.writeVarLong(size)
        hash(out, hashLow, hashHigh)
    }

    /**
     * Stub for a string stored as STRCHUNK fragments; `prefix[prefixOffset, +16)` holds the string's first
     * [PayloadFormat.BIGSTR_PREFIX_BYTES] bytes.
     */
    fun bigString(out: GrowableBytes, totalBytes: Long, chunks: Long, hashLow: Long, hashHigh: Long, prefix: ByteArray, prefixOffset: Int) {
        out.writeByte(PayloadTag.BIGSTR)
        out.writeVarLong(totalBytes)
        out.writeVarLong(chunks)
        hash(out, hashLow, hashHigh)
        out.writeBytes(prefix, prefixOffset, PayloadFormat.BIGSTR_PREFIX_BYTES)
    }

    /** PART header; follow it with [sortPermEntry] calls for an object run of ≥ 2 members, then the members. */
    fun partHeader(out: GrowableBytes, containerKind: Int, memberCount: Int) {
        require(containerKind == PayloadTag.OBJ || containerKind == PayloadTag.ARR)
        out.writeByte(containerKind)
        out.writeVarLong(memberCount.toLong())
    }

    /** HEADER `seq 0` summary; follow it with [dirEntryCount] and the entries. */
    fun headerSummary(out: GrowableBytes, containerKind: Int, count: Long, size: Long, hashLow: Long, hashHigh: Long, partCount: Long) {
        require(containerKind == PayloadTag.OBJ || containerKind == PayloadTag.ARR)
        out.writeByte(containerKind)
        out.writeVarLong(count)
        out.writeVarLong(size)
        hash(out, hashLow, hashHigh)
        out.writeVarLong(partCount)
    }

    fun dirEntryCount(out: GrowableBytes, entryCount: Int) = out.writeVarLong(entryCount.toLong())

    fun dirEntry(out: GrowableBytes, firstPre: Long, firstIndex: Long, memberCount: Long) {
        out.writeVarLong(firstPre)
        out.writeVarLong(firstIndex)
        out.writeVarLong(memberCount)
    }

    fun hash(out: GrowableBytes, low: Long, high: Long) {
        out.writeLongBE(high)
        out.writeLongBE(low)
    }
}
