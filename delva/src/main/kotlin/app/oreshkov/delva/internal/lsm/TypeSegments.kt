package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.BigEndian
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.hash.Xxh3
import app.oreshkov.delva.internal.io.FileIo
import java.util.Arrays

/**
 * Type-index segments: one segment per flush holds both key spaces, the newest
 * version of every key, after a small header of per-segment and per-type statistics. Keys are big-endian:
 * ```
 * info       key := 00                                     value := baseLsn:varint | maxLsn:varint
 * type stats key := 00 | typeId:u32                        value := puts:varint | tombstones:varint
 *                                                                   | minDocId:varint | maxDocId:varint
 * FORWARD    key := 01 | docId:u64 | nodePre:u64           value := typeId:varint (0 = none) | lsn − baseLsn:varint
 * INVERTED   key := 02 | typeId:u32 | docId:u64 | nodePre:u64
 *                                                          value := op:u8 (1 PUT, 2 TOMBSTONE) | lsn − baseLsn:varint
 * ```
 * A type has a stats entry exactly when the segment has inverted entries of it (the presence set). The bloom filter
 * holds the docIds of forward entries (as fragment segments do), so point lookups and per-document scans skip segments
 * without the document. The segment stats count every entry (info and type stats included), `itemCount` the forward
 * entries, and the docId range covers the forward and inverted entries.
 */
internal object TypeSegments {
    const val INFO_KEY_BYTES: Int = 1
    const val STATS_KEY_BYTES: Int = 5
    const val FORWARD_KEY_BYTES: Int = 17
    const val INVERTED_KEY_BYTES: Int = 21

    fun forwardKey(out: ByteArray, docId: Long, pre: Long): ByteArray {
        out[0] = TypeEntry.FORWARD.toByte()
        BigEndian.putLong(out, 1, docId)
        BigEndian.putLong(out, 9, pre)
        return out
    }

    fun invertedKey(out: ByteArray, typeId: Int, docId: Long, pre: Long): ByteArray {
        out[0] = TypeEntry.INVERTED.toByte()
        BigEndian.putInt(out, 1, typeId)
        BigEndian.putLong(out, 5, docId)
        BigEndian.putLong(out, 13, pre)
        return out
    }

    fun bloomHash(docId: Long): Long {
        val bytes = ByteArray(8)
        BigEndian.putLong(bytes, 0, docId)
        return Xxh3.hash64(bytes, 0, 8, 0L)
    }

    /** Writes the newest version of every key of [memtable] (frozen), except those of [hidden] docIds; returns its stats. */
    fun write(memtable: TypeMemtable, file: FileIo, blockBytes: Int, hidden: HiddenDocIds = HiddenDocIds.NONE): SegmentStats =
        write({ memtable.newest() }, { hidden.contains(it.docId) }, hidden, file, blockBytes, merged = false)

    /**
     * Compaction: writes the newest version of every key of [inputs] (a run of adjacent
     * segments, in any order) as one segment. Entries of [hidden] docIds are left out, and so are forward "none" entries
     * and inverted tombstones for which [olderMayHold] says no older segment can hold the key: nothing is left for them
     * to hide. Returns the stats, or null when nothing is left to write.
     */
    fun merge(inputs: List<SegmentReader>, olderMayHold: (TypeEntry) -> Boolean, hidden: HiddenDocIds, control: MergeControl, file: FileIo, blockBytes: Int): SegmentStats? {
        val infos = inputs.map { TypeSegmentInfo.read(it) }
        var checked = 0L
        val drop = { e: TypeEntry ->
            if (++checked and 0xFFF == 0L) control.checkContinue()
            hidden.contains(e.docId) || ((e.space == TypeEntry.FORWARD && e.value == 0 || e.space == TypeEntry.INVERTED && e.value == TypeEntry.TOMBSTONE) && !olderMayHold(e))
        }
        val stats = write({ newest(inputs.indices.map { entries(inputs[it], infos[it].baseLsn) }) }, drop, hidden, file, blockBytes, merged = true)
        return stats.takeIf { it.itemCount > 0 }
    }

