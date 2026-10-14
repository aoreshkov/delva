package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.Crc32c
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.bytes.Varint
import app.oreshkov.delva.internal.hash.Xxh3
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.readFully
import java.util.Arrays

/**
 * Byte layout of a segment file, shared by the posting, fragment and type indexes. Integers are
 * little-endian unless noted; keys compare as unsigned bytes.
 * ```
 * segment  := { dataBlock | externalValue }* | index | bloom | stats | footer
 * dataBlock:= entry* | restartOffset:u32 × n | n:u32 | crc32c:u32        restart every 16 entries; crc over the block
 * entry    := shared:varint | suffixLength:varint | valueLength << 1 | external :varint | suffix
 *           | inline value bytes, or externalOffset:varint
 * externalValue := { chunk[≤ 4 KiB] | crc32c(chunk):u32 }*                 random access by chunk
 * index    := blockCount:varint | { lastKeyLength:varint lastKey offset:varint length:varint }* | crc32c:u32
 * bloom    := blockCount:u32 | word:u64 × 8 × blockCount | crc32c:u32   BloomFilter; 0 blocks = no filter
 * stats    := entryCount:u64 | itemCount:u64 | minDocId:u64 | maxDocId:u64 | hiddenDocIds:u64 | crc32c:u32
 * footer   := magic "DVSG" | formatVersion:u16 | indexKind:u8 | reserved:u8 | indexOffset:u64 | indexLength:u32
 *           | bloomLength:u32 | statsLength:u32 | reserved:32B | crc32c:u32 over the bytes before it       64 bytes
 * ```
 * Index, bloom and stats follow each other directly, then the footer ends the file. Restart entries store the whole
 * key (`shared` = 0).
 */
internal object SegmentFormat {
    const val FORMAT_VERSION: Int = 2
    const val MAGIC: Int = 0x4753_5644 // "DVSG" read little-endian
    const val FOOTER_BYTES: Int = 64
    const val STATS_BYTES: Int = 44
    const val RESTART_INTERVAL: Int = 16
    const val CHUNK_BYTES: Int = 4096

    const val INDEX_POSTING: Int = 1
    const val INDEX_FRAGMENT: Int = 2
    const val INDEX_TYPE: Int = 3
    const val INDEX_KEY: Int = 4
}

/**
 * What a segment's stats section records. [minDocId] > [maxDocId] when the segment has no documents. [hiddenDocIds] is
 * how many aborted or deleted docIds in `[minDocId, maxDocId]` the writer knew of: it left out their entries, so only
 * docIds hidden later can still have entries in the segment (the deleted-ratio trigger of compaction).
 */
internal class SegmentStats(val entryCount: Long, val itemCount: Long, val minDocId: Long, val maxDocId: Long, val hiddenDocIds: Long = 0) {
    /** DocIds in `[minDocId, maxDocId]`; 0 without documents. */
    val docIdRange: Long get() = if (maxDocId < minDocId) 0 else maxDocId - minDocId + 1
}

/**
 * Writes one segment file sequentially ([SegmentFormat]); entries must come in strictly increasing key order. Values
 * above [inlineLimit] bytes are written as external values right away, before the data block that refers to them, so
 * memory stays at one block plus the index and the bloom filter; a value too large for memory is streamed with
 * [addStreamed].
 *
 * The bloom filter holds the hashes given to [addBloomHash] (8 bytes of memory per key until [finish]). With a
 * [bloomKey], the writer instead counts the distinct bloom keys of the entries it's given and, at [finish], reads its
 * data blocks back from the file to fill the filter, so it holds only the filter (10 bits per key): compaction writes
 * segments larger than any memtable this way. Not thread-safe.
 */
