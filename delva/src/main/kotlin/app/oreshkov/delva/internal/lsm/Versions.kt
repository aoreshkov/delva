package app.oreshkov.delva.internal.lsm

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.BigEndian
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.hash.Xxh3
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.discard
import java.nio.file.Path
import java.util.Arrays
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The memtables of the posting, fragment, type and document-key indexes; frozen and flushed together. Thread-safe. */
internal class Memtable {
    val postings = PostingMemtable()
    val fragments = FragmentIndexMemtable()
    val types = TypeMemtable()
    val keys = KeyMemtable()

    val approximateBytes: Long get() = postings.approximateBytes + fragments.approximateBytes + types.approximateBytes + keys.approximateBytes

    val isEmpty: Boolean get() = postings.runCount == 0 && fragments.size == 0 && types.isEmpty && keys.isEmpty
}

/**
 * An open segment shared by the [Version]s that list it. When the last version holding it is released it's closed,
 * and deleted through [graveyard] if it was marked [obsolete] ([discard]: a delete that fails doesn't fail the store);
 * without a graveyard, in a process that only reads the store, it's only closed. A type segment's statistics ([types])
 * are read when the handle is created. Thread-safe.
 */
internal class SegmentHandle(val reader: SegmentReader, val path: Path, private val io: FileSystemIo, private val graveyard: Path?) {
    private val refs = AtomicInteger()

    /** Statistics of a type segment; null for other segments. */
    val types: TypeSegmentInfo? = if (reader.indexKind == SegmentFormat.INDEX_TYPE) TypeSegmentInfo.read(reader) else null

    @Volatile
    var obsolete: Boolean = false
        private set

    @Volatile
    var deleted: Boolean = false
        private set

    /**
     * Whether a flush merge or a compaction is rewriting the segment, so no other one takes it. Changed only under
     * the lock that serializes index changes.
     */
    @Volatile
    var claimed: Boolean = false

    /** Whether compaction gave up on the segment (a damaged block, a list too large); it's not picked again. */
    @Volatile
    var compactionFailed: Boolean = false

    val fileNo: Long get() = reader.fileNo

    /** The segment file's size. */
    val fileBytes: Long get() = reader.fileBytes

    fun retain() {
        refs.incrementAndGet()
    }

    fun release() {
        if (refs.decrementAndGet() == 0 && obsolete) destroy()
    }

    /** Marks the file for deletion, or only for closing without a graveyard, once no version holds it. */
    fun markObsolete() {
        obsolete = true
        if (refs.get() == 0) destroy()
    }

    /** Closes the file without deleting it (the store is closing). */
    fun close() = reader.close()

    @Synchronized
    private fun destroy() {
        if (deleted) return
        deleted = true
        reader.close()
        if (graveyard != null) io.discard(path, graveyard)
    }
}

/**
 * One consistent set of index sources: the [active] memtable, [frozen] memtables waiting for their
 * flush (oldest first) and the live segments of the four indexes (oldest first). [flushedLsn] is the LSN up to which
 * the segments hold everything flushed (the newest flush's freeze LSN): a type segment keeps only the newest version of
 * a key, and flushes and compactions leave out the entries of docIds hidden by then, so a reader must not see
 * the segments with a snapshot older than that. Immutable; reference-counted, so a search that pins a version
 * keeps its segments open. [seq] numbers versions in the order they were installed; lane files that space
 * reclamation retires stay until every version installed before the retirement is released.
 */