    /** The forward and inverted entries of [segment], in key order, with absolute LSNs. */
    fun entries(segment: SegmentReader, baseLsn: Long): Iterator<TypeEntry> = object : Iterator<TypeEntry> {
        private val cursor = segment.cursor()
        private var valid = cursor.first()

        init {
            while (valid && cursor.key.array[0].toInt() == 0) valid = cursor.next()
        }

        override fun hasNext(): Boolean = valid

        override fun next(): TypeEntry {
            if (!valid) throw NoSuchElementException()
            val k = cursor.key.array
            val source = segment.source
            if (cursor.valueExternal) throw CorruptionException(source, "external type entry")
            val r = ByteReader(cursor.valueBytes, cursor.valueOffset, cursor.valueLength, source)
            val e = when {
                k[0].toInt() == TypeEntry.FORWARD && cursor.key.size == FORWARD_KEY_BYTES -> {
                    val typeId = forwardTypeId(cursor, source)
                    r.readVarU32()
                    TypeEntry(TypeEntry.FORWARD, 0, BigEndian.getLong(k, 1), BigEndian.getLong(k, 9), baseLsn + r.readVarLong(), typeId)
                }
                k[0].toInt() == TypeEntry.INVERTED && cursor.key.size == INVERTED_KEY_BYTES -> {
                    val op = invertedOp(cursor, source)
                    r.readByte()
                    TypeEntry(TypeEntry.INVERTED, BigEndian.getInt(k, 1), BigEndian.getLong(k, 5), BigEndian.getLong(k, 13), baseLsn + r.readVarLong(), op)
                }
                else -> throw CorruptionException(source, "type key of ${cursor.key.size} bytes in key space ${k[0]}")
            }
            if (e.docId < 0 || e.pre < 0 || e.lsn < baseLsn) throw CorruptionException(source, "type entry (${e.docId}, ${e.pre}) at LSN ${e.lsn}")
            valid = cursor.next()
            return e
        }
    }

    /** Merges sorted [sources] into key order, keeping the newest version of each key. */
    private fun newest(sources: List<Iterator<TypeEntry>>): Iterator<TypeEntry> = object : Iterator<TypeEntry> {
        private val heads = arrayOfNulls<TypeEntry>(sources.size)
        private val heap = java.util.PriorityQueue<Int>(maxOf(1, sources.size)) { a, b -> heads[a]!!.compareTo(heads[b]!!) }
        private var pending: TypeEntry? = null
        private var last: TypeEntry? = null

        init {
            for (i in sources.indices) advance(i)
        }

        private fun advance(i: Int) {
            val source = sources[i]
            if (!source.hasNext()) return
            val e = source.next()
            val previous = heads[i]
            if (previous != null && (e.sameKey(previous) || e < previous)) throw CorruptionException("type index", "entries out of order at (${e.docId}, ${e.pre})")
            heads[i] = e
            heap.add(i)
        }

        override fun hasNext(): Boolean {
            while (pending == null && heap.isNotEmpty()) {
                val i = heap.poll()
                val e = heads[i]!!
                advance(i)
                // Versions of a key come newest first.
                if (last?.sameKey(e) != true) pending = e
                last = e
            }
            return pending != null
        }

        override fun next(): TypeEntry {
            if (!hasNext()) throw NoSuchElementException()
            return pending!!.also { pending = null }
        }
    }

