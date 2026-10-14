package app.oreshkov.delva.internal.canon

import app.oreshkov.delva.internal.bytes.BigEndian
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.bytes.Varint
import app.oreshkov.delva.internal.hash.Xxh3
import app.oreshkov.delva.internal.hash.Xxh3State

/**
 * Canonical value hash: a Merkle hash over JSON values, computed while streaming in O(depth) memory.
 * Shared by literal-value terms, subtree hashes in fragments, and comparison. Every hash is XXH3-128, unseeded.
 *
 * ```
 * null / false / true   h = H( tag )                          tags 0x01 / 0x02 / 0x03
 * number                h = H( 0x10 | NumberKey )
 * string                h = H( 0x20 | utf8 )                  streamed in any number of parts
 * array                 a₀ = 0,  aᵢ = H( 'A' | aᵢ₋₁ | h(eᵢ) ),  h = H( 'a' | aₙ | n:u64 )
 * object                m = Σ H( 'M' | keyLen:varint | key | h(v) )  mod 2¹²⁸,  h = H( 'O' | m | n:u64 )
 * ```
 * A 128-bit value inside a hash input is 16 bytes: high 64 bits big-endian, then low 64 bits big-endian (the xxHash
 * canonical form). Counts `n` are little-endian. The object sum ignores member order; distinct keys make it a
 * set hash.
 *
 * Container state lives in caller-provided frames of [FRAME_SLOTS] longs (the splitter's frame stack):
 * `[low, high, count]`. Hash results are written as `(low, high)` pairs. One instance per insert or thread; only one
 * string may be open at a time.
 */
internal class CanonicalHash {
    private val stringState = Xxh3State()
    private val inputState = Xxh3State()
    private val scratch = ByteArray(1 + 16 + 16)
    private val member = LongArray(2)

    /** Short hash inputs are assembled here and hashed one-shot, which gives the same digest as the streaming state. */
    private val shortInput = ByteArray(SHORT_INPUT_BYTES)

    fun nullValue(out: LongArray, outIndex: Int) = constant(NULL_HASH, out, outIndex)

    fun booleanValue(value: Boolean, out: LongArray, outIndex: Int) =
        constant(if (value) TRUE_HASH else FALSE_HASH, out, outIndex)

    /** Hash of a number given its NumberKey bytes (see [CanonicalNumber]). */
    fun number(numberKey: ByteArray, offset: Int, length: Int, out: LongArray, outIndex: Int) {
        tagged(TAG_NUMBER, numberKey, offset, length, out, outIndex)
    }

    fun string(utf8: ByteArray, offset: Int, length: Int, out: LongArray, outIndex: Int) = tagged(TAG_STRING, utf8, offset, length, out, outIndex)

    private fun tagged(tag: Int, bytes: ByteArray, offset: Int, length: Int, out: LongArray, outIndex: Int) {
        if (length < SHORT_INPUT_BYTES) {
            shortInput[0] = tag.toByte()
            System.arraycopy(bytes, offset, shortInput, 1, length)
            Xxh3.hash128(shortInput, 0, 1 + length, 0L, out, outIndex)
        } else {
            inputState.reset().update(tag).update(bytes, offset, length).digest128(out, outIndex)
        }
    }

    fun beginString() {
        stringState.reset().update(TAG_STRING)
    }

    fun stringPart(utf8: ByteArray, offset: Int, length: Int) {
        stringState.update(utf8, offset, length)
    }

    fun endString(out: LongArray, outIndex: Int) = stringState.digest128(out, outIndex)

    fun beginArray(frames: LongArray, frame: Int) = clearFrame(frames, frame)

    fun arrayElement(frames: LongArray, frame: Int, hash: LongArray, hashIndex: Int) {
        scratch[0] = DOMAIN_ARRAY_ELEMENT
        putHash(scratch, 1, frames[frame], frames[frame + 1])
        putHash(scratch, 17, hash[hashIndex], hash[hashIndex + 1])
        Xxh3.hash128(scratch, 0, 33, 0L, frames, frame)
        frames[frame + 2]++
    }