internal class Version(
    val active: Memtable,
    val frozen: List<Memtable>,
    val postingSegments: List<SegmentHandle>,
    val fragmentSegments: List<SegmentHandle>,
    val typeSegments: List<SegmentHandle>,
    val keySegments: List<SegmentHandle>,
    val flushedLsn: Long,
) {
    private val refs = AtomicInteger(1)

    /** Install order, set by [VersionSet] before the version is visible. */
    @Volatile
    var seq: Long = 0
        internal set

    @Volatile
    internal var set: VersionSet? = null

    init {
        for (s in postingSegments) s.retain()
        for (s in fragmentSegments) s.retain()
        for (s in typeSegments) s.retain()
        for (s in keySegments) s.retain()
    }

    /** Takes a reference unless the version was already released for good. */
    fun tryRetain(): Boolean {
        while (true) {
            val n = refs.get()
            if (n == 0) return false
            if (refs.compareAndSet(n, n + 1)) return true
        }
    }

    fun release() {
        val n = refs.decrementAndGet()
        check(n >= 0) { "version released too often" }
        if (n == 0) {
            for (s in postingSegments) s.release()
            for (s in fragmentSegments) s.release()
            for (s in typeSegments) s.release()
            for (s in keySegments) s.release()
            set?.released(this)
        }
    }

    /** Memtables newest first. */
    val memtables: List<Memtable> get() = listOf(active) + frozen.asReversed()

    /** The segments of index [indexKind] ([SegmentFormat]), oldest first. */
    fun segments(indexKind: Int): List<SegmentHandle> = when (indexKind) {
        SegmentFormat.INDEX_POSTING -> postingSegments
        SegmentFormat.INDEX_FRAGMENT -> fragmentSegments
        SegmentFormat.INDEX_KEY -> keySegments
        else -> typeSegments
    }

    /** This version with the segments of index [indexKind] replaced by [segments]. */
    fun withSegments(indexKind: Int, segments: List<SegmentHandle>, active: Memtable = this.active, frozen: List<Memtable> = this.frozen, flushedLsn: Long = this.flushedLsn): Version = Version(
        active, frozen,
        if (indexKind == SegmentFormat.INDEX_POSTING) segments else postingSegments,
        if (indexKind == SegmentFormat.INDEX_FRAGMENT) segments else fragmentSegments,
        if (indexKind == SegmentFormat.INDEX_TYPE) segments else typeSegments,
        if (indexKind == SegmentFormat.INDEX_KEY) segments else keySegments,
        flushedLsn,
    )

    fun fragmentIndex(): FragmentIndexView = FragmentIndexView(this)
}

/**
 * The current [Version]. Readers [acquire] it without locking; changes are serialized by callers. It also
 * knows which versions haven't been released yet ([oldestLiveSeq]) and calls [onReleased] with each one released.
 */
internal class VersionSet(initial: Version) {
    private val current = AtomicReference(initial)
    private val live = ConcurrentSkipListMap<Long, Version>()
    private var lastSeq = 0L

    /** Runs on the releasing thread after a version was released for good. */
    @Volatile
    var onReleased: (Version) -> Unit = {}

    init {
        register(initial)
    }

    fun current(): Version = current.get()

    /** The current version, retained; release it when done. */
    fun acquire(): Version {
        while (true) {
            val v = current.get()
            if (v.tryRetain()) return v
        }
    }

    /** Makes [next] (with its initial reference) current and releases the previous one. */
    fun install(next: Version) {
        synchronized(this) { register(next) }
        current.getAndSet(next).release()
    }

    /** [Version.seq] of the oldest version not released yet; the current one never is. */
    fun oldestLiveSeq(): Long = live.firstKey()

    private fun register(version: Version) {
        version.seq = ++lastSeq
        version.set = this
        live[version.seq] = version
    }

    internal fun released(version: Version) {
        live.remove(version.seq)
        onReleased(version)
    }
}

/** Lookups in the fragment index. */
internal interface FragmentIndex {
    fun find(docId: Long, pre: Long, kind: Int, seq: Int): FragmentEntry?

    /** The entry with the greatest key ≤ `(docId, pre, kind, seq)` in document [docId], or null. */
    fun floor(docId: Long, pre: Long, kind: Int, seq: Int): FragmentEntry?

    /** Entries of [docId] with keys in `[(fromPre, fromKind, 0), (toPre, any, any)]`, in key order, each key once. */
    fun range(docId: Long, fromPre: Long, fromKind: Int, toPre: Long): Iterable<FragmentEntry>
}

/**
 * The fragment index of one [Version]: memtables and segments. A key may be in several sources: with the same value
 * when a replay after a mid-document flush indexed fragments again, or with a newer location after space
 * reclamation relocated the fragment. The newest source holding a key answers it: memtables newest first, then
 * segments newest first. Segments are skipped by docId range and by the bloom filter over docIds. Thread-safe;
 * allocates a cursor per segment lookup.
 */
internal class FragmentIndexView(private val version: Version) : FragmentIndex {
    override fun find(docId: Long, pre: Long, kind: Int, seq: Int): FragmentEntry? {
        for (m in version.memtables) m.fragments.find(docId, pre, kind, seq)?.let { return it }
        val segments = version.fragmentSegments
        if (segments.isEmpty()) return null
        val key = FragmentSegments.key(docId, pre, kind, seq)
        val hash = FragmentSegments.bloomHash(docId)
        for (i in segments.indices.reversed()) {
            val segment = segments[i].reader
            if (!covers(segment, docId, hash)) continue
            val cursor = segment.cursor()
            if (cursor.seekCeiling(key) && cursor.key.size == key.size && Arrays.equals(cursor.key.array, 0, key.size, key, 0, key.size)) {
                return FragmentSegments.decode(cursor, segment.source)
            }
        }
        return null
    }

