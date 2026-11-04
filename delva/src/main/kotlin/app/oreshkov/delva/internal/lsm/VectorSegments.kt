package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.internal.bytes.BigEndian
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.vector.Vectors
import java.util.PriorityQueue

/**
 * Vector segments: one entry per document with a vector, in docId order.
 * ```
 * key   := docId:u64                                  big-endian, so keys sort by docId
 * value := norm:f32 | { value:f32 }× dimensions       little-endian IEEE 754 bits, as the COMMIT logs them
 * ```
 * Every value has the same length, `4 + 4 × dimensions` bytes, and is always inline. The norm is [Vectors.norm] of the
 * values, stored so that a search under any metric needs one dot product per vector. There is no bloom filter: a lookup
 * by docId is bounded by the segment's docId range and its block index, and a search reads every block anyway. The
 * stats count vectors in both `entryCount` and `itemCount`.
 */
internal object VectorSegments {
    const val KEY_BYTES: Int = 8

    /** Bytes of one entry's value for vectors of [dimensions] numbers. */
    fun valueBytes(dimensions: Int): Int = 4 + 4 * dimensions

    fun key(docId: Long): ByteArray = ByteArray(KEY_BYTES).also { BigEndian.putLong(it, 0, docId) }

    /** The value of a vector, written into [out] (of [valueBytes] bytes). */
    fun encode(values: FloatArray, norm: Float, out: ByteArray) {
        LittleEndian.putInt(out, 0, norm.toRawBits())
        for (i in values.indices) LittleEndian.putInt(out, 4 + 4 * i, values[i].toRawBits())
    }

    /**
     * The docId of the cursor's entry, its values read into [values] (of the field's dimensions); returns the stored
     * norm. Throws [CorruptionException] when the entry isn't shaped as one of a vector segment; [problem] checks what
     * the numbers mean.
     */
    fun read(cursor: SegmentCursor, values: FloatArray, source: String): Float {
        val docId = docIdOf(cursor, source)
        if (cursor.valueExternal || cursor.valueLength != valueBytes(values.size)) {
            throw CorruptionException(source, "vector of document $docId in ${if (cursor.valueExternal) "an external value" else "${cursor.valueLength} bytes"}, not ${valueBytes(values.size)}")
        }
        val bytes = cursor.valueBytes
        val at = cursor.valueOffset
        for (i in values.indices) values[i] = Float.fromBits(LittleEndian.getInt(bytes, at + 4 + 4 * i))
        return Float.fromBits(LittleEndian.getInt(bytes, at))
    }

    /** The docId of the cursor's entry; throws [CorruptionException] when its key isn't one. */
    fun docIdOf(cursor: SegmentCursor, source: String): Long {
        if (cursor.key.size != KEY_BYTES) throw CorruptionException(source, "vector-index key of ${cursor.key.size} bytes")
        val docId = BigEndian.getLong(cursor.key.array, 0)
        if (docId < 0) throw CorruptionException(source, "vector of document $docId")
        return docId
    }

    /**
     * What is wrong with a stored vector of [field]: the [problem][Vectors.commitProblem] a COMMIT with it would have,
     * or a [norm] that isn't the one its values give; null when it's sound.
     */
    fun problem(values: FloatArray, norm: Float, field: VectorField): String? {
        Vectors.commitProblem(values, field)?.let { return it }
        val expected = Vectors.norm(values)
        // Compared by bits: the norm is computed one way, so any other bits are damage.
        if (norm.toRawBits() != expected.toRawBits()) return "with the norm $norm, but its values give $expected"
        return null
    }

    /** Values of every length the field allows stay inline, so a block never points outside itself. */
    private fun writer(file: FileIo, blockBytes: Int): SegmentWriter =
        SegmentWriter(file, SegmentFormat.INDEX_VECTOR, blockBytes, inlineLimit = maxOf(256, blockBytes / 4, valueBytes(VectorField.MAX_DIMENSIONS)))