    fun endArray(frames: LongArray, frame: Int, out: LongArray, outIndex: Int) =
        finishContainer(DOMAIN_ARRAY_END, frames, frame, out, outIndex)

    fun beginObject(frames: LongArray, frame: Int) = clearFrame(frames, frame)

    fun objectMember(frames: LongArray, frame: Int, key: ByteArray, keyOffset: Int, keyLength: Int, hash: LongArray, hashIndex: Int) {
        if (keyLength + 1 + Varint.MAX_BYTES + HASH_BYTES <= SHORT_INPUT_BYTES) {
            shortInput[0] = DOMAIN_MEMBER
            var p = Varint.write(shortInput, 1, keyLength.toLong())
            System.arraycopy(key, keyOffset, shortInput, p, keyLength)
            p += keyLength
            putHash(shortInput, p, hash[hashIndex], hash[hashIndex + 1])
            Xxh3.hash128(shortInput, 0, p + HASH_BYTES, 0L, member, 0)
        } else {
            scratch[0] = DOMAIN_MEMBER
            val varintEnd = Varint.write(scratch, 1, keyLength.toLong())
            inputState.reset().update(scratch, 0, varintEnd).update(key, keyOffset, keyLength)
            putHash(scratch, 0, hash[hashIndex], hash[hashIndex + 1])
            inputState.update(scratch, 0, 16).digest128(member, 0)
        }

        val low = frames[frame] + member[0]
        val carry = if (java.lang.Long.compareUnsigned(low, member[0]) < 0) 1L else 0L
        frames[frame + 1] += member[1] + carry
        frames[frame] = low
        frames[frame + 2]++
    }

    fun endObject(frames: LongArray, frame: Int, out: LongArray, outIndex: Int) =
        finishContainer(DOMAIN_OBJECT_END, frames, frame, out, outIndex)

    private fun finishContainer(domain: Byte, frames: LongArray, frame: Int, out: LongArray, outIndex: Int) {
        scratch[0] = domain
        putHash(scratch, 1, frames[frame], frames[frame + 1])
        LittleEndian.putLong(scratch, 17, frames[frame + 2])
        Xxh3.hash128(scratch, 0, 25, 0L, out, outIndex)
    }

    private fun clearFrame(frames: LongArray, frame: Int) {
        frames[frame] = 0
        frames[frame + 1] = 0
        frames[frame + 2] = 0
    }

    private fun constant(hash: LongArray, out: LongArray, outIndex: Int) {
        out[outIndex] = hash[0]
        out[outIndex + 1] = hash[1]
    }

    companion object {
        const val FRAME_SLOTS: Int = 3
        const val HASH_BYTES: Int = 16
        private const val SHORT_INPUT_BYTES = 256

        const val TAG_NULL: Int = 0x01
        const val TAG_FALSE: Int = 0x02
        const val TAG_TRUE: Int = 0x03
        const val TAG_NUMBER: Int = 0x10
        const val TAG_STRING: Int = 0x20
        private const val DOMAIN_ARRAY_ELEMENT = 'A'.code.toByte()
        private const val DOMAIN_ARRAY_END = 'a'.code.toByte()
        private const val DOMAIN_MEMBER = 'M'.code.toByte()
        private const val DOMAIN_OBJECT_END = 'O'.code.toByte()

        private val NULL_HASH = tagHash(TAG_NULL)
        private val FALSE_HASH = tagHash(TAG_FALSE)
        private val TRUE_HASH = tagHash(TAG_TRUE)

        private fun tagHash(tag: Int) = LongArray(2).also { Xxh3.hash128(byteArrayOf(tag.toByte()), 0, 1, 0L, it, 0) }

        /** Writes a 128-bit hash in its 16-byte canonical form: high then low, both big-endian. */
        fun putHash(bytes: ByteArray, index: Int, low: Long, high: Long) {
            BigEndian.putLong(bytes, index, high)
            BigEndian.putLong(bytes, index + 8, low)
        }
    }
}
