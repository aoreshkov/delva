package app.oreshkov.delva.internal.term

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.hash.Xxh3
import app.oreshkov.delva.internal.payload.FragmentKind
import java.util.Arrays

/**
 * Where a posting's containing object starts: in a fragment of kind [PART] or [SUBTREE] at a
 * payload offset, as the [HEADER] of a split container, or [UNRESOLVED].
 */
internal object PostingLocator {
    const val PART: Int = 0
    const val SUBTREE: Int = 1
    const val HEADER: Int = 2
    const val UNRESOLVED: Int = 3

    /** Locator kind of a fragment kind that can hold an object start. */
    fun of(fragmentKind: Int): Int = when (fragmentKind) {
        FragmentKind.PART -> PART
        FragmentKind.SUBTREE -> SUBTREE
        FragmentKind.HEADER -> HEADER
        else -> throw IllegalArgumentException("fragment kind $fragmentKind holds no object start")
    }
}

/** Receives postings: `term → (containerPre, memberPre, inArray, locator)`. */
internal interface PostingSink {
    /**
     * [locatorKind] is a [PostingLocator] kind; [fragmentPre] and [locatorOffset] are the fragment key `pre` and the
     * payload offset of the object's tag byte for PART and SUBTREE, `containerPre` and 0 for HEADER, 0 and 0 for
     * UNRESOLVED. [pathHash] is the 16-bit path hash of the containing object's parent (SPEC.md §15). The term bytes
     * are valid only during the call.
     */
    fun posting(term: ByteArray, termOffset: Int, termLength: Int, containerPre: Long, memberPre: Long, inArray: Boolean, locatorKind: Int, fragmentPre: Long, locatorOffset: Int, pathHash: Int)
}

/** Receives the sorted runs of one insert. */
internal fun interface TermSink {
    /** Run [runSeq] (0, 1, 2 … per insert) of document [docId]; [run] is valid only during the call. */
    fun run(docId: Long, runSeq: Int, run: SortedTermRun)
}

/**
 * A run of postings sorted by `(term, containerPre)` (unsigned term bytes), with duplicate `(term, containerPre)`
 * pairs collapsed to the smallest `memberPre`. Entries are addressed by index `0 until size`; entries with
 * equal terms share their bytes and [termRank].
 */
internal class SortedTermRun internal constructor(private val sorter: TermSorter) {
    val size: Int get() = sorter.runSize

    /** Array that holds every term; see [termOffset] and [termLength]. */
    val bytes: ByteArray get() = sorter.termBytes

    fun termOffset(index: Int): Int = sorter.termStart[term(index)]

    fun termLength(index: Int): Int = sorter.termLength[term(index)]

    /** Position of the entry's term among the run's distinct terms. */
    fun termRank(index: Int): Int = sorter.termRank[term(index)]

    fun containerPre(index: Int): Long = sorter.containerPre[sorter.order[index]]

    fun memberPre(index: Int): Long = sorter.memberPre[sorter.order[index]]

    fun fragmentPre(index: Int): Long = sorter.fragmentPre[sorter.order[index]]

    fun locatorOffset(index: Int): Int = sorter.locatorOffset[sorter.order[index]]

    fun pathHash(index: Int): Int = sorter.pathHash[sorter.order[index]]

    fun inArray(index: Int): Boolean = sorter.flags[sorter.order[index]].toInt() and 1 != 0

    fun locatorKind(index: Int): Int = (sorter.flags[sorter.order[index]].toInt() ushr 1) and 3

    private fun term(index: Int): Int = sorter.postingTerm[sorter.order[index]]
}

/**
 * The insert-local sorter: collects postings until about [bufferBytes] are accounted; then, and at [finish],
 * sorts them by `(term, containerPre, memberPre)`, drops later duplicates of `(term, containerPre)` (the smallest
 * `memberPre` stays) and hands the run to [sink]. A small document gives one run.
 *
 * A document repeats its terms a lot, so terms are interned per run: only distinct terms are compared byte-wise, and
 * postings are ordered by term rank with a counting sort. Accounting: each distinct term's bytes plus
 * [TERM_OVERHEAD_BYTES], and [POSTING_BYTES] per posting. Reusable through [reset]; not thread-safe.
 */
