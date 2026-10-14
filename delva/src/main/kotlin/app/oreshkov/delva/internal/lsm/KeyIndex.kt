package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.internal.bytes.BigEndian
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.visibility.Visibility
import app.oreshkov.delva.internal.hash.Xxh3
import app.oreshkov.delva.internal.io.FileIo
import java.util.Arrays
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicLong

/**
 * A document key (UTF-8 bytes) and the document that has it. Ordered by the key's bytes unsigned, which is the order of
 * its code points, then by docId. Immutable; [key] must not be changed.
 */
internal class KeyEntry(val key: ByteArray, val docId: Long) : Comparable<KeyEntry> {
    override fun compareTo(other: KeyEntry): Int {
        val c = Arrays.compareUnsigned(key, other.key)
        return if (c != 0) c else docId.compareTo(other.docId)
    }

    companion object {
        /** Heap estimate per document besides its key bytes: the entry, two skip-list nodes and the array header. */
        const val APPROXIMATE_BYTES: Long = 32 + 2 * 40 + 24 + 16
    }
}

/**
 * The document-key memtable: each document committed with a key, by key and by docId. Keys never change and there are
 * no tombstones: a deleted document's entries are hidden by visibility, and flushes and compactions drop them. Thread-safe;
 * only the writer (or recovery) adds.
 */
internal class KeyMemtable {
    private val byKey = ConcurrentSkipListSet<KeyEntry>()
    private val byDocId = ConcurrentSkipListMap<Long, ByteArray>()
    private val bytes = AtomicLong()

    val approximateBytes: Long get() = bytes.get()

    /** Documents with a key. */
    val size: Int get() = byDocId.size

    val isEmpty: Boolean get() = byDocId.isEmpty()

    fun add(key: ByteArray, docId: Long) {
        require(docId >= 0 && key.size <= CreationOptions.MAX_DOCUMENT_KEY_BYTES)
        check(byDocId.putIfAbsent(docId, key) == null) { "document $docId keyed twice" }
        byKey.add(KeyEntry(key, docId))
        bytes.addAndGet(KeyEntry.APPROXIMATE_BYTES + key.size)
    }

    /** The key of [docId], or null. */
    fun keyOf(docId: Long): ByteArray? = byDocId[docId]

    /** The entries of [key], by docId. */
    fun withKey(key: ByteArray): Collection<KeyEntry> = byKey.subSet(KeyEntry(key, Long.MIN_VALUE), true, KeyEntry(key, Long.MAX_VALUE), true)

    /** Every entry in key order; weakly consistent (read a frozen memtable). */
    fun entries(): Iterator<KeyEntry> = byKey.iterator()

    /** `docId → key` in docId order; weakly consistent. */
    fun keysByDocId(): Iterator<Map.Entry<Long, ByteArray>> = byDocId.entries.iterator()

    /** The entries from [from] up ([inclusive] or not), ascending. */
    fun ascending(from: KeyEntry, inclusive: Boolean): Iterator<KeyEntry> = byKey.tailSet(from, inclusive).iterator()

    /** The entries from [to] down ([inclusive] or not), descending. */
    fun descending(to: KeyEntry, inclusive: Boolean): Iterator<KeyEntry> = byKey.headSet(to, inclusive).descendingIterator()
}

/**
 * Document-key segments: one segment per flush holds both key spaces, big-endian:
 * ```
 * KEY    key := 01 | escaped(key) | 00 | docId:u64        value := (empty)
 * DOCKEY key := 02 | docId:u64                            value := key utf8
 * ```
 * `escaped` writes each 0x00 byte of the key as `00 FF`, which UTF-8 never has, so the KEY entries are in the order of
 * the keys' code points, and a key that is a prefix of another comes first. The bloom filter holds the hash of
 * `escaped(key) | 00` of KEY entries and of the docId bytes of DOCKEY entries, so point lookups by either skip
 * segments without it. The segment stats count every entry; `itemCount` is the DOCKEY entries, and the docId range
 * covers both spaces.
 */
internal object KeySegments {
    const val KEY: Int = 1
    const val DOCKEY: Int = 2
    const val DOCKEY_BYTES: Int = 9

