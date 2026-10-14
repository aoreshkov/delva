package app.oreshkov.delva.internal.term

import app.oreshkov.delva.internal.bytes.BigEndian
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.CanonicalHash
import app.oreshkov.delva.internal.canon.CanonicalNumber
import app.oreshkov.delva.internal.hash.Xxh3

/** Value tags of posting-index terms. The tag precedes the value, so typed values never compare equal. */
internal object TermTag {
    const val NULL: Int = 0x01
    const val FALSE: Int = 0x02
    const val TRUE: Int = 0x03
    const val NUMBER: Int = 0x10
    const val NUMBER_LONG: Int = 0x11
    const val STRING: Int = 0x20
    const val STRING_LONG: Int = 0x21
    const val OBJECT: Int = 0x30
    const val ARRAY: Int = 0x31
}

/**
 * Encodes posting-index terms: `term := keyLen:varint | key:UTF-8 | tag:u8 | valueKey`.
 *
 * | tag | valueKey |
 * |---|---|
 * | NULL, FALSE, TRUE | empty |
 * | NUMBER | NumberKey, when it's at most 64 bytes |
 * | NUMBER_LONG | first 16 bytes of the NumberKey, then the canonical number hash |
 * | STRING | the UTF-8 bytes, when at most 64 bytes |
 * | STRING_LONG | first 16 bytes of the UTF-8, then the canonical string hash |
 * | OBJECT, ARRAY | the canonical value hash |
 *
 * Lossy tags hash with the [CanonicalHash] of the value (16 bytes, high then low, big-endian), so a long string or
 * number needs a single hash computation for both its term and its subtree hash.
 *
 * A term is written in two steps: [writeKey], then exactly one value method. Reusable; not thread-safe.
 */
internal class TermEncoder {
    private val hash = LongArray(2)

    fun writeKey(out: GrowableBytes, key: ByteArray, offset: Int, length: Int) {
        out.writeVarLong(length.toLong())
        out.writeBytes(key, offset, length)
    }

    fun writeNull(out: GrowableBytes) = out.writeByte(TermTag.NULL)

    fun writeBoolean(out: GrowableBytes, value: Boolean) = out.writeByte(if (value) TermTag.TRUE else TermTag.FALSE)

    /**
     * Number value from JSON number text. Returns the term's value length (tag included), or
     * [CanonicalNumber.MALFORMED] / [CanonicalNumber.OUT_OF_RANGE] with nothing written.
     */
    fun writeNumber(out: GrowableBytes, text: ByteArray, offset: Int, length: Int): Int {
        val tagPosition = out.mark()
        out.writeByte(TermTag.NUMBER)
        val keyLength = CanonicalNumber.writeKey(text, offset, length, out)
        if (keyLength < 0) {
            out.reset(tagPosition)
            return keyLength
        }
        return finishNumber(out, tagPosition, keyLength)
    }

    /** Number value from an integer; identical to [writeNumber] over its decimal text. */
    fun writeNumber(out: GrowableBytes, value: Long): Int {
        val tagPosition = out.mark()
        out.writeByte(TermTag.NUMBER)
        return finishNumber(out, tagPosition, CanonicalNumber.writeKey(value, out))
    }

    /**
     * Number value from its NumberKey `key[offset, offset + length)` and the number's [CanonicalHash]
     * `(hashLow, hashHigh)`, which is only used when the key is longer than [MAX_EXACT_VALUE_BYTES]. Identical to
     * [writeNumber] over the same number.
     */
    fun writeNumberKey(out: GrowableBytes, key: ByteArray, offset: Int, length: Int, hashLow: Long, hashHigh: Long): Int {
        if (length <= MAX_EXACT_VALUE_BYTES) {
            out.writeByte(TermTag.NUMBER)
            out.writeBytes(key, offset, length)
            return 1 + length
        }
        out.writeByte(TermTag.NUMBER_LONG)
        out.writeBytes(key, offset, LONG_PREFIX_BYTES)
        writeHash(out, hashLow, hashHigh)
        return 1 + LONG_PREFIX_BYTES + CanonicalHash.HASH_BYTES
    }

    /** String value given whole. */
    fun writeString(out: GrowableBytes, utf8: ByteArray, offset: Int, length: Int): Int {
        val tagPosition = out.mark()
        out.writeByte(TermTag.STRING)
        out.writeBytes(utf8, offset, length)
        if (length <= MAX_EXACT_VALUE_BYTES) return 1 + length
        // The tag byte plus the string is exactly the canonical string hash input.
        return shortenToHash(out, tagPosition, TermTag.STRING_LONG)
    }

    /**
     * String value for a string longer than [MAX_EXACT_VALUE_BYTES] whose bytes were streamed: [prefix] holds its
     * first [LONG_PREFIX_BYTES] bytes and `(hashLow, hashHigh)` its [CanonicalHash].
     */
    fun writeLongString(out: GrowableBytes, prefix: ByteArray, prefixOffset: Int, hashLow: Long, hashHigh: Long): Int {
        out.writeByte(TermTag.STRING_LONG)
        out.writeBytes(prefix, prefixOffset, LONG_PREFIX_BYTES)
        writeHash(out, hashLow, hashHigh)
        return 1 + LONG_PREFIX_BYTES + CanonicalHash.HASH_BYTES
    }

    /** Whole-object ([TermTag.OBJECT]) or whole-array ([TermTag.ARRAY]) value by its [CanonicalHash]. */
    fun writeContainer(out: GrowableBytes, tag: Int, hashLow: Long, hashHigh: Long): Int {
        require(tag == TermTag.OBJECT || tag == TermTag.ARRAY)
        out.writeByte(tag)
        writeHash(out, hashLow, hashHigh)
        return 1 + CanonicalHash.HASH_BYTES
    }

    private fun finishNumber(out: GrowableBytes, tagPosition: Int, keyLength: Int): Int {
        if (keyLength <= MAX_EXACT_VALUE_BYTES) return 1 + keyLength
        // The tag byte (0x10) plus the NumberKey is exactly the canonical number hash input.
        return shortenToHash(out, tagPosition, TermTag.NUMBER_LONG)
    }

    /** Replaces `tag | value` (from [tagPosition] to the end) with `longTag | value[0, 16) | hash(tag | value)`. */
    private fun shortenToHash(out: GrowableBytes, tagPosition: Int, longTag: Int): Int {
        Xxh3.hash128(out.array, tagPosition, out.size - tagPosition, 0L, hash, 0)
        out.truncate(tagPosition + 1 + LONG_PREFIX_BYTES)
        out.putByte(tagPosition, longTag)
        writeHash(out, hash[0], hash[1])
        return 1 + LONG_PREFIX_BYTES + CanonicalHash.HASH_BYTES
    }

    private fun writeHash(out: GrowableBytes, low: Long, high: Long) {
        val position = out.reserve(CanonicalHash.HASH_BYTES)
        BigEndian.putLong(out.array, position, high)
        BigEndian.putLong(out.array, position + 8, low)
    }

    companion object {
        /** Longest NumberKey or string stored exactly; longer values use the lossy `_LONG` tags. */
        const val MAX_EXACT_VALUE_BYTES: Int = 64

        /** Prefix kept by `NUMBER_LONG` and `STRING_LONG` terms before the hash. */
        const val LONG_PREFIX_BYTES: Int = 16
    }
}