internal class TermSorter(private val bufferBytes: Int, private val sink: TermSink) : PostingSink {
    init {
        require(bufferBytes >= MIN_BUFFER_BYTES) { "sorter buffer of $bufferBytes bytes is below $MIN_BUFFER_BYTES" }
    }

    // Distinct terms of the current run.
    internal var termBytes = ByteArray(minOf(bufferBytes, 64 * 1024))
    private var termUsed = 0
    internal var termStart = IntArray(256)
    internal var termLength = IntArray(256)
    internal var termRank = IntArray(256)
    private var termHash = IntArray(256)
    private var termPrefix = LongArray(256)
    private var termOrder = IntArray(256)
    private var termScratch = IntArray(256)
    private var termCount = 0
    private var table = IntArray(512) // term id + 1; 0 = empty

    // Postings of the current run, in arrival order.
    internal var postingTerm = IntArray(1024)
    internal var containerPre = LongArray(1024)
    internal var memberPre = LongArray(1024)
    internal var fragmentPre = LongArray(1024)
    internal var locatorOffset = IntArray(1024)
    internal var pathHash = IntArray(1024)
    internal var flags = ByteArray(1024)
    private var count = 0

    private var rankStarts = IntArray(0)
    internal var order = IntArray(0)
    private var scratch = IntArray(0)
    private var usedBytes = 0L
    private val run = SortedTermRun(this)

    /** Entries of the run being handed out. */
    internal var runSize = 0
        private set

    private var docId = 0L
    private var runSeq = 0

    /** Runs emitted since [reset]. */
    val runCount: Int get() = runSeq

    /** Postings added since [reset], before duplicates were dropped. */
    var postingCount: Long = 0
        private set

    /** Bytes of the run being filled, as accounted against the buffer. */
    val bufferedBytes: Long get() = usedBytes

    /** Heap held by the sorter's arrays, which it keeps for reuse. */
    val retainedBytes: Long
        get() = termBytes.size + 36L * termStart.size + 4L * table.size + 41L * postingTerm.size + 4L * (order.size + scratch.size + rankStarts.size)

    fun reset(docId: Long) {
        this.docId = docId
        runSeq = 0
        postingCount = 0
        clear()
    }

    override fun posting(term: ByteArray, termOffset: Int, termLength: Int, containerPre: Long, memberPre: Long, inArray: Boolean, locatorKind: Int, fragmentPre: Long, locatorOffset: Int, pathHash: Int) {
        require(locatorKind in 0..3)
        val worstCase = termLength.toLong() + TERM_OVERHEAD_BYTES + POSTING_BYTES
        check(worstCase <= bufferBytes) { "a posting with a $termLength-byte term doesn't fit the sorter buffer" }
        if (usedBytes + worstCase > bufferBytes && count > 0) emitRun()
        val id = intern(term, termOffset, termLength)
        if (count == postingTerm.size) growPostings()
        val i = count++
        postingTerm[i] = id
        this.containerPre[i] = containerPre
        this.memberPre[i] = memberPre
        this.fragmentPre[i] = fragmentPre
        this.locatorOffset[i] = locatorOffset
        this.pathHash[i] = pathHash
        flags[i] = ((locatorKind shl 1) or (if (inArray) 1 else 0)).toByte()
        usedBytes += POSTING_BYTES
        postingCount++
    }

    /** Emits the last run, if any postings are buffered. */
    fun finish() {
        if (count > 0) emitRun()
    }

