package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.internal.bytes.LittleEndian

/**
 * Which bytes of a segment key its bloom filter holds: the whole term of a posting entry, or the
 * docId inside a fragment or forward type key. Entries in a row with equal bloom keys add one hash.
 */
internal fun interface BloomKey {
    /** `offset shl 32 or length` of the bloom key within `key[keyOffset, keyOffset + keyLength)`, or -1 for none. */
    fun of(key: ByteArray, keyOffset: Int, keyLength: Int): Long

    companion object {
        val WHOLE_KEY: BloomKey = BloomKey { _, _, length -> length.toLong() }

        /** The 8-byte docId at [offset] of keys that start with [firstByte] (any key when -1). */
        fun docIdAt(offset: Int, firstByte: Int = -1): BloomKey = BloomKey { key, keyOffset, length ->
            if (length < offset + 8 || (firstByte >= 0 && key[keyOffset].toInt() and 0xFF != firstByte)) -1L else (offset.toLong() shl 32) or 8L
        }
    }
}

/**
 * A blocked bloom filter: the key's 64-bit hash picks one 512-bit block, then [PROBES] bits inside it,
 * so a lookup touches one cache line. With [BITS_PER_KEY] bits per key about 1 % of absent keys pass. The words are
 * stored little-endian in the segment. Keys are hashed by the caller (XXH3-64, seed 0).
 */
internal object BloomFilter {
    const val BITS_PER_KEY: Int = 10
    const val BLOCK_WORDS: Int = 8
    const val PROBES: Int = 7

    /** Blocks for [keys] keys; at least one. */
    fun blocksFor(keys: Long): Int {
        val bits = maxOf(keys, 1L) * BITS_PER_KEY
        val blocks = (bits + 511) / 512
        require(blocks <= Int.MAX_VALUE / BLOCK_WORDS) { "bloom filter for $keys keys" }
        return blocks.toInt()
    }

    fun add(words: LongArray, hash: Long) {
        val base = block(words.size / BLOCK_WORDS, hash) * BLOCK_WORDS
        var bit = hash.toInt()
        val step = ((hash * -0x61c8864680b583ebL) ushr 32).toInt() or 1
        repeat(PROBES) {
            val b = bit and 511
            words[base + (b ushr 6)] = words[base + (b ushr 6)] or (1L shl b)
            bit += step
        }
    }

    fun mayContain(words: LongArray, hash: Long): Boolean {
        val base = block(words.size / BLOCK_WORDS, hash) * BLOCK_WORDS
        var bit = hash.toInt()
        val step = ((hash * -0x61c8864680b583ebL) ushr 32).toInt() or 1
        repeat(PROBES) {
            val b = bit and 511
            if (words[base + (b ushr 6)] and (1L shl b) == 0L) return false
            bit += step
        }
        return true
    }

    /** [mayContain] over words stored little-endian in `bytes`, where block [firstBlock] starts at [offset]. */
    fun mayContain(bytes: ByteArray, offset: Int, firstBlock: Int, blocks: Int, hash: Long): Boolean {
        val base = offset + (block(blocks, hash) - firstBlock) * BLOCK_WORDS * 8
        var bit = hash.toInt()
        val step = ((hash * -0x61c8864680b583ebL) ushr 32).toInt() or 1
        repeat(PROBES) {
            val b = bit and 511
            if (LittleEndian.getLong(bytes, base + 8 * (b ushr 6)) and (1L shl b) == 0L) return false
            bit += step
        }
        return true
    }

    /** Block of [hash]: the high bits scaled to the block count, independent of the probe bits. */
    fun block(blocks: Int, hash: Long): Int = (((hash ushr 40) * blocks) ushr 24).toInt()
}