    /** `01 | escaped(key) | 00`: the KEY entries of [key] are this followed by their docIds. */
    fun keyPrefix(key: ByteArray): ByteArray {
        val out = GrowableBytes(key.size + 2)
        out.writeByte(KEY)
        escape(key, out)
        out.writeByte(0)
        return out.toByteArray()
    }

    /** `01 | escaped(key)`, the lower bound of every KEY entry of a key that starts with [key]. */
    fun keyBound(key: ByteArray, past: Boolean = false, upper: Boolean = false): ByteArray {
        val out = GrowableBytes(key.size + 3)
        out.writeByte(KEY)
        escape(key, out)
        // Past every entry of [key] itself: above `00 | docId`, below the first key that continues it.
        if (past) {
            out.writeByte(0)
            out.writeByte(0xFF)
        }
        // Above every key that starts with [key]: 0xFF never starts a UTF-8 sequence or an escape.
        if (upper) out.writeByte(0xFF)
        return out.toByteArray()
    }

    private fun escape(key: ByteArray, out: GrowableBytes) {
        for (b in key) {
            out.writeByte(b.toInt() and 0xFF)
            if (b.toInt() == 0) out.writeByte(0xFF)
        }
    }

    /** The key of a KEY entry `key[0, length)`; throws [CorruptionException] when it isn't one. */
    fun unescape(entry: ByteArray, length: Int, source: String): ByteArray {
        if (length < 2 + 8 || entry[0].toInt() != KEY || entry[length - 9].toInt() != 0) throw CorruptionException(source, "key entry of $length bytes")
        val out = GrowableBytes(length - 10)
        var i = 1
        val end = length - 9
        while (i < end) {
            val b = entry[i++].toInt() and 0xFF
            out.writeByte(b)
            if (b == 0) {
                if (i == end || entry[i].toInt() and 0xFF != 0xFF) throw CorruptionException(source, "key entry with an unescaped 00 byte")
                i++
            }
        }
        if (out.size > CreationOptions.MAX_DOCUMENT_KEY_BYTES) throw CorruptionException(source, "key of ${out.size} bytes")
        return out.toByteArray()
    }

    fun docKey(docId: Long): ByteArray = ByteArray(DOCKEY_BYTES).also {
        it[0] = DOCKEY.toByte()
        BigEndian.putLong(it, 1, docId)
    }

    /** The bloom hash of the KEY entries whose [keyPrefix] is [prefix]. */
    fun keyHash(prefix: ByteArray): Long = Xxh3.hash64(prefix, 1, prefix.size - 1, 0L)

    /** The bloom hash of the DOCKEY entry of [docId]. */
    fun docIdHash(docId: Long): Long = TypeSegments.bloomHash(docId)

    /** The docId of the entry `key[0, length)` of either space, or -1 when it's neither. */
    fun docIdOf(key: ByteArray, length: Int): Long = when {
        length == DOCKEY_BYTES && key[0].toInt() == DOCKEY -> BigEndian.getLong(key, 1)
        length >= 10 && key[0].toInt() == KEY -> BigEndian.getLong(key, length - 8)
        else -> -1
    }

    /** The bloom key of a merged segment: what [keyHash] and [docIdHash] hash. */
    private val BLOOM_KEY = BloomKey { key, offset, length ->
        when {
            length >= 10 && key[offset].toInt() == KEY -> (1L shl 32) or (length - 9).toLong()
            length == DOCKEY_BYTES && key[offset].toInt() == DOCKEY -> (1L shl 32) or 8L
            else -> -1L
        }
    }

    /** Inline values: a DOCKEY value is the key, which segments never move to an external value. */
    private fun writer(file: FileIo, blockBytes: Int, merged: Boolean): SegmentWriter = SegmentWriter(
        file, SegmentFormat.INDEX_KEY, blockBytes, inlineLimit = maxOf(256, blockBytes / 4, CreationOptions.MAX_DOCUMENT_KEY_BYTES),
        bloomKey = if (merged) BLOOM_KEY else null,
    )

