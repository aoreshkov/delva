package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.CreationOptionsMismatchException
import app.oreshkov.delva.internal.bits.DocIdSet
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.compress.DictionaryFiles
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.discard
import app.oreshkov.delva.internal.io.readFully
import app.oreshkov.delva.internal.log.ControlRecords
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.log.LogLane
import app.oreshkov.delva.internal.log.Manifest
import app.oreshkov.delva.internal.log.ManifestFile
import app.oreshkov.delva.internal.log.ManifestStore
import app.oreshkov.delva.internal.log.RecordReader
import app.oreshkov.delva.internal.log.RecordType
import app.oreshkov.delva.internal.lsm.Memtable
import app.oreshkov.delva.internal.lsm.SegmentFormat
import app.oreshkov.delva.internal.lsm.SegmentHandle
import app.oreshkov.delva.internal.lsm.Version
import app.oreshkov.delva.internal.types.TypeDictionary
import java.io.IOException
import java.nio.file.Path
import java.util.TreeMap

/** What recovery hands to the engine. */
internal class Recovered(
    val files: LogFiles,
    val control: LogLane,
    val smallLane: LogLane,
    /** The GC lane. */
    val gcLane: LogLane,
    /** Large lanes by lane id, for every lane that has files. */
    val largeLanes: Map<Int, LogLane>,
    val indexes: Indexes,
    val dictionary: TypeDictionary,
    val nextDocId: Long,
    val lastLsn: Long,
    val aborted: DocIdSet,
    val deleted: DocIdSet,
    val committedEnd: Map<Long, Long>,
    val damagedDocIds: LongArray,
    val stats: RecoveryStats,
    /** Lane files the replayed COMMIT and RELOCATE records name. */
    val usage: LaneUsage,
)

/** What an open read and replayed; for tests and diagnostics. */
internal class RecoveryStats {
    var controlFilesSkipped: Int = 0
    var controlRecordsScanned: Long = 0
    var replayedCommits: Long = 0
    var replayedFragments: Long = 0

    /** Lane bytes scanned by COMMIT replay. */
    var replayedBytes: Long = 0

    /** RELOCATE records replayed, and damaged fragment records in their extents. */
    var replayedRelocations: Long = 0
    var damagedRelocatedRecords: Long = 0

    /** Reclaimed log files found on disk and deleted. */
    var deletedReclaimedFiles: Int = 0

    /** COMMITs cut off with the unsynced control-log tail because their fragments didn't survive. */
    var truncatedCommits: Int = 0

    /** DocIds deleted by the replayed DELETE records. */
    var replayedDeletes: Long = 0
}

/**
 * Opens a store directory: loads the MANIFEST (creating the store when the directory has none), opens
 * its segments, bitmap files and type dictionary file, adopts log files created after it, and replays the control log
 * from the file holding `indexedUpToLsn + 1` ([Replay]), truncating a torn tail and refusing to open when records are
 * damaged before it. BEGINs without COMMIT or ABORT get an ABORT. Lane files end at their last committed byte;
 * older lane files are sealed. Files no MANIFEST refers to are deleted, and so are log files that space reclamation
 * dropped from the MANIFEST before a crash kept it from deleting them; a delete that fails leaves the file to the next
 * open ([discard]).
 *
 * Damaged fragment records inside replayed extents don't stop the store from opening: the fragment is skipped, its
 * document is reported in [Recovered.damagedDocIds], and reads that need it fail.
 */
internal class Recovery(private val engine: Engine, private val dir: Path) {
    private val options = engine.options
    private val io = engine.io
    private val logOptions = options.log
    private val stats = RecoveryStats()