    /**
     * Writes the entries [entries] returns (the newest version of each key, in key order), except those [drop] names,
     * in two passes: the statistics come first in key order. A merged segment derives its bloom filter from its keys.
     */
    private fun write(entries: () -> Iterator<TypeEntry>, drop: (TypeEntry) -> Boolean, hidden: HiddenDocIds, file: FileIo, blockBytes: Int, merged: Boolean): SegmentStats {
        // First pass: LSN range and per-type statistics, which come first in key order.
        var baseLsn = Long.MAX_VALUE
        var maxLsn = 0L
        val stats = java.util.TreeMap<Int, LongArray>()
        run {
            val all = entries()
            while (all.hasNext()) {
                val e = all.next()
                if (drop(e)) continue
                baseLsn = minOf(baseLsn, e.lsn)
                maxLsn = maxOf(maxLsn, e.lsn)
                if (e.space != TypeEntry.INVERTED) continue
                val s = stats.getOrPut(e.typeId) { longArrayOf(0, 0, Long.MAX_VALUE, -1) }
                if (e.value == TypeEntry.PUT) s[0]++ else s[1]++
                s[2] = minOf(s[2], e.docId)
                s[3] = maxOf(s[3], e.docId)
            }
        }
        val writer = SegmentWriter(file, SegmentFormat.INDEX_TYPE, blockBytes, bloomKey = if (merged) BloomKey.docIdAt(1, TypeEntry.FORWARD) else null)
        val value = GrowableBytes(64)
        val key = ByteArray(INVERTED_KEY_BYTES)
        if (baseLsn == Long.MAX_VALUE) baseLsn = 0
        key[0] = 0
        value.writeVarLong(baseLsn)
        value.writeVarLong(maxLsn)
        writer.add(key, 0, INFO_KEY_BYTES, value.array, 0, value.size)
        var count = 1L + stats.size
        for ((typeId, s) in stats) {
            BigEndian.putInt(key, 1, typeId)
            value.clear()
            for (v in s) value.writeVarLong(v)
            writer.add(key, 0, STATS_KEY_BYTES, value.array, 0, value.size)
        }
        var forward = 0L
        var minDocId = Long.MAX_VALUE
        var maxDocId = -1L
        var lastBloomDocId = -1L
        val all = entries()
        while (all.hasNext()) {
            val e = all.next()
            if (drop(e)) continue
            value.clear()
            if (e.space == TypeEntry.FORWARD) {
                forwardKey(key, e.docId, e.pre)
                value.writeVarLong(e.value.toLong())
                value.writeVarLong(e.lsn - baseLsn)
                writer.add(key, 0, FORWARD_KEY_BYTES, value.array, 0, value.size)
                if (e.docId != lastBloomDocId && !merged) writer.addBloomHash(bloomHash(e.docId))
                lastBloomDocId = e.docId
                forward++
            } else {
                invertedKey(key, e.typeId, e.docId, e.pre)
                value.writeByte(e.value)
                value.writeVarLong(e.lsn - baseLsn)
                writer.add(key, 0, INVERTED_KEY_BYTES, value.array, 0, value.size)
            }
            count++
            minDocId = minOf(minDocId, e.docId)
            maxDocId = maxOf(maxDocId, e.docId)
        }
        val segmentStats = SegmentStats(count, forward, if (maxDocId < 0) 0 else minDocId, maxDocId, hidden.countInRange(minDocId, maxDocId))
        writer.finish(segmentStats)
        return segmentStats
    }

    /** Decodes a forward entry's typeId at [cursor]. */
    fun forwardTypeId(cursor: SegmentCursor, source: String): Int {
        if (cursor.valueExternal) throw CorruptionException(source, "external type entry")
        val r = ByteReader(cursor.valueBytes, cursor.valueOffset, cursor.valueLength, source)
        val typeId = r.readVarU32()
        r.readVarLong()
        if (r.remaining != 0 || typeId > Int.MAX_VALUE) throw CorruptionException(source, "forward type entry of ${cursor.valueLength} bytes")
        return typeId.toInt()
    }

