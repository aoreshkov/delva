package app.oreshkov.delva.internal.store

import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.log.FragmentRecord
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.lsm.FragmentEntry
import app.oreshkov.delva.internal.lsm.FragmentIndexMemtable
import app.oreshkov.delva.internal.payload.FragmentContext
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.FragmentLookup
import app.oreshkov.delva.internal.payload.PayloadSlice

/**
 * Reads stored fragments by key: [LogFragmentStore] reads lane files, [MemoryFragmentStore] is
 * the test double. Thread-safe; a [FragmentView] must not be shared between threads.
 */
internal interface FragmentStore {
    /** Sets [into] to the fragment with this exact key; false if there's none. */
    fun find(docId: Long, pre: Long, kind: Int, seq: Int, into: FragmentView): Boolean

    /** Sets [into] to the fragment of [docId] with the greatest key ≤ `(pre, kind, seq)`; false if there's none. */
    fun floor(docId: Long, pre: Long, kind: Int, seq: Int, into: FragmentView): Boolean

    /** Payload bytes of the fragments of [docId] with keys in `[(fromPre, fromKind, 0), (toPre, any, any)]`. */
    fun payloadBytes(docId: Long, fromPre: Long, fromKind: Int, toPre: Long): Long
}

/** A [FragmentLookup] over one document of a [FragmentStore], for the reassembler. Reusable; not thread-safe. */
internal class DocumentFragments(private val store: FragmentStore) : FragmentLookup {
    private val view = FragmentView()
    var docId: Long = 0

    override fun find(pre: Long, kind: Int, seq: Int): PayloadSlice? = if (store.find(docId, pre, kind, seq, view)) view.slice() else null
}

/**
 * Fragments kept on the heap: records appended to large chunks, located through a [FragmentIndexMemtable]. Appends are
 * serialized; reads are lock-free (a fragment is readable once its index entry is added). Nothing is ever removed.
 */
internal class MemoryFragmentStore(maxRecordBytes: Int) : FragmentStore {
    private val chunkBytes = maxOf(DEFAULT_CHUNK_BYTES, maxRecordBytes + 64)
    private val index = FragmentIndexMemtable()

    @Volatile
    private var chunks = arrayOfNulls<ByteArray>(16)
    private var chunkCount = 0
    private var used = chunkBytes // forces a first chunk
    private val lock = Any()
    private val scratch = GrowableBytes(256)

    val fragmentCount: Int get() = index.size

    /** Heap estimate: chunk bytes plus the index. */
    val approximateBytes: Long get() = chunkCount.toLong() * chunkBytes + index.approximateBytes

    /** Stores one fragment and indexes it. */
    fun add(docId: Long, pre: Long, kind: Int, seq: Int, context: FragmentContext, payload: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            scratch.clear()
            val payloadOffset = FragmentRecord.write(scratch, context, payload, offset, length)
            addRecordLocked(docId, pre, kind, seq, scratch.array, 0, scratch.size, payloadOffset)
        }
    }

    /** Stores a record already in [FragmentRecord] layout and indexes it. */
    fun addRecord(docId: Long, pre: Long, kind: Int, seq: Int, record: ByteArray, offset: Int, length: Int, payloadOffset: Int) {
        synchronized(lock) { addRecordLocked(docId, pre, kind, seq, record, offset, length, payloadOffset) }
    }

    private fun addRecordLocked(docId: Long, pre: Long, kind: Int, seq: Int, record: ByteArray, offset: Int, length: Int, payloadOffset: Int) {
        require(length <= chunkBytes) { "record of $length bytes" }
        require(kind == FragmentKind.PART || kind == FragmentKind.SUBTREE || kind == FragmentKind.HEADER || kind == FragmentKind.STRCHUNK)
        if (chunkBytes - used < length) {
            if (chunkCount == chunks.size) chunks = chunks.copyOf(chunkCount * 2)
            chunks[chunkCount++] = ByteArray(chunkBytes)
            used = 0
        }
        val chunk = chunkCount - 1
        System.arraycopy(record, offset, chunks[chunk]!!, used, length)
        val location = (chunk.toLong() shl 32) or used.toLong()
        used += length
        index.add(FragmentEntry(docId, pre, kind, seq, location, length, payloadOffset, length - payloadOffset))
    }

    override fun find(docId: Long, pre: Long, kind: Int, seq: Int, into: FragmentView): Boolean {
        val entry = index.find(docId, pre, kind, seq) ?: return false
        read(entry, into)
        return true
    }

    override fun floor(docId: Long, pre: Long, kind: Int, seq: Int, into: FragmentView): Boolean {
        val entry = index.floor(docId, pre, kind, seq) ?: return false
        read(entry, into)
        return true
    }

    override fun payloadBytes(docId: Long, fromPre: Long, fromKind: Int, toPre: Long): Long {
        if (toPre < fromPre) return 0
        var total = 0L
        for (entry in index.range(docId, fromPre, fromKind, toPre)) total += entry.rawLength
        return total
    }

    private fun read(entry: FragmentEntry, into: FragmentView) {
        val chunk = chunks[(entry.location ushr 32).toInt()]!!
        into.set(entry, chunk, entry.location.toInt())
    }

    private companion object {
        const val DEFAULT_CHUNK_BYTES = 1 shl 20
    }
}

/** A [FragmentStore] that forwards to a replaceable [target]; lets pooled readers switch between versions. */
internal class SwitchableFragmentStore : FragmentStore {
    var target: FragmentStore = EMPTY

    override fun find(docId: Long, pre: Long, kind: Int, seq: Int, into: FragmentView): Boolean = target.find(docId, pre, kind, seq, into)

    override fun floor(docId: Long, pre: Long, kind: Int, seq: Int, into: FragmentView): Boolean = target.floor(docId, pre, kind, seq, into)

    override fun payloadBytes(docId: Long, fromPre: Long, fromKind: Int, toPre: Long): Long = target.payloadBytes(docId, fromPre, fromKind, toPre)

    private companion object {
        val EMPTY = object : FragmentStore {
            override fun find(docId: Long, pre: Long, kind: Int, seq: Int, into: FragmentView): Boolean = false

            override fun floor(docId: Long, pre: Long, kind: Int, seq: Int, into: FragmentView): Boolean = false

            override fun payloadBytes(docId: Long, fromPre: Long, fromKind: Int, toPre: Long): Long = 0
        }
    }
}
