package app.oreshkov.delva.internal.lsm

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * One version of a type-index entry: a FORWARD entry `(docId, pre) → typeId` (0 = none) or an INVERTED
 * entry `(typeId, docId, pre) → PUT | TOMBSTONE`, written at control-log sequence number [lsn]. [value] is the typeId of
 * a forward entry and the op of an inverted one. Immutable.
 *
 * Order: the key (key space, typeId, docId, pre — the order of the segment key bytes), then newest version first, so
 * the first version at or after a probe with `lsn = L` is the newest one a snapshot at L sees.
 */
internal class TypeEntry(val space: Int, val typeId: Int, val docId: Long, val pre: Long, val lsn: Long, val value: Int) : Comparable<TypeEntry> {
    override fun compareTo(other: TypeEntry): Int {
        if (space != other.space) return space.compareTo(other.space)
        if (typeId != other.typeId) return Integer.compareUnsigned(typeId, other.typeId)
        if (docId != other.docId) return docId.compareTo(other.docId)
        if (pre != other.pre) return pre.compareTo(other.pre)
        return other.lsn.compareTo(lsn)
    }

    fun sameKey(other: TypeEntry): Boolean = space == other.space && typeId == other.typeId && docId == other.docId && pre == other.pre

    companion object {
        const val FORWARD: Int = 1
        const val INVERTED: Int = 2

        const val PUT: Int = 1
        const val TOMBSTONE: Int = 2

        /** Heap estimate per entry: the entry and a skip-list node with its share of index nodes. */
        const val APPROXIMATE_BYTES: Long = 48 + 40
    }
}

/**
 * The entries of one key range of one type-index source, newest visible version per key: the forward entries of one
 * document, or the inverted entries of one type. Ascending, or descending for a source opened so. Not thread-safe.
 */
internal interface TypeSource {
    /** Whether the source is positioned on an entry; the fields below are valid only then. */
    val valid: Boolean
    val docId: Long
    val pre: Long

    /** A forward entry's typeId, or an inverted entry's op. */
    val value: Int

    fun next()

    /** Moves to the first entry at or past `(docId, pre)` in the source's order; never moves back. */
    fun seek(docId: Long, pre: Long)
}

/**
 * The type-index memtable: every version of every key, so a snapshot taken before a reassignment
 * still sees the older type; a flush keeps only the newest version per key. Counts puts minus tombstones per type for
 * approximate counts. Thread-safe; only the writer (or recovery) adds.
 */
internal class TypeMemtable {
    private val entries = ConcurrentSkipListSet<TypeEntry>()
    private val bytes = AtomicLong()
    private val count = AtomicInteger()
    private val netCounts = ConcurrentHashMap<Int, LongAdder>()

    val approximateBytes: Long get() = bytes.get()

    /** Entries of all versions. */
    val size: Int get() = count.get()

    val isEmpty: Boolean get() = count.get() == 0

    /**
     * Records that the object `(docId, pre)` changed from type [oldTypeId] to [newTypeId] (0 = none) at [lsn]: the forward
     * entry, a PUT for the new type and a TOMBSTONE for the old one.
     */
    fun assign(docId: Long, pre: Long, lsn: Long, newTypeId: Int, oldTypeId: Int) {
        require(newTypeId != oldTypeId && docId >= 0 && pre >= 0)
        add(TypeEntry(TypeEntry.FORWARD, 0, docId, pre, lsn, newTypeId))
        if (newTypeId != 0) {
            add(TypeEntry(TypeEntry.INVERTED, newTypeId, docId, pre, lsn, TypeEntry.PUT))
            netCounts.computeIfAbsent(newTypeId) { LongAdder() }.increment()
        }
        if (oldTypeId != 0) {
            add(TypeEntry(TypeEntry.INVERTED, oldTypeId, docId, pre, lsn, TypeEntry.TOMBSTONE))
            netCounts.computeIfAbsent(oldTypeId) { LongAdder() }.decrement()
        }
    }

    private fun add(entry: TypeEntry) {
        check(entries.add(entry)) { "type entry (${entry.space}, ${entry.typeId}, ${entry.docId}, ${entry.pre}) at LSN ${entry.lsn} added twice" }
        count.incrementAndGet()
        bytes.addAndGet(TypeEntry.APPROXIMATE_BYTES)
    }

    /** Puts minus tombstones of [typeId] in this memtable; approximate across memtables and segments. */
    fun netCount(typeId: Int): Long = netCounts[typeId]?.sum() ?: 0L

    /** The typeId of `(docId, pre)` in its newest version at or before [maxLsn]; -1 when there is none. */
    fun forward(docId: Long, pre: Long, maxLsn: Long): Int {
        val e = entries.ceiling(TypeEntry(TypeEntry.FORWARD, 0, docId, pre, maxLsn, 0)) ?: return -1
        return if (e.space == TypeEntry.FORWARD && e.docId == docId && e.pre == pre) e.value else -1
    }

