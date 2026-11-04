package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.internal.vector.Vectors
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A document's vector: its float32 [values] and the sum of their [squares] ([Vectors.squares]), which makes a cosine
 * distance one dot product. Immutable; [values] must not be changed.
 */
internal class VectorEntry(val docId: Long, val values: FloatArray, val squares: Double) {
    companion object {
        /** Heap estimate per document besides 4 bytes per value: the entry, its skip-list node, the boxed docId and the array header. */
        const val APPROXIMATE_BYTES: Long = 32 + 40 + 16 + 16
    }
}

/**
 * The vector memtable: the vector of each document committed with one, by docId. Vectors never change and there are
 * no tombstones: a deleted document's vector is hidden by visibility. Thread-safe; only the writer (or recovery) adds.
 */
internal class VectorMemtable {
    private val byDocId = ConcurrentSkipListMap<Long, VectorEntry>()
    private val bytes = AtomicLong()

    val approximateBytes: Long get() = bytes.get()

    /** Documents with a vector. */
    val size: Int get() = byDocId.size

    val isEmpty: Boolean get() = byDocId.isEmpty()

    /** Adds the vector of [docId], taking [values] over: the caller must not change them afterwards. */
    fun add(docId: Long, values: FloatArray) {
        require(docId >= 0 && values.isNotEmpty())
        check(byDocId.putIfAbsent(docId, VectorEntry(docId, values, Vectors.squares(values))) == null) { "document $docId has two vectors" }
        bytes.addAndGet(VectorEntry.APPROXIMATE_BYTES + 4L * values.size)
    }

    /** The vector of [docId], or null. */
    fun vectorOf(docId: Long): VectorEntry? = byDocId[docId]

    /** The entries from [from] on, in docId order; weakly consistent (read a frozen memtable). */
    fun entries(from: Long = 0): Iterator<VectorEntry> = byDocId.tailMap(from).values.iterator()
}
