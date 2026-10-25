package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.BlockingSecondaryJsonStore
import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.CreationOptionsMismatchException
import app.oreshkov.delva.DelvaException
import app.oreshkov.delva.EngineFailedException
import app.oreshkov.delva.LockHolder
import app.oreshkov.delva.StoreClosedException
import app.oreshkov.delva.StoreFormat
import app.oreshkov.delva.StoreNotFoundException
import app.oreshkov.delva.UnsupportedFormatException
import app.oreshkov.delva.internal.bits.DocIdSet
import app.oreshkov.delva.internal.bits.LongHashSet
import app.oreshkov.delva.internal.compress.DictionaryFiles
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.ReadOnlyFileSystemIo
import app.oreshkov.delva.internal.io.readFully
import app.oreshkov.delva.internal.log.LaneReaders
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.log.LogReaders
import app.oreshkov.delva.internal.log.Manifest
import app.oreshkov.delva.internal.log.ManifestFile
import app.oreshkov.delva.internal.log.ManifestStore
import app.oreshkov.delva.internal.lsm.Memtable
import app.oreshkov.delva.internal.lsm.SegmentFormat
import app.oreshkov.delva.internal.lsm.SegmentHandle
import app.oreshkov.delva.internal.lsm.Version
import app.oreshkov.delva.internal.lsm.VersionSet
import app.oreshkov.delva.internal.split.FileSystemSpillFiles
import app.oreshkov.delva.internal.split.SpillFiles
import app.oreshkov.delva.internal.store.FragmentStore
import app.oreshkov.delva.internal.store.LogFragmentStore
import app.oreshkov.delva.internal.types.TypeDictionary
import app.oreshkov.delva.internal.visibility.LateCommits
import app.oreshkov.delva.internal.visibility.Visibility
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A store read beside its writer (SPEC.md §8.1): the state the writer's recovery would rebuild from the same bytes,
 * with replay in place of the writer. It never changes anything in the directory: it reads through a
 * [ReadOnlyFileSystemIo], never opens `CURRENT` or the lock files, never maps files, and spills the JSON operands of
 * comparisons to a directory of its own under `java.io.tmpdir`, deleted when it closes.
 *
 * **State.** An *epoch* is what one MANIFEST describes: its segments, bitmap files and type dictionary, and a [Replay]
 * ([Replay.Tail.FOLLOW]) of the control log after its `indexedUpToLsn` into memtables of its own, with one [Version].
 * The view reads see is that version with the snapshot of the last replayed record. [refresh] continues the replay
 * when the newest MANIFEST describes the same indexes (adopting the files it lists anew) and opens a new epoch from
 * it otherwise: after a flush, a compaction, a recovery or a reset of the writer. Snapshots published before keep
 * reading the same memtables while the replay adds to them, so a replayed COMMIT is recorded in [LateCommits] before
 * its document is indexed.
 *
 * **Files.** The writer deletes files as it always does; a file stays readable here as long as a handle is open on
 * it. An epoch opens every file its MANIFEST lists before it uses any of them, and when one is gone, a newer MANIFEST
 * replaced that one, so the refresh starts over from the newest. An epoch keeps its handles until its version is
 * released, which is when no result reads it and a newer epoch is current.
 *
 * **Damage** is reported only where the writer had synced: a replay that ends below its MANIFEST's `lastLsn`, or a
 * record that recovery would refuse too. Anything after it that doesn't check out is a write in progress.
 *
 * Thread-safe; refreshes are serialized, reads never wait for one.
 */