    /** Forward entries of [docId] from [fromPre] on, as seen at [maxLsn]. */
    fun forwardSource(docId: Long, fromPre: Long, maxLsn: Long): TypeSource = Source(TypeEntry.FORWARD, 0, docId, fromPre, maxLsn)

    /** Inverted entries of [typeId] from `(fromDocId, fromPre)` on, as seen at [maxLsn]. */
    fun invertedSource(typeId: Int, fromDocId: Long, fromPre: Long, maxLsn: Long): TypeSource = Source(TypeEntry.INVERTED, typeId, fromDocId, fromPre, maxLsn)

    /** Inverted entries of [typeId] from `(toDocId, toPre)` down, as seen at [maxLsn]. */
    fun invertedSourceDescending(typeId: Int, toDocId: Long, toPre: Long, maxLsn: Long): TypeSource = ReverseSource(typeId, toDocId, toPre, maxLsn)

    /** The newest version of every key, in key order; weakly consistent (read a frozen memtable). */
    fun newest(): Iterator<TypeEntry> = object : Iterator<TypeEntry> {
        private val all = entries.iterator()
        private var pending: TypeEntry? = null
        private var last: TypeEntry? = null

        override fun hasNext(): Boolean {
            while (pending == null && all.hasNext()) {
                val e = all.next()
                val previous = last
                last = e
                if (previous == null || !previous.sameKey(e)) pending = e
            }
            return pending != null
        }

        override fun next(): TypeEntry {
            if (!hasNext()) throw NoSuchElementException()
            return pending!!.also { pending = null }
        }
    }

    /** A key range of one key space: forward entries of one document, or inverted entries of one type. */
    private inner class Source(private val space: Int, private val typeId: Int, docId: Long, pre: Long, private val maxLsn: Long) : TypeSource {
        /** The forward range is one document. */
        private val onlyDocId = if (space == TypeEntry.FORWARD) docId else -1L
        private var tail: Iterator<TypeEntry> = tailFrom(docId, pre)
        private var current: TypeEntry? = null
        private var exhausted = false

        override var valid: Boolean = false
            private set
        override val docId: Long get() = current!!.docId
        override val pre: Long get() = current!!.pre
        override val value: Int get() = current!!.value

        init {
            next()
        }

        private fun tailFrom(docId: Long, pre: Long): Iterator<TypeEntry> =
            entries.tailSet(TypeEntry(space, typeId, docId, pre, Long.MAX_VALUE, 0), true).iterator()

        override fun next() {
            val previous = if (valid) current else null
            while (tail.hasNext()) {
                val e = tail.next()
                if (e.space != space || e.typeId != typeId || (onlyDocId >= 0 && e.docId != onlyDocId)) break
                // Versions newer than the snapshot, and older versions of the key already returned, are skipped.
                if (e.lsn > maxLsn || (previous != null && previous.sameKey(e))) continue
                current = e
                valid = true
                return
            }
            valid = false
            exhausted = true
        }

        override fun seek(docId: Long, pre: Long) {
            if (exhausted || (valid && (this.docId > docId || (this.docId == docId && this.pre >= pre)))) return
            tail = tailFrom(docId, pre)
            current = null
            valid = false
            next()
        }
    }

    /**
     * The inverted entries of one type read from the top down. Walking the set backwards meets a key's versions oldest
     * first, so each key is read to its end and the newest version at or before [maxLsn] decides, as it does forward.
     */
    private inner class ReverseSource(private val typeId: Int, docId: Long, pre: Long, private val maxLsn: Long) : TypeSource {
        private var tail: Iterator<TypeEntry> = headFrom(docId, pre)

        /** The first version of the next key, read while finishing the current one. */
        private var pending: TypeEntry? = null
        private var current: TypeEntry? = null
        private var exhausted = false

        override var valid: Boolean = false
            private set
        override val docId: Long get() = current!!.docId
        override val pre: Long get() = current!!.pre
        override val value: Int get() = current!!.value

        init {
            next()
        }

        /** Every version of every key up to `(docId, pre)`, last key first. */
        private fun headFrom(docId: Long, pre: Long): Iterator<TypeEntry> =
            entries.headSet(TypeEntry(TypeEntry.INVERTED, typeId, docId, pre, Long.MIN_VALUE, 0), true).descendingIterator()

        override fun next() {
            while (true) {
                val first = pending ?: if (tail.hasNext()) tail.next() else null
                pending = null
                if (first == null || first.space != TypeEntry.INVERTED || first.typeId != typeId) break
                var newest: TypeEntry? = if (first.lsn <= maxLsn) first else null
                while (tail.hasNext()) {
                    val e = tail.next()
                    if (!e.sameKey(first)) {
                        pending = e
                        break
                    }
                    if (e.lsn <= maxLsn) newest = e
                }
                if (newest != null) {
                    current = newest
                    valid = true
                    return
                }
            }
            valid = false
            exhausted = true
        }

        override fun seek(docId: Long, pre: Long) {
            if (exhausted || (valid && (this.docId < docId || (this.docId == docId && this.pre <= pre)))) return
            tail = headFrom(docId, pre)
            pending = null
            current = null
            valid = false
            next()
        }
    }
}