    fun run(): Recovered {
        val controlDir = dir.resolve(LogFiles.CONTROL_DIR)
        val dataDir = dir.resolve(LogFiles.DATA_DIR)
        val indexDir = dir.resolve(LogFiles.INDEX_DIR)
        val bitmapDir = dir.resolve(LogFiles.BITMAP_DIR)
        val dictionaryDir = dir.resolve(LogFiles.DICTIONARY_DIR)
        val tmpDir = dir.resolve(LogFiles.TMP_DIR)
        for (d in listOf(controlDir, dataDir, indexDir, bitmapDir, dictionaryDir, tmpDir)) io.createDirectories(d)
        io.syncDirectory(dir)

        val onDisk = TreeMap<Long, ManifestFile>()
        for (path in io.list(controlDir)) LogFiles.parseControl(path.fileName.toString())?.let { onDisk[it] = ManifestFile(it, true, 0, false) }
        for (path in io.list(dataDir)) LogFiles.parseLane(path.fileName.toString())?.let { (no, lane) -> onDisk[no] = ManifestFile(no, false, lane, false) }
        val segmentsOnDisk = HashMap<Long, Int>()
        for (path in io.list(indexDir)) LogFiles.parseSegment(path.fileName.toString())?.let { (no, kind) -> segmentsOnDisk[no] = kind }
        val bitmapsOnDisk = io.list(bitmapDir).mapNotNull { LogFiles.parseBitmap(it.fileName.toString()) }.toMap()
        val typeDictionariesOnDisk = io.list(indexDir).mapNotNull { LogFiles.parseTypeDictionary(it.fileName.toString()) }.toSet()

        val manifests = ManifestStore(io, dir)
        val info = LogFiles.StoreInfo(
            options.creation.fragmentBytes, options.encoderBudgetBytes, options.creation.hashTerms, options.indexPolicy,
            options.creation.normalization, options.creation.documentKey,
        )
        val filesExist = { m: Manifest ->
            m.files.all { f -> onDisk[f.fileNo]?.let { it.control == f.control && it.laneId == f.laneId } == true } &&
                m.segments.all { segmentsOnDisk[it.fileNo] == it.index } &&
                (m.abortedFileNo == 0L || bitmapsOnDisk[m.abortedFileNo] == LogFiles.BITMAP_ABORTED) &&
                (m.deletedFileNo == 0L || bitmapsOnDisk[m.deletedFileNo] == LogFiles.BITMAP_DELETED) &&
                (m.typeDictionaryFileNo == 0L || m.typeDictionaryFileNo in typeDictionariesOnDisk)
        }
        val loaded = manifests.load(filesExist, hasLogFiles = onDisk.isNotEmpty())
        val manifest = loaded ?: Manifest(
            1, info.fragmentBytes, info.encoderBudgetBytes, info.hashTerms, nextFileNo = 1, nextDocId = 1, lastLsn = 0, files = emptyList(),
            indexPolicy = info.indexPolicy, normalization = info.normalization, documentKey = info.documentKey,
        ).also { manifests.write(it) }
        if (manifest.fragmentBytes != info.fragmentBytes || manifest.hashTerms != info.hashTerms ||
            manifest.indexPolicy != info.indexPolicy || manifest.normalization != info.normalization ||
            manifest.documentKey != info.documentKey
        ) {
            throw CreationOptionsMismatchException(dir, manifest.creationOptions(), options.creation)
        }
        // The next MANIFEST goes above every one on disk, also one a crash left above the loaded one.
        val manifestNo = maxOf(manifest.manifestNo, manifests.highestOnDisk())
        val files = LogFiles(io, dir, logOptions, manifests, info, manifestNo, manifest.nextFileNo, manifest.files)
        // Before replay: replayed fragments may be compressed with any of them.
        val dictionaries = DictionaryFiles.load(io, dictionaryDir, manifest.dictionaries, engine.codecs, tmpDir)
        files.restoreDictionaries(manifest.dictionaries + dictionaries)
        files.restoreIndex(
            LogFiles.IndexState(
                manifest.indexedUpToLsn,
                manifest.abortedFileNo,
                manifest.typeDictionaryFileNo,
                manifest.deletedFileNo,
                manifest.segments,
                manifest.watermarks,
                manifest.files.filter { !it.control && it.committedEnd > 0 }.associate { it.fileNo to it.committedEnd },
                manifest.files.filter { !it.control && it.discardedBytes > 0 }.associate { it.fileNo to it.discardedBytes },
            ),
        )
        val handles = ArrayList<SegmentHandle>()
        try {
            return recover(files, manifest, onDisk, handles).also {
                manifests.sweep()
                deleteOrphans(files, manifest)
                emptyTmp(tmpDir)
            }
        } catch (t: Throwable) {
            for (h in handles) runCatching { h.close() }
            files.close()
            throw t
        }
    }