    override fun floor(docId: Long, pre: Long, kind: Int, seq: Int): FragmentEntry? {
        var best: FragmentEntry? = null
        for (m in version.memtables) {
            val e = m.fragments.floor(docId, pre, kind, seq) ?: continue
            if (best == null || e > best) best = e
        }
        val segments = version.fragmentSegments
        if (segments.isEmpty()) return best
        val key = FragmentSegments.key(docId, pre, kind, seq)
        val hash = FragmentSegments.bloomHash(docId)
        // Newest first, and only a greater key replaces the best: an equal key keeps the newest source's location.
        for (i in segments.indices.reversed()) {
            val segment = segments[i].reader
            if (!covers(segment, docId, hash)) continue
            val cursor = segment.cursor()
            if (!cursor.seekFloor(key)) continue
            if (BigEndian.getLong(cursor.key.array, 0) != docId) continue
            val e = FragmentSegments.decode(cursor, segment.source)
            if (best == null || e > best) best = e
        }
        return best
    }

    override fun range(docId: Long, fromPre: Long, fromKind: Int, toPre: Long): Iterable<FragmentEntry> {
        val memtables = version.memtables
        val segments = version.fragmentSegments
        if (memtables.size == 1 && segments.isEmpty()) return memtables[0].fragments.range(docId, fromPre, fromKind, toPre)
        return Iterable {
            // Newest first: memtables, then segments; of equal keys the first source's entry is kept.
            val sources = ArrayList<Iterator<FragmentEntry>>()
            for (m in memtables) sources += m.fragments.range(docId, fromPre, fromKind, toPre).iterator()
            if (segments.isNotEmpty()) {
                val from = FragmentSegments.key(docId, fromPre, fromKind, 0)
                val to = FragmentSegments.key(docId, toPre, FragmentEntry.MAX_KIND, FragmentEntry.MAX_SEQ)
                val hash = FragmentSegments.bloomHash(docId)
                for (i in segments.indices.reversed()) {
                    val segment = segments[i].reader
                    if (covers(segment, docId, hash)) sources += SegmentRange(segment, from, to)
                }
            }
            MergedRange(sources)
        }
    }

    /** One segment's entries with keys in `[from, to]`, in key order. */
    private class SegmentRange(private val segment: SegmentReader, from: ByteArray, private val to: ByteArray) : Iterator<FragmentEntry> {
        private val cursor = segment.cursor()
        private var ok = cursor.seekCeiling(from) && inRange()

        private fun inRange() = Arrays.compareUnsigned(cursor.key.array, 0, cursor.key.size, to, 0, to.size) <= 0

        override fun hasNext(): Boolean = ok

        override fun next(): FragmentEntry {
            if (!ok) throw NoSuchElementException()
            val entry = FragmentSegments.decode(cursor, segment.source)
            ok = cursor.next() && inRange()
            return entry
        }
    }

    /** A merge of sources in key order, newest first, giving each key once, from the newest source that holds it. */
    private class MergedRange(private val sources: List<Iterator<FragmentEntry>>) : Iterator<FragmentEntry> {
        private val heads = arrayOfNulls<FragmentEntry>(sources.size)

        init {
            for (i in sources.indices) heads[i] = if (sources[i].hasNext()) sources[i].next() else null
        }

        override fun hasNext(): Boolean = heads.any { it != null }

        override fun next(): FragmentEntry {
            var best = -1
            for (i in heads.indices) {
                val head = heads[i] ?: continue
                if (best < 0 || head < heads[best]!!) best = i
            }
            if (best < 0) throw NoSuchElementException()
            val entry = heads[best]!!
            for (i in heads.indices) {
                val head = heads[i] ?: continue
                if (head.compareTo(entry) == 0) heads[i] = if (sources[i].hasNext()) sources[i].next() else null
            }
            return entry
        }
    }

    private fun covers(segment: SegmentReader, docId: Long, hash: Long): Boolean =
        docId >= segment.stats.minDocId && docId <= segment.stats.maxDocId && segment.mayContain(hash)
}

/**
 * What flushes and merges of the fragment index learn about lane bytes: entries whose lane file was reclaimed
 * are left out, and the entries left out because their docId is hidden are reported, so the bytes they located can be
 * counted as dead.
 */
internal interface FragmentDrops {
    /**
     * Whether [entry] locates bytes in a reclaimed lane file. Such an entry is left out: a newer source holds the key's
     * relocated entry, or its docId is hidden.
     */
    fun isReclaimed(entry: FragmentEntry): Boolean

