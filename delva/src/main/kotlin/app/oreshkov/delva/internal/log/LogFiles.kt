package app.oreshkov.delva.internal.log

import app.oreshkov.delva.Normalization
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.compress.DictionaryFiles
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.discard
import app.oreshkov.delva.internal.lsm.SegmentFormat
import app.oreshkov.delva.internal.term.IndexPolicy
import java.nio.file.Path
import java.util.Arrays
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The log files of a store directory and the MANIFEST that lists them:
 * ```
 * CURRENT, MANIFEST-n
 * control/ctl-<fileNo>.log
 * data/lane-S-<fileNo>.frag, data/lane-L<k>-<fileNo>.frag, data/lane-G-<fileNo>.frag
 * index/posting-<fileNo>.seg, index/frag-<fileNo>.seg, index/type-<fileNo>.seg, index/types-<fileNo>.dict
 * bitmap/aborted-<fileNo>.dis, bitmap/deleted-<fileNo>.dis
 * tmp/
 * ```
 * File numbers are global and never reused. Creating a file writes and syncs its header, syncs the directory and
 * then writes a MANIFEST that lists it, so a listed file always has a durable header. Thread-safe: the writer thread
 * and a large lane may roll files at the same time.
 *
 * Files leave the listing when their space is reclaimed: a control file once every record in it
 * is at or below `indexedUpToLsn` ([updateIndex]), a lane file once its live fragments were relocated
 * ([retireLaneFile]). A MANIFEST without the file is written first; a retired lane file stays readable until
 * [deleteLaneFile].
 */