    private fun recover(files: LogFiles, manifest: Manifest, onDisk: TreeMap<Long, ManifestFile>, handles: MutableList<SegmentHandle>): Recovered {
        val listed = manifest.files.associateBy { it.fileNo }
        for (file in manifest.files) {
            val found = onDisk[file.fileNo]
            if (found == null || found.control != file.control || found.laneId != file.laneId) {
                throw CorruptionException(files.pathOf(file.fileNo, file.control, file.laneId).toString(), "listed in the MANIFEST but missing")
            }
        }
        for (file in onDisk.values) {
            if (file.fileNo in listed) continue
            val path = files.pathOf(file.fileNo, file.control, file.laneId)
            if (file.fileNo < manifest.nextFileNo) {
                // A file is listed before anything is appended to it, and the MANIFEST that lists it holds a larger
                // nextFileNo: this one was reclaimed, and a crash came before its deletion.
                io.discard(path, files.tmpDir)
                stats.deletedReclaimedFiles++
                continue
            }
            val header = readHeader(path)
            if (header == null || header.fileNo != file.fileNo || header.control != file.control || header.laneId != file.laneId) {
                // Created but its header never became durable, so nothing was ever appended to it.
                io.discard(path, files.tmpDir)
            } else {
                files.adopt(file)
            }
        }
        val all = files.listed()
        val headers = HashMap<Long, LogFormat.Header>()
        for (file in all) {
            if (file.fileNo > LogFiles.MAX_FILE_NO) throw CorruptionException("log file ${file.fileNo}", "file number out of range")
            val header = readHeader(files.pathOf(file.fileNo, file.control, file.laneId))
            if (header == null || header.fileNo != file.fileNo || header.control != file.control || header.laneId != file.laneId) {
                throw CorruptionException(files.pathOf(file.fileNo, file.control, file.laneId).toString(), "invalid file header")
            }
            headers[file.fileNo] = header
            if (file.control) files.noteControlCreatedLsn(file.fileNo, header.createdLsn)
        }

        // Segments, aborted and deleted docIds and type names as of indexedUpToLsn.
        val postingSegments = ArrayList<SegmentHandle>()
        val fragmentSegments = ArrayList<SegmentHandle>()
        val typeSegments = ArrayList<SegmentHandle>()
        val keySegments = ArrayList<SegmentHandle>()
        // In MANIFEST order, which is age order within each index: a compaction's segment takes the place of its inputs.
        for (segment in manifest.segments) {
            val path = files.segmentPath(segment.fileNo, segment.index)
            if (!io.exists(path)) throw CorruptionException(path.toString(), "listed in the MANIFEST but missing")
            val handle = openSegment(io, engine.blockCache, segment.fileNo, segment.index, path, files.tmpDir)
            handles += handle
            if (handle.reader.fileBytes != segment.fileBytes) throw CorruptionException(path.toString(), "${handle.reader.fileBytes} bytes, the MANIFEST says ${segment.fileBytes}")
            when (segment.index) {
                SegmentFormat.INDEX_POSTING -> postingSegments += handle
                SegmentFormat.INDEX_FRAGMENT -> fragmentSegments += handle
                SegmentFormat.INDEX_KEY -> keySegments += handle
                else -> typeSegments += handle
            }
        }
        val persistedAborted = readBitmap(files, manifest.abortedFileNo, LogFiles.BITMAP_ABORTED, manifest.nextDocId)
        val persistedDeleted = readBitmap(files, manifest.deletedFileNo, LogFiles.BITMAP_DELETED, manifest.nextDocId)
        val dictionary = if (manifest.typeDictionaryFileNo == 0L) TypeDictionary() else readTypeDictionary(files.typeDictionaryPath(manifest.typeDictionaryFileNo))
        val persistedTypeNames = dictionary.size

        val memtable = Memtable()
        val replay = Replay(files, memtable, manifest, persistedAborted, persistedDeleted, dictionary, typeSegments, options, engine.codecs, stats, Replay.Tail.RECOVER)
        replay.listLaneFiles(all)
        val controlFiles = all.filter { it.control }
        controlFiles.lastOrNull()?.let { replay.lastSyncMarkLsn = lastSyncMarkLsn(files, it) }
        // Skip control files whose records all precede indexedUpToLsn + 1: the next file starts at or before it.
        var first = 0
        while (first + 1 < controlFiles.size && headers[controlFiles[first + 1].fileNo]!!.createdLsn <= manifest.indexedUpToLsn + 1) first++
        stats.controlFilesSkipped = first
        var activeControl: Pair<ManifestFile, Long>? = null
        for (i in first until controlFiles.size) {
            val file = controlFiles[i]
            if (i == first) {
                val createdLsn = headers[file.fileNo]!!.createdLsn
                if (createdLsn > manifest.indexedUpToLsn + 1) {
                    throw CorruptionException(files.pathOf(file.fileNo, true, 0).toString(), "starts at LSN $createdLsn, after indexed LSN ${manifest.indexedUpToLsn}")
                }
                replay.lsn = createdLsn - 1
            }
            val last = i == controlFiles.size - 1
            val tail = replay.scanControl(file, last)
            if (last && tail >= 0) activeControl = file to tail
        }
        if (replay.lsn < manifest.lastLsn) {
            throw CorruptionException(files.controlDir.toString(), "the control log ends at LSN ${replay.lsn}, the MANIFEST says ${manifest.lastLsn} was durable")
        }

        val control = LogLane(files, control = true, laneId = 0, active = activeControl?.let { (f, tail) -> files.reopen(f.fileNo, true, 0, tail) })
        control.active?.force()

        // Inserts that began but never ended are aborted for good.
        val scratch = GrowableBytes(64)
        var lsn = replay.lsn
        for (docId in replay.begun) {
            scratch.clear()
            ControlRecords.writeAbort(scratch, ++lsn, docId, ControlRecords.ABORT_OTHER)
            control.append(scratch.array, 0, scratch.size, lsn)
            replay.aborted += docId
        }
        if (replay.begun.isNotEmpty()) control.force()

        // Lane files end at their last committed byte; every file but a lane's newest is sealed.
        val lanes = HashMap<Int, LogLane>()
        for ((laneId, laneFiles) in all.filter { !it.control }.groupBy { it.laneId }) {
            val sorted = laneFiles.sortedBy { it.fileNo }
            for ((i, file) in sorted.withIndex()) {
                if (file.sealed) continue
                val end = replay.committedEnd[file.fileNo] ?: LogFormat.HEADER_BYTES.toLong()
                val reopened = files.reopen(file.fileNo, false, laneId, end)
                if (i == sorted.size - 1) {
                    reopened.force()
                    lanes[laneId] = LogLane(files, control = false, laneId = laneId, active = reopened)
                } else {
                    reopened.seal(scratch)
                    files.sealed(reopened)
                }
            }
        }
        val nextDocId = maxOf(manifest.nextDocId, replay.maxDocId + 1, 1)
        val builder = persistedAborted.builder()
        for (docId in replay.aborted) builder.add(docId)
        val aborted = builder.build()
        val deleted = persistedDeleted.builder().apply { for (docId in replay.deleted) add(docId) }.build()
        stats.replayedDeletes = replay.deleted.size.toLong()
        files.info.nextDocId = { nextDocId }
        files.info.durableLsn = { lsn }
        files.writeManifest()

        val initial = Version(memtable, emptyList(), postingSegments, fragmentSegments, typeSegments, keySegments, manifest.indexedUpToLsn)
        val indexes = Indexes(files, io, options, engine, engine.blockCache, initial, persistedAborted, persistedDeleted, dictionary, persistedTypeNames)
        for (h in handles) indexes.track(h)
        return Recovered(
            files = files,
            control = control,
            smallLane = lanes[LogFormat.LANE_SMALL] ?: LogLane(files, false, LogFormat.LANE_SMALL, null),
            gcLane = lanes[LogFormat.LANE_GC] ?: LogLane(files, false, LogFormat.LANE_GC, null),
            largeLanes = lanes.filterKeys { it != LogFormat.LANE_SMALL && it != LogFormat.LANE_GC },
            indexes = indexes,
            dictionary = dictionary,
            nextDocId = nextDocId,
            lastLsn = lsn,
            aborted = aborted,
            deleted = deleted,
            committedEnd = replay.committedEnd,
            damagedDocIds = replay.damaged.toLongArray(),
            stats = stats,
            usage = replay.usage,
        )
    }