    /** Writes [memtable] (frozen) as a segment of vectors of [dimensions] numbers, without those of [hidden] docIds; returns its stats. */
    fun write(memtable: VectorMemtable, dimensions: Int, file: FileIo, blockBytes: Int, hidden: HiddenDocIds = HiddenDocIds.NONE): SegmentStats {
        val writer = writer(file, blockBytes)
        val value = ByteArray(valueBytes(dimensions))
        var count = 0L
        var minDocId = Long.MAX_VALUE
        var maxDocId = -1L
        for (e in memtable.entries()) {
            if (hidden.contains(e.docId)) continue
            check(e.values.size == dimensions) { "a vector of ${e.values.size} numbers in a store of $dimensions" }
            val key = key(e.docId)
            encode(e.values, e.norm, value)
            writer.add(key, 0, KEY_BYTES, value, 0, value.size)
            count++
            minDocId = minOf(minDocId, e.docId)
            maxDocId = e.docId
        }
        val stats = SegmentStats(count, count, if (count == 0L) 0 else minDocId, maxDocId, hidden.countInRange(minDocId, maxDocId))
        writer.finish(stats)
        return stats
    }

    /**
     * Compaction: writes the vectors of [inputs] (adjacent segments, oldest first) as one segment, each docId once and
     * none of [hidden] docIds. Every entry is checked against [field] first, so damage stops the merge rather than
     * spreading into a segment with valid checksums. Returns the stats, or null when nothing is left. Memory: one block
     * per input.
     */
    fun merge(inputs: List<SegmentReader>, field: VectorField, hidden: HiddenDocIds, control: MergeControl, file: FileIo, blockBytes: Int): SegmentStats? {
        val cursors = inputs.map { it.cursor() }
        val docIds = LongArray(cursors.size)
        // By docId, the newest input first: vectors never change, so a docId in several inputs is the same vector,
        // and the newest copy is the one lookups find.
        val heap = PriorityQueue<Int>(maxOf(1, cursors.size)) { a, b ->
            val c = docIds[a].compareTo(docIds[b])
            if (c != 0) c else b.compareTo(a)
        }
        // Block keys are checked in order when a segment opens, entries within a block only here.
        fun advance(i: Int, ok: Boolean) {
            if (!ok) return
            val docId = docIdOf(cursors[i], inputs[i].source)
            if (docId <= docIds[i]) throw CorruptionException(inputs[i].source, "vector of document $docId after document ${docIds[i]}")
            docIds[i] = docId
            heap.add(i)
        }
        docIds.fill(-1)
        for (i in cursors.indices) advance(i, cursors[i].first())
        val writer = writer(file, blockBytes)
        val values = FloatArray(field.dimensions)
        val value = ByteArray(valueBytes(field.dimensions))
        var read = 0L
        var count = 0L
        var minDocId = Long.MAX_VALUE
        var maxDocId = -1L
        while (heap.isNotEmpty()) {
            val i = heap.poll()
            val c = cursors[i]
            val docId = docIds[i]
            if (++read and 0xFF == 0L) control.checkContinue()
            if (docId != maxDocId && !hidden.contains(docId)) {
                val norm = read(c, values, inputs[i].source)
                problem(values, norm, field)?.let { throw CorruptionException(inputs[i].source, "vector of document $docId $it") }
                encode(values, norm, value)
                writer.add(c.key.array, 0, KEY_BYTES, value, 0, value.size)
                count++
                minDocId = minOf(minDocId, docId)
                maxDocId = docId
            }
            advance(i, c.next())
        }
        val stats = SegmentStats(count, count, if (count == 0L) 0 else minDocId, maxDocId, hidden.countInRange(minDocId, maxDocId))
        writer.finish(stats)
        return stats.takeIf { it.entryCount > 0 }
    }
}

/**
 * The vector index as one reader sees it: memtables, then segments. Vectors never change, so like the document-key
 * index it needs no LSN, only a version holding everything the reader's snapshot sees. Thread-safe.
 */
internal class VectorIndexView(
    private val memtables: List<Memtable>,
    /** Oldest first. */
    private val segments: List<SegmentHandle>,
    private val dimensions: Int,
) {
    constructor(version: Version, dimensions: Int) : this(version.memtables, version.vectorSegments, dimensions)

    /** The vector of [docId] (visible or not), from the newest source holding it, or null when it has none. */
    fun vectorOf(docId: Long): VectorEntry? {
        for (m in memtables) m.vectors.vectorOf(docId)?.let { return it }
        if (segments.isEmpty()) return null
        val key = VectorSegments.key(docId)
        for (i in segments.indices.reversed()) {
            val reader = segments[i].reader
            if (docId < reader.stats.minDocId || docId > reader.stats.maxDocId) continue
            val cursor = reader.cursor()
            if (cursor.seekCeiling(key) && VectorSegments.docIdOf(cursor, reader.source) == docId) {
                val values = FloatArray(dimensions)
                val norm = VectorSegments.read(cursor, values, reader.source)
                return VectorEntry(docId, values, norm)
            }
        }
        return null
    }
}