    /** Decodes an inverted entry's op at [cursor]. */
    fun invertedOp(cursor: SegmentCursor, source: String): Int {
        if (cursor.valueExternal || cursor.valueLength < 2) throw CorruptionException(source, "inverted type entry of ${cursor.valueLength} bytes")
        val r = ByteReader(cursor.valueBytes, cursor.valueOffset, cursor.valueLength, source)
        val op = r.readByte()
        r.readVarLong()
        if (r.remaining != 0 || (op != TypeEntry.PUT && op != TypeEntry.TOMBSTONE)) throw CorruptionException(source, "inverted type entry with op $op")
        return op
    }
}

/**
 * The statistics of a type segment, read and validated when the segment opens: the LSN range and, per type
 * present, puts, tombstones and the docId range. Immutable.
 */
internal class TypeSegmentInfo private constructor(
    val baseLsn: Long,
    val maxLsn: Long,
    private val typeIds: IntArray,
    private val puts: LongArray,
    private val tombstones: LongArray,
    private val minDocIds: LongArray,
    private val maxDocIds: LongArray,
) {
    val typeCount: Int get() = typeIds.size

    /** The [i]-th type present, in increasing typeId order. */
    fun typeIdAt(i: Int): Int = typeIds[i]

    private fun indexOf(typeId: Int): Int = Arrays.binarySearch(typeIds, typeId)

    /** Whether the segment has inverted entries of [typeId] (the presence set). */
    fun has(typeId: Int): Boolean = indexOf(typeId) >= 0

    fun puts(typeId: Int): Long = indexOf(typeId).let { if (it >= 0) puts[it] else 0L }

    fun tombstones(typeId: Int): Long = indexOf(typeId).let { if (it >= 0) tombstones[it] else 0L }

    fun maxDocId(typeId: Int): Long = indexOf(typeId).let { if (it >= 0) maxDocIds[it] else -1L }

    fun minDocId(typeId: Int): Long = indexOf(typeId).let { if (it >= 0) minDocIds[it] else Long.MAX_VALUE }

    companion object {
        fun read(segment: SegmentReader): TypeSegmentInfo {
            val source = "${segment.source} type stats"
            val cursor = segment.cursor()
            if (!cursor.first() || cursor.key.size != TypeSegments.INFO_KEY_BYTES || cursor.key.array[0].toInt() != 0 || cursor.valueExternal) {
                throw CorruptionException(source, "missing segment info")
            }
            var r = ByteReader(cursor.valueBytes, cursor.valueOffset, cursor.valueLength, source)
            val baseLsn = r.readVarLong()
            val maxLsn = r.readVarLong()
            if (baseLsn < 0 || maxLsn < baseLsn || r.remaining != 0) throw CorruptionException(source, "LSN range [$baseLsn, $maxLsn]")
            var ids = IntArray(8)
            var stats = LongArray(32)
            var n = 0
            while (cursor.next() && cursor.key.array[0].toInt() == 0) {
                if (cursor.key.size != TypeSegments.STATS_KEY_BYTES || cursor.valueExternal) throw CorruptionException(source, "stats entry of ${cursor.key.size} key bytes")
                val typeId = BigEndian.getInt(cursor.key.array, 1)
                if (typeId <= 0 || (n > 0 && typeId <= ids[n - 1])) throw CorruptionException(source, "type $typeId out of order")
                r = ByteReader(cursor.valueBytes, cursor.valueOffset, cursor.valueLength, source)
                val puts = r.readVarLong()
                val tombstones = r.readVarLong()
                val min = r.readVarLong()
                val max = r.readVarLong()
                if (r.remaining != 0 || puts + tombstones <= 0 || min > max || max > segment.stats.maxDocId || min < segment.stats.minDocId) {
                    throw CorruptionException(source, "stats of type $typeId ($puts, $tombstones, $min, $max)")
                }
                if (n == ids.size) {
                    ids = ids.copyOf(n * 2)
                    stats = stats.copyOf(n * 8)
                }
                ids[n] = typeId
                stats[4 * n] = puts
                stats[4 * n + 1] = tombstones
                stats[4 * n + 2] = min
                stats[4 * n + 3] = max
                n++
            }
            return TypeSegmentInfo(
                baseLsn, maxLsn, ids.copyOf(n),
                LongArray(n) { stats[4 * it] }, LongArray(n) { stats[4 * it + 1] }, LongArray(n) { stats[4 * it + 2] }, LongArray(n) { stats[4 * it + 3] },
            )
        }
    }
}