internal class LogFiles(
    val io: FileSystemIo,
    val dir: Path,
    val options: LogOptions,
    private val manifests: ManifestStore,
    val info: StoreInfo,
    manifestNo: Long,
    nextFileNo: Long,
    files: Collection<ManifestFile>,
) : LogReaders, LaneReaders {
    /** What a MANIFEST records besides the files; [nextDocId] and [durableLsn] are read when one is written. */
    class StoreInfo(
        val fragmentBytes: Int,
        val encoderBudgetBytes: Int,
        val hashTerms: Boolean,
        val indexPolicy: IndexPolicy = IndexPolicy.EVERYTHING,
        val normalization: Normalization = Normalization.NONE,
        val documentKey: String? = null,
    ) {
        @Volatile
        var nextDocId: () -> Long = { 1 }

        @Volatile
        var durableLsn: () -> Long = { 0 }
    }

    /** The index part of a MANIFEST: what segments and the bitmap files hold, as of [indexedUpToLsn]. */
    class IndexState(
        val indexedUpToLsn: Long,
        /** The aborted docIds; 0 when none were ever aborted. */
        val abortedFileNo: Long,
        /** The type dictionary file; 0 when no type was ever defined. */
        val typeDictionaryFileNo: Long,
        /** The deleted docIds; 0 when none were ever deleted. */
        val deletedFileNo: Long,
        val segments: List<ManifestSegment>,
        val watermarks: List<ManifestWatermark>,
        /** `committedEnd` of lane files by file number. */
        val committedEnd: Map<Long, Long>,
        /** `discardedBytes` of lane files by file number; entries of files no longer listed are ignored. */
        val discarded: Map<Long, Long> = emptyMap(),
    )

    val controlDir: Path = dir.resolve(CONTROL_DIR)
    val dataDir: Path = dir.resolve(DATA_DIR)
    val indexDir: Path = dir.resolve(INDEX_DIR)
    val bitmapDir: Path = dir.resolve(BITMAP_DIR)

    /** Where files the store no longer needs are deleted ([discard]); every open empties it. */
    val tmpDir: Path = dir.resolve(TMP_DIR)

    private val lock = Any()
    private var manifestNo = manifestNo
    private var nextFileNo = nextFileNo
    private val files = TreeMap<Long, ManifestFile>().apply { for (f in files) put(f.fileNo, f) }

    /** Read handles, opened on first use; the writing thread of a file has its own, in [writeHandles]. */
    private val handles = ConcurrentHashMap<Long, FileIo>()

    /**
     * The handles that active files are appended through, used only by the thread that writes the file. A reader that
     * shared one could close it under an append or a sync if it were interrupted while reading.
     */
    private val writeHandles = ConcurrentHashMap<Long, FileIo>()

    /** Compression dictionaries the MANIFEST lists. */
    private val dictionaries = java.util.TreeSet<Int>()

    /** `createdLsn` of control files by file number, from their headers. */
    private val controlCreatedLsn = HashMap<Long, Long>()

    /** Lane files retired from the listing but not deleted yet: still readable. */
    private val retired = HashMap<Long, ManifestFile>()

    /** Numbers of the listed lane files, sorted; replaced whenever the listing changes. */
    @Volatile
    private var laneFileNos = LongArray(0)

    @Volatile
    var index: IndexState = IndexState(0, 0, 0, 0, emptyList(), emptyList(), emptyMap())
        private set

    @Volatile
    private var closed = false

    /** Control files deleted because the indexes held all their records; for tests. */
    @Volatile
    var deletedControlFiles: Long = 0
        private set

    init {
        refreshLaneFileNos()
    }

    override fun pathOf(fileNo: Long, control: Boolean, laneId: Int): Path =
        if (control) controlDir.resolve(controlName(fileNo)) else dataDir.resolve(laneName(fileNo, laneId))

    fun listed(): List<ManifestFile> = synchronized(lock) { files.values.toList() }

    /** Whether lane file [fileNo] is listed: false once it was retired, so no fragment-index entry needs its bytes. */
    fun isListedLane(fileNo: Long): Boolean = Arrays.binarySearch(laneFileNos, fileNo) >= 0

    /** The number the next new file gets, or a larger one. */
    fun peekNextFileNo(): Long = synchronized(lock) { nextFileNo }

    /** Creates a new active file and lists it in a new MANIFEST. */
    fun create(control: Boolean, laneId: Int, createdLsn: Long): LogFile = synchronized(lock) {
        check(!closed) { "log files are closed" }
        val fileNo = nextFileNo++
        val path = pathOf(fileNo, control, laneId)
        val io = io.open(path, OpenMode.CREATE_NEW)
        val file = try {
            LogFile.create(io, fileNo, control, laneId, createdLsn, options)
        } catch (e: Throwable) {
            io.close()
            throw e
        }
        writeHandles[fileNo] = io
        this.io.syncDirectory(path.parent)
        files[fileNo] = ManifestFile(fileNo, control, laneId, sealed = false)
        if (control) controlCreatedLsn[fileNo] = createdLsn
        refreshLaneFileNos()
        writeManifestLocked()
        file
    }

    /** Opens an existing file for appending at [end], dropping what follows. */
    fun reopen(fileNo: Long, control: Boolean, laneId: Int, end: Long): LogFile = synchronized(lock) {
        check(!closed) { "log files are closed" }
        val handle = writeHandles.getOrPut(fileNo) { io.open(pathOf(fileNo, control, laneId), OpenMode.READ_WRITE) }
        LogFile.reopen(handle, fileNo, control, laneId, end, options)
    }

    /** Records that [file] was sealed, and closes its write handle; the next MANIFEST lists it so. */
    fun sealed(file: LogFile) = synchronized(lock) {
        files[file.fileNo] = ManifestFile(file.fileNo, file.control, file.laneId, sealed = true)
        writeHandles.remove(file.fileNo)?.let { runCatching { it.close() } }
    }

    fun writeManifest() = synchronized(lock) { writeManifestLocked() }

    /** A new file number for a segment or bitmap file; persisted with the next MANIFEST. */
    fun allocateFileNo(): Long = synchronized(lock) { nextFileNo++ }

    /**
     * Replaces the index state and writes a MANIFEST with it: the commit point of a flush. Control files whose records
     * the state's `indexedUpToLsn` covers completely (the next control file starts at or below `indexedUpToLsn + 1`) are
     * no longer needed by recovery; they leave the listing with this MANIFEST and are deleted after it.
     */
    fun updateIndex(state: IndexState) {
        val covered = synchronized(lock) {
            check(!closed) { "log files are closed" }
            index = state
            val covered = coveredControlFiles(state.indexedUpToLsn)
            writeManifestLocked(covered)
            for (fileNo in covered) {
                files.remove(fileNo)
                controlCreatedLsn.remove(fileNo)
            }
            covered
        }
        for (fileNo in covered) {
            // Nothing reads a control file after recovery; a crash before the delete, or a delete that fails, leaves an
            // unlisted file that the next open deletes.
            closeHandles(fileNo)
            io.discard(pathOf(fileNo, true, 0), tmpDir)
            deletedControlFiles++
        }
    }

    /** Control files, oldest first, whose records are all at or below [indexedUpToLsn]; never the newest one. */
    private fun coveredControlFiles(indexedUpToLsn: Long): List<Long> {
        val control = files.values.filter { it.control }
        val covered = ArrayList<Long>()
        for (i in 0 until control.size - 1) {
            // A control file followed by another one is sealed: the next one starts right after its last record.
            val nextCreatedLsn = controlCreatedLsn[control[i + 1].fileNo] ?: break
            if (nextCreatedLsn > indexedUpToLsn + 1) break
            covered += control[i].fileNo
        }
        return covered
    }

    /** Sets the `createdLsn` recovery read from the header of control file [fileNo]. */
    fun noteControlCreatedLsn(fileNo: Long, createdLsn: Long) = synchronized(lock) { controlCreatedLsn[fileNo] = createdLsn }

    /** Sets the index state loaded from the MANIFEST, before recovery writes the next one. */
    fun restoreIndex(state: IndexState) = synchronized(lock) { index = state }

    /**
     * Drops sealed lane file [fileNo] from the listing, whose live fragments were relocated: writes a
     * MANIFEST without it. It stays readable by [laneReader] for searches that may still reach it, until
     * [deleteLaneFile].
     */
    fun retireLaneFile(fileNo: Long) = synchronized(lock) {
        check(!closed) { "log files are closed" }
        val file = files[fileNo]
        require(file != null && !file.control && file.sealed) { "lane file $fileNo isn't a sealed, listed lane file" }
        writeManifestLocked(listOf(fileNo))
        files.remove(fileNo)
        retired[fileNo] = file
        refreshLaneFileNos()
    }

    /**
     * Closes and deletes retired lane file [fileNo]; nothing may read it anymore. Returns false when the delete failed:
     * the file isn't listed, so the next open deletes it.
     */
    fun deleteLaneFile(fileNo: Long): Boolean {
        val file = synchronized(lock) { retired.remove(fileNo) } ?: return true
        closeHandles(fileNo)
        return io.discard(pathOf(fileNo, false, file.laneId), tmpDir)
    }

    fun segmentPath(fileNo: Long, indexKind: Int): Path = indexDir.resolve(segmentName(fileNo, indexKind))

    fun bitmapPath(fileNo: Long, kind: Int): Path = bitmapDir.resolve(bitmapName(fileNo, kind))

    fun typeDictionaryPath(fileNo: Long): Path = indexDir.resolve(typeDictionaryName(fileNo))

    /**
     * A handle for positional reads of a listed or recovered file; opened on first use and kept until [close]. Opened
     * for writing too, so it is never a mapping, which an active file would outgrow.
     */
    override fun reader(fileNo: Long, control: Boolean, laneId: Int): FileIo {
        handles[fileNo]?.let { return it }
        synchronized(lock) {
            check(!closed) { "log files are closed" }
            return handles.getOrPut(fileNo) { io.open(pathOf(fileNo, control, laneId), OpenMode.READ_WRITE) }
        }
    }

    /** The handle of a lane file known to exist (listed, or retired and not deleted yet), for reads. */
    override fun laneReader(fileNo: Long): FileIo {
        handles[fileNo]?.let { return it }
        val file = synchronized(lock) { files[fileNo] ?: retired[fileNo] } ?: throw java.io.IOException("log file $fileNo isn't listed")
        return reader(fileNo, file.control, file.laneId)
    }

    /** Lists a file found during recovery that the MANIFEST didn't list yet. */
    fun adopt(file: ManifestFile) = synchronized(lock) {
        files[file.fileNo] = file
        if (file.fileNo >= nextFileNo) nextFileNo = file.fileNo + 1
        refreshLaneFileNos()
    }

    fun close() {
        synchronized(lock) {
            closed = true
            for (h in handles.values + writeHandles.values) runCatching { h.close() }
            handles.clear()
            writeHandles.clear()
        }
    }

    private fun closeHandles(fileNo: Long) {
        handles.remove(fileNo)?.let { runCatching { it.close() } }
        writeHandles.remove(fileNo)?.let { runCatching { it.close() } }
    }

    private fun refreshLaneFileNos() {
        laneFileNos = files.values.filter { !it.control }.map { it.fileNo }.toLongArray()
    }

    /** Lists compression dictionaries whose files are durable, in a new MANIFEST unless all were listed already. */
    fun addDictionaries(ids: Collection<Int>) = synchronized(lock) {
        check(!closed) { "log files are closed" }
        if (dictionaries.addAll(ids)) writeManifestLocked()
    }

    fun listedDictionaries(): List<Int> = synchronized(lock) { dictionaries.toList() }

    /** The dictionaries a store had when it opened; no MANIFEST is written. */
    fun restoreDictionaries(ids: Collection<Int>) = synchronized(lock) { dictionaries.addAll(ids) }

    /** Writes a MANIFEST of the listed files but [excluded], with the current index state. */
    private fun writeManifestLocked(excluded: Collection<Long> = emptyList()) {
        val manifest = Manifest(
            manifestNo = ++manifestNo,
            fragmentBytes = info.fragmentBytes,
            encoderBudgetBytes = info.encoderBudgetBytes,
            hashTerms = info.hashTerms,
            nextFileNo = nextFileNo,
            nextDocId = info.nextDocId(),
            lastLsn = info.durableLsn(),
            files = files.values.filter { it.fileNo !in excluded }.map { f ->
                if (f.control) f else ManifestFile(f.fileNo, false, f.laneId, f.sealed, index.committedEnd[f.fileNo] ?: 0L, index.discarded[f.fileNo] ?: 0L)
            },
            indexedUpToLsn = index.indexedUpToLsn,
            abortedFileNo = index.abortedFileNo,
            typeDictionaryFileNo = index.typeDictionaryFileNo,
            deletedFileNo = index.deletedFileNo,
            segments = index.segments,
            watermarks = index.watermarks,
            dictionaries = dictionaries.toList(),
            indexPolicy = info.indexPolicy,
            normalization = info.normalization,
            documentKey = info.documentKey,
        )
        manifests.write(manifest)
    }

    companion object {
        const val CONTROL_DIR: String = "control"
        const val DATA_DIR: String = "data"
        const val TMP_DIR: String = "tmp"
        const val INDEX_DIR: String = "index"
        const val BITMAP_DIR: String = "bitmap"
        const val DICTIONARY_DIR: String = DictionaryFiles.DIR
        const val LOCK_FILE: String = "LOCK"

        /** Largest file number a fragment location can address. */
        const val MAX_FILE_NO: Long = (1L shl 23) - 1

        private val CONTROL_NAME = Regex("ctl-(\\d{6,18})\\.log")
        private val LANE_NAME = Regex("lane-(S|G|L(\\d{1,9}))-(\\d{6,18})\\.frag")

        fun controlName(fileNo: Long): String = "ctl-%06d.log".format(Locale.ROOT, fileNo)

        fun laneName(fileNo: Long, laneId: Int): String = when (laneId) {
            LogFormat.LANE_SMALL -> "lane-S-%06d.frag".format(Locale.ROOT, fileNo)
            LogFormat.LANE_GC -> "lane-G-%06d.frag".format(Locale.ROOT, fileNo)
            else -> "lane-L%d-%06d.frag".format(Locale.ROOT, laneId, fileNo)
        }

        /** `(fileNo, laneId)` of a control file name (laneId 0), or null. */
        fun parseControl(name: String): Long? = CONTROL_NAME.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

        /** `fileNo to laneId` of a lane file name, or null. */
        fun parseLane(name: String): Pair<Long, Int>? {
            val m = LANE_NAME.matchEntire(name) ?: return null
            val fileNo = m.groupValues[3].toLongOrNull() ?: return null
            val laneId = when (m.groupValues[1]) {
                "S" -> LogFormat.LANE_SMALL
                "G" -> LogFormat.LANE_GC
                else -> m.groupValues[2].toIntOrNull()?.takeIf { it > 0 && it != LogFormat.LANE_GC } ?: return null
            }
            return fileNo to laneId
        }

        private val SEGMENT_NAME = Regex("(posting|frag|type|key)-(\\d{6,18})\\.seg")
        private val BITMAP_NAME = Regex("(aborted|deleted)-(\\d{6,18})\\.dis")
        private val TYPE_DICTIONARY_NAME = Regex("types-(\\d{6,18})\\.dict")

        fun segmentName(fileNo: Long, indexKind: Int): String = when (indexKind) {
            SegmentFormat.INDEX_POSTING -> "posting-%06d.seg"
            SegmentFormat.INDEX_FRAGMENT -> "frag-%06d.seg"
            SegmentFormat.INDEX_KEY -> "key-%06d.seg"
            else -> "type-%06d.seg"
        }.format(Locale.ROOT, fileNo)

        /** Bitmap file kinds: aborted or deleted docIds. */
        const val BITMAP_ABORTED: Int = 1
        const val BITMAP_DELETED: Int = 2

        fun bitmapName(fileNo: Long, kind: Int): String = (if (kind == BITMAP_ABORTED) "aborted-%06d.dis" else "deleted-%06d.dis").format(Locale.ROOT, fileNo)

        fun typeDictionaryName(fileNo: Long): String = "types-%06d.dict".format(Locale.ROOT, fileNo)

        /** `fileNo to indexKind` of a segment file name, or null. */
        fun parseSegment(name: String): Pair<Long, Int>? {
            val m = SEGMENT_NAME.matchEntire(name) ?: return null
            val kind = when (m.groupValues[1]) {
                "posting" -> SegmentFormat.INDEX_POSTING
                "frag" -> SegmentFormat.INDEX_FRAGMENT
                "key" -> SegmentFormat.INDEX_KEY
                else -> SegmentFormat.INDEX_TYPE
            }
            return (m.groupValues[2].toLongOrNull() ?: return null) to kind
        }

        /** `fileNo to kind` of a bitmap file name, or null. */
        fun parseBitmap(name: String): Pair<Long, Int>? {
            val m = BITMAP_NAME.matchEntire(name) ?: return null
            return (m.groupValues[2].toLongOrNull() ?: return null) to if (m.groupValues[1] == "aborted") BITMAP_ABORTED else BITMAP_DELETED
        }

        fun parseTypeDictionary(name: String): Long? = TYPE_DICTIONARY_NAME.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()
    }
}