internal class SecondaryEngine private constructor(
    options: EngineOptions,
    private val io: ReadOnlyFileSystemIo,
    val dir: Path,
    private val scratchIo: FileSystemIo,
    /** The file system [io] wraps, only to read the writer's advisory lock record ([writer]). */
    private val ownerIo: FileSystemIo,
) : ReadEngine(options), BlockingSecondaryJsonStore {
    private val files = SecondaryFiles(io, dir)
    private val controlDir = dir.resolve(LogFiles.CONTROL_DIR)
    private val dictionaryDir = dir.resolve(LogFiles.DICTIONARY_DIR)

    /** Where JSON operands of comparisons spill: created on first use, deleted at close. */
    private val scratchDir: Path = Path.of(System.getProperty("java.io.tmpdir")).resolve("delva-secondary-${UUID.randomUUID()}")
    override val spillFiles: SpillFiles = FileSystemSpillFiles(scratchIo, scratchDir)
    override val literalIo: FileSystemIo get() = scratchIo
    override val literalDir: Path get() = scratchDir
    override val secondary: Boolean get() = true

    private val refreshLock = ReentrantLock()
    private val closed = AtomicBoolean()

    /** The view reads use; replaced by [refresh]. */
    @Volatile
    private lateinit var published: Published

    /** The current epoch; changed under [refreshLock]. */
    private var epoch: Epoch? = null
    private var versions: VersionSet? = null

    /** Epochs by their version, until the version is released. */
    private val epochs = ConcurrentHashMap<Version, Epoch>()

    /** Every segment handle opened, to close them with the store. */
    private val opened = ConcurrentLinkedQueue<SegmentHandle>()

    /** Epochs opened and refreshes that changed the view; for tests. */
    @Volatile
    var epochsOpened: Long = 0
        private set

    /** What [Replay] counted over every epoch; for tests. */
    val stats = RecoveryStats()

    /** The calls the read-only file system refused; zero unless a bug tried to change the store. */
    val refusedCalls: Long get() = io.refused.get()

    /** The LSN of the view reads see; for tests and diagnostics. */
    val viewLsn: Long get() = published.snapshot.lsn

    /** A view: [snapshot] over [version], with the log files it lists and where its replay of the control log ended. */
    private class Published(
        val snapshot: Snapshot,
        val version: Version,
        val listed: List<ManifestFile>,
        val controlFileNo: Long,
        /** Where the replayed records of [controlFileNo] end; [Long.MAX_VALUE] once its SEAL was read. */
        val controlEnd: Long,
    )

    // ---- views ---------------------------------------------------------------------------------------------------

    override fun openView(): Pair<Snapshot, Version> {
        val view = acquire()
        return view.snapshot to view.version
    }

    /** The published view, its version retained. */
    private fun acquire(): Published {
        while (true) {
            checkUsable()
            val view = published
            if (view.version.tryRetain()) return view
            // A refresh replaced the view after it was read; the next read sees the new one.
        }
    }

    override fun currentSnapshot(): Snapshot = published.snapshot

    override fun fragmentsOf(version: Version): FragmentStore =
        LogFragmentStore(files, version.fragmentIndex(), fragmentCache, codecs, options.split.maxRecordBytes)

    override fun dictionaryOf(version: Version): TypeDictionary =
        (epochs[version] ?: throw StoreClosedException()).dictionary

    /** The log files the view lists, and its control log only as far as its replay read it. */
    override fun openScrubView(): ScrubView {
        val view = acquire()
        val lanes = view.listed.filter { !it.control }.mapTo(HashSet()) { it.fileNo }
        return ScrubView(view.snapshot, view.version, view.listed, files, files, { it in lanes }) { file, handle ->
            when {
                file.fileNo < view.controlFileNo -> handle.size()
                file.fileNo == view.controlFileNo -> minOf(handle.size(), view.controlEnd)
                else -> LogFormat.HEADER_BYTES.toLong()
            }
        }
    }

    // ---- refresh -------------------------------------------------------------------------------------------------

    /**
     * The writer's record of itself. It is read through [ownerIo]: a short read of a file the writer only ever creates
     * by renaming, at open, which waits out a reader that has it open on Windows ([app.oreshkov.delva.internal.io.replace]).
     */
    override fun writer(): LockHolder? {
        checkUsable()
        return StoreLock.holder(dir, ownerIo)
    }

    override fun refresh(): Boolean = refreshLock.withLock {
        checkUsable()
        catchUp()
    }

    /**
     * Brings the view up to what the store's files hold now; returns whether it changed. Up to [MAX_PASSES] passes
     * when a newer MANIFEST appeared during one (it may list the next control file, or a file a record names), and
     * up to [MAX_ATTEMPTS] starts when the writer deleted a file before it could be opened.
     */
    private fun catchUp(): Boolean {
        val before = if (epoch == null) null else published
        var rebuild = epoch?.broken == true
        var attempts = 0
        var passes = 0
        var lastChange: IOException? = null
        while (true) {
            if (++attempts > MAX_ATTEMPTS) throw IOException("the store in $dir changed faster than it could be read", lastChange)
            val current = epoch
            val target = try {
                val newest = newestManifest(io, dir) ?: throw StoreNotFoundException(dir)
                when {
                    current == null || rebuild || !current.adopts(newest) -> openEpoch(newest, current)
                    else -> current.also { it.adopt(newest) }
                }
            } catch (e: Changed) {
                lastChange = e.failure
                rebuild = true
                continue
            }
            try {
                DictionaryFiles.loadNew(io, dictionaryDir, codecs)
                target.follow()
            } catch (t: Throwable) {
                if (target !== current) target.discard() else target.broken = true
                when {
                    t is Changed -> lastChange = t.failure
                    // A record after the view that breaks the log's sequence: the writer restarted, and a new epoch tells.
                    t is CorruptionException && target === current -> {}
                    else -> throw t
                }
                rebuild = true
                continue
            }
            if (target !== current) install(target) else publish(target)
            if (++passes < MAX_PASSES && (highestManifestNo(io, dir) ?: 0L) > target.manifestNo) {
                rebuild = false
                continue
            }
            return published !== before
        }
    }

    /** Makes [next] the current epoch, its version the current one, and publishes its view. */
    private fun install(next: Epoch) {
        val previous = epoch
        epochs[next.version] = next
        publish(next)
        val set = versions
        if (set == null) {
            versions = VersionSet(next.version).also { it.onReleased = { version -> epochs.remove(version)?.release() } }
        } else {
            set.install(next.version)
        }
        epoch = next
        epochsOpened++
        // Segments the new epoch doesn't list close once no version holds them.
        if (previous != null) for ((fileNo, handle) in previous.segments) if (fileNo !in next.segments) handle.markObsolete()
    }

    /** Publishes the state [e]'s replay reached, unless the view already shows it. */
    private fun publish(e: Epoch) {
        val replay = e.replay
        val old = if (epoch === e) published else null
        if (old != null && old.snapshot.lsn == replay.lsn && old.listed === e.listing && old.controlFileNo == e.controlFileNo && old.controlEnd == e.controlEnd) return
        e.aborted = union(e.aborted, e.persistedAborted, replay.aborted)
        e.deleted = union(e.deleted, e.persistedDeleted, replay.deleted)
        val inFlight = LongArray(replay.begun.size)
        var i = 0
        for (docId in replay.begun) inFlight[i++] = docId
        val maxDocId = maxOf(e.manifest.nextDocId - 1, replay.maxDocId)
        val visibility = Visibility(maxDocId, inFlight, e.aborted, e.deleted, e.late, replay.lsn)
        published = Published(Snapshot(replay.lsn, visibility), e.version, e.listing, e.controlFileNo, e.controlEnd)
    }

    /** [set] (which holds [persisted]) with every docId of [replayed]; a replay never adds one [persisted] holds. */
    private fun union(set: DocIdSet, persisted: DocIdSet, replayed: LongHashSet): DocIdSet {
        if (set.size == persisted.size + replayed.size) return set
        val builder = set.builder()
        for (docId in replayed) builder.add(docId)
        return builder.build()
    }

    // ---- epochs --------------------------------------------------------------------------------------------------

    /**
     * Opens what [manifest] describes, reusing the segment handles of [previous]: every file it lists, its segments,
     * bitmap files and type dictionary. Throws [Changed] when a file is gone.
     */
    private fun openEpoch(manifest: Manifest, previous: Epoch?): Epoch {
        val creation = manifest.creationOptions()
        if (creation != options.creation) throw CreationOptionsMismatchException(dir, creation, options.creation)
        val retained = ArrayList<Long>()
        val segments = LinkedHashMap<Long, SegmentHandle>()
        val fresh = ArrayList<SegmentHandle>()
        try {
            for (file in manifest.files) {
                files.retain(file)
                retained += file.fileNo
            }
            val controls = manifest.files.filter { it.control }
            val created = controls.map { readHeader(it).createdLsn }
            // Skip control files whose records all precede indexedUpToLsn + 1: the next file starts at or before it.
            var first = 0
            while (first + 1 < controls.size && created[first + 1] <= manifest.indexedUpToLsn + 1) first++
            if (controls.isNotEmpty() && created[first] > manifest.indexedUpToLsn + 1) {
                throw CorruptionException(files.pathOf(controls[first].fileNo, true, 0).toString(), "starts at LSN ${created[first]}, after indexed LSN ${manifest.indexedUpToLsn}")
            }
            for (segment in manifest.segments) {
                val handle = previous?.segments?.get(segment.fileNo) ?: openSegment(segment.fileNo, segment.index).also { fresh += it }
                if (handle.reader.fileBytes != segment.fileBytes) throw CorruptionException(handle.path.toString(), "${handle.reader.fileBytes} bytes, the MANIFEST says ${segment.fileBytes}")
                segments[segment.fileNo] = handle
            }
            val aborted = readBitmap(manifest.abortedFileNo, LogFiles.BITMAP_ABORTED, manifest.nextDocId)
            val deleted = readBitmap(manifest.deletedFileNo, LogFiles.BITMAP_DELETED, manifest.nextDocId)
            val dictionary = if (manifest.typeDictionaryFileNo == 0L) {
                TypeDictionary()
            } else {
                val path = dir.resolve(LogFiles.INDEX_DIR).resolve(LogFiles.typeDictionaryName(manifest.typeDictionaryFileNo))
                TypeDictionary.decode(readListedFile(path), path.toString())
            }
            DictionaryFiles.loadNew(io, dictionaryDir, codecs)
            val epoch = Epoch(manifest, segments, aborted, deleted, dictionary, retained, controls.subList(first, controls.size))
            if (controls.isNotEmpty()) epoch.replay.lsn = created[first] - 1 else epoch.replay.lsn = manifest.indexedUpToLsn
            return epoch
        } catch (t: Throwable) {
            files.release(retained)
            for (h in fresh) runCatching { h.close() }
            throw t
        }
    }

    private fun openSegment(fileNo: Long, index: Int): SegmentHandle {
        val path = dir.resolve(LogFiles.INDEX_DIR).resolve(LogFiles.segmentName(fileNo, index))
        val handle = try {
            openSegment(io, blockCache, fileNo, index, path, graveyard = null)
        } catch (e: NoSuchFileException) {
            throw Changed(e)
        } catch (e: AccessDeniedException) {
            throw Changed(e)
        }
        opened += handle
        return handle
    }

    private fun readHeader(file: ManifestFile): LogFormat.Header {
        val handle = files.reader(file.fileNo, file.control, file.laneId)
        val path = files.pathOf(file.fileNo, file.control, file.laneId).toString()
        if (handle.size() < LogFormat.HEADER_BYTES) throw CorruptionException(path, "invalid file header")
        val bytes = ByteArray(LogFormat.HEADER_BYTES)
        handle.readFully(0, bytes, 0, bytes.size)
        val header = LogFormat.readHeader(bytes, path)
        if (header == null || header.fileNo != file.fileNo || header.control != file.control || header.laneId != file.laneId) throw CorruptionException(path, "invalid file header")
        return header
    }

    /** The docId set of bitmap file [fileNo] of [kind], whose members are all below [endDocId]; empty for file 0. */
    private fun readBitmap(fileNo: Long, kind: Int, endDocId: Long): DocIdSet {
        if (fileNo == 0L) return DocIdSet.EMPTY
        val path = dir.resolve(LogFiles.BITMAP_DIR).resolve(LogFiles.bitmapName(fileNo, kind))
        return DocIdSet.decode(readListedFile(path), path.toString(), endDocId)
    }

    /** A file a MANIFEST lists, read whole; [Changed] when it's gone. */
    private fun readListedFile(path: Path): ByteArray = try {
        io.open(path, OpenMode.READ).use { file ->
            val size = file.size()
            if (size > Int.MAX_VALUE - 8) throw CorruptionException(path.toString(), "$size bytes")
            ByteArray(size.toInt()).also { file.readFully(0, it, 0, it.size) }
        }
    } catch (e: NoSuchFileException) {
        throw Changed(e)
    } catch (e: AccessDeniedException) {
        throw Changed(e)
    }

    /**
     * What one MANIFEST describes, and the replay of the control log after it; see the class comment. Its [retained]
     * file handles are released with its [version].
     */
    private inner class Epoch(
        val manifest: Manifest,
        val segments: Map<Long, SegmentHandle>,
        val persistedAborted: DocIdSet,
        val persistedDeleted: DocIdSet,
        val dictionary: TypeDictionary,
        private val retained: MutableList<Long>,
        controls: List<ManifestFile>,
    ) {
        private val memtable = Memtable()
        val late = LateCommits()
        val replay = Replay(
            files, memtable, manifest, persistedAborted, persistedDeleted, dictionary,
            segments.values.filter { it.reader.indexKind == SegmentFormat.INDEX_TYPE }, options, codecs, stats, Replay.Tail.FOLLOW, late,
        ).also { it.listLaneFiles(manifest.files) }

        val version = Version(
            memtable, emptyList(),
            segments.values.filter { it.reader.indexKind == SegmentFormat.INDEX_POSTING },
            segments.values.filter { it.reader.indexKind == SegmentFormat.INDEX_FRAGMENT },
            segments.values.filter { it.reader.indexKind == SegmentFormat.INDEX_TYPE },
            segments.values.filter { it.reader.indexKind == SegmentFormat.INDEX_KEY },
            manifest.indexedUpToLsn,
        )

        var aborted: DocIdSet = persistedAborted
        var deleted: DocIdSet = persistedDeleted

        /** The files the newest MANIFEST it adopted lists. */
        var listing: List<ManifestFile> = manifest.files
            private set
        var manifestNo: Long = manifest.manifestNo
            private set
        private var lastLsn = manifest.lastLsn

        /** Control files known, from the one holding `indexedUpToLsn + 1`. */
        private val controlFiles = TreeMap<Long, ManifestFile>().apply { for (f in controls) put(f.fileNo, f) }
        private var controlFile: ManifestFile? = null
        private var position = LogFormat.HEADER_BYTES.toLong()
        private var sealed = false

        /** Set when a replay failed midway: the next refresh opens a new epoch. */
        var broken = false

        val controlFileNo: Long get() = controlFile?.fileNo ?: 0L
        val controlEnd: Long get() = if (sealed) Long.MAX_VALUE else position

        /**
         * Whether [newer] describes the same indexes, so this epoch's replay can go on: the same `indexedUpToLsn`,
         * segments and bitmap and type dictionary files, still listing the control file being read, and nothing that
         * went back, as a reset makes it.
         */
        fun adopts(newer: Manifest): Boolean =
            newer.manifestNo >= manifestNo && newer.indexedUpToLsn == manifest.indexedUpToLsn && newer.lastLsn >= lastLsn &&
                newer.nextDocId >= manifest.nextDocId && newer.abortedFileNo == manifest.abortedFileNo &&
                newer.deletedFileNo == manifest.deletedFileNo && newer.typeDictionaryFileNo == manifest.typeDictionaryFileNo &&
                newer.segments.map { it.fileNo } == manifest.segments.map { it.fileNo } &&
                (controlFile == null || newer.files.any { it.fileNo == controlFile!!.fileNo })

        /** Opens the files [newer] lists that this epoch doesn't hold yet; throws [Changed] when one is gone. */
        fun adopt(newer: Manifest) {
            if (newer.manifestNo == manifestNo) return
            val added = ArrayList<ManifestFile>()
            for (file in newer.files) {
                if (file.fileNo in retained) continue
                files.retain(file)
                retained += file.fileNo
                added += file
                if (file.control) controlFiles[file.fileNo] = file
            }
            replay.listLaneFiles(added)
            listing = newer.files
            manifestNo = newer.manifestNo
            lastLsn = newer.lastLsn
        }

        /**
         * Replays the control log from where the last call stopped, as far as it holds complete records now, and on
         * into the next control file after each SEAL that this epoch knows. Repeats while the replay is below the
         * MANIFEST's `lastLsn`, which the writer had written before the MANIFEST; a replay that stays there is damage.
         */
        fun follow() {
            var passes = 0
            while (true) {
                followOnce()
                if (replay.lsn >= manifest.lastLsn) return
                if (++passes >= MAX_STUCK_PASSES) {
                    throw CorruptionException(controlDir.toString(), "the control log ends at LSN ${replay.lsn}, the MANIFEST says ${manifest.lastLsn} was durable")
                }
            }
        }

        private fun followOnce() {
            while (true) {
                var file = controlFile
                if (file == null) {
                    file = controlFiles.firstEntry()?.value ?: return
                    controlFile = file
                    position = LogFormat.HEADER_BYTES.toLong()
                } else if (sealed) {
                    // The writer creates the next file, and lists it, only after the SEAL.
                    file = controlFiles.higherEntry(file.fileNo)?.value ?: return
                    controlFile = file
                    position = LogFormat.HEADER_BYTES.toLong()
                    sealed = false
                }
                // Recovery only cuts what a reader never applies: a file shorter than what was read is another store.
                if (files.reader(file.fileNo, true, 0).size() < position) throw Changed(IOException("control file ${file.fileNo} shrank below $position"))
                val end = replay.scanControl(file, last = true, from = position)
                if (end >= 0) {
                    position = end
                    return
                }
                sealed = true
            }
        }

        /** A view of it was published: the epoch lives until its version is released. */
        fun release() {
            files.release(retained)
        }

        /** Never installed: releases what it opened; segments the current epoch doesn't share close. */
        fun discard() {
            files.release(retained)
            for ((fileNo, handle) in segments) if (epoch?.segments?.containsKey(fileNo) != true) handle.markObsolete()
            version.release()
        }
    }

    // ---- health and close ----------------------------------------------------------------------------------------

    override fun checkUsable() {
        if (closed.get()) throw StoreClosedException()
    }

    override val failed: Boolean get() = false

    /** Nothing fails a secondary for good: a refresh that fails leaves the view as it was. */
    override fun fail(cause: Throwable): DelvaException = EngineFailedException("A secondary read failed: $cause", cause)

    /** Releases every file handle; results still open fail with [StoreClosedException]. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        refreshLock.withLock {
            try {
                for (h in opened) runCatching { h.close() }
                files.close()
                codecs.close()
            } finally {
                deleteScratch()
            }
        }
    }

    private fun deleteScratch() {
        try {
            if (!scratchIo.exists(scratchDir)) return
            for (path in scratchIo.list(scratchDir)) scratchIo.delete(path)
            scratchIo.delete(scratchDir)
        } catch (e: IOException) {
            System.getLogger("app.oreshkov.delva").log(System.Logger.Level.WARNING, "deleting $scratchDir failed: $e")
        }
    }

    companion object {
        /** Starts of a refresh when files keep disappearing under it. */
        private const val MAX_ATTEMPTS = 100

        /** Passes of a refresh while newer MANIFESTs appear. */
        private const val MAX_PASSES = 2

        /** Passes over a control log that ends below its MANIFEST's `lastLsn`, before that is damage. */
        private const val MAX_STUCK_PASSES = 16

        /** The files the writer replaces or locks, which a secondary never opens. */
        private val WRITER_FILES = setOf(ManifestStore.CURRENT, LogFiles.LOCK_FILE, StoreLock.OWNER_FILE)

        /**
         * Opens the store in [dir] as a secondary over [io], with the options [options] makes of its [CreationOptions],
         * and reads it. Comparisons spill through [scratchIo].
         */
        fun open(dir: Path, io: FileSystemIo, scratchIo: FileSystemIo = JdkFileSystemIo(), options: (CreationOptions) -> EngineOptions): SecondaryEngine {
            val readOnly = ReadOnlyFileSystemIo(io, WRITER_FILES)
            var attempts = 0
            var manifest: Manifest
            while (true) {
                try {
                    manifest = newestManifest(readOnly, dir) ?: throw StoreNotFoundException(dir)
                    break
                } catch (e: Changed) {
                    if (++attempts >= MAX_ATTEMPTS) throw IOException("the store in $dir changed faster than it could be read", e.failure)
                }
            }
            val engine = SecondaryEngine(options(manifest.creationOptions()), readOnly, dir, scratchIo, io)
            try {
                engine.refresh()
            } catch (t: Throwable) {
                engine.close()
                throw t
            }
            return engine
        }

        /**
         * The format of the store in [dir], read from its newest MANIFEST the way [open] finds it: without the lock and
         * never through `CURRENT`, so beside a writer too. Null when [dir] holds no store. Reads nothing else and
         * changes nothing.
         */
        fun probe(dir: Path, io: FileSystemIo): StoreFormat? {
            val readOnly = ReadOnlyFileSystemIo(io, WRITER_FILES)
            var attempts = 0
            while (true) {
                try {
                    newestManifest(readOnly, dir) ?: return null
                    // A MANIFEST decodes only in the format version this version writes.
                    return StoreFormat(Manifest.FORMAT_VERSION, Manifest.SUPPORTED_VERSIONS)
                } catch (e: UnsupportedFormatException) {
                    return StoreFormat(e.version, e.supportedVersions)
                } catch (e: Changed) {
                    if (++attempts >= MAX_ATTEMPTS) throw IOException("the store in $dir changed faster than it could be read", e.failure)
                }
            }
        }

        /** The highest MANIFEST number in [dir], read or not; null when there is none. */
        private fun highestManifestNo(io: FileSystemIo, dir: Path): Long? = io.list(dir).mapNotNull { ManifestStore.manifestNo(it) }.maxOrNull()

        /**
         * The newest MANIFEST, never through `CURRENT`, which the writer replaces: the highest-numbered one that can be
         * read. A MANIFEST appears only complete (written, synced and renamed), numbers are never written twice, and
         * the files it lists exist until a newer one is current. Null when the directory holds no store.
         *
         * A busy writer may replace every MANIFEST the listing named before one is read, and a directory listing taken
         * while names are renamed in and deleted may name none of them; then, if the store is there (`CURRENT` exists,
         * which reads no file), it throws [Changed].
         */
        private fun newestManifest(io: FileSystemIo, dir: Path): Manifest? {
            val numbers = io.list(dir).mapNotNull { ManifestStore.manifestNo(it) }.sortedDescending()
            var failure: CorruptionException? = null
            for (no in numbers) {
                val path = dir.resolve(ManifestStore.nameOf(no))
                try {
                    val bytes = io.open(path, OpenMode.READ).use { file ->
                        val size = file.size()
                        if (size > MAX_MANIFEST_BYTES) throw CorruptionException(path.toString(), "$size bytes")
                        ByteArray(size.toInt()).also { file.readFully(0, it, 0, it.size) }
                    }
                    val manifest = Manifest.decode(bytes, path)
                    if (manifest.manifestNo != no) throw CorruptionException(path.toString(), "holds MANIFEST ${manifest.manifestNo}")
                    return manifest
                } catch (e: CorruptionException) {
                    failure = failure ?: e
                } catch (_: IOException) {
                    // Superseded and deleted since the listing: an older one may still answer, a newer one does next time.
                }
            }
            if (failure != null) throw failure
            if (numbers.isEmpty() && !io.exists(dir.resolve(ManifestStore.CURRENT))) return null
            throw Changed(IOException("every MANIFEST of $dir was replaced while it was read"))
        }

        private const val MAX_MANIFEST_BYTES = 64L * 1024 * 1024
    }
}

