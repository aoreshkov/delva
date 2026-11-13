package app.oreshkov.delva.internal.query

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.BigEndian
import app.oreshkov.delva.internal.lsm.SegmentCursor
import app.oreshkov.delva.internal.lsm.SegmentHandle
import app.oreshkov.delva.internal.lsm.VectorEntry
import app.oreshkov.delva.internal.lsm.VectorMemtable
import app.oreshkov.delva.internal.lsm.VectorSegments
import app.oreshkov.delva.internal.lsm.Version
import app.oreshkov.delva.internal.vector.VectorDistance
import app.oreshkov.delva.internal.visibility.Visibility

/**
 * The vector index of one [Version] in ascending docId order: its memtables and vector segments merged, each docId
 * once, read from the newest source that has it. [docId] is [Long.MAX_VALUE] after the last. Segment blocks that
 * aren't cached are added to the cache only with [admit]. Not thread-safe.
 */
internal class VectorScan(version: Version, dimensions: Int, admit: Boolean = true) {
    private abstract class Source {
        /** The current entry's docId, [Long.MAX_VALUE] after the last. */
        abstract val docId: Long

        /** Moves to the first entry at or past [target]; never moves back. */
        abstract fun seek(target: Long)

        /** The distance from [distance]'s query to the current entry's vector. */
        abstract fun distance(distance: VectorDistance): Float
    }

    private class MemtableSource(private val memtable: VectorMemtable) : Source() {
        private var entries: Iterator<VectorEntry> = memtable.entries()
        private var entry: VectorEntry? = null
        override var docId: Long = Long.MAX_VALUE
            private set

        init {
            step()
        }

        private fun step() {
            entry = if (entries.hasNext()) entries.next() else null
            docId = entry?.docId ?: Long.MAX_VALUE
        }

        override fun seek(target: Long) {
            if (target <= docId) return
            // The next entry is usually the one: a scan seeks one past the current docId.
            step()
            if (docId < target) {
                entries = memtable.entries(target)
                step()
            }
        }

        override fun distance(distance: VectorDistance): Float = entry!!.let { distance.to(it.values, it.squares) }
    }

    private class SegmentSource(private val segment: SegmentHandle, dimensions: Int, admit: Boolean) : Source() {
        private val reader = segment.reader
        private val cursor: SegmentCursor = reader.cursor(admit, READ_AHEAD_BYTES)
        private val values = FloatArray(dimensions)
        private val key = ByteArray(VectorSegments.KEY_BYTES)
        override var docId: Long = Long.MAX_VALUE
            private set

        init {
            if (cursor.first()) docId = VectorSegments.docIdOf(cursor, reader.source)
        }

        override fun seek(target: Long) {
            if (target <= docId) return
            if (target > reader.stats.maxDocId) {
                docId = Long.MAX_VALUE
                return
            }
            BigEndian.putLong(key, 0, target)
            // A target in a later block is sought there directly: stepping first would read the next block for nothing.
            if (!reader.blockEndsBefore(cursor.block, key, 0, key.size)) {
                val previous = docId
                // Step once, which a scan nearly always needs; seek within the block only to skip further.
                docId = if (cursor.next()) VectorSegments.docIdOf(cursor, reader.source) else Long.MAX_VALUE
                if (docId <= previous) throw CorruptionException(reader.source, "vector of document $docId after document $previous")
                if (docId >= target) return
            }
            docId = if (cursor.seekCeiling(key)) VectorSegments.docIdOf(cursor, reader.source) else Long.MAX_VALUE
        }

        override fun distance(distance: VectorDistance): Float {
            val squares = VectorSegments.read(cursor, values, reader.source)
            return distance.to(values, squares)
        }

        override fun toString(): String = segment.reader.source
    }

    // Newest first, so that a docId in two sources is read from the newer, as lookups do.
    private val sources: Array<Source> = buildList<Source> {
        for (m in version.memtables) if (!m.vectors.isEmpty) add(MemtableSource(m.vectors))
        for (s in version.vectorSegments.asReversed()) add(SegmentSource(s, dimensions, admit))
    }.toTypedArray()

    var docId: Long = minDocId()
        private set

    private fun minDocId(): Long {
        var min = Long.MAX_VALUE
        for (s in sources) if (s.docId < min) min = s.docId
        return min
    }

    /** Moves to the first vector at or past [target]; never moves back. */
    fun seek(target: Long) {
        if (target <= docId) return
        for (s in sources) s.seek(target)
        docId = minDocId()
    }

    /** The distance from [distance]'s query to the vector at [docId]. */
    fun distance(distance: VectorDistance): Float {
        for (s in sources) if (s.docId == docId) return s.distance(distance)
        throw NoSuchElementException("no vector after the last")
    }

    /** Where the vector at [docId] is read from, for messages. */
    fun sourceName(): String = sources.firstOrNull { it.docId == docId }?.let { if (it is SegmentSource) it.toString() else "vector memtable" } ?: "vector index"

    companion object {
        /**
         * Bytes a scan reads at once when it moves on to the next block: vector blocks are small so that a selective
         * filter reads little per vector, and reading ahead keeps a full scan to as few reads as large blocks would.
         */
        const val READ_AHEAD_BYTES: Int = 64 * 1024
    }
}