/**
 * The entries of one key range of a type segment ([TypeSource]): forward entries of one document or inverted entries of
 * one type. With [descending] (inverted entries only), from `(docId, pre)` down: each step floors on the key just below
 * the current one. Not thread-safe.
 */
internal class SegmentTypeSource(
    private val segment: SegmentReader,
    private val space: Int,
    private val typeId: Int,
    docId: Long,
    pre: Long,
    private val descending: Boolean = false,
) : TypeSource {
    private val cursor = segment.cursor()
    private val key = ByteArray(TypeSegments.INVERTED_KEY_BYTES)
    private val onlyDocId = if (space == TypeEntry.FORWARD) docId else -1L
    private var exhausted = false

    override var valid: Boolean = false
        private set
    override var docId: Long = 0
        private set
    override var pre: Long = 0
        private set
    override var value: Int = 0
        private set

    init {
        require(!descending || space == TypeEntry.INVERTED)
        if (descending) floor(docId, pre) else ceiling(docId, pre)
    }

    override fun next() {
        if (!valid) return
        when {
            !descending -> position(cursor.next())
            pre > 0 -> floor(docId, pre - 1)
            docId > 0 -> floor(docId - 1, Long.MAX_VALUE)
            else -> {
                valid = false
                exhausted = true
            }
        }
    }

    override fun seek(docId: Long, pre: Long) {
        if (exhausted) return
        if (descending) {
            if (valid && (this.docId < docId || (this.docId == docId && this.pre <= pre))) return
            floor(docId, pre)
        } else {
            if (valid && (this.docId > docId || (this.docId == docId && this.pre >= pre))) return
            ceiling(docId, pre)
        }
    }

    private fun ceiling(docId: Long, pre: Long) = position(
        if (space == TypeEntry.FORWARD) cursor.seekCeiling(TypeSegments.forwardKey(key, docId, pre), 0, TypeSegments.FORWARD_KEY_BYTES) else cursor.seekCeiling(TypeSegments.invertedKey(key, typeId, docId, pre)),
    )

    private fun floor(docId: Long, pre: Long) = position(cursor.seekFloor(TypeSegments.invertedKey(key, typeId, docId, pre)))

    private fun position(found: Boolean) {
        val k = cursor.key.array
        val size = cursor.key.size
        val inRange = found && when (space) {
            TypeEntry.FORWARD -> k[0].toInt() == TypeEntry.FORWARD && size == TypeSegments.FORWARD_KEY_BYTES && BigEndian.getLong(k, 1) == onlyDocId
            else -> k[0].toInt() == TypeEntry.INVERTED && size == TypeSegments.INVERTED_KEY_BYTES && BigEndian.getInt(k, 1) == typeId
        }
        if (!inRange) {
            if (found && k[0].toInt() == space && size != (if (space == TypeEntry.FORWARD) TypeSegments.FORWARD_KEY_BYTES else TypeSegments.INVERTED_KEY_BYTES)) {
                throw CorruptionException(segment.source, "type key of $size bytes")
            }
            valid = false
            exhausted = true
            return
        }
        if (space == TypeEntry.FORWARD) {
            docId = BigEndian.getLong(k, 1)
            pre = BigEndian.getLong(k, 9)
            value = TypeSegments.forwardTypeId(cursor, segment.source)
        } else {
            docId = BigEndian.getLong(k, 5)
            pre = BigEndian.getLong(k, 13)
            value = TypeSegments.invertedOp(cursor, segment.source)
        }
        if (docId < 0 || pre < 0) throw CorruptionException(segment.source, "type entry ($docId, $pre)")
        valid = true
    }
}
