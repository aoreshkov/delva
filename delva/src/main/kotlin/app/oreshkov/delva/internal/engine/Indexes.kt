package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.internal.bits.DocIdSet
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.discard
import app.oreshkov.delva.internal.io.uninterruptibly
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.ManifestSegment
import app.oreshkov.delva.internal.log.ManifestWatermark
import app.oreshkov.delva.internal.lsm.ByteCache
import app.oreshkov.delva.internal.lsm.FragmentSegments
import app.oreshkov.delva.internal.lsm.HiddenDocIds
import app.oreshkov.delva.internal.lsm.KeySegments
import app.oreshkov.delva.internal.lsm.Memtable
import app.oreshkov.delva.internal.lsm.PostingSegments
import app.oreshkov.delva.internal.lsm.SegmentFormat
import app.oreshkov.delva.internal.lsm.SegmentHandle
import app.oreshkov.delva.internal.lsm.SegmentReader
import app.oreshkov.delva.internal.lsm.SegmentStats
import app.oreshkov.delva.internal.lsm.TypeSegments
import app.oreshkov.delva.internal.lsm.Version
import app.oreshkov.delva.internal.lsm.VersionSet
import app.oreshkov.delva.internal.store.LogFragmentStore
import app.oreshkov.delva.internal.types.TypeDictionary
import app.oreshkov.delva.internal.visibility.Visibility
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

/**
 * The index watermark of one in-flight large insert: every posting of the fragments before lane position
 * `(fileNo, offset)` is in a memtable; `fileNo` 0 when none is known to be. Its lane advances it under the memtable
 * read lock after inserting a run; a freeze reads it under the write lock.
 */
internal class LaneWatermark(val docId: Long) {
    @Volatile
    var fileNo: Long = 0
        private set

    @Volatile
    var offset: Long = 0
        private set

    /** Moves the watermark to the start of the fragment record at [location]. */
    fun advanceTo(location: Long) {
        fileNo = LogFragmentStore.fileNoOf(location)
        offset = LogFragmentStore.offsetOf(location)
    }
}

/**
 * A frozen memtable with what the flush must record in the MANIFEST, all as of control-log sequence number [lsn]:
 * the watermarks of in-flight large inserts, where committed bytes end in each lane file, the aborted and deleted docIds
 * and the number of type names.
 */
internal class FrozenMemtable(
    val memtable: Memtable,
    val lsn: Long,
    val watermarks: List<ManifestWatermark>,
    val committedEnd: Map<Long, Long>,
    val aborted: DocIdSet,
    val deleted: DocIdSet,
    /** Type names defined by then; the first [typeNames] names of the dictionary. */
    val typeNames: Int,
)

/**
 * The engine's LSM indexes: the current [Version] of memtables and segments, the block and
 * fragment caches, the flush pipeline and the [compactor].
 *
 * - **Writes** to the active memtable run under the memtable read lock ([withActive]); a **freeze** takes the write
 *   lock, so it swaps memtables and captures the watermarks of in-flight large inserts atomically and never
 *   waits for a client stream.
 * - **Flush** (on the flush thread, or inline without one): write the posting, fragment, type and key segments that have
 *   entries to `*.tmp`, leaving out the entries of docIds aborted or deleted by the freeze and fragment entries
 *   of reclaimed lane files, counting the lane bytes of the hidden fragment entries as discarded, sync, rename → the
 *   type dictionary file if names were added → sync the directory → the aborted and the deleted docIds
 *   as bitmap files, each if it changed → a MANIFEST with the new segments, `indexedUpToLsn`, watermarks,
 *   committed lane ends, discarded lane bytes and the files → install a version without the frozen memtable whose
 *   [Version.flushedLsn] is the freeze LSN → ask for a compaction and space reclamation. A crash before the MANIFEST
 *   leaves orphan files that the next open deletes; the log still holds everything.
 * - **Compaction** ([Compactor]) replaces a run of adjacent segments of one index with one segment. The MANIFEST lists
 *   segments oldest first within each index, so the replacement takes the run's place (type segments are ordered by
 *   age).
 * - Any flush error fails the engine.
 *
 * Thread-safe. Changes that write a MANIFEST (flush and compaction commits) and the claims of segments to rewrite are
 * serialized by the commit lock; version changes (freeze, install) by the version lock, taken after it.
 */