    private fun intern(term: ByteArray, offset: Int, length: Int): Int {
        val hash = Xxh3.hash64(term, offset, length, 0L)
        val h = hash.toInt() xor (hash ushr 32).toInt()
        val mask = table.size - 1
        var slot = h and mask
        while (true) {
            val entry = table[slot]
            if (entry == 0) break
            val id = entry - 1
            if (termHash[id] == h && termLength[id] == length &&
                Arrays.equals(termBytes, termStart[id], termStart[id] + length, term, offset, offset + length)
            ) {
                return id
            }
            slot = (slot + 1) and mask
        }
        if (termCount == termStart.size) growTerms()
        if (termUsed + length > termBytes.size) termBytes = termBytes.copyOf(maxOf(termUsed + length, termBytes.size * 2))
        val id = termCount++
        System.arraycopy(term, offset, termBytes, termUsed, length)
        termStart[id] = termUsed
        termLength[id] = length
        termHash[id] = h
        termPrefix[id] = prefix(term, offset, length)
        termUsed += length
        usedBytes += length.toLong() + TERM_OVERHEAD_BYTES
        if (2 * termCount > table.size) rehash(table.size * 2) else table[slot] = id + 1
        return id
    }

    private fun rehash(size: Int) {
        table = IntArray(size)
        val mask = size - 1
        for (id in 0 until termCount) {
            var slot = termHash[id] and mask
            while (table[slot] != 0) slot = (slot + 1) and mask
            table[slot] = id + 1
        }
    }

    private fun emitRun() {
        rankTerms()
        // Counting sort by term rank (stable, so arrival order within a term), then each term's postings by pre numbers.
        if (order.size < count) {
            order = IntArray(postingTerm.size)
            scratch = IntArray(postingTerm.size)
        }
        if (rankStarts.size < termCount + 1) rankStarts = IntArray(termStart.size + 1)
        rankStarts.fill(0, 0, termCount + 1)
        for (i in 0 until count) rankStarts[termRank[postingTerm[i]] + 1]++
        for (r in 1..termCount) rankStarts[r] += rankStarts[r - 1]
        for (i in 0 until count) order[rankStarts[termRank[postingTerm[i]]]++] = i
        var groupStart = 0
        while (groupStart < count) {
            val term = postingTerm[order[groupStart]]
            var groupEnd = groupStart + 1
            while (groupEnd < count && postingTerm[order[groupEnd]] == term) groupEnd++
            if (groupEnd - groupStart > 1) sortGroup(groupStart, groupEnd)
            groupStart = groupEnd
        }
        runSize = dropDuplicates()
        sink.run(docId, runSeq++, run)
        clear()
    }

    /** Sets [termRank] to each distinct term's position in unsigned byte order (bottom-up merge sort of ids). */
    private fun rankTerms() {
        val n = termCount
        for (i in 0 until n) termOrder[i] = i
        var src = termOrder
        var dst = termScratch
        var width = 1
        while (width < n) {
            var lo = 0
            while (lo < n) {
                val mid = minOf(lo + width, n)
                val hi = minOf(lo + 2 * width, n)
                if (mid >= hi || compareTerms(src[mid - 1], src[mid]) <= 0) {
                    System.arraycopy(src, lo, dst, lo, hi - lo)
                } else {
                    var i = lo
                    var j = mid
                    var k = lo
                    while (i < mid && j < hi) dst[k++] = if (compareTerms(src[i], src[j]) <= 0) src[i++] else src[j++]
                    while (i < mid) dst[k++] = src[i++]
                    while (j < hi) dst[k++] = src[j++]
                }
                lo = hi
            }
            val t = src
            src = dst
            dst = t
            width *= 2
        }
        termOrder = src
        termScratch = dst
        for (rank in 0 until n) termRank[src[rank]] = rank
    }

    private fun compareTerms(a: Int, b: Int): Int {
        val c = java.lang.Long.compareUnsigned(termPrefix[a], termPrefix[b])
        if (c != 0) return c
        return Arrays.compareUnsigned(termBytes, termStart[a], termStart[a] + termLength[a], termBytes, termStart[b], termStart[b] + termLength[b])
    }