/** Every docId, for a vector search that no index term narrows: seeking to a docId always lands on it. */
internal class EveryDocument : Candidates(descending = false) {
    override var docId: Long = 0
        private set

    override fun seek(target: Long) {
        if (target > docId) docId = target
    }
}

/**
 * Runs one exact vector search over one snapshot (SPEC.md §11.3): every visible document of [candidates] that has a
 * vector in [vectors] is compared with the query; the [k] nearest within [maxDistance] are kept, ordered by distance
 * and then docId. The two streams meet by seeking each to the other's docId, so a selective filter skips most
 * vectors, and an [EveryDocument] candidate stream makes it a scan of the vector index.
 *
 * [accept], the document filter, is tested only on a candidate near enough to enter the result as it stands. The
 * filter depends on the document alone, so the result is the one testing every candidate would give, while documents
 * that can't be among the [k] nearest are never read. [checkpoint] runs every [CHECK_EVERY] steps of the merge, each
 * of which reads at most one candidate and one vector, so a long search notices a closed store or a cancelled caller
 * even when it compares nothing: when every vector it meets is deleted, or no candidate has one.
 */
internal class VectorSearch(
    private val vectors: VectorScan,
    private val candidates: Candidates,
    private val visibility: Visibility,
    private val distance: VectorDistance,
    private val k: Int,
    private val maxDistance: Float?,
    private val accept: ((Long) -> Boolean)?,
    private val checkpoint: () -> Unit,
) {
    /** Vectors compared with the query, for tests. */
    var compared: Long = 0
        private set

    /** Documents whose filter was tested, for tests. */
    var filterTests: Long = 0
        private set

    private val top = TopK(k)

    /** Runs the search; the hits are [TopK.docIds] and [TopK.distances], nearest first. */
    fun run(): TopK {
        var target = 0L
        var steps = 0L
        while (true) {
            if (++steps % CHECK_EVERY == 0L) checkpoint()
            candidates.seek(target)
            val c = candidates.docId
            if (c == candidates.exhausted) break
            vectors.seek(c)
            val v = vectors.docId
            if (v == Long.MAX_VALUE) break
            if (v != c) {
                target = v
                continue
            }
            target = v + 1
            if (!visibility.isVisible(v)) continue
            compared++
            val d = vectors.distance(distance)
            if (d.isNaN()) throw CorruptionException(vectors.sourceName(), "the vector of document $v has a number that isn't finite")
            if (maxDistance != null && d > maxDistance) continue
            if (!top.admits(d, v)) continue
            if (accept != null) {
                filterTests++
                if (!accept(v)) continue
            }
            top.add(d, v)
        }
        top.sort()
        return top
    }

    companion object {
        const val CHECK_EVERY: Long = 256
    }
}

/**
 * The [k] smallest `(distance, docId)` pairs seen, as a max-heap with the farthest on top; [sort] orders them nearest
 * first. Memory is `12 × k` bytes, grown as hits come.
 */
internal class TopK(private val k: Int) {
    var distances = FloatArray(minOf(k, 64))
        private set
    var docIds = LongArray(minOf(k, 64))
        private set
    var size = 0
        private set

    /** Whether `(d, docId)` would be kept: the heap isn't full, or it is nearer than the farthest kept. */
    fun admits(d: Float, docId: Long): Boolean = size < k || less(d, docId, distances[0], docIds[0])

    /** Adds `(d, docId)`, which [admits]; drops the farthest when full. */
    fun add(d: Float, docId: Long) {
        if (size < k) {
            if (size == distances.size) {
                val grown = minOf(k, size * 2)
                distances = distances.copyOf(grown)
                docIds = docIds.copyOf(grown)
            }
            var i = size++
            while (i > 0) {
                val parent = (i - 1) / 2
                if (!less(distances[parent], docIds[parent], d, docId)) break
                distances[i] = distances[parent]
                docIds[i] = docIds[parent]
                i = parent
            }
            distances[i] = d
            docIds[i] = docId
        } else {
            siftDown(0, d, docId, size)
        }
    }

    /** Puts `(d, docId)` at [start] of the heap of the first [n] entries and moves it down to its place. */
    private fun siftDown(start: Int, d: Float, docId: Long, n: Int) {
        var i = start
        while (true) {
            var child = 2 * i + 1
            if (child >= n) break
            if (child + 1 < n && less(distances[child], docIds[child], distances[child + 1], docIds[child + 1])) child++
            if (!less(d, docId, distances[child], docIds[child])) break
            distances[i] = distances[child]
            docIds[i] = docIds[child]
            i = child
        }
        distances[i] = d
        docIds[i] = docId
    }

    /** Orders the entries nearest first, by heap sort in place. */
    fun sort() {
        var n = size
        while (n > 1) {
            n--
            val d = distances[n]
            val docId = docIds[n]
            distances[n] = distances[0]
            docIds[n] = docIds[0]
            siftDown(0, d, docId, n)
        }
    }

    private companion object {
        /** By distance, then docId. Distances are never NaN, and zero is never negative. */
        fun less(d1: Float, id1: Long, d2: Float, id2: Long): Boolean = d1 < d2 || (d1 == d2 && id1 < id2)
    }
}