    private fun readHeader(path: Path): LogFormat.Header? = try {
        io.open(path, OpenMode.READ).use { file ->
            if (file.size() < LogFormat.HEADER_BYTES) return null
            val bytes = ByteArray(LogFormat.HEADER_BYTES)
            file.readFully(0, bytes, 0, bytes.size)
            LogFormat.readHeader(bytes)
        }
    } catch (_: IOException) {
        null
    }

    /** The docId set of bitmap file [fileNo] of [kind], whose members are all below [endDocId] (the MANIFEST written after it
     * records the next docId); empty for file 0. */
    private fun readBitmap(files: LogFiles, fileNo: Long, kind: Int, endDocId: Long): DocIdSet {
        if (fileNo == 0L) return DocIdSet.EMPTY
        val path = files.bitmapPath(fileNo, kind)
        return DocIdSet.decode(readListedFile(path), path.toString(), endDocId)
    }

    private fun readTypeDictionary(path: Path): TypeDictionary = TypeDictionary.decode(readListedFile(path), path.toString())

    private fun readListedFile(path: Path): ByteArray {
        if (!io.exists(path)) throw CorruptionException(path.toString(), "listed in the MANIFEST but missing")
        return try {
            io.open(path, OpenMode.READ).use { file ->
                val size = file.size()
                if (size > Int.MAX_VALUE - 8) throw CorruptionException(path.toString(), "$size bytes")
                ByteArray(size.toInt()).also { file.readFully(0, it, 0, it.size) }
            }
        } catch (e: IOException) {
            throw CorruptionException(path.toString(), "unreadable", e)
        }
    }