internal class SegmentWriter(
    private val file: FileIo,
    private val indexKind: Int,
    private val blockBytes: Int,
    private val inlineLimit: Int = maxOf(256, blockBytes / 4),
    private val bloomKey: BloomKey? = null,
) {
    private val out = GrowableBytes(FLUSH_BYTES + 256)
    private var outStart = 0L
    private val block = GrowableBytes(blockBytes + 256)
    private var restarts = IntArray(64)
    private var restartCount = 0
    private var blockEntries = 0
    private val lastKey = GrowableBytes(64)
    private var hasLast = false

    private val index = GrowableBytes(1024)
    private var blockCount = 0
    private var bloomHashes = LongArray(if (bloomKey == null) 1024 else 0)
    private var bloomCount = 0L
    private val lastBloomKey = GrowableBytes(if (bloomKey == null) 0 else 32)
    private var blockOffsets = LongArray(if (bloomKey == null) 0 else 64)
    private var blockLengths = IntArray(if (bloomKey == null) 0 else 64)
    private val chunkCrc = ByteArray(4)

    /** Adds a key's hash to the bloom filter; call once per distinct bloom key. Not with a [bloomKey]. */
    fun addBloomHash(hash: Long) {
        check(bloomKey == null) { "the writer derives bloom hashes from keys" }
        val i = bloomCount.toInt()
        if (i == bloomHashes.size) bloomHashes = bloomHashes.copyOf(i * 2)
        bloomHashes[i] = hash
        bloomCount++
    }

    /** Counts the entry's bloom key when it differs from the previous entry's. */
    private fun countBloomKey(key: ByteArray, keyOffset: Int, keyLength: Int) {
        val range = bloomKey!!.of(key, keyOffset, keyLength)
        if (range < 0) return
        val from = keyOffset + (range ushr 32).toInt()
        val length = range.toInt()
        if (lastBloomKey.size == length && Arrays.equals(lastBloomKey.array, 0, length, key, from, from + length) && bloomCount > 0) return
        lastBloomKey.clear()
        lastBloomKey.writeBytes(key, from, length)
        bloomCount++
    }

    /** The filter's words from the bloom keys of the blocks already written. */
    private fun bloomFromBlocks(): LongArray {
        val words = LongArray(BloomFilter.blocksFor(bloomCount) * BloomFilter.BLOCK_WORDS)
        flushOut()
        val source = "${file.path} (being written)"
        val reader = ByteReader(EMPTY, 0, 0, source)
        val key = GrowableBytes(64)
        val last = GrowableBytes(32)
        var hashed = 0L
        for (b in 0 until blockCount) {
            val length = blockLengths[b]
            val bytes = ByteArray(length)
            file.readFully(blockOffsets[b], bytes, 0, length)
            val entriesEnd = length - 8 - 4 * LittleEndian.getInt(bytes, length - 8)
            reader.reset(bytes, 0, entriesEnd, source)
            key.clear()
            while (reader.remaining > 0) {
                val shared = reader.readVarInt(key.size)
                val suffix = reader.readVarInt(reader.remaining)
                val info = reader.readVarLong()
                key.truncate(shared)
                val at = reader.position
                reader.skip(suffix)
                key.writeBytes(bytes, at, suffix)
                if (info and 1L != 0L) reader.readVarLong() else reader.skip((info ushr 1).toInt())
                val range = bloomKey!!.of(key.array, 0, key.size)
                if (range < 0) continue
                val from = (range ushr 32).toInt()
                val n = range.toInt()
                if (hashed > 0 && last.size == n && Arrays.equals(last.array, 0, n, key.array, from, from + n)) continue
                last.clear()
                last.writeBytes(key.array, from, n)
                BloomFilter.add(words, Xxh3.hash64(key.array, from, n, 0L))
                hashed++
            }
        }
        check(hashed == bloomCount) { "$hashed bloom keys read back, $bloomCount written" }
        return words
    }

    fun add(key: ByteArray, keyOffset: Int, keyLength: Int, value: ByteArray, valueOffset: Int, valueLength: Int) {
        val external = valueLength > inlineLimit
        startEntry(key, keyOffset, keyLength, if (external) Varint.MAX_BYTES else valueLength)
        val externalOffset = if (external) writeExternal(value, valueOffset, valueLength) else 0L
        endEntry(key, keyOffset, keyLength, valueLength.toLong(), external, externalOffset, value, valueOffset)
    }

    /**
     * Adds an entry whose value of [valueLength] bytes (1 to [MAX_VALUE_BYTES]) [write] streams to the [ExternalValue]
     * it's given; it's stored as an external value however small.
     */
    fun addStreamed(key: ByteArray, keyOffset: Int, keyLength: Int, valueLength: Long, write: (ExternalValue) -> Unit) {
        require(valueLength in 1..MAX_VALUE_BYTES) { "value of $valueLength bytes" }
        startEntry(key, keyOffset, keyLength, Varint.MAX_BYTES)
        val externalOffset = position()
        val value = ExternalValue()
        write(value)
        value.finish()
        check(value.written == valueLength) { "streamed ${value.written} value bytes, announced $valueLength" }
        endEntry(key, keyOffset, keyLength, valueLength, true, externalOffset, EMPTY, 0)
    }

    /** The bytes of one streamed external value, cut into checksummed chunks as they arrive. */
    inner class ExternalValue internal constructor() {
        private val chunk = ByteArray(SegmentFormat.CHUNK_BYTES)
        private var filled = 0

        var written: Long = 0
            private set

        fun write(bytes: ByteArray, offset: Int, length: Int) {
            var p = 0
            while (p < length) {
                val n = minOf(chunk.size - filled, length - p)
                System.arraycopy(bytes, offset + p, chunk, filled, n)
                filled += n
                p += n
                if (filled == chunk.size) emit()
            }
            written += length
        }

        internal fun finish() {
            if (filled > 0) emit()
        }

        private fun emit() {
            writeOut(chunk, 0, filled)
            LittleEndian.putInt(chunkCrc, 0, Crc32c.of(chunk, 0, filled))
            writeOut(chunkCrc, 0, 4)
            filled = 0
        }
    }

    /** Checks the key order, and ends the block when an entry taking [valueBytes] for its value wouldn't fit. */
    private fun startEntry(key: ByteArray, keyOffset: Int, keyLength: Int, valueBytes: Int) {
        if (hasLast) {
            require(Arrays.compareUnsigned(lastKey.array, 0, lastKey.size, key, keyOffset, keyOffset + keyLength) < 0) { "segment keys out of order" }
        }
        val estimate = 3 * Varint.MAX_BYTES + keyLength + valueBytes
        if (blockEntries > 0 && block.size + estimate + 4 * (restartCount + 1) + 8 > blockBytes) finishBlock()
    }

    private fun endEntry(key: ByteArray, keyOffset: Int, keyLength: Int, valueLength: Long, external: Boolean, externalOffset: Long, value: ByteArray, valueOffset: Int) {
        var shared = 0
        if (blockEntries % SegmentFormat.RESTART_INTERVAL == 0) {
            if (restartCount == restarts.size) restarts = restarts.copyOf(restartCount * 2)
            restarts[restartCount++] = block.size
        } else {
            val max = minOf(lastKey.size, keyLength)
            while (shared < max && lastKey.array[shared] == key[keyOffset + shared]) shared++
        }
        block.writeVarLong(shared.toLong())
        block.writeVarLong((keyLength - shared).toLong())
        block.writeVarLong((valueLength shl 1) or (if (external) 1L else 0L))
        block.writeBytes(key, keyOffset + shared, keyLength - shared)
        if (external) block.writeVarLong(externalOffset) else block.writeBytes(value, valueOffset, valueLength.toInt())
        if (bloomKey != null) countBloomKey(key, keyOffset, keyLength)
        lastKey.clear()
        lastKey.writeBytes(key, keyOffset, keyLength)
        hasLast = true
        blockEntries++
    }

    /** Ends the file: the last block, index, bloom filter, stats and footer. Returns the file size. */
    fun finish(stats: SegmentStats): Long {
        if (blockEntries > 0) finishBlock()
        val indexOffset = position()
        // Built before the index is written, so the blocks read back end where the index starts.
        val words = when {
            bloomCount == 0L -> EMPTY_WORDS
            bloomKey != null -> bloomFromBlocks()
            else -> LongArray(BloomFilter.blocksFor(bloomCount) * BloomFilter.BLOCK_WORDS).also { w -> for (i in 0 until bloomCount.toInt()) BloomFilter.add(w, bloomHashes[i]) }
        }
        val sections = GrowableBytes(index.size + 128)
        sections.writeVarLong(blockCount.toLong())
        sections.writeBytes(index.array, 0, index.size)
        sections.writeIntLE(Crc32c.of(sections.array, 0, sections.size))
        val indexLength = sections.size
        writeOut(sections.array, 0, sections.size)

        // The bloom filter goes out in pieces, so a large one is never held twice.
        val crc = Crc32c()
        sections.clear()
        sections.writeIntLE(words.size / BloomFilter.BLOCK_WORDS)
        var w = 0
        do {
            val n = minOf(words.size - w, BLOOM_PIECE_WORDS)
            for (i in w until w + n) sections.writeLongLE(words[i])
            w += n
            crc.update(sections.array, 0, sections.size)
            writeOut(sections.array, 0, sections.size)
            sections.clear()
        } while (w < words.size)
        sections.writeIntLE(crc.value)
        writeOut(sections.array, 0, 4)
        val bloomLength = 8L + 8L * words.size
        require(bloomLength <= Int.MAX_VALUE) { "bloom filter of $bloomLength bytes" }

        sections.clear()
        sections.writeLongLE(stats.entryCount)
        sections.writeLongLE(stats.itemCount)
        sections.writeLongLE(stats.minDocId)
        sections.writeLongLE(stats.maxDocId)
        sections.writeLongLE(stats.hiddenDocIds)
        sections.writeIntLE(Crc32c.of(sections.array, 0, sections.size))

        val footerStart = sections.size
        sections.writeIntLE(SegmentFormat.MAGIC)
        sections.writeShortLE(SegmentFormat.FORMAT_VERSION)
        sections.writeByte(indexKind)
        sections.writeByte(0)
        sections.writeLongLE(indexOffset)
        sections.writeIntLE(indexLength)
        sections.writeIntLE(bloomLength.toInt())
        sections.writeIntLE(SegmentFormat.STATS_BYTES)
        sections.reserve(32).let { sections.array.fill(0, it, it + 32) }
        sections.writeIntLE(Crc32c.of(sections.array, footerStart, sections.size - footerStart))
        writeOut(sections.array, 0, sections.size)
        flushOut()
        return outStart
    }

    private fun finishBlock() {
        for (i in 0 until restartCount) block.writeIntLE(restarts[i])
        block.writeIntLE(restartCount)
        block.writeIntLE(Crc32c.of(block.array, 0, block.size))
        val offset = position()
        if (bloomKey != null) {
            if (blockCount == blockOffsets.size) {
                blockOffsets = blockOffsets.copyOf(blockCount * 2)
                blockLengths = blockLengths.copyOf(blockCount * 2)
            }
            blockOffsets[blockCount] = offset
            blockLengths[blockCount] = block.size
        }
        writeOut(block.array, 0, block.size)
        index.writeVarLong(lastKey.size.toLong())
        index.writeBytes(lastKey.array, 0, lastKey.size)
        index.writeVarLong(offset)
        index.writeVarLong(block.size.toLong())
        blockCount++
        block.clear()
        restartCount = 0
        blockEntries = 0
    }

    private fun writeExternal(value: ByteArray, offset: Int, length: Int): Long {
        val at = position()
        var p = 0
        while (p < length) {
            val n = minOf(SegmentFormat.CHUNK_BYTES, length - p)
            writeOut(value, offset + p, n)
            LittleEndian.putInt(chunkCrc, 0, Crc32c.of(value, offset + p, n))
            writeOut(chunkCrc, 0, 4)
            p += n
        }
        return at
    }

    private fun position(): Long = outStart + out.size

    private fun writeOut(bytes: ByteArray, offset: Int, length: Int) {
        if (length >= FLUSH_BYTES) {
            flushOut()
            file.write(outStart, bytes, offset, length)
            outStart += length
            return
        }
        out.writeBytes(bytes, offset, length)
        if (out.size >= FLUSH_BYTES) flushOut()
    }

    private fun flushOut() {
        if (out.size == 0) return
        file.write(outStart, out.array, 0, out.size)
        outStart += out.size
        out.clear()
    }

    companion object {
        /** Largest value a segment holds: readers take value lengths as `Int`. */
        const val MAX_VALUE_BYTES: Long = Int.MAX_VALUE.toLong() - (1L shl 20)

        private const val FLUSH_BYTES = 1 shl 16
        private const val BLOOM_PIECE_WORDS = 8192
        private val EMPTY = ByteArray(0)
        private val EMPTY_WORDS = LongArray(0)
    }
}