/**
 * The log files a secondary holds open, by file number, shared by its epochs: a file is opened when the first epoch
 * listing it needs it and closed when the last one is released. Opening one that is gone throws [IOException]; that is
 * how a secondary learns the writer replaced the MANIFEST it reads. Thread-safe.
 */
private class SecondaryFiles(private val io: FileSystemIo, dir: Path) : LogReaders, LaneReaders {
    private val controlDir = dir.resolve(LogFiles.CONTROL_DIR)
    private val dataDir = dir.resolve(LogFiles.DATA_DIR)

    private class Held(val file: FileIo) {
        var refs = 0
    }

    private val held = ConcurrentHashMap<Long, Held>()
    private var closed = false

    override fun pathOf(fileNo: Long, control: Boolean, laneId: Int): Path =
        if (control) controlDir.resolve(LogFiles.controlName(fileNo)) else dataDir.resolve(LogFiles.laneName(fileNo, laneId))

    /** Opens [file] unless it's open, and counts one more holder. */
    @Synchronized
    fun retain(file: ManifestFile) {
        check(!closed) { "the secondary is closed" }
        val h = held[file.fileNo] ?: Held(openGone(pathOf(file.fileNo, file.control, file.laneId))).also { held[file.fileNo] = it }
        h.refs++
    }

