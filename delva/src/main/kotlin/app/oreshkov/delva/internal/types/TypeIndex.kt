package app.oreshkov.delva.internal.types

import app.oreshkov.delva.internal.visibility.Visibility
import app.oreshkov.delva.internal.lsm.Memtable
import app.oreshkov.delva.internal.lsm.SegmentHandle
import app.oreshkov.delva.internal.lsm.SegmentTypeSource
import app.oreshkov.delva.internal.lsm.TypeEntry
import app.oreshkov.delva.internal.lsm.TypeSegments
import app.oreshkov.delva.internal.lsm.TypeSource
import app.oreshkov.delva.internal.lsm.Version

/**
 * The type index as one reader sees it: memtables newest first, where each key's newest version
 * at or before [maxLsn] counts, then segments newest first; for one key the newest source that has it wins. Entries of
 * documents that [visibility] hides are skipped (null: the writer's and recovery's view of everything).
 *
 * Segments hold only the newest version of each key, so a reader with a snapshot must use a version whose
 * [Version.flushedLsn] is at most its snapshot's LSN; [maxLsn] only filters memtable versions.
 *
 * Thread-safe; the cursors and scans it creates are not.
 */
internal class TypeIndexView(
    private val memtables: List<Memtable>,
    /** Oldest first. */
    private val segments: List<SegmentHandle>,
    private val visibility: Visibility?,
    private val maxLsn: Long,
) {
    constructor(version: Version, visibility: Visibility?, maxLsn: Long) : this(version.memtables, version.typeSegments, visibility, maxLsn)

    /** Whether no source holds any type entry now, so every node is untyped. Not cached: recovery's view grows as it replays. */
    val isEmpty: Boolean get() = segments.isEmpty() && memtables.all { it.types.isEmpty }

    private fun visible(docId: Long): Boolean = visibility?.isVisible(docId) ?: true

    /** The typeId of the object `(docId, pre)`, or 0 when it has none. */
    fun typeIdOf(docId: Long, pre: Long): Int {
        if (isEmpty || !visible(docId)) return 0
        for (m in memtables) {
            val typeId = m.types.forward(docId, pre, maxLsn)
            if (typeId >= 0) return typeId
        }
        var hash = 0L
        for (i in segments.indices.reversed()) {
            val reader = segments[i].reader
            if (docId < reader.stats.minDocId || docId > reader.stats.maxDocId) continue
            if (hash == 0L) hash = TypeSegments.bloomHash(docId)
            if (!reader.mayContain(hash)) continue
            val source = SegmentTypeSource(reader, TypeEntry.FORWARD, 0, docId, pre)
            if (source.valid && source.pre == pre) return source.value
        }
        return 0
    }

    /**
     * Whether an object of [docId] may have a type: false only when no memtable has a forward entry of the document and
     * no type segment's docId range and bloom filter admit it.
     */
    fun mayHaveTypes(docId: Long): Boolean {
        if (isEmpty || !visible(docId)) return false
        for (m in memtables) {
            if (m.types.isEmpty) continue
            val source = m.types.forwardSource(docId, 0, maxLsn)
            if (source.valid && source.docId == docId) return true
        }
        val hash = TypeSegments.bloomHash(docId)
        return segments.any { docId >= it.reader.stats.minDocId && docId <= it.reader.stats.maxDocId && it.reader.mayContain(hash) }
    }

    /** Approximate number of objects of [typeId]: puts minus tombstones across sources, not corrected for visibility. */
    fun approximateCount(typeId: Int): Long {
        var n = 0L
        for (m in memtables) n += m.types.netCount(typeId)
        for (s in segments) {
            val info = s.types ?: continue
            n += info.puts(typeId) - info.tombstones(typeId)
        }
        return maxOf(0L, n)
    }

    /** A cursor over the forward entries of one document at a time, for hits that arrive in `pre` order. */
    fun forwardCursor(): ForwardTypeCursor = ForwardTypeCursor()

    /** The visible objects of [typeId] strictly after `(afterDocId, afterPre)` (-1 for the start), in `(docId, pre)` order. */
    fun scan(typeId: Int, afterDocId: Long = -1, afterPre: Long = -1): InvertedTypeScan =
        InvertedTypeScan(typeId, maxOf(afterDocId, 0L), if (afterDocId < 0) 0L else afterPre, descending = false, after = afterDocId >= 0)

    /** The visible objects of [typeId] at or before `(toDocId, toPre)`, in `(docId, pre)` descending order. */
    fun scanDescending(typeId: Int, toDocId: Long, toPre: Long): InvertedTypeScan = InvertedTypeScan(typeId, toDocId, toPre, descending = true, after = false)

    /**
     * Forward entries of the document given to [open], read in increasing `pre` ([typeAt]); segments without the document
     * (docId range, bloom filter) are left out and the others positioned lazily.
     */
    inner class ForwardTypeCursor internal constructor() {
        private val sources = ArrayList<TypeSource?>()
        private val segmentReaders = ArrayList<SegmentHandle>()
        private var docId = -1L
        private var lastPre = -1L

        fun open(docId: Long) {
            this.docId = docId
            lastPre = -1
            sources.clear()
            segmentReaders.clear()
            if (isEmpty || !visible(docId)) return
            for (m in memtables) {
                if (!m.types.isEmpty) sources += m.types.forwardSource(docId, 0, maxLsn)
            }
            val hash = TypeSegments.bloomHash(docId)
            for (i in segments.indices.reversed()) {
                val reader = segments[i].reader
                if (docId < reader.stats.minDocId || docId > reader.stats.maxDocId || !reader.mayContain(hash)) continue
                segmentReaders += segments[i]
            }
            // Segment sources are created on first use.
            repeat(segmentReaders.size) { sources += null }
        }

        /** The typeId of the object at [pre] (0 = none); [pre] must not decrease between calls for one document. */
        fun typeAt(pre: Long): Int {
            require(pre >= lastPre) { "pre $pre after $lastPre" }
            lastPre = pre
            val firstSegment = sources.size - segmentReaders.size
            for (i in sources.indices) {
                var source = sources[i]
                if (source == null) {
                    source = SegmentTypeSource(segmentReaders[i - firstSegment].reader, TypeEntry.FORWARD, 0, docId, pre)
                    sources[i] = source
                } else {
                    source.seek(docId, pre)
                }
                if (source.valid && source.pre == pre) return source.value
            }
            return 0
        }
    }

    /**
     * A k-way merge of the inverted entries of one type by `(docId, pre)`, ascending or [descending]: for a key held by
     * several sources the newest one decides, so a TOMBSTONE hides older PUTs; only visible PUTs are returned. It starts
     * at `(startDocId, startPre)`, or strictly past it with [after].
     */
    inner class InvertedTypeScan internal constructor(
        private val typeId: Int,
        startDocId: Long,
        startPre: Long,
        private val descending: Boolean,
        after: Boolean,
    ) {
        private val sources: Array<TypeSource>
        private val heap: IntArray
        private var heapSize = 0

        var valid: Boolean = false
            private set
        var docId: Long = 0
            private set
        var pre: Long = 0
            private set

        init {
            val list = ArrayList<TypeSource>()
            val fromPre = maxOf(startPre, 0L)
            if (!descending || startDocId >= 0) {
                for (m in memtables) {
                    if (m.types.isEmpty) continue
                    list += if (descending) m.types.invertedSourceDescending(typeId, startDocId, fromPre, maxLsn) else m.types.invertedSource(typeId, startDocId, fromPre, maxLsn)
                }
                for (i in segments.indices.reversed()) {
                    val info = segments[i].types ?: continue
                    if (!info.has(typeId) || (if (descending) info.minDocId(typeId) > startDocId else info.maxDocId(typeId) < startDocId)) continue
                    list += SegmentTypeSource(segments[i].reader, TypeEntry.INVERTED, typeId, startDocId, fromPre, descending)
                }
            }
            sources = list.toTypedArray()
            heap = IntArray(sources.size)
            for (i in sources.indices) if (sources[i].valid) push(i)
            advance()
            if (valid && after && docId == startDocId && pre == startPre) advance()
        }

        /** Moves to the next visible object. */
        fun next() {
            if (valid) advance()
        }

        /** Moves to the first visible object at or past `(docId, pre)` in the scan's order; never moves back. */
        fun seek(docId: Long, pre: Long) {
            if (!valid) return
            if (descending) {
                if (this.docId < docId || (this.docId == docId && this.pre <= pre)) return
            } else {
                if (this.docId > docId || (this.docId == docId && this.pre >= pre)) return
            }
            heapSize = 0
            for (i in sources.indices) {
                sources[i].seek(docId, pre)
                if (sources[i].valid) push(i)
            }
            advance()
        }

        private fun advance() {
            while (heapSize > 0) {
                val winner = heap[0]
                val d = sources[winner].docId
                val p = sources[winner].pre
                val op = sources[winner].value
                // Every source holding the key moves past it; the newest (lowest index) decided.
                while (heapSize > 0 && sources[heap[0]].docId == d && sources[heap[0]].pre == p) {
                    val i = heap[0]
                    sources[i].next()
                    if (sources[i].valid) siftDown(0) else pop()
                }
                if (op == TypeEntry.PUT && visible(d)) {
                    docId = d
                    pre = p
                    valid = true
                    return
                }
            }
            valid = false
        }

        private fun less(a: Int, b: Int): Boolean {
            val sa = sources[a]
            val sb = sources[b]
            if (sa.docId != sb.docId) return (sa.docId < sb.docId) != descending
            if (sa.pre != sb.pre) return (sa.pre < sb.pre) != descending
            return a < b
        }

        private fun push(i: Int) {
            var k = heapSize++
            heap[k] = i
            while (k > 0) {
                val parent = (k - 1) / 2
                if (!less(heap[k], heap[parent])) break
                swap(k, parent)
                k = parent
            }
        }

        private fun pop() {
            heap[0] = heap[--heapSize]
            if (heapSize > 0) siftDown(0)
        }

        private fun siftDown(start: Int) {
            var k = start
            while (true) {
                val left = 2 * k + 1
                if (left >= heapSize) return
                val right = left + 1
                val child = if (right < heapSize && less(heap[right], heap[left])) right else left
                if (!less(heap[child], heap[k])) return
                swap(k, child)
                k = child
            }
        }

        private fun swap(a: Int, b: Int) {
            val t = heap[a]
            heap[a] = heap[b]
            heap[b] = t
        }
    }
}
