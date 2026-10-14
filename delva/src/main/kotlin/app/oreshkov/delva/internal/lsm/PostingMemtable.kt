package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.term.PostingCodec
import app.oreshkov.delva.internal.term.SortedTermRun
import java.util.Arrays
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicLong

/**
 * The postings of one term in one sorted run of one document: [count] entries in [entries], encoded by
 * [PostingCodec]. Immutable once created, so readers need no locks.
 */
internal class PostingRun(val term: ByteArray, val docId: Long, val runSeq: Int, val count: Int, val entries: ByteArray) : Comparable<PostingRun> {
    /** Order of the memtable key `term | docId | runSeq`: unsigned term bytes, then docId, then runSeq. */
    override fun compareTo(other: PostingRun): Int {
        val c = Arrays.compareUnsigned(term, other.term)
        if (c != 0) return c
        val d = docId.compareTo(other.docId)
        return if (d != 0) d else runSeq.compareTo(other.runSeq)
    }

    fun hasTerm(term: ByteArray): Boolean = Arrays.equals(this.term, term)

    /** Heap estimate: the run, its two arrays, and a skip-list node with its share of index nodes. */
    val approximateBytes: Long get() = RUN_OVERHEAD_BYTES + term.size + entries.size

    companion object {
        const val RUN_OVERHEAD_BYTES: Long = 40 + 16 + 16 + 24 + 16

        private val EMPTY = ByteArray(0)

        /** A key-only run for searching: sorts before every run of `(term, docId)` with a `runSeq` ≥ [runSeq]. */
        fun probe(term: ByteArray, docId: Long, runSeq: Int = 0): PostingRun = PostingRun(term, docId, runSeq, 0, EMPTY)

        /** Turns a sorted run into one [PostingRun] per distinct term, in term order. */
        fun fromSortedRun(docId: Long, runSeq: Int, run: SortedTermRun, scratch: GrowableBytes, into: MutableList<PostingRun>) {
            var from = 0
            while (from < run.size) {
                val rank = run.termRank(from)
                var to = from + 1
                while (to < run.size && run.termRank(to) == rank) to++
                scratch.clear()
                PostingCodec.writeEntries(scratch, run, from, to)
                val offset = run.termOffset(from)
                val term = run.bytes.copyOfRange(offset, offset + run.termLength(from))
                into += PostingRun(term, docId, runSeq, to - from, scratch.toByteArray())
                from = to
            }
        }
    }
}

/**
 * Posting memtable: a concurrent sorted set of immutable [PostingRun]s keyed by `term | docId | runSeq`.
 * Runs of in-flight documents may be present; readers filter them by visibility. Thread-safe.
 */
internal class PostingMemtable {
    private val runs = ConcurrentSkipListSet<PostingRun>()
    private val bytes = AtomicLong()

    /** Heap estimate of the runs held. */
    val approximateBytes: Long get() = bytes.get()

    val runCount: Int get() = runs.size

    fun add(run: PostingRun) {
        check(runs.add(run)) { "run (docId ${run.docId}, runSeq ${run.runSeq}) of a term added twice" }
        bytes.addAndGet(run.approximateBytes)
    }

    /** Every run in key order; weakly consistent. For tests and diagnostics. */
    fun allRuns(): Iterable<PostingRun> = runs

    /** Runs of [term] and every term after it, ordered by `(term, docId, runSeq)`. Weakly consistent. */
    fun runsFrom(term: ByteArray): Iterator<PostingRun> = runs.tailSet(PostingRun.probe(term, -1), true).iterator()

    /** Runs of [term] from document [toDocId] down, ordered by `(docId, runSeq)` descending. Weakly consistent. */
    fun runsDescending(term: ByteArray, toDocId: Long): Iterator<PostingRun> =
        termRuns(term, runs.headSet(PostingRun.probe(term, toDocId, Int.MAX_VALUE), true).descendingIterator())

    /** Runs of [term] from document [fromDocId] on, ordered by `(docId, runSeq)`. Weakly consistent. */
    fun runs(term: ByteArray, fromDocId: Long): Iterator<PostingRun> = termRuns(term, runs.tailSet(PostingRun.probe(term, fromDocId), true).iterator())

    /** The runs of [tail] up to the first of another term. */
    private fun termRuns(term: ByteArray, tail: Iterator<PostingRun>): Iterator<PostingRun> = object : Iterator<PostingRun> {
        private var next: PostingRun? = advance()

        private fun advance(): PostingRun? = if (tail.hasNext()) tail.next().takeIf { it.hasTerm(term) } else null

        override fun hasNext(): Boolean = next != null

        override fun next(): PostingRun {
            val run = next ?: throw NoSuchElementException()
            next = advance()
            return run
        }
    }
}