    /** Writes [memtable] (frozen) as a segment, without the entries of [hidden] docIds; returns its stats. */
    fun write(memtable: KeyMemtable, file: FileIo, blockBytes: Int, hidden: HiddenDocIds = HiddenDocIds.NONE): SegmentStats {
        val writer = writer(file, blockBytes, merged = false)
        var count = 0L
        var documents = 0L
        var minDocId = Long.MAX_VALUE
        var maxDocId = -1L
        var lastKey: ByteArray? = null
        var prefix = EMPTY
        val entry = GrowableBytes(64)
        for (e in memtable.entries()) {
            if (hidden.contains(e.docId)) continue
            if (lastKey == null || !Arrays.equals(lastKey, e.key)) {
                prefix = keyPrefix(e.key)
                writer.addBloomHash(keyHash(prefix))
                lastKey = e.key
            }
            entry.clear()
            entry.writeBytes(prefix)
            entry.writeLongBE(e.docId)
            writer.add(entry.array, 0, entry.size, EMPTY, 0, 0)
            count++
        }
        for ((docId, key) in memtable.keysByDocId()) {
            if (hidden.contains(docId)) continue
            val k = docKey(docId)
            writer.addBloomHash(docIdHash(docId))
            writer.add(k, 0, k.size, key, 0, key.size)
            count++
            documents++
            minDocId = minOf(minDocId, docId)
            maxDocId = maxOf(maxDocId, docId)
        }
        val stats = SegmentStats(count, documents, if (documents == 0L) 0 else minDocId, maxDocId, hidden.countInRange(minDocId, maxDocId))
        writer.finish(stats)
        return stats
    }

    /**
     * Compaction: writes the entries of [inputs] (adjacent segments, in any order) as one segment, each key once and
     * none of [hidden] docIds. Returns the stats, or null when nothing is left. Memory: one block per input.
     */
    fun merge(inputs: List<SegmentReader>, hidden: HiddenDocIds, control: MergeControl, file: FileIo, blockBytes: Int): SegmentStats? {
        val cursors = inputs.map { it.cursor() }
        val heap = PriorityQueue<Int>(maxOf(1, cursors.size)) { a, b ->
            val ka = cursors[a].key
            val kb = cursors[b].key
            val c = Arrays.compareUnsigned(ka.array, 0, ka.size, kb.array, 0, kb.size)
            if (c != 0) c else a.compareTo(b)
        }
        for (i in cursors.indices) if (cursors[i].first()) heap.add(i)
        val writer = writer(file, blockBytes, merged = true)
        val last = GrowableBytes(64)
        var hasLast = false
        var read = 0L
        var count = 0L
        var documents = 0L
        var minDocId = Long.MAX_VALUE
        var maxDocId = -1L
        while (heap.isNotEmpty()) {
            val i = heap.poll()
            val c = cursors[i]
            if (++read and 0xFFF == 0L) control.checkContinue()
            val k = c.key
            val docId = docIdOf(k.array, k.size)
            if (docId < 0) throw CorruptionException(inputs[i].source, "key-index entry of ${k.size} bytes in key space ${k.array[0]}")
            val duplicate = hasLast && Arrays.equals(last.array, 0, last.size, k.array, 0, k.size)
            if (!duplicate && !hidden.contains(docId)) {
                if (c.valueExternal) throw CorruptionException(inputs[i].source, "external key-index value")
                if (k.array[0].toInt() == KEY) {
                    // Only checks the entry: a damaged one stops the merge rather than spreading.
                    unescape(k.array, k.size, inputs[i].source)
                } else {
                    if (c.valueLength > CreationOptions.MAX_DOCUMENT_KEY_BYTES) throw CorruptionException(inputs[i].source, "key of ${c.valueLength} bytes for document $docId")
                    documents++
                    minDocId = minOf(minDocId, docId)
                    maxDocId = maxOf(maxDocId, docId)
                }
                writer.add(k.array, 0, k.size, c.valueBytes, c.valueOffset, c.valueLength)
                count++
            }
            last.clear()
            last.writeBytes(k.array, 0, k.size)
            hasLast = true
            if (c.next()) heap.add(i)
        }
        val stats = SegmentStats(count, documents, if (documents == 0L) 0 else minDocId, maxDocId, hidden.countInRange(minDocId, maxDocId))
        writer.finish(stats)
        return stats.takeIf { it.entryCount > 0 }
    }

    private val EMPTY = ByteArray(0)
}

