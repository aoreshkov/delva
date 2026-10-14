package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.internal.bits.DocIdSet
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.term.PostingCodec
import java.util.Arrays

/**
 * The docIds whose index entries segment writers leave out: aborted docIds, which never become visible,
 * and deleted ones, which never become visible again. Writers only get sets a MANIFEST already names in its bitmap
 * files, so a crash can't bring back an entry-less document whose delete was lost. The two sets are disjoint.
 * Immutable.
 */
internal class HiddenDocIds(val aborted: DocIdSet, val deleted: DocIdSet) {
    fun contains(docId: Long): Boolean = deleted.contains(docId) || aborted.contains(docId)

    /** Hidden docIds in `[from, to]`; 0 for an empty range. */
    fun countInRange(from: Long, to: Long): Long = if (to < from) 0 else aborted.countInRange(from, to) + deleted.countInRange(from, to)

    val size: Long get() = aborted.size + deleted.size

    companion object {
        val NONE: HiddenDocIds = HiddenDocIds(DocIdSet.EMPTY, DocIdSet.EMPTY)
    }
}

/** Lets a merge stop early: [checkContinue] throws when the merge must end (the store is closing). */
internal fun interface MergeControl {
    fun checkContinue()

    companion object {
        val ALWAYS: MergeControl = MergeControl {}
    }
}

/** A merged value would exceed what a segment can hold ([SegmentWriter.MAX_VALUE_BYTES]); the merge is abandoned. */
internal class MergeLimitException(message: String) : RuntimeException(message)

/**
 * Merges posting segments into one: terms in key order; per term, documents in docId order across
 * the inputs, skipping [hidden] ones; per document, every run of every input merged by `containerPre` with duplicates
 * dropped ([RunMerger]), then written as runs of at most [maxRunBytes] entry bytes (a larger document gets several
 * consecutive runs, which readers merge as any runs). Lists longer than [listMemoryBytes] move their body to the file
 * [spill] creates, and the bloom filter is read back from the written keys, so memory is bounded by the options, not
 * by the inputs. Not thread-safe.
 */