/**
 * The append side of one lane or of the control log: its active file, rolled when a record doesn't fit (seal, then a
 * new file listed in a new MANIFEST). [beforeRoll] runs before the active file is sealed. Not thread-safe; its owner
 * serializes access.
 *
 * In the control log, the first record appended after a sync (or a roll, which syncs) gets
 * [LogFormat.RECORD_FLAG_AFTER_SYNC], so recovery can tell a torn unsynced tail from damage to synced records.
 */
internal class LogLane(private val files: LogFiles, val control: Boolean, val laneId: Int, var active: LogFile?) {
    var beforeRoll: (() -> Unit)? = null
    private val scratch = GrowableBytes(64)
    private var syncedSinceAppend = true

    /** File number of the last append. */
    var lastFileNo: Long = 0
        private set

    /** Lowest file number the next append can go to: the active file's, or that of a file not created yet. */
    val appendFloor: Long get() = active?.fileNo ?: files.peekNextFileNo()

    /** Appends padded record bytes; returns their offset in the file [lastFileNo]. [nextLsn] labels a new file. */
    fun append(bytes: ByteArray, offset: Int, length: Int, nextLsn: Long = 0): Long {
        var file = active
        if (file == null || !file.fits(length.toLong())) file = roll(nextLsn)
        if (control && syncedSinceAppend) LogFormat.addRecordFlags(bytes, offset, LogFormat.RECORD_FLAG_AFTER_SYNC)
        syncedSinceAppend = false
        lastFileNo = file.fileNo
        return file.append(bytes, offset, length)
    }

    /** Starts a new file unless [bytes] more fit into the active one, so appends of that many bytes stay in one file. */
    fun makeRoom(bytes: Long) {
        val file = active ?: return
        if (!file.fits(bytes)) roll(0)
    }

    private fun roll(nextLsn: Long): LogFile {
        active?.let { file ->
            beforeRoll?.invoke()
            file.seal(scratch)
            files.sealed(file)
        }
        return files.create(control, laneId, nextLsn).also {
            active = it
            syncedSinceAppend = true
        }
    }

    fun flush() {
        active?.flush()
    }

    fun force() {
        active?.force()
        syncedSinceAppend = true
    }
}
