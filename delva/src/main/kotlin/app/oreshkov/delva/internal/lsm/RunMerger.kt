package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.term.PostingEntryReader

/**
 * Merges the runs of one term in one document into `(containerPre, memberPre)` order, dropping repeated
 * containers and keeping the smallest `memberPre`. A repeated container is either an exact copy (a fragment indexed
 * again by a replay after a mid-document flush) or differs only in `memberPre` with both entries in an array (a
 * posting collapsed across runs and fragments); anything else means the runs are corrupt.
 *
 * One run is read directly; a heap is used only from the second run on. Reusable; not thread-safe.
 */
internal class RunMerger {
    private var readers = arrayOfNulls<PostingEntryReader>(4)
    private var runCount = 0
    private var heap = IntArray(4)
    private var heapSize = 0
    private var primed = false
    private var docId = 0L

    var containerPre: Long = -1
        private set
    var memberPre: Long = 0
        private set
    var inArray: Boolean = false
        private set
    var locatorKind: Int = 0
        private set
    var fragmentPre: Long = 0
        private set
    var locatorOffset: Int = 0
        private set

    /** Path hash of the container's parent (SPEC.md §15), 16 bits. */
    var pathHash: Int = 0
        private set

    /** Entries decoded since the merger was created. */
    var entriesDecoded: Long = 0
        private set

    /** Starts merging the runs of document [docId]; nothing is decoded yet. */
    fun reset(docId: Long, runs: DocRuns) {
        require(runs.count > 0)
        runCount = runs.count
        this.docId = docId
        if (readers.size < runCount) readers = readers.copyOf(maxOf(runCount, readers.size * 2))
        if (heap.size < runCount) heap = IntArray(readers.size)
        // With many runs, segment runs drop their block after each entry: memory stays per run, not per block.
        val release = runCount > TermSources.RELEASE_ABOVE_SOURCES
        for (i in 0 until runCount) {
            val reader = readers[i] ?: PostingEntryReader().also { readers[i] = it }
            val bytes = runs.bytes[i]
            if (bytes != null) {
                reader.reset(bytes, runs.offsets[i], runs.lengths[i], runs.entryCounts[i])
            } else {
                val input = runs.inputs[i]!!
                if (release) input.release()
                reader.reset(input, runs.entryCounts[i], release)
            }
        }
        heapSize = 0
        primed = false
        containerPre = -1
    }

    /** Advances to the next distinct container; false when the runs are exhausted. */
    fun next(): Boolean {
        if (runCount == 1) {
            val reader = readers[0]!!
            while (true) {
                if (!reader.next()) return false
                entriesDecoded++
                if (accept(reader)) return true
            }
        }
        if (!primed) {
            primed = true
            for (i in 0 until runCount) {
                if (readers[i]!!.next()) {
                    entriesDecoded++
                    heapPush(i)
                }
            }
        }
        while (heapSize > 0) {
            val i = heap[0]
            val reader = readers[i]!!
            val accepted = accept(reader)
            if (reader.next()) {
                entriesDecoded++
                heap[0] = i
                siftDown(0)
            } else {
                heap[0] = heap[--heapSize]
                if (heapSize > 0) siftDown(0)
            }
            if (accepted) return true
        }
        return false
    }

    /** Takes the reader's current entry unless it repeats the last container. */
    private fun accept(reader: PostingEntryReader): Boolean {
        if (reader.containerPre == containerPre) {
            val sameLocator = reader.inArray == inArray && reader.locatorKind == locatorKind && reader.fragmentPre == fragmentPre && reader.locatorOffset == locatorOffset && reader.pathHash == pathHash
            if (!sameLocator || reader.memberPre < memberPre || (reader.memberPre != memberPre && !inArray)) {
                throw CorruptionException("postings of document $docId", "entries for container $containerPre differ in more than memberPre")
            }
            return false
        }
        if (reader.containerPre < containerPre) throw CorruptionException("postings of document $docId", "container ${reader.containerPre} after $containerPre")
        containerPre = reader.containerPre
        memberPre = reader.memberPre
        inArray = reader.inArray
        locatorKind = reader.locatorKind
        fragmentPre = reader.fragmentPre
        locatorOffset = reader.locatorOffset
        pathHash = reader.pathHash
        return true
    }

    private fun less(a: Int, b: Int): Boolean {
        val ra = readers[a]!!
        val rb = readers[b]!!
        if (ra.containerPre != rb.containerPre) return ra.containerPre < rb.containerPre
        return ra.memberPre < rb.memberPre
    }

    private fun heapPush(i: Int) {
        var k = heapSize++
        heap[k] = i
        while (k > 0) {
            val parent = (k - 1) / 2
            if (!less(heap[k], heap[parent])) break
            val t = heap[k]
            heap[k] = heap[parent]
            heap[parent] = t
            k = parent
        }
    }

    private fun siftDown(start: Int) {
        var k = start
        while (true) {
            val left = 2 * k + 1
            if (left >= heapSize) return
            val right = left + 1
            val child = if (right < heapSize && less(heap[right], heap[left])) right else left
            if (!less(heap[child], heap[k])) return
            val t = heap[k]
            heap[k] = heap[child]
            heap[child] = t
            k = child
        }
    }
}
