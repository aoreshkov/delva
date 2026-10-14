package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.internal.bits.DocIdSet
import app.oreshkov.delva.internal.visibility.Visibility
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicReference

/** A consistent view of the store: everything committed at control-log sequence number [lsn]. Immutable. */
internal class Snapshot(val lsn: Long, val visibility: Visibility)

/**
 * Allocates docIds when inserts start, tracks allocated-but-uncommitted ones, and publishes a new [Snapshot]
 * when the writer completes a batch. Allocation and publication share one lock, so a snapshot never sees a docId as
 * allocated without also seeing whether it's still in flight. Readers take [current] without locking.
 *
 * Starts from the recovered state: [nextDocId], the last [lsn], and the [aborted] and [deleted] docIds.
 */
internal class DocIdRegistry(nextDocId: Long, lsn: Long, aborted: DocIdSet, deleted: DocIdSet) {
    private val lock = Any()
    private var nextDocId = nextDocId
    private val inFlight = TreeSet<Long>()
    private var aborted = aborted
    private var deleted = deleted
    private val published = AtomicReference(Snapshot(lsn, Visibility(nextDocId - 1, EMPTY, aborted, deleted)))

    fun current(): Snapshot = published.get()

    /** The docId the next insert gets; for the MANIFEST. */
    fun peekNextDocId(): Long = synchronized(lock) { nextDocId }

    fun allocate(): Long = synchronized(lock) {
        val docId = nextDocId++
        inFlight += docId
        docId
    }

    /**
     * The insert of [docId] failed before writing anything: the docId stays unused. When a batch published the current
     * snapshot while it was in flight, the snapshot is published again without it, at the same LSN: nothing it reads
     * changes, and a cursor taken from it doesn't report a late commit that can't come.
     */
    fun forget(docId: Long) = synchronized(lock) {
        check(inFlight.remove(docId)) { "docId $docId isn't in flight" }
        val current = published.get()
        if (current.visibility.isInFlight(docId)) publishLocked(current.lsn)
    }

    /**
     * A batch is durable up to [lsn]: `commits[0 until commitCount]` become visible, `aborts[0 until abortCount]` hidden
     * for good and `deletes[0 until deleteCount]` (committed documents) deleted, in one new snapshot.
     */
    fun publish(lsn: Long, commits: LongArray, commitCount: Int, aborts: LongArray, abortCount: Int, deletes: LongArray, deleteCount: Int) = synchronized(lock) {
        for (i in 0 until commitCount) check(inFlight.remove(commits[i])) { "docId ${commits[i]} isn't in flight" }
        if (abortCount > 0) {
            val builder = aborted.builder()
            for (i in 0 until abortCount) {
                check(inFlight.remove(aborts[i])) { "docId ${aborts[i]} isn't in flight" }
                builder.add(aborts[i])
            }
            aborted = builder.build()
        }
        if (deleteCount > 0) {
            val builder = deleted.builder()
            for (i in 0 until deleteCount) {
                val docId = deletes[i]
                check(docId < nextDocId && docId !in inFlight && !aborted.contains(docId)) { "docId $docId isn't committed" }
                check(builder.add(docId)) { "docId $docId is already deleted" }
            }
            deleted = builder.build()
        }
        publishLocked(lsn)
    }

    private fun publishLocked(lsn: Long) {
        val ids = LongArray(inFlight.size)
        var i = 0
        for (id in inFlight) ids[i++] = id
        published.set(Snapshot(lsn, Visibility(nextDocId - 1, ids, aborted, deleted)))
    }

    private companion object {
        val EMPTY = LongArray(0)
    }
}