    /** [entry], of a hidden docId, was left out. */
    fun dropped(entry: FragmentEntry)
}

/**
 * Fragment-index segments: key = the 21-byte fragment key `docId | pre | kind | seq` (big-endian),
 * value = `location:varint | length:varint | payloadOffset:varint | rawLength:varint` ([FragmentEntry]). The bloom
 * filter holds the docIds, so lookups skip segments without the document.
 */
internal object FragmentSegments {
    const val KEY_BYTES: Int = 21

    fun key(docId: Long, pre: Long, kind: Int, seq: Int): ByteArray {
        val key = ByteArray(KEY_BYTES)
        BigEndian.putLong(key, 0, docId)
        BigEndian.putLong(key, 8, pre)
        key[16] = kind.toByte()
        BigEndian.putInt(key, 17, seq)
        return key
    }

    fun bloomHash(docId: Long): Long {
        val bytes = ByteArray(8)
        BigEndian.putLong(bytes, 0, docId)
        return Xxh3.hash64(bytes, 0, 8, 0L)
    }

    fun decode(cursor: SegmentCursor, source: String): FragmentEntry {
        val k = cursor.key.array
        if (cursor.key.size != KEY_BYTES || cursor.valueExternal) throw CorruptionException(source, "fragment-index entry of ${cursor.key.size} key bytes")
        val docId = BigEndian.getLong(k, 0)
        val pre = BigEndian.getLong(k, 8)
        val kind = k[16].toInt() and 0xFF
        val seq = BigEndian.getInt(k, 17)
        val r = ByteReader(cursor.valueBytes, cursor.valueOffset, cursor.valueLength, source)
        val location = r.readVarLong()
        val length = r.readVarInt()
        val payloadOffset = r.readVarInt(length)
        val rawLength = r.readVarInt()
        if (r.remaining != 0 || docId < 0 || pre < 0 || location < 0) throw CorruptionException(source, "fragment-index entry ($docId, $pre, $kind, $seq)")
        return FragmentEntry(docId, pre, kind, seq, location, length, payloadOffset, rawLength)
    }

    /** Writes [memtable] as a segment without the entries of [hidden] docIds or those [drops] reports reclaimed; returns its stats. */
    fun write(memtable: FragmentIndexMemtable, file: FileIo, blockBytes: Int, hidden: HiddenDocIds = HiddenDocIds.NONE, drops: FragmentDrops? = null): SegmentStats =
        write(memtable.entries().iterator(), file, blockBytes, hidden, null, drops)

    /**
     * Writes the entries of [memtable] and [segments] as one segment, each key once from the newest source holding it
     * (the memtable, then segments newest first) and none of [hidden] docIds or reclaimed by [drops]; returns its stats.
     * Memory: one block per segment, and the bloom filter.
     */
    fun write(memtable: FragmentIndexMemtable, segments: List<SegmentReader>, file: FileIo, blockBytes: Int, hidden: HiddenDocIds = HiddenDocIds.NONE, drops: FragmentDrops? = null): SegmentStats {
        val sources = ArrayList<Iterator<FragmentEntry>>(segments.size + 1)
        sources += memtable.entries().iterator()
        for (s in segments.asReversed()) sources += entries(s)
        return write(merge(sources), file, blockBytes, hidden, null, drops)
    }

    /** Compaction: writes the entries of [segments] as one segment, like the flush merge; [control] may stop it. */
    fun merge(segments: List<SegmentReader>, file: FileIo, blockBytes: Int, hidden: HiddenDocIds, control: MergeControl, drops: FragmentDrops? = null): SegmentStats =
        write(merge(segments.asReversed().map { entries(it) }), file, blockBytes, hidden, control, drops)

    /** Every entry of [segment] in key order. */
    fun entries(segment: SegmentReader): Iterator<FragmentEntry> = object : Iterator<FragmentEntry> {
        private val cursor = segment.cursor()
        private var valid = cursor.first()

        override fun hasNext(): Boolean = valid

        override fun next(): FragmentEntry {
            if (!valid) throw NoSuchElementException()
            return decode(cursor, segment.source).also { valid = cursor.next() }
        }
    }