    /** Orders `order[from, to)`, the postings of one term, by (containerPre, memberPre). */
    private fun sortGroup(from: Int, to: Int) {
        if (to - from <= 16) {
            for (i in from + 1 until to) {
                val v = order[i]
                var j = i - 1
                while (j >= from && comparePre(order[j], v) > 0) {
                    order[j + 1] = order[j]
                    j--
                }
                order[j + 1] = v
            }
            return
        }
        var src = order
        var dst = scratch
        var width = 1
        while (width < to - from) {
            var lo = from
            while (lo < to) {
                val mid = minOf(lo + width, to)
                val hi = minOf(lo + 2 * width, to)
                if (mid >= hi || comparePre(src[mid - 1], src[mid]) <= 0) {
                    System.arraycopy(src, lo, dst, lo, hi - lo)
                } else {
                    var i = lo
                    var j = mid
                    var k = lo
                    while (i < mid && j < hi) dst[k++] = if (comparePre(src[i], src[j]) <= 0) src[i++] else src[j++]
                    while (i < mid) dst[k++] = src[i++]
                    while (j < hi) dst[k++] = src[j++]
                }
                lo = hi
            }
            val t = src
            src = dst
            dst = t
            width *= 2
        }
        if (src !== order) System.arraycopy(src, from, order, from, to - from)
    }

    private fun comparePre(a: Int, b: Int): Int {
        val c = containerPre[a].compareTo(containerPre[b])
        return if (c != 0) c else memberPre[a].compareTo(memberPre[b])
    }

    private fun dropDuplicates(): Int {
        var kept = 0
        for (i in 0 until count) {
            val p = order[i]
            if (kept > 0) {
                val q = order[kept - 1]
                if (postingTerm[p] == postingTerm[q] && containerPre[p] == containerPre[q]) {
                    // Collapsed postings: both in-array with the same locator; anything else is inconsistent.
                    if (flags[p] != flags[q] || flags[p].toInt() and 1 == 0 || fragmentPre[p] != fragmentPre[q] || locatorOffset[p] != locatorOffset[q] || pathHash[p] != pathHash[q]) {
                        throw CorruptionException("posting run of document $docId", "postings for one (term, container) differ in more than memberPre")
                    }
                    continue
                }
            }
            order[kept++] = p
        }
        return kept
    }

    private fun clear() {
        if (termCount > 0) table.fill(0)
        termCount = 0
        termUsed = 0
        count = 0
        usedBytes = 0
        runSize = 0
    }

    private fun growTerms() {
        val n = termStart.size * 2
        termStart = termStart.copyOf(n)
        termLength = termLength.copyOf(n)
        termRank = termRank.copyOf(n)
        termHash = termHash.copyOf(n)
        termPrefix = termPrefix.copyOf(n)
        termOrder = IntArray(n)
        termScratch = IntArray(n)
    }

    private fun growPostings() {
        val n = postingTerm.size * 2
        postingTerm = postingTerm.copyOf(n)
        containerPre = containerPre.copyOf(n)
        memberPre = memberPre.copyOf(n)
        fragmentPre = fragmentPre.copyOf(n)
        locatorOffset = locatorOffset.copyOf(n)
        pathHash = pathHash.copyOf(n)
        flags = flags.copyOf(n)
    }

    companion object {
        /** Posting fields, term id, order and scratch slots. */
        const val POSTING_BYTES: Int = 8 + 8 + 8 + 4 + 4 + 1 + 4 + 4 + 4

        /** Start, length, rank, hash, prefix, sort and count slots, and two hash-table slots of a distinct term. */
        const val TERM_OVERHEAD_BYTES: Int = 4 + 4 + 4 + 4 + 8 + 4 + 4 + 4 + 8

        const val MIN_BUFFER_BYTES: Int = 4096

        /** First 8 bytes of a term, big-endian, zero-padded; unsigned order of prefixes agrees with the bytes. */
        private fun prefix(bytes: ByteArray, offset: Int, length: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (if (i < length) bytes[offset + i].toLong() and 0xFF else 0L)
            return v
        }
    }
}