/**
 * The document-key index as one reader sees it: memtables, then segments, each document's key once; entries of documents
 * that [visibility] hides are skipped (null: every entry). Keys never change, so it needs no LSN: a version holding
 * everything the snapshot sees is enough ([app.oreshkov.delva.internal.engine.Engine.openView]). Thread-safe; its
 * scans are not.
 */
internal class KeyIndexView(
    private val memtables: List<Memtable>,
    /** Oldest first. */
    private val segments: List<SegmentHandle>,
    private val visibility: Visibility?,
) {
    constructor(version: Version, visibility: Visibility?) : this(version.memtables, version.keySegments, visibility)

    private fun visible(docId: Long): Boolean = visibility?.isVisible(docId) ?: true

    /** The visible document with [key] that [skip] doesn't exclude, the largest docId if damage left several; -1 for none. */
    fun docIdOf(key: ByteArray, skip: ((Long) -> Boolean)? = null): Long {
        var best = -1L
        val accept = { docId: Long -> docId > best && visible(docId) && skip?.invoke(docId) != true }
        for (m in memtables) {
            if (m.keys.isEmpty) continue
            for (e in m.keys.withKey(key)) if (accept(e.docId)) best = e.docId
        }
        if (segments.isEmpty()) return best
        val prefix = KeySegments.keyPrefix(key)
        val hash = KeySegments.keyHash(prefix)
        for (i in segments.indices.reversed()) {
            val reader = segments[i].reader
            if (!reader.mayContain(hash)) continue
            val cursor = reader.cursor()
            var ok = cursor.seekCeiling(prefix)
            while (ok && cursor.key.size == prefix.size + 8 && Arrays.equals(cursor.key.array, 0, prefix.size, prefix, 0, prefix.size)) {
                val docId = BigEndian.getLong(cursor.key.array, prefix.size)
                if (accept(docId)) best = docId
                ok = cursor.next()
            }
        }
        return best
    }

    /** The key of [docId] (visible or not), or null when it has none. */
    fun keyOf(docId: Long): ByteArray? {
        for (m in memtables) m.keys.keyOf(docId)?.let { return it }
        if (segments.isEmpty()) return null
        val target = KeySegments.docKey(docId)
        val hash = KeySegments.docIdHash(docId)
        for (i in segments.indices.reversed()) {
            val reader = segments[i].reader
            if (docId < reader.stats.minDocId || docId > reader.stats.maxDocId || !reader.mayContain(hash)) continue
            val cursor = reader.cursor()
            if (cursor.seekCeiling(target) && cursor.key.size == target.size && Arrays.equals(cursor.key.array, 0, target.size, target, 0, target.size)) {
                if (cursor.valueExternal || cursor.valueLength > CreationOptions.MAX_DOCUMENT_KEY_BYTES) throw CorruptionException(reader.source, "key of document $docId")
                return cursor.valueBytes.copyOfRange(cursor.valueOffset, cursor.valueOffset + cursor.valueLength)
            }
        }
        return null
    }

    /**
     * The visible documents whose key starts with [prefix], in key order ([descending] or not), strictly past [after]
     * when it's given.
     */
    fun scan(prefix: ByteArray, after: ByteArray?, descending: Boolean): KeyScan = KeyScan(prefix, after, descending)

    /**
     * A k-way merge of the KEY entries of every source in key order, each `(key, docId)` once, of visible documents
     * whose key starts with the prefix. [key] and [docId] are valid while [valid].
     */
    inner class KeyScan internal constructor(private val prefix: ByteArray, after: ByteArray?, private val descending: Boolean) {
        private val sources = ArrayList<KeySource>()
        private val heap: PriorityQueue<KeySource>

        var valid: Boolean = false
            private set
        var key: ByteArray = EMPTY_KEY
            private set
        var docId: Long = -1
            private set

        init {
            // The tighter of the prefix's bound and the cursor's (which is exclusive).
            val start: ByteArray
            val exclusive: Boolean
            if (!descending) {
                exclusive = after != null && Arrays.compareUnsigned(after, prefix) >= 0
                start = if (exclusive) after else prefix
            } else {
                val top = prefix.copyOf(prefix.size + 1).also { it[prefix.size] = 0xFF.toByte() }
                exclusive = after != null && Arrays.compareUnsigned(after, top) < 0
                start = if (exclusive) after else top
            }
            for (m in memtables) {
                if (m.keys.isEmpty) continue
                sources += if (!descending) {
                    MemtableKeySource(m.keys.ascending(KeyEntry(start, if (exclusive) Long.MAX_VALUE else Long.MIN_VALUE), inclusive = false))
                } else {
                    MemtableKeySource(m.keys.descending(KeyEntry(start, if (exclusive) Long.MIN_VALUE else Long.MAX_VALUE), inclusive = false))
                }
            }
            for (i in segments.indices.reversed()) {
                val bound = when {
                    !descending -> KeySegments.keyBound(start, past = exclusive)
                    exclusive -> KeySegments.keyPrefix(start)
                    else -> KeySegments.keyBound(prefix, upper = true)
                }
                sources += SegmentKeySource(segments[i].reader, bound, descending)
            }
            heap = PriorityQueue(maxOf(1, sources.size)) { a, b ->
                val c = Arrays.compareUnsigned(a.key, b.key).let { if (it != 0) it else a.docId.compareTo(b.docId) }
                if (descending) -c else c
            }
            for (s in sources) if (s.valid) heap.add(s)
            next()
        }

        /** Moves to the next visible document. */
        fun next() {
            while (heap.isNotEmpty()) {
                val s = heap.poll()
                val k = s.key
                val d = s.docId
                // Every source holding the entry moves past it.
                while (heap.isNotEmpty() && heap.peek().docId == d && Arrays.equals(heap.peek().key, k)) {
                    val other = heap.poll()
                    other.next()
                    if (other.valid) heap.add(other)
                }
                s.next()
                if (s.valid) heap.add(s)
                if (!startsWith(k, prefix)) break
                if (visible(d)) {
                    key = k
                    docId = d
                    valid = true
                    return
                }
            }
            valid = false
            heap.clear()
        }

        private fun startsWith(key: ByteArray, prefix: ByteArray): Boolean =
            key.size >= prefix.size && Arrays.equals(key, 0, prefix.size, prefix, 0, prefix.size)
    }

    /** The KEY entries of one source in one direction. */
    private interface KeySource {
        val valid: Boolean
        val key: ByteArray
        val docId: Long

        fun next()
    }

    private class MemtableKeySource(private val entries: Iterator<KeyEntry>) : KeySource {
        private var current: KeyEntry? = null
        override val valid: Boolean get() = current != null
        override val key: ByteArray get() = current!!.key
        override val docId: Long get() = current!!.docId

        init {
            next()
        }

        override fun next() {
            current = if (entries.hasNext()) entries.next() else null
        }
    }

    /** From [bound] up, or for [descending] from [bound] down: each step floors strictly below the current entry. */
    private class SegmentKeySource(private val segment: SegmentReader, bound: ByteArray, private val descending: Boolean) : KeySource {
        private val cursor = segment.cursor()
        override var valid: Boolean = false
            private set
        override var key: ByteArray = EMPTY_KEY
            private set
        override var docId: Long = -1
            private set

        init {
            position(if (descending) cursor.seekFloor(bound) else cursor.seekCeiling(bound))
        }

        override fun next() {
            if (!valid) return
            if (!descending) {
                position(cursor.next())
                return
            }
            val k = cursor.key
            // Strictly below `(key, docId)`: `(key, docId - 1)`, or below every entry of the key.
            val below = if (docId > 0) k.array.copyOf(k.size).also { BigEndian.putLong(it, k.size - 8, docId - 1) } else k.array.copyOf(k.size - 8)
            position(cursor.seekFloor(below))
        }

        private fun position(found: Boolean) {
            val k = cursor.key
            if (!found || k.size == 0 || k.array[0].toInt() != KeySegments.KEY) {
                valid = false
                return
            }
            key = KeySegments.unescape(k.array, k.size, segment.source)
            docId = BigEndian.getLong(k.array, k.size - 8)
            if (docId < 0) throw CorruptionException(segment.source, "key entry of document $docId")
            valid = true
        }
    }

    private companion object {
        val EMPTY_KEY = ByteArray(0)
    }
}
