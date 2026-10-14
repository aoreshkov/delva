package app.oreshkov.delva.internal.lsm

import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A fragment-index entry: the key `docId | pre | kind | seq` and where the fragment's bytes are.
 * [location] and [length] are opaque to the index; [payloadOffset] is where the stored payload starts within the record, [rawLength] how long the payload is.
 */
internal class FragmentEntry(
    val docId: Long,
    val pre: Long,
    val kind: Int,
    val seq: Int,
    val location: Long,
    val length: Int,
    val payloadOffset: Int,
    /** Length of the payload before compression. */
    val rawLength: Int,
) : Comparable<FragmentEntry> {
    /** Order of the big-endian key bytes: docId, pre, kind, seq, all unsigned (and non-negative). */
    override fun compareTo(other: FragmentEntry): Int {
        var c = docId.compareTo(other.docId)
        if (c != 0) return c
        c = pre.compareTo(other.pre)
        if (c != 0) return c
        c = kind.compareTo(other.kind)
        return if (c != 0) c else Integer.compareUnsigned(seq, other.seq)
    }

    companion object {
        /** Heap estimate per entry: the entry and a skip-list node with its share of index nodes. */
        const val APPROXIMATE_BYTES: Long = 48 + 40

        /** Largest `kind` and `seq`, to make probes that sort after every fragment at a `pre`. */
        const val MAX_KIND: Int = 0xFF
        const val MAX_SEQ: Int = -1

        fun probe(docId: Long, pre: Long, kind: Int, seq: Int): FragmentEntry = FragmentEntry(docId, pre, kind, seq, 0, 0, 0, 0)
    }
}

/**
 * Fragment-index memtable: a concurrent sorted map of [FragmentEntry]s by key. An entry is added once; only relocation
 * ([put]) replaces the location of a key, atomically for readers. Thread-safe.
 */
internal class FragmentIndexMemtable : FragmentIndex {
    private val entries = ConcurrentSkipListMap<FragmentEntry, FragmentEntry>()
    private val bytes = AtomicLong()

    val approximateBytes: Long get() = bytes.get()

    val size: Int get() = entries.size

    fun add(entry: FragmentEntry) {
        check(entries.putIfAbsent(entry, entry) == null) { "fragment (${entry.docId}, ${entry.pre}, ${entry.kind}, ${entry.seq}) indexed twice" }
        bytes.addAndGet(FragmentEntry.APPROXIMATE_BYTES)
    }

    /** Adds [entry], or replaces the entry with its key: the fragment was relocated. */
    fun put(entry: FragmentEntry) {
        if (entries.put(entry, entry) == null) bytes.addAndGet(FragmentEntry.APPROXIMATE_BYTES)
    }

    /** Every entry in key order; weakly consistent. For flushes, tests and diagnostics. */
    fun entries(): Iterable<FragmentEntry> = entries.values

    override fun find(docId: Long, pre: Long, kind: Int, seq: Int): FragmentEntry? = entries[FragmentEntry.probe(docId, pre, kind, seq)]

    /** The entry with the greatest key ≤ `(docId, pre, kind, seq)` in document [docId], or null. */
    override fun floor(docId: Long, pre: Long, kind: Int, seq: Int): FragmentEntry? =
        entries.floorEntry(FragmentEntry.probe(docId, pre, kind, seq))?.value?.takeIf { it.docId == docId }

    /** Entries of document [docId] with keys in `[(fromPre, fromKind, 0), (toPre, MAX_KIND, MAX_SEQ)]`, in key order. */
    override fun range(docId: Long, fromPre: Long, fromKind: Int, toPre: Long): Iterable<FragmentEntry> =
        entries.subMap(FragmentEntry.probe(docId, fromPre, fromKind, 0), true, FragmentEntry.probe(docId, toPre, FragmentEntry.MAX_KIND, FragmentEntry.MAX_SEQ), true).values
}