    /** Merges sorted [sources] into key order; a key present in several sources comes once, from the first of them. */
    private fun merge(sources: List<Iterator<FragmentEntry>>): Iterator<FragmentEntry> = object : Iterator<FragmentEntry> {
        private val heads = arrayOfNulls<FragmentEntry>(sources.size)
        private val heap = java.util.PriorityQueue<Int>(maxOf(1, sources.size)) { a, b ->
            val c = heads[a]!!.compareTo(heads[b]!!)
            if (c != 0) c else a.compareTo(b)
        }
        private var last: FragmentEntry? = null
        private var pending: FragmentEntry? = null

        init {
            for (i in sources.indices) advance(i)
        }

        private fun advance(i: Int) {
            val source = sources[i]
            if (!source.hasNext()) return
            val e = source.next()
            val previous = heads[i]
            if (previous != null && e <= previous) throw CorruptionException("fragment index", "entries out of order at (${e.docId}, ${e.pre}, ${e.kind}, ${e.seq})")
            heads[i] = e
            heap.add(i)
        }

        override fun hasNext(): Boolean {
            while (pending == null && heap.isNotEmpty()) {
                val i = heap.poll()
                val e = heads[i]!!
                advance(i)
                if (last == null || last!!.compareTo(e) != 0) pending = e
                last = e
            }
            return pending != null
        }

        override fun next(): FragmentEntry {
            if (!hasNext()) throw NoSuchElementException()
            return pending!!.also { pending = null }
        }
    }

    /** Writes [entries] (in key order); a merge ([control] given) derives its bloom filter from the keys it wrote. */
    private fun write(entries: Iterator<FragmentEntry>, file: FileIo, blockBytes: Int, hidden: HiddenDocIds, control: MergeControl?, drops: FragmentDrops?): SegmentStats {
        val writer = SegmentWriter(file, SegmentFormat.INDEX_FRAGMENT, blockBytes, bloomKey = if (control != null) BloomKey.docIdAt(0) else null)
        val value = GrowableBytes(32)
        var count = 0L
        var minDocId = Long.MAX_VALUE
        var maxDocId = -1L
        var hiddenDocId = -1L
        for (e in entries) {
            if (drops != null && drops.isReclaimed(e)) continue
            if (e.docId == hiddenDocId || (e.docId != maxDocId && hidden.contains(e.docId))) {
                if (e.docId != hiddenDocId) {
                    hiddenDocId = e.docId
                    control?.checkContinue()
                }
                drops?.dropped(e)
                continue
            }
            val key = key(e.docId, e.pre, e.kind, e.seq)
            if (e.docId != maxDocId) {
                if (control == null) writer.addBloomHash(bloomHash(e.docId)) else control.checkContinue()
            }
            value.clear()
            value.writeVarLong(e.location)
            value.writeVarLong(e.length.toLong())
            value.writeVarLong(e.payloadOffset.toLong())
            value.writeVarLong(e.rawLength.toLong())
            writer.add(key, 0, key.size, value.array, 0, value.size)
            count++
            minDocId = minOf(minDocId, e.docId)
            maxDocId = e.docId
        }
        val stats = SegmentStats(count, count, if (count == 0L) 0 else minDocId, maxDocId, hidden.countInRange(minDocId, maxDocId))
        writer.finish(stats)
        return stats
    }
}

/** Posting-index segments: key = term, value = its [PostingListWriter] list; bloom over terms. */
internal object PostingSegments {
    fun bloomHash(term: ByteArray): Long = Xxh3.hash64(term, 0, term.size, 0L)

    /**
     * Writes [memtable] as a segment, runs of one `(term, docId)` as consecutive groups, without the runs of [hidden]
     * docIds; returns its stats.
     */
    fun write(memtable: PostingMemtable, file: FileIo, blockBytes: Int, hidden: HiddenDocIds = HiddenDocIds.NONE): SegmentStats {
        val writer = SegmentWriter(file, SegmentFormat.INDEX_POSTING, blockBytes)
        val list = PostingListWriter()
        val value = GrowableBytes(1024)
        var term: ByteArray? = null
        var terms = 0L
        var postings = 0L
        var minDocId = Long.MAX_VALUE
        var maxDocId = -1L

        fun finishTerm() {
            val t = term ?: return
            value.clear()
            list.finish(value)
            writer.addBloomHash(bloomHash(t))
            writer.add(t, 0, t.size, value.array, 0, value.size)
            terms++
            postings += list.postingCount
        }

        for (run in memtable.allRuns()) {
            if (hidden.contains(run.docId)) continue
            if (term == null || !run.hasTerm(term)) {
                finishTerm()
                term = run.term
                list.reset()
            }
            list.addRun(run.docId, run.count, run.entries, 0, run.entries.size)
            minDocId = minOf(minDocId, run.docId)
            maxDocId = maxOf(maxDocId, run.docId)
        }
        finishTerm()
        val stats = SegmentStats(terms, postings, if (terms == 0L) 0 else minDocId, maxDocId, hidden.countInRange(minDocId, maxDocId))
        writer.finish(stats)
        return stats
    }
}