internal class Indexes(
    internal val files: LogFiles,
    internal val io: FileSystemIo,
    internal val options: EngineOptions,
    internal val health: EngineHealth,
    internal val blockCache: ByteCache,
    initial: Version,
    persistedAborted: DocIdSet,
    persistedDeleted: DocIdSet,
    private val dictionary: TypeDictionary,
    private var persistedTypeNames: Int,
) {
    @PublishedApi
    internal val memtableLock: ReentrantReadWriteLock = ReentrantReadWriteLock()
    private val versionLock = Any()
    private val commitLock = ReentrantLock()
    val versions = VersionSet(initial)

    private val abortedFile = BitmapFile(LogFiles.BITMAP_ABORTED, persistedAborted)
    private val deletedFile = BitmapFile(LogFiles.BITMAP_DELETED, persistedDeleted)
    private var previousTypeDictionaryFileNo = 0L
    private val queue = LinkedBlockingQueue<FrozenMemtable>()
    private var thread: Thread? = null
    private val idleLock = ReentrantLock()
    private val idle = idleLock.newCondition()
    private var pending = 0

    /**
     * The aborted and deleted docIds the current MANIFEST's bitmap files hold: what compaction leaves out. Recovery hides
     * them whatever happens to the log after it, so dropping their entries can't be undone by a crash.
     */
    @Volatile
    var persistedHidden: HiddenDocIds = HiddenDocIds(persistedAborted, persistedDeleted)
        private set

    val compactor: Compactor = Compactor(this, options.compaction, options.clock)

    /** Fragment segments merged away by flushes; for tests. */
    @Volatile
    var fragmentMerges: Long = 0
        private set

    /** Flushes completed; for tests. */
    @Volatile
    var flushes: Long = 0
        private set

    /** Segment bytes flushes wrote; with [Compactor.outputBytes], the write amplification of the indexes. */
    @Volatile
    var flushedBytes: Long = 0
        private set

    /** Handles of every segment opened, to close them with the store. */
    private val opened = ConcurrentLinkedQueue<SegmentHandle>()

    val active: Memtable get() = versions.current().active

    val frozenCount: Int get() = versions.current().frozen.size

    private val roomLock = ReentrantLock()
    private val roomChanged = roomLock.newCondition()

    /** Times an insert waited for memtable room; for tests. */
    @Volatile
    var memtableWaits: Long = 0
        private set

    /** Largest memtable heap estimate (active and frozen) seen by an insert; for tests. */
    @Volatile
    var peakMemtableBytes: Long = 0
        private set

    /** Estimated heap of the active and frozen memtables. */
    val memtableBytes: Long
        get() {
            val v = versions.current()
            var bytes = v.active.approximateBytes
            for (m in v.frozen) bytes += m.approximateBytes
            return bytes
        }

    /** Bytes of every live segment file; for tests and diagnostics. */
    val segmentBytes: Long
        get() {
            val v = versions.current()
            return (v.postingSegments + v.fragmentSegments + v.typeSegments + v.keySegments).sumOf { it.fileBytes }
        }

    /**
     * Back-pressure: waits while the memtables hold more than the memtable budget, asking for a flush with
     * [requestFlush] (which must not block) until one makes room. Called by inserts before they add to the memtables,
     * never while holding the memtable lock, so a freeze never waits for them. Throws once the engine fails or closes.
     */
    fun awaitMemtableRoom(requestFlush: () -> Unit) {
        val budget = options.memtableBudgetBytes
        var bytes = memtableBytes
        if (bytes > peakMemtableBytes) peakMemtableBytes = bytes
        if (bytes <= budget) return
        roomLock.withLock {
            var waited = false
            while (true) {
                bytes = memtableBytes
                if (bytes <= budget) return
                health.checkUsable()
                requestFlush()
                if (!waited) {
                    waited = true
                    memtableWaits++
                }
                // A flush signals when it installs its version; the timeout re-checks the engine's state.
                roomChanged.await(ROOM_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun signalRoom() = roomLock.withLock { roomChanged.signalAll() }

    fun track(handle: SegmentHandle) {
        // Merged segments are deleted once unpinned; don't keep their readers reachable.
        opened.removeIf { it.deleted }
        opened += handle
    }

    /** Runs [block] with the active memtable; no freeze happens meanwhile. */
    inline fun <T> withActive(block: (Memtable) -> T): T = memtableLock.read { block(versions.current().active) }

    fun startFlushThread() {
        thread = Thread.ofPlatform().name("delva-flush").daemon().start {
            while (true) {
                val frozen = queue.take()
                if (frozen.memtable === STOP) break
                flushSafely(frozen)
            }
        }
    }

    /**
     * Freezes the active memtable (unless it's empty and not [allowEmpty]): a new version gets a fresh active memtable,
     * the old one joins the frozen list, and [capture] reads the watermarks under the write lock. Called by the writer
     * between batches, after the log is durable up to [lsn]. Flushing an empty memtable still records [lsn] as indexed
     * and the aborted and deleted docIds of [visibility], which shortens the next replay.
     */
    fun freeze(lsn: Long, committedEnd: Map<Long, Long>, visibility: Visibility, typeNames: Int, allowEmpty: Boolean, capture: () -> List<ManifestWatermark>): FrozenMemtable? =
        memtableLock.write {
            synchronized(versionLock) {
                val v = versions.current()
                if (v.active.isEmpty && !allowEmpty) return null
                val frozen = FrozenMemtable(v.active, lsn, capture(), committedEnd, visibility.aborted, visibility.deleted, typeNames)
                versions.install(Version(Memtable(), v.frozen + v.active, v.postingSegments, v.fragmentSegments, v.typeSegments, v.keySegments, v.flushedLsn))
                frozen
            }
        }

    /** Flushes [frozen] on the flush thread, or right away when there is none. */
    fun submit(frozen: FrozenMemtable) {
        idleLock.withLock { pending++ }
        if (thread != null) queue.put(frozen) else flushSafely(frozen)
    }

    /** Waits until every submitted flush has finished. */
    fun awaitFlushes() {
        idleLock.withLock {
            while (pending > 0) idle.await()
        }
    }

    /** Stops compaction (a running one is abandoned), then the flush thread after the queued flushes; closes every segment. */
    fun close() {
        compactor.stop()
        thread?.let {
            uninterruptibly { queue.put(FrozenMemtable(STOP, 0, emptyList(), emptyMap(), DocIdSet.EMPTY, DocIdSet.EMPTY, 0)) }
            uninterruptibly { it.join() }
        }
        for (h in opened) runCatching { h.close() }
        signalRoom()
    }

    private fun flushSafely(frozen: FrozenMemtable) {
        try {
            if (!health.failed) flush(frozen)
        } catch (t: Throwable) {
            health.fail(t)
        } finally {
            idleLock.withLock {
                pending--
                idle.signalAll()
            }
        }
    }

    private fun flush(frozen: FrozenMemtable) {
        val memtable = frozen.memtable
        val hidden = HiddenDocIds(frozen.aborted, frozen.deleted)
        val drops = LaneDrops(files)
        val blockBytes = options.segmentBlockBytes
        var posting: SegmentHandle? = null
        var fragment: SegmentHandle? = null
        var type: SegmentHandle? = null
        var key: SegmentHandle? = null
        if (memtable.postings.runCount > 0) {
            posting = writeSegment(SegmentFormat.INDEX_POSTING) { file -> PostingSegments.write(memtable.postings, file, blockBytes, hidden) }
        }
        // Only this thread appends segments or merges the newest fragment segments, and compaction never takes a claimed
        // segment, so the merged suffix is still the suffix when the version is installed.
        val merged = if (memtable.fragments.size > 0) claimFragmentsToMerge(memtable.fragments.size.toLong()) else emptyList()
        var committed = false
        try {
            if (memtable.fragments.size > 0) {
                fragment = writeSegment(SegmentFormat.INDEX_FRAGMENT) { file ->
                    if (merged.isEmpty()) {
                        FragmentSegments.write(memtable.fragments, file, blockBytes, hidden, drops)
                    } else {
                        FragmentSegments.write(memtable.fragments, merged.map { it.reader }, file, blockBytes, hidden, drops)
                    }
                }
            }
            if (!memtable.types.isEmpty) {
                type = writeSegment(SegmentFormat.INDEX_TYPE) { file -> TypeSegments.write(memtable.types, file, blockBytes, hidden) }
            }
            if (!memtable.keys.isEmpty) {
                key = writeSegment(SegmentFormat.INDEX_KEY) { file -> KeySegments.write(memtable.keys, file, blockBytes, hidden) }
            }
            // Names are only ever added, so a different count is a change.
            val dictionaryChanged = frozen.typeNames != persistedTypeNames
            val dictionaryFileNo = if (dictionaryChanged) writeTypeDictionary(dictionary.names(frozen.typeNames)) else files.index.typeDictionaryFileNo
            io.syncDirectory(files.indexDir)

            commitLock.withLock {
                val current = files.index
                val mergedFileNos = merged.mapTo(HashSet()) { it.fileNo }
                val added = listOfNotNull(posting, fragment, type, key).map { ManifestSegment(it.fileNo, it.reader.indexKind, it.fileBytes) }
                val abortedFileNo = abortedFile.fileFor(frozen.aborted, current.abortedFileNo)
                val deletedFileNo = deletedFile.fileFor(frozen.deleted, current.deletedFileNo)
                files.updateIndex(
                    LogFiles.IndexState(
                        frozen.lsn, abortedFileNo, dictionaryFileNo, deletedFileNo, current.segments.filter { it.fileNo !in mergedFileNos } + added, frozen.watermarks, frozen.committedEnd,
                        drops.addTo(current.discarded),
                    ),
                )
                committed = true
                abortedFile.installed(frozen.aborted, abortedFileNo, current.abortedFileNo)
                deletedFile.installed(frozen.deleted, deletedFileNo, current.deletedFileNo)
                persistedHidden = hidden
                if (dictionaryChanged) {
                    if (previousTypeDictionaryFileNo != 0L && previousTypeDictionaryFileNo != current.typeDictionaryFileNo) io.discard(files.typeDictionaryPath(previousTypeDictionaryFileNo), files.tmpDir)
                    previousTypeDictionaryFileNo = current.typeDictionaryFileNo
                    persistedTypeNames = frozen.typeNames
                }

                synchronized(versionLock) {
                    val v = versions.current()
                    val postingSegments = if (posting != null) v.postingSegments + posting else v.postingSegments
                    check(v.fragmentSegments.takeLast(merged.size) == merged) { "fragment segments changed during a flush" }
                    val fragmentSegments = v.fragmentSegments.dropLast(merged.size) + listOfNotNull(fragment)
                    val typeSegments = if (type != null) v.typeSegments + type else v.typeSegments
                    val keySegments = if (key != null) v.keySegments + key else v.keySegments
                    versions.install(Version(v.active, v.frozen.filter { it !== memtable }, postingSegments, fragmentSegments, typeSegments, keySegments, maxOf(v.flushedLsn, frozen.lsn)))
                }
            }
        } finally {
            if (!committed) commitLock.withLock { for (s in merged) s.claimed = false }
        }
        // Deleted once no search pins a version holding them.
        for (s in merged) s.markObsolete()
        fragmentMerges += merged.size
        flushedBytes += listOfNotNull(posting, fragment, type, key).sumOf { it.fileBytes }
        flushes++
        signalRoom()
        onIndexChanged()
        compactor.request()
    }

    /**
     * Claims the newest fragment segments to merge into the flush's segment, so the fragment index stays at O(log n)
     * segments whatever the number of flushes (a single huge document is flushed hundreds of times, and every fragment
     * lookup visits each segment holding its document). Binary-counter tiering: an older segment joins while it has no
     * more entries than the memtable and the newer segments already chosen, which bounds each entry's rewrites by
     * O(log n). Segments above [MAX_MERGED_FRAGMENT_ENTRIES], and those a compaction claimed, are left to compaction.
     */
    private fun claimFragmentsToMerge(memtableEntries: Long): List<SegmentHandle> = commitLock.withLock {
        val segments = versions.current().fragmentSegments
        var accumulated = memtableEntries
        var from = segments.size
        while (from > 0) {
            val segment = segments[from - 1]
            val entries = segment.reader.stats.entryCount
            if (segment.claimed || entries > accumulated || accumulated + entries > MAX_MERGED_FRAGMENT_ENTRIES) break
            accumulated += entries
            from--
        }
        segments.subList(from, segments.size).toList().onEach { it.claimed = true }
    }

    /** Runs [block] under the commit lock: nothing claims segments or changes the index meanwhile. */
    fun <T> withCommitLock(block: () -> T): T = commitLock.withLock(block)

    /**
     * Replaces [inputs], adjacent segments of index [indexKind] claimed by a compaction, with [output] (none when nothing
     * was left): a MANIFEST lists [output] where [inputs] were, with the lane bytes of the fragment-index entries the merge
     * [drops] counted as discarded, then a version does. The inputs are deleted once no search pins a version holding
     * them.
     */
    fun commitCompaction(indexKind: Int, inputs: List<SegmentHandle>, output: SegmentHandle?, drops: LaneDrops? = null) {
        commitLock.withLock {
            val current = files.index
            val inputFileNos = inputs.mapTo(HashSet()) { it.fileNo }
            val segments = ArrayList<ManifestSegment>(current.segments.size)
            var found = 0
            for (s in current.segments) {
                if (s.fileNo !in inputFileNos) {
                    segments += s
                } else if (found++ == 0 && output != null) {
                    segments += ManifestSegment(output.fileNo, indexKind, output.fileBytes)
                }
            }
            check(found == inputs.size) { "the MANIFEST lists $found of ${inputs.size} compacted segments" }
            synchronized(versionLock) {
                val list = versions.current().segments(indexKind)
                val from = list.indexOf(inputs[0])
                check(from >= 0 && from + inputs.size <= list.size && list.subList(from, from + inputs.size) == inputs) { "compacted segments aren't adjacent in the version" }
            }
            val discarded = drops?.addTo(current.discarded) ?: current.discarded
            files.updateIndex(LogFiles.IndexState(current.indexedUpToLsn, current.abortedFileNo, current.typeDictionaryFileNo, current.deletedFileNo, segments, current.watermarks, current.committedEnd, discarded))
            synchronized(versionLock) {
                val v = versions.current()
                val list = v.segments(indexKind)
                val from = list.indexOf(inputs[0])
                versions.install(v.withSegments(indexKind, list.subList(0, from) + listOfNotNull(output) + list.subList(from + inputs.size, list.size)))
            }
        }
        for (s in inputs) s.markObsolete()
        onIndexChanged()
    }

    /**
     * Installs a copy of the current version and returns its [Version.seq]. Space reclamation calls it after a
     * relocation: versions from before it may still read the old locations, later ones can't.
     */
    fun newVersion(): Long = synchronized(versionLock) {
        val v = versions.current()
        val next = Version(v.active, v.frozen, v.postingSegments, v.fragmentSegments, v.typeSegments, v.keySegments, v.flushedLsn)
        versions.install(next)
        next.seq
    }

    /** Runs after a flush or compaction committed: the discarded lane bytes may have grown. Must not block. */
    @Volatile
    var onIndexChanged: () -> Unit = {}

    /**
     * Writes a segment of [indexKind] with [write] to a temporary file, syncs it and renames it into place; returns
     * its handle, or null (and no file) when [write] wrote no entries.
     */
    private fun writeSegment(indexKind: Int, write: (FileIo) -> SegmentStats): SegmentHandle? {
        val fileNo = files.allocateFileNo()
        val path = files.segmentPath(fileNo, indexKind)
        val tmp = tmpOf(path)
        io.delete(tmp)
        val stats = io.open(tmp, OpenMode.CREATE_NEW).use { file ->
            write(file).also { file.force(true) }
        }
        // A type segment always holds its info entry; it's empty without forward entries.
        if ((if (indexKind == SegmentFormat.INDEX_TYPE) stats.itemCount else stats.entryCount) == 0L) {
            io.delete(tmp)
            return null
        }
        io.atomicRename(tmp, path)
        return open(fileNo, indexKind, path)
    }

    /** Opens a durable segment file and tracks its handle. */
    fun open(fileNo: Long, indexKind: Int, path: Path): SegmentHandle = openSegment(io, blockCache, fileNo, indexKind, path, files.tmpDir).also { track(it) }

    private fun writeTypeDictionary(names: List<String>): Long {
        val fileNo = files.allocateFileNo()
        val path = files.typeDictionaryPath(fileNo)
        val tmp = tmpOf(path)
        val bytes = TypeDictionary.encode(names)
        io.delete(tmp)
        io.open(tmp, OpenMode.CREATE_NEW).use { file ->
            file.write(0, bytes, 0, bytes.size)
            file.force(true)
        }
        io.atomicRename(tmp, path)
        return fileNo
    }

    /**
     * The bitmap file of one docId set of [kind], used only by flushes: what its newest file holds, and the file
     * of the MANIFEST before, which stays as that MANIFEST's fallback.
     */
    private inner class BitmapFile(private val kind: Int, private var persisted: DocIdSet) {
        private var previousFileNo = 0L

        /** The file that holds [set]: [currentFileNo], or a new one when the set changed. */
        fun fileFor(set: DocIdSet, currentFileNo: Long): Long =
            // DocIds are only ever added (they're never retired), so a different size is a change.
            if (set.size == persisted.size) currentFileNo else write(set)

        /** A MANIFEST now names [fileNo] instead of [replacedFileNo]: deletes the file two MANIFESTs back. */
        fun installed(set: DocIdSet, fileNo: Long, replacedFileNo: Long) {
            if (fileNo == replacedFileNo) return
            if (previousFileNo != 0L && previousFileNo != replacedFileNo) io.discard(files.bitmapPath(previousFileNo, kind), files.tmpDir)
            previousFileNo = replacedFileNo
            persisted = set
        }

        private fun write(set: DocIdSet): Long {
            val fileNo = files.allocateFileNo()
            val path = files.bitmapPath(fileNo, kind)
            val tmp = tmpOf(path)
            val bytes = set.encode()
            io.delete(tmp)
            io.open(tmp, OpenMode.CREATE_NEW).use { file ->
                file.write(0, bytes, 0, bytes.size)
                file.force(true)
            }
            io.atomicRename(tmp, path)
            io.syncDirectory(files.bitmapDir)
            return fileNo
        }
    }

    internal companion object {
        private val STOP = Memtable()

        /** Largest fragment segment a flush produces by merging. */
        const val MAX_MERGED_FRAGMENT_ENTRIES: Long = 4L shl 20

        private const val ROOM_WAIT_MILLIS: Long = 50

        fun tmpOf(path: Path): Path = path.resolveSibling("${path.fileName}.tmp")
    }
}

/**
 * Opens and validates the segment at [path] (for a type segment, its statistics too); closes the file on failure. Without
 * a [graveyard] the segment is never deleted (see [SegmentHandle]).
 */
internal fun openSegment(io: FileSystemIo, cache: ByteCache, fileNo: Long, indexKind: Int, path: Path, graveyard: Path?): SegmentHandle {
    val file = io.open(path, OpenMode.READ)
    try {
        return SegmentHandle(SegmentReader.open(fileNo, file, cache, indexKind), path, io, graveyard)
    } catch (t: Throwable) {
        file.close()
        throw t
    }
}