    /** One holder fewer of each of [fileNos]; a file nobody holds is closed. */
    @Synchronized
    fun release(fileNos: Collection<Long>) {
        for (fileNo in fileNos) {
            val h = held[fileNo] ?: continue
            if (--h.refs == 0) {
                held.remove(fileNo)
                runCatching { h.file.close() }
            }
        }
    }

    @Synchronized
    fun close() {
        closed = true
        for (h in held.values) runCatching { h.file.close() }
        held.clear()
    }

    private fun openGone(path: Path): FileIo = try {
        io.open(path, OpenMode.READ)
    } catch (e: NoSuchFileException) {
        throw Changed(e)
    } catch (e: AccessDeniedException) {
        // A delete-pending name, with legacy Windows delete semantics.
        throw Changed(e)
    }

    override fun reader(fileNo: Long, control: Boolean, laneId: Int): FileIo = laneReader(fileNo)

    override fun laneReader(fileNo: Long): FileIo = held[fileNo]?.file ?: throw IOException("log file $fileNo isn't open in this secondary")
}

/**
 * A file the MANIFEST being read lists is gone, or a file is shorter than what was read of it: the writer moved on
 * since, so a secondary starts its refresh over from the newest MANIFEST. [failure] says what was found.
 */
private class Changed(val failure: IOException) : Exception(failure.message, failure, false, false)
