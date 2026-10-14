package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.ByteInput
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.Crc32c
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.readFully
import app.oreshkov.delva.internal.io.unreadable
import java.io.IOException
import java.util.Arrays

/**
 * An open segment file ([SegmentFormat]): the index and stats are validated and kept on the heap when it opens; data
 * blocks and external value chunks are read on demand, checked against their CRC and kept in the shared [ByteCache].
 * A small bloom filter is kept on the heap too; a larger one is read in pages through the cache, each page checked
 * against a CRC computed when the segment opened, so an open segment holds a few KiB of heap however large it is
 * (segments accumulate with every flush until compaction). Every inconsistency throws [CorruptionException].
 * Thread-safe; cursors are not.
 */
internal class SegmentReader private constructor(
    val fileNo: Long,
    private val file: FileIo,
    private val cache: ByteCache,
    val indexKind: Int,
    val stats: SegmentStats,
    val fileBytes: Long,
    private val lastKeys: Array<ByteArray>,
    private val blockOffsets: LongArray,
    private val blockLengths: IntArray,
    private val bloom: LongArray?,
    private val bloomBlocks: Int,
    private val bloomWordsOffset: Long,
    private val bloomPageCrcs: IntArray?,
    private val dataEnd: Long,
    val source: String,
) : AutoCloseable {
    val blockCount: Int get() = lastKeys.size

    /** Heap held while the segment is open: block index, bloom filter or its page CRCs. */
    val heapBytes: Long = run {
        var bytes = HEAP_BASE_BYTES + 16L * lastKeys.size + (bloom?.size ?: 0) * 8L + (bloomPageCrcs?.size ?: 0) * 4L
        for (k in lastKeys) bytes += 16 + k.size
        bytes
    }

    /** Whether a key whose bloom hash is [hash] may be in the segment. */
    fun mayContain(hash: Long): Boolean {
        if (bloom != null) return BloomFilter.mayContain(bloom, hash)
        if (bloomBlocks == 0) return true
        val page = BloomFilter.block(bloomBlocks, hash) / BLOOM_PAGE_BLOCKS
        return BloomFilter.mayContain(bloomPage(page), 0, page * BLOOM_PAGE_BLOCKS, bloomBlocks, hash)
    }

    private fun bloomPage(page: Int): ByteArray {
        val offset = bloomWordsOffset + page.toLong() * BLOOM_PAGE_BYTES
        val key = cacheKey(offset)
        cache.get(key)?.let { return it }
        val blocks = minOf(BLOOM_PAGE_BLOCKS, bloomBlocks - page * BLOOM_PAGE_BLOCKS)
        val bytes = read(offset, blocks * BloomFilter.BLOCK_WORDS * 8)
        if (Crc32c.of(bytes, 0, bytes.size) != bloomPageCrcs!![page]) throw CorruptionException("$source@$offset", "bloom filter page checksum mismatch")
        cache.put(key, bytes)
        return bytes
    }

    fun cursor(): SegmentCursor = SegmentCursor(this)

    /**
     * Reads every bloom page through its CRC check; a filter kept on the heap was checked when the segment
     * opened. Throws [CorruptionException] at the first damaged page.
     */
    fun checkBloom() {
        if (bloom != null || bloomPageCrcs == null) return
        for (page in bloomPageCrcs.indices) bloomPage(page)
    }

    /** First block whose last key is ≥ the target, or [blockCount]. */
    internal fun blockFor(target: ByteArray, offset: Int, length: Int): Int {
        var lo = 0
        var hi = lastKeys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val k = lastKeys[mid]
            if (Arrays.compareUnsigned(k, 0, k.size, target, offset, offset + length) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The verified bytes of block [i], trailer included. */
    internal fun block(i: Int): ByteArray {
        val offset = blockOffsets[i]
        val key = cacheKey(offset)
        cache.get(key)?.let { return it }
        val length = blockLengths[i]
        val bytes = read(offset, length)
        if (Crc32c.of(bytes, 0, length - 4) != LittleEndian.getInt(bytes, length - 4)) throw CorruptionException("$source@$offset", "block checksum mismatch")
        val restarts = LittleEndian.getInt(bytes, length - 8)
        val entriesEnd = length.toLong() - 8 - 4L * restarts
        if (restarts < 1 || entriesEnd <= 0) throw CorruptionException("$source@$offset", "$restarts restart points in a $length-byte block")
        var previous = -1
        for (r in 0 until restarts) {
            val at = LittleEndian.getInt(bytes, entriesEnd.toInt() + 4 * r)
            if (at <= previous || at >= entriesEnd || (r == 0 && at != 0)) throw CorruptionException("$source@$offset", "restart point $at")
            previous = at
        }
        cache.put(key, bytes)
        return bytes
    }

    /** The verified payload of the external value chunk at [offset] with [length] payload bytes. */
    internal fun chunk(offset: Long, length: Int): ByteArray {
        val key = cacheKey(offset)
        cache.get(key)?.let { if (it.size == length) return it }
        if (offset + length + 4 > dataEnd) throw CorruptionException("$source@$offset", "value chunk beyond the data")
        val bytes = read(offset, length + 4)
        if (Crc32c.of(bytes, 0, length) != LittleEndian.getInt(bytes, length)) throw CorruptionException("$source@$offset", "value chunk checksum mismatch")
        val payload = bytes.copyOf(length)
        cache.put(key, payload)
        return payload
    }

    internal fun checkExternal(offset: Long, length: Long, at: String) {
        val chunks = (length + SegmentFormat.CHUNK_BYTES - 1) / SegmentFormat.CHUNK_BYTES
        if (offset < 0 || length <= 0 || offset > dataEnd - length - 4 * chunks) throw CorruptionException(at, "external value [$offset, +$length) beyond the data")
    }

    private fun read(offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        try {
            file.readFully(offset, bytes, 0, length)
        } catch (e: IOException) {
            throw unreadable("$source@$offset", "unreadable", e)
        }
        return bytes
    }

    private fun cacheKey(offset: Long): Long = (fileNo shl 40) or offset

    override fun close() = file.close()

    companion object {
        /** Reader object, arrays headers, file handle. */
        private const val HEAP_BASE_BYTES: Long = 512

        /** Bloom blocks per page read through the cache (4 KiB); filters up to one page stay on the heap. */
        private const val BLOOM_PAGE_BLOCKS: Int = 64
        private const val BLOOM_PAGE_BYTES: Int = BLOOM_PAGE_BLOCKS * BloomFilter.BLOCK_WORDS * 8
        /** Opens and validates the segment in [file]; [expectedKind] is the index it must belong to. */
        fun open(fileNo: Long, file: FileIo, cache: ByteCache, expectedKind: Int): SegmentReader {
            val source = file.path.toString()
            val size = try {
                file.size()
            } catch (e: IOException) {
                throw unreadable(source, "unreadable", e)
            }
            if (size < SegmentFormat.FOOTER_BYTES || size >= 1L shl 40) throw CorruptionException(source, "$size bytes")
            val footer = ByteArray(SegmentFormat.FOOTER_BYTES)
            try {
                file.readFully(size - footer.size, footer, 0, footer.size)
            } catch (e: IOException) {
                throw unreadable(source, "unreadable", e)
            }
            if (Crc32c.of(footer, 0, 60) != LittleEndian.getInt(footer, 60)) throw CorruptionException(source, "footer checksum mismatch")
            if (LittleEndian.getInt(footer, 0) != SegmentFormat.MAGIC) throw CorruptionException(source, "not a segment")
            val version = LittleEndian.getShort(footer, 4).toInt()
            if (version != SegmentFormat.FORMAT_VERSION) throw CorruptionException(source, "unsupported format version $version")
            val kind = footer[6].toInt()
            if (kind != expectedKind) throw CorruptionException(source, "index kind $kind, expected $expectedKind")
            val indexOffset = LittleEndian.getLong(footer, 8)
            val indexLength = LittleEndian.getInt(footer, 16).toLong() and 0xFFFFFFFFL
            val bloomLength = LittleEndian.getInt(footer, 20).toLong() and 0xFFFFFFFFL
            val statsLength = LittleEndian.getInt(footer, 24).toLong() and 0xFFFFFFFFL
            val sectionsLength = indexLength + bloomLength + statsLength
            if (indexOffset < 0 || statsLength != SegmentFormat.STATS_BYTES.toLong() || indexLength < 5 || bloomLength < 8 ||
                indexOffset + sectionsLength + SegmentFormat.FOOTER_BYTES != size || sectionsLength > Int.MAX_VALUE
            ) {
                throw CorruptionException(source, "section layout ($indexOffset, $indexLength, $bloomLength, $statsLength) in $size bytes")
            }
            val sections = ByteArray(sectionsLength.toInt())
            try {
                file.readFully(indexOffset, sections, 0, sections.size)
            } catch (e: IOException) {
                throw unreadable(source, "unreadable", e)
            }
            val i = indexLength.toInt()
            val b = bloomLength.toInt()
            checkSection(sections, 0, i, source, "index")
            checkSection(sections, i, b, source, "bloom filter")
            checkSection(sections, i + b, SegmentFormat.STATS_BYTES, source, "stats")

            val r = ByteReader(sections, 0, i - 4, "$source index")
            val blocks = r.readVarInt(r.remaining / 3)
            val lastKeys = arrayOfNulls<ByteArray>(blocks)
            val offsets = LongArray(blocks)
            val lengths = IntArray(blocks)
            var end = 0L
            for (k in 0 until blocks) {
                val keyLength = r.readVarInt(r.remaining)
                val key = ByteArray(keyLength)
                r.readBytes(key, 0, keyLength)
                val offset = r.readVarLong()
                val length = r.readVarInt()
                if (offset < end || length < 12 || offset > indexOffset - length) throw CorruptionException("$source index", "block [$offset, +$length)")
                if (k > 0) {
                    val previous = lastKeys[k - 1]!!
                    if (Arrays.compareUnsigned(previous, key) >= 0) throw CorruptionException("$source index", "block keys out of order")
                }
                lastKeys[k] = key
                offsets[k] = offset
                lengths[k] = length
                end = offset + length
            }
            if (r.remaining != 0) throw CorruptionException("$source index", "${r.remaining} trailing bytes")

            val bloomBlocks = LittleEndian.getInt(sections, i)
            if (bloomBlocks < 0 || 4L + 8L * BloomFilter.BLOCK_WORDS * bloomBlocks + 4 != bloomLength) throw CorruptionException("$source bloom filter", "$bloomBlocks blocks in $bloomLength bytes")
            val onHeap = bloomBlocks in 1..BLOOM_PAGE_BLOCKS
            val bloom = if (onHeap) LongArray(bloomBlocks * BloomFilter.BLOCK_WORDS) { LittleEndian.getLong(sections, i + 4 + 8 * it) } else null
            val pageCrcs = if (bloomBlocks > BLOOM_PAGE_BLOCKS) {
                IntArray((bloomBlocks + BLOOM_PAGE_BLOCKS - 1) / BLOOM_PAGE_BLOCKS) { p ->
                    val from = i + 4 + p * BLOOM_PAGE_BYTES
                    Crc32c.of(sections, from, minOf(BLOOM_PAGE_BYTES, i + 4 + bloomBlocks * BloomFilter.BLOCK_WORDS * 8 - from))
                }
            } else {
                null
            }

            val s = i + b
            val stats = SegmentStats(LittleEndian.getLong(sections, s), LittleEndian.getLong(sections, s + 8), LittleEndian.getLong(sections, s + 16), LittleEndian.getLong(sections, s + 24), LittleEndian.getLong(sections, s + 32))
            if (stats.entryCount < 0 || stats.itemCount < 0 || (stats.entryCount > 0) != (blocks > 0)) throw CorruptionException("$source stats", "${stats.entryCount} entries in $blocks blocks")
            if (stats.hiddenDocIds < 0 || stats.hiddenDocIds > stats.docIdRange) throw CorruptionException("$source stats", "${stats.hiddenDocIds} hidden docIds in [${stats.minDocId}, ${stats.maxDocId}]")
            @Suppress("UNCHECKED_CAST")
            return SegmentReader(fileNo, file, cache, kind, stats, size, lastKeys as Array<ByteArray>, offsets, lengths, bloom, bloomBlocks, indexOffset + i + 4, pageCrcs, indexOffset, source)
        }

        private fun checkSection(bytes: ByteArray, offset: Int, length: Int, source: String, what: String) {
            if (Crc32c.of(bytes, offset, length - 4) != LittleEndian.getInt(bytes, offset + length - 4)) throw CorruptionException(source, "$what checksum mismatch")
        }
    }
}

/**
 * Walks the entries of one segment in key order: [seekCeiling], [seekFloor], [first] and [next] position it on an
 * entry, whose [key] and value ([valueLength], inline or external) are then available. Reusable; not thread-safe.
 */
internal class SegmentCursor(private val segment: SegmentReader) {
    /** Key of the current entry: `key.array[0, key.size)`. */
    val key = GrowableBytes(64)

    var valid: Boolean = false
        private set
    var valueLength: Int = 0
        private set
    var valueExternal: Boolean = false
        private set

    /** Inline value: `valueBytes[valueOffset, valueOffset + valueLength)`. External value: at [externalOffset]. */
    var valueBytes: ByteArray = EMPTY
        private set
    var valueOffset: Int = 0
        private set
    var externalOffset: Long = 0
        private set

    private var blockIndex = -1
    private var bytes = EMPTY
    private var position = 0
    private var entriesEnd = 0
    private var entryIndex = 0
    private val reader = ByteReader(EMPTY)

    // The best floor candidate while scanning.
    private val savedKey = GrowableBytes(64)
    private var savedLength = 0
    private var savedExternal = false
    private var savedBytes = EMPTY
    private var savedOffset = 0
    private var savedExternalOffset = 0L
    private var savedBlock = -1
    private var savedPosition = 0
    private var savedEntryIndex = 0

    /** Positions on the first entry with a key ≥ the target; false if there is none. */
    fun seekCeiling(target: ByteArray, offset: Int = 0, length: Int = target.size): Boolean {
        val b = segment.blockFor(target, offset, length)
        if (b == segment.blockCount) return invalid()
        loadBlock(b)
        seekRestart(target, offset, length)
        while (decodeNext()) {
            if (compareKey(target, offset, length) >= 0) return true
        }
        throw CorruptionException("${segment.source} block $b", "the block's last key doesn't match the index")
    }

    /** Positions on the last entry with a key ≤ the target; false if there is none. */
    fun seekFloor(target: ByteArray, offset: Int = 0, length: Int = target.size): Boolean {
        if (segment.blockCount == 0) return invalid()
        var b = segment.blockFor(target, offset, length)
        if (b == segment.blockCount) b--
        loadBlock(b)
        if (!seekRestart(target, offset, length)) {
            // The target is before this block's first key.
            if (b == 0) return invalid()
            return lastOf(b - 1)
        }
        var found = false
        while (decodeNext()) {
            if (compareKey(target, offset, length) > 0) break
            save()
            found = true
        }
        if (!found) throw CorruptionException("${segment.source} block $b", "restart key after the target")
        restore()
        return true
    }

    fun first(): Boolean {
        if (segment.blockCount == 0) return invalid()
        loadBlock(0)
        position = 0
        entryIndex = 0
        key.clear()
        return decodeNext() || corruptEmpty()
    }

    fun next(): Boolean {
        if (!valid) return false
        if (position < entriesEnd) return decodeNext()
        if (blockIndex + 1 >= segment.blockCount) return invalid()
        loadBlock(blockIndex + 1)
        position = 0
        entryIndex = 0
        key.clear()
        return decodeNext() || corruptEmpty()
    }

    /** Points [input] at the current entry's value. */
    fun openValue(input: ValueInput) {
        check(valid)
        if (valueExternal) input.openExternal(segment, externalOffset, valueLength) else input.openBlock(segment, blockIndex, valueBytes, valueOffset, valueLength)
    }

    /**
     * Reads the current entry's value through its CRC checks without keeping it: an inline value came with its
     * block, an external one is read chunk by chunk. Throws [CorruptionException] on the first damaged chunk.
     */
    fun checkValue() {
        check(valid)
        if (!valueExternal) return
        var p = 0
        while (p < valueLength) {
            val n = minOf(SegmentFormat.CHUNK_BYTES, valueLength - p)
            segment.chunk(externalOffset + (p / SegmentFormat.CHUNK_BYTES) * (SegmentFormat.CHUNK_BYTES + 4L), n)
            p += n
        }
    }

    /** Index of the block the current entry is in; -1 before the first entry. */
    val block: Int get() = blockIndex

    /** Appends the current entry's value to [out]. */
    fun copyValue(out: GrowableBytes) {
        check(valid)
        if (!valueExternal) {
            out.writeBytes(valueBytes, valueOffset, valueLength)
            return
        }
        val start = out.reserve(valueLength)
        var p = 0
        while (p < valueLength) {
            val n = minOf(SegmentFormat.CHUNK_BYTES, valueLength - p)
            val chunk = segment.chunk(externalOffset + (p / SegmentFormat.CHUNK_BYTES) * (SegmentFormat.CHUNK_BYTES + 4L), n)
            System.arraycopy(chunk, 0, out.array, start + p, n)
            p += n
        }
    }

    private fun lastOf(b: Int): Boolean {
        loadBlock(b)
        position = 0
        entryIndex = 0
        key.clear()
        while (decodeNext()) save()
        restore()
        return true
    }

    /**
     * Moves to the last restart point whose key is ≤ the target (the block's first one if none is) and decodes
     * nothing yet. Returns false when even the first key is after the target.
     */
    private fun seekRestart(target: ByteArray, offset: Int, length: Int): Boolean {
        val restarts = LittleEndian.getInt(bytes, bytes.size - 8)
        var lo = 0
        var hi = restarts - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            position = restartOffset(mid)
            entryIndex = mid * SegmentFormat.RESTART_INTERVAL
            key.clear()
            decodeNext()
            if (compareKey(target, offset, length) <= 0) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        position = restartOffset(maxOf(best, 0))
        entryIndex = maxOf(best, 0) * SegmentFormat.RESTART_INTERVAL
        key.clear()
        valid = false
        return best >= 0
    }

    private fun restartOffset(r: Int): Int = LittleEndian.getInt(bytes, entriesEnd + 4 * r)

    private fun loadBlock(b: Int) {
        bytes = segment.block(b)
        blockIndex = b
        entriesEnd = bytes.size - 8 - 4 * LittleEndian.getInt(bytes, bytes.size - 8)
    }

    private fun decodeNext(): Boolean {
        if (position >= entriesEnd) {
            valid = false
            return false
        }
        val at = position
        reader.reset(bytes, position, entriesEnd - position, "${segment.source} block $blockIndex")
        val shared = reader.readVarInt(key.size)
        if (entryIndex % SegmentFormat.RESTART_INTERVAL == 0) {
            val r = entryIndex / SegmentFormat.RESTART_INTERVAL
            if (r >= LittleEndian.getInt(bytes, bytes.size - 8) || restartOffset(r) != at || shared != 0) {
                throw CorruptionException("${segment.source} block $blockIndex@$at", "entry $entryIndex isn't at its restart point")
            }
        }
        entryIndex++
        val suffix = reader.readVarInt(reader.remaining)
        val info = reader.readVarLong()
        val length = info ushr 1
        if (length > Int.MAX_VALUE) throw CorruptionException("${segment.source} block $blockIndex@$at", "value of $length bytes")
        key.truncate(shared)
        val suffixAt = reader.position
        reader.skip(suffix)
        key.writeBytes(bytes, suffixAt, suffix)
        valueLength = length.toInt()
        valueExternal = info and 1L != 0L
        if (valueExternal) {
            externalOffset = reader.readVarLong()
            segment.checkExternal(externalOffset, length, "${segment.source} block $blockIndex@$at")
            valueBytes = EMPTY
        } else {
            valueBytes = bytes
            valueOffset = reader.position
            reader.skip(valueLength)
        }
        position = reader.position
        valid = true
        return true
    }

    private fun compareKey(target: ByteArray, offset: Int, length: Int): Int =
        Arrays.compareUnsigned(key.array, 0, key.size, target, offset, offset + length)

    private fun save() {
        savedKey.clear()
        savedKey.writeBytes(key.array, 0, key.size)
        savedLength = valueLength
        savedExternal = valueExternal
        savedBytes = valueBytes
        savedOffset = valueOffset
        savedExternalOffset = externalOffset
        savedBlock = blockIndex
        savedPosition = position
        savedEntryIndex = entryIndex
    }

    /** Makes the saved entry current; [next] continues after it. */
    private fun restore() {
        key.clear()
        key.writeBytes(savedKey.array, 0, savedKey.size)
        valueLength = savedLength
        valueExternal = savedExternal
        valueBytes = savedBytes
        valueOffset = savedOffset
        externalOffset = savedExternalOffset
        if (blockIndex != savedBlock) loadBlock(savedBlock)
        position = savedPosition
        entryIndex = savedEntryIndex
        valid = true
    }

    private fun invalid(): Boolean {
        valid = false
        return false
    }

    private fun corruptEmpty(): Boolean = throw CorruptionException("${segment.source} block $blockIndex", "empty block")

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * Sequential and random reads of one value, inline in a block or external in CRC-checked chunks. Varints follow the
 * rules of [ByteReader.readVarLong]; reading past the value throws [CorruptionException]. Reusable; not thread-safe.
 *
 * An input can be a [slice][openSlice] of another, and can [release] the block or chunk it holds: the next read
 * fetches it again through the block cache. A k-way merge over many runs keeps one small input per run this way,
 * not one block per run.
 */
internal class ValueInput : ByteInput {
    private var inline: ByteArray = EMPTY
    private var inlineOffset = 0
    private var segment: SegmentReader? = null

    /** Segment block holding an inline value, fetched again after [release]; -1 when the bytes aren't from a block. */
    private var block = -1
    private var external = false
    private var externalOffset = 0L

    /** Length of the whole external value, and where this input's byte 0 lies in it. */
    private var valueLength = 0L
    private var base = 0L
    private var source = "value"
    private var chunk: ByteArray = EMPTY
    private var chunkIndex = -1L

    var length: Long = 0
        private set

    /** Position within the value. */
    override var position: Long = 0

    fun openInline(bytes: ByteArray, offset: Int, length: Int, source: String) {
        inline = bytes
        inlineOffset = offset
        segment = null
        block = -1
        external = false
        this.length = length.toLong()
        this.source = source
        position = 0
    }

    /** An inline value at `bytes[offset, offset + length)`, where `bytes` is block [block] of [segment]. */
    fun openBlock(segment: SegmentReader, block: Int, bytes: ByteArray, offset: Int, length: Int) {
        openInline(bytes, offset, length, segment.source)
        this.segment = segment
        this.block = block
    }

    fun openExternal(segment: SegmentReader, offset: Long, length: Int) {
        this.segment = segment
        block = -1
        external = true
        externalOffset = offset
        valueLength = length.toLong()
        base = 0
        this.length = length.toLong()
        source = segment.source
        chunk = EMPTY
        chunkIndex = -1
        position = 0
    }

    /** Bytes `[start, start + length)` of [parent]'s value, as a value of their own. */
    fun openSlice(parent: ValueInput, start: Long, length: Long) {
        if (start < 0 || length < 0 || start > parent.length - length) parent.corrupt("slice [$start, +$length) of a ${parent.length}-byte value")
        segment = parent.segment
        block = parent.block
        external = parent.external
        source = parent.source
        this.length = length
        position = 0
        if (external) {
            externalOffset = parent.externalOffset
            valueLength = parent.valueLength
            base = parent.base + start
            chunk = parent.chunk
            chunkIndex = parent.chunkIndex
        } else {
            inline = parent.inline
            inlineOffset = parent.inlineOffset + start.toInt()
        }
    }

    /** Drops the block or chunk held when it can be fetched again. */
    override fun release() {
        if (block >= 0) inline = EMPTY
        chunk = EMPTY
        chunkIndex = -1
    }

    override val remaining: Long get() = length - position

    override fun readByte(): Int {
        if (position >= length) corrupt("read past the end of a $length-byte value")
        if (!external) return inlineBytes()[inlineOffset + (position++).toInt()].toInt() and 0xFF
        val at = base + position
        val index = at / SegmentFormat.CHUNK_BYTES
        if (index != chunkIndex) {
            val n = minOf(SegmentFormat.CHUNK_BYTES.toLong(), valueLength - index * SegmentFormat.CHUNK_BYTES).toInt()
            chunk = segment!!.chunk(externalOffset + index * (SegmentFormat.CHUNK_BYTES + 4L), n)
            chunkIndex = index
        }
        position++
        return chunk[(at - index * SegmentFormat.CHUNK_BYTES).toInt()].toInt() and 0xFF
    }

    override fun readVarLong(): Long {
        val start = position
        var result = 0L
        var shift = 0
        while (true) {
            val b = readByte()
            if (shift == 63 && b > 0x01) corrupt("varint at $start exceeds 64 bits")
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) {
                if (b == 0 && shift > 0) corrupt("non-minimal varint at $start")
                return result
            }
            shift += 7
        }
    }

    override fun readVarInt(max: Int): Int {
        val v = readVarLong()
        if (v !in 0L..max.toLong()) corrupt("varint ${java.lang.Long.toUnsignedString(v)} exceeds $max")
        return v.toInt()
    }

    fun readBytes(destination: ByteArray, offset: Int, count: Int) {
        if (count > remaining) corrupt("need $count bytes, $remaining left")
        if (!external) {
            System.arraycopy(inlineBytes(), inlineOffset + position.toInt(), destination, offset, count)
            position += count
            return
        }
        for (i in 0 until count) destination[offset + i] = readByte().toByte()
    }

    fun skip(count: Long) {
        if (count < 0 || count > remaining) corrupt("skip of $count bytes, $remaining left")
        position += count
    }

    override fun corrupt(detail: String): Nothing = throw CorruptionException(source, detail)

    private fun inlineBytes(): ByteArray {
        if (inline === EMPTY && block >= 0) inline = segment!!.block(block)
        return inline
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
