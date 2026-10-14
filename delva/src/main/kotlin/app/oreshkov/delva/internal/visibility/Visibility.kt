package app.oreshkov.delva.internal.visibility

import app.oreshkov.delva.internal.bits.DocIdSet
import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap

/**
 * Which documents a snapshot sees: a docId is visible when it was allocated by then
 * (≤ [maxAllocatedDocId]), wasn't in flight, and was neither aborted nor deleted. DocIds of inserts rejected before they
 * wrote anything count as visible but have no data. The [aborted] and [deleted] sets are kept apart because only a
 * deleted docId ever named a document (SPEC.md §7). Immutable.
 *
 * A process that only reads the store learns of an insert when it replays its COMMIT, so it can't list the inserts in
 * flight; [late] holds the docIds its replay committed instead, and those committed after [lateAfterLsn] aren't visible.
 */
internal class Visibility(
    val maxAllocatedDocId: Long,
    private val inFlight: LongArray,
    val aborted: DocIdSet,
    val deleted: DocIdSet,
    private val late: LateCommits? = null,
    private val lateAfterLsn: Long = 0,
) {
    fun isVisible(docId: Long): Boolean =
        docId <= maxAllocatedDocId && Arrays.binarySearch(inFlight, docId) < 0 && !aborted.contains(docId) && !deleted.contains(docId) &&
            (late == null || !late.committedAfter(docId, lateAfterLsn))

    /** Smallest docId in flight at this snapshot, or `maxAllocatedDocId + 1` when none was. */
    val inFlightFloor: Long get() = if (inFlight.isEmpty()) maxAllocatedDocId + 1 else inFlight[0]

    /** Largest docId in flight at this snapshot, or -1 when none was. */
    val inFlightCeiling: Long get() = if (inFlight.isEmpty()) -1 else inFlight[inFlight.size - 1]

    val inFlightCount: Int get() = inFlight.size

    /** Whether [docId] was in flight at this snapshot. */
    fun isInFlight(docId: Long): Boolean = Arrays.binarySearch(inFlight, docId) >= 0

    fun isAborted(docId: Long): Boolean = aborted.contains(docId)

    fun isDeleted(docId: Long): Boolean = deleted.contains(docId)
}

/**
 * The LSNs of the COMMITs a replay applied, by docId: a replay indexes a document's fragments when it reads its COMMIT,
 * while snapshots published before still read the same memtables, and a snapshot must not see a document committed after
 * it. Recorded before the document is indexed. Thread-safe: one replay writes, any reader reads.
 */
internal class LateCommits {
    private val lsns = ConcurrentHashMap<Long, Long>()

    /** [docId] commits at [lsn]; called before its fragments are indexed. */
    fun committed(docId: Long, lsn: Long) {
        lsns[docId] = lsn
    }

    /** Whether [docId]'s COMMIT was replayed with an LSN above [lsn]. */
    fun committedAfter(docId: Long, lsn: Long): Boolean {
        if (lsns.isEmpty()) return false
        val at = lsns[docId] ?: return false
        return at > lsn
    }
}