internal class PostingMerge(
    private val inputs: List<SegmentReader>,
    private val hidden: HiddenDocIds,
    private val control: MergeControl,
    spill: () -> FileIo,
    private val maxRunBytes: Int = DEFAULT_MAX_RUN_BYTES,
    listMemoryBytes: Int = DEFAULT_LIST_MEMORY_BYTES,
) {
    private val list = PostingListWriter(listMemoryBytes, spill)
    private val run = GrowableBytes(4096)
    private val header = GrowableBytes(256)
    private val runs = DocRuns()
    private val merger = RunMerger()

    /** Postings the inputs held and the output holds: the difference is duplicates and hidden documents. */
    var postingsRead: Long = 0
        private set
    var postingsWritten: Long = 0
        private set

    fun write(file: FileIo, blockBytes: Int): SegmentStats {
        val writer = SegmentWriter(file, SegmentFormat.INDEX_POSTING, blockBytes, bloomKey = BloomKey.WHOLE_KEY)
        val cursors = Array(inputs.size) { inputs[it].cursor() }
        val heap = KeyHeap(cursors)
        for (i in cursors.indices) if (cursors[i].first()) heap.push(i)
        val same = IntArray(cursors.size)
        var terms = 0L
        var minDocId = Long.MAX_VALUE
        var maxDocId = -1L
        while (heap.size > 0) {
            control.checkContinue()
            // Every input holding the smallest term.
            var n = 0
            same[n++] = heap.pop()
            while (heap.size > 0 && heap.peekEquals(same[0])) same[n++] = heap.pop()
            val term = cursors[same[0]].key.toByteArray()
            val sources = Array<TermSource>(n) { SegmentTermSource.at(cursors[same[it]]).also { s -> postingsRead += s.postingCount } }
            if (mergeTerm(TermSources(sources))) {
                header.clear()
                list.finishHeader(header)
                val length = header.size + list.bodyBytes
                if (length > SegmentWriter.MAX_VALUE_BYTES) throw MergeLimitException("posting list of $length bytes")
                writer.addStreamed(term, 0, term.size, length) { value ->
                    value.write(header.array, 0, header.size)
                    list.copyBody { bytes, offset, count -> value.write(bytes, offset, count) }
                }
                terms++
                postingsWritten += list.postingCount
                minDocId = minOf(minDocId, list.firstDocId)
                maxDocId = maxOf(maxDocId, list.lastDocId)
            }
            for (k in 0 until n) if (cursors[same[k]].next()) heap.push(same[k])
        }
        val stats = SegmentStats(terms, postingsWritten, if (terms == 0L) 0 else minDocId, maxDocId, hidden.countInRange(minDocId, maxDocId))
        writer.finish(stats)
        return stats
    }

    /** Builds the list of one term into [list]; false when every document was hidden. */
    private fun mergeTerm(sources: TermSources): Boolean {
        list.reset()
        while (true) {
            val docId = sources.docId
            if (docId == Long.MAX_VALUE) break
            if (hidden.contains(docId)) {
                sources.skipDocument()
                continue
            }
            runs.clear()
            sources.takeRuns(runs)
            merger.reset(docId, runs)
            run.clear()
            var count = 0
            var previous = 0L
            var previousHash = -1
            while (merger.next()) {
                val containerPre = merger.containerPre
                PostingCodec.writeEntry(run, if (count == 0) containerPre else containerPre - previous, containerPre, merger.memberPre, merger.inArray, merger.locatorKind, merger.fragmentPre, merger.locatorOffset, merger.pathHash, previousHash)
                previousHash = merger.pathHash
                previous = containerPre
                count++
                if (run.size >= maxRunBytes) {
                    list.addRun(docId, count, run.array, 0, run.size)
                    run.clear()
                    count = 0
                    previousHash = -1
                }
            }
            if (count > 0) list.addRun(docId, count, run.array, 0, run.size)
            runs.clear()
            control.checkContinue()
        }
        return list.docFreq > 0
    }

    /** Input indexes ordered by their cursor's key (unsigned bytes), ties by index. */
    private class KeyHeap(private val cursors: Array<SegmentCursor>) {
        private val heap = IntArray(cursors.size)
        var size = 0
            private set

        fun push(i: Int) {
            var k = size++
            heap[k] = i
            while (k > 0) {
                val parent = (k - 1) / 2
                if (compare(heap[parent], heap[k]) <= 0) break
                swap(parent, k)
                k = parent
            }
        }

        fun pop(): Int {
            val top = heap[0]
            heap[0] = heap[--size]
            var k = 0
            while (true) {
                val left = 2 * k + 1
                if (left >= size) break
                val right = left + 1
                val child = if (right < size && compare(heap[right], heap[left]) < 0) right else left
                if (compare(heap[k], heap[child]) <= 0) break
                swap(k, child)
                k = child
            }
            return top
        }

        /** Whether the smallest remaining input's key equals input [i]'s. */
        fun peekEquals(i: Int): Boolean {
            val a = cursors[heap[0]].key
            val b = cursors[i].key
            return Arrays.equals(a.array, 0, a.size, b.array, 0, b.size)
        }

        private fun compare(a: Int, b: Int): Int {
            val ka = cursors[a].key
            val kb = cursors[b].key
            val c = Arrays.compareUnsigned(ka.array, 0, ka.size, kb.array, 0, kb.size)
            return if (c != 0) c else a.compareTo(b)
        }

        private fun swap(a: Int, b: Int) {
            val t = heap[a]
            heap[a] = heap[b]
            heap[b] = t
        }
    }

    companion object {
        /** Entry bytes per merged run of one document. */
        const val DEFAULT_MAX_RUN_BYTES: Int = 1 shl 20

        /** Body bytes a list keeps in memory before spilling. */
        const val DEFAULT_LIST_MEMORY_BYTES: Int = 4 shl 20
    }
}