    /**
     * LSN of the last record in [file] (the newest control file) flagged [LogFormat.RECORD_FLAG_AFTER_SYNC], or 0.
     * Records before it were covered by a sync; it and the records after it may not have been, when no sync followed.
     */
    private fun lastSyncMarkLsn(files: LogFiles, file: ManifestFile): Long {
        val handle = files.reader(file.fileNo, true, 0)
        val reader = RecordReader(LogFormat.MAX_CONTROL_BODY_BYTES)
        val body = ControlRecords.Body("control log")
        reader.open(handle, LogFormat.HEADER_BYTES.toLong(), handle.size())
        var mark = 0L
        while (reader.next() == RecordReader.RECORD) {
            if (reader.type == RecordType.SEAL || reader.flags and LogFormat.RECORD_FLAG_AFTER_SYNC == 0) continue
            body.open(reader.bytes, reader.bodyOffset, reader.bodyLength, reader.type)
            mark = body.lsn
        }
        return mark
    }

    private fun emptyTmp(tmpDir: Path) {
        for (path in io.list(tmpDir)) io.discard(path, tmpDir)
    }

    /** Deletes segment, type dictionary and bitmap files the MANIFEST just written doesn't refer to (unfinished flushes). */
    private fun deleteOrphans(files: LogFiles, manifest: Manifest) {
        val live = manifest.segments.map { it.fileNo }.toSet()
        for (path in io.list(files.indexDir)) {
            val name = path.fileName.toString()
            val segment = LogFiles.parseSegment(name)
            val dictionary = LogFiles.parseTypeDictionary(name)
            when {
                segment != null -> if (segment.first in live) continue
                dictionary != null -> if (dictionary == manifest.typeDictionaryFileNo) continue
                !name.endsWith(".tmp") -> continue
            }
            io.discard(path, files.tmpDir)
        }
        for (path in io.list(files.bitmapDir)) {
            val no = LogFiles.parseBitmap(path.fileName.toString())
            if (no == null && !path.fileName.toString().endsWith(".tmp")) continue
            if (no != null && (no.first == manifest.abortedFileNo || no.first == manifest.deletedFileNo)) continue
            io.discard(path, files.tmpDir)
        }
    }
}
