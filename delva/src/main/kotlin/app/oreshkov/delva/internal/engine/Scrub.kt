package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.DocId
import app.oreshkov.delva.VerifyIssue
import app.oreshkov.delva.VerifyIssueKind
import app.oreshkov.delva.VerifyPhase
import app.oreshkov.delva.VerifyProgress
import app.oreshkov.delva.VerifyReport
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.log.CommitInfo
import app.oreshkov.delva.internal.log.ControlRecords
import app.oreshkov.delva.internal.log.DeleteRecord
import app.oreshkov.delva.internal.log.FragmentRecords
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.log.LaneReaders
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.log.LogReaders
import app.oreshkov.delva.internal.log.ManifestFile
import app.oreshkov.delva.internal.log.RecordReader
import app.oreshkov.delva.internal.log.RecordType
import app.oreshkov.delva.internal.log.RelocateInfo
import app.oreshkov.delva.internal.log.TypeRecord
import app.oreshkov.delva.internal.lsm.FragmentEntry
import app.oreshkov.delva.internal.lsm.FragmentSegments
import app.oreshkov.delva.internal.lsm.KeyIndexView
import app.oreshkov.delva.internal.lsm.KeySegments
import app.oreshkov.delva.internal.lsm.SegmentHandle
import app.oreshkov.delva.internal.lsm.Version
import app.oreshkov.delva.internal.store.LogFragmentStore
import app.oreshkov.delva.internal.visibility.Visibility
import java.io.IOException

/**
 * The scrub behind `JsonStore.verify`: reads everything a store can read back and reports what doesn't check
 * out, without changing anything. Three phases, each reported through [VerifyProgress]:
 *
 * 1. [VerifyPhase.LOG] — every listed file's header, then every control-log record (frame CRC and body), and for each
 *    COMMIT still in the log the fragment records its extents name, which must be as many, and hold as many payload
 *    bytes, as the COMMIT recorded.
 * 2. [VerifyPhase.INDEX] — every entry of every segment of the pinned version, which reads each data block through its
 *    CRC, each external value through its chunk CRCs, and each bloom-filter page through the CRC taken when the
 *    segment opened.
 * 3. [VerifyPhase.FRAGMENTS] — every fragment-index entry against the record it locates: the file is still listed, the
 *    record is a valid FRAGMENT, and its key and payload bounds are the entry's.
 *
 * Damage never throws, and neither do bytes that can't be read, which are an issue too. The extent of damage is
 * measured with the 8-byte resync scan ([RecordReader.hasValidRecordFrom]), which is only ever used to report how far
 * the damage reaches, never to skip data silently. The store keeps running throughout; a scrub pins one snapshot and one index version, so a concurrent flush or compaction doesn't disturb it,
 * and anything written while it runs simply isn't in its report.
 */
internal class Scrub(engine: ReadEngine, private val scrubbed: ScrubView, private val progress: VerifyProgress) {
    private val options = engine.options
    private val files = scrubbed.logs
    private val issues = ArrayList<VerifyIssue>()

    private var filesChecked = 0
    private var bytesChecked = 0L
    private var logRecords = 0L
    private var fragments = 0L
    private var blocks = 0L

    /** Bytes of the phase in progress, reported every [PROGRESS_BYTES]. */
    private var phase = VerifyPhase.LOG
    private var phaseBytes = 0L
    private var phaseTotal = 0L
    private var reportedAt = 0L

    private val control = RecordReader(LogFormat.MAX_CONTROL_BODY_BYTES)
    private val lane = RecordReader(options.split.maxRecordBytes)
    private val body = ControlRecords.Body("control log")
    private val commit = CommitInfo()
    private val typeRecord = TypeRecord()
    private val deleteRecord = DeleteRecord()
    private var abortedDocId = -1L
    private val relocateInfo = RelocateInfo()
    private val view = FragmentView()
    private val byteReader = ByteReader(ByteArray(0))

    fun run(): VerifyReport {
        val snapshot = scrubbed.snapshot
        val version = scrubbed.version
        try {
            val listed = scrubbed.listed
            checkLog(listed, snapshot)
            checkSegments(version)
            checkKeyIndex(version, snapshot)
            checkFragmentIndex(version, listed, snapshot)
        } finally {
            version.release()
        }
        return VerifyReport(filesChecked, bytesChecked, logRecords, fragments, blocks, issues)
    }

    // ---- phase 1: headers, control records, COMMIT extents -----------------------------------------------------

    private fun checkLog(listed: List<ManifestFile>, snapshot: Snapshot) {
        startPhase(VerifyPhase.LOG, listed.sumOf { sizeOf(it) })
        for (file in listed) {
            val handle = openOrReport(file) ?: continue
            filesChecked++
            if (!checkHeader(file, handle)) continue
            // A lane file's records are read through the COMMITs that name them, which advance the progress.
            if (file.control) {
                checkControlFile(file, handle, snapshot)
                inFirstControlFile = false
            }
        }
        endPhase()
    }

    /** The file's handle, or null with an issue when it can't be opened or is shorter than a header. */
    private fun openOrReport(file: ManifestFile): FileIo? {
        val name = nameOf(file)
        val handle = try {
            files.reader(file.fileNo, file.control, file.laneId)
        } catch (e: IOException) {
            issue(VerifyIssueKind.FILE, name, "can't be opened: ${e.message}")
            return null
        }
        return handle
    }

    private fun checkHeader(file: ManifestFile, handle: FileIo): Boolean {
        val name = nameOf(file)
        val bytes = ByteArray(LogFormat.HEADER_BYTES)
        val read = try {
            handle.read(0, bytes, 0, bytes.size)
        } catch (e: IOException) {
            issue(VerifyIssueKind.FILE, name, "can't be read: ${e.message}")
            return false
        }
        if (read < LogFormat.HEADER_BYTES) {
            issue(VerifyIssueKind.FILE, "$name@0", "file of $read bytes has no header", read.toLong())
            return false
        }
        bytesChecked += LogFormat.HEADER_BYTES
        val header = try {
            LogFormat.readHeader(bytes, name)
        } catch (e: CorruptionException) {
            issue(VerifyIssueKind.FILE, "$name@0", e.detail, LogFormat.HEADER_BYTES.toLong())
            return false
        }
        if (header == null || header.control != file.control || header.fileNo != file.fileNo || header.laneId != file.laneId) {
            issue(VerifyIssueKind.FILE, "$name@0", "the header isn't this file's", LogFormat.HEADER_BYTES.toLong())
            return false
        }
        return true
    }

    /**
     * Walks a control file's records. Invalid bytes at the very end of the newest file are an unsynced torn tail, which
     * the next open cuts off; anywhere else they're damage, and the resync scan says how far it reaches. Bytes that
     * can't be read end the walk of the file.
     */
    private fun checkControlFile(file: ManifestFile, handle: FileIo, snapshot: Snapshot) {
        val name = nameOf(file)
        var at = LogFormat.HEADER_BYTES.toLong()
        try {
            val size = scrubbed.controlEnd(file, handle)
            control.open(handle, at, size)
            while (true) {
                at = control.position
                when (control.next()) {
                    RecordReader.RECORD -> {
                        logRecords++
                        advance(control.position - at)
                        bytesChecked += control.position - at
                        checkControlRecord(name, snapshot)
                    }
                    RecordReader.END -> {
                        advance(size - at)
                        return
                    }
                    else -> {
                        val resumed = control.hasValidRecordFrom(at + LogFormat.ALIGNMENT)
                        val to = if (resumed) control.position else size
                        issue(VerifyIssueKind.LOG_RECORD, "$name@$at", "no valid record", to - at)
                        advance(to - at)
                        if (!resumed) return
                    }
                }
            }
        } catch (e: IOException) {
            issue(VerifyIssueKind.FILE, "$name@$at", "can't be read: ${e.message}")
        }
    }

    /** Reads the record's body, and for a COMMIT the fragments its extents name. */
    private fun checkControlRecord(name: String, snapshot: Snapshot) {
        val at = control.recordStart
        val type = control.type
        val isCommit = try {
            body.open(control.bytes, control.bodyOffset, control.bodyLength, type)
            readBody(type)
        } catch (e: CorruptionException) {
            issue(VerifyIssueKind.LOG_RECORD, "$name@$at", e.detail, control.recordLength.toLong())
            return
        }
        checkReplacePairing("$name@$at", type)
        // A deleted document's fragments may already have been reclaimed; a document still in flight has no COMMIT.
        if (isCommit && snapshot.visibility.isVisible(commit.docId)) checkCommitExtents("$name@$at")
    }

    /** Whether the records read so far are in the first listed control file, whose older records may be gone. */
    private var inFirstControlFile = true

    /** Replace chains by the docId of their insert: true once the chain's last record was read. */
    private val replaceChains = HashMap<Long, Boolean>()

    /** The insert whose replace chain hasn't read its last record, or -1. */
    private var openReplace = -1L

    /**
     * Pairs the `AT_INSERT` DELETE records of a replace with its COMMIT, as recovery does: a COMMIT flagged `REPLACES`
     * follows a complete chain of its insert, and one without the flag follows none. A chain a crash cut off ends at the
     * next record that isn't part of it. The first control file may begin inside a chain whose start was deleted.
     */
    private fun checkReplacePairing(location: String, type: Int) {
        // A chain may span a roll of the control file.
        if (type == RecordType.SEAL) return
        val atInsert = type == RecordType.DELETE && deleteRecord.atInsert
        val continuing = atInsert && !deleteRecord.first
        if (openReplace >= 0 && !(continuing && deleteRecord.insertDocId == openReplace)) {
            replaceChains.remove(openReplace)
            openReplace = -1
        }
        when {
            atInsert -> {
                val insertDocId = deleteRecord.insertDocId
                if (deleteRecord.first) {
                    replaceChains[insertDocId] = false
                    openReplace = insertDocId
                } else if (insertDocId !in replaceChains) {
                    if (!inFirstControlFile) {
                        issue(VerifyIssueKind.LOG_RECORD, location, "DELETE record continues no delete with the insert of document $insertDocId", document = DocId(insertDocId))
                    }
                    return
                }
                if (deleteRecord.continued) {
                    openReplace = insertDocId
                } else {
                    replaceChains[insertDocId] = true
                    openReplace = -1
                }
            }
            type == RecordType.COMMIT -> {
                val docId = commit.docId
                val chain = replaceChains.remove(docId) == true
                if (commit.replaces && !chain && !inFirstControlFile) {
                    issue(VerifyIssueKind.COMMIT, location, "COMMIT of document $docId replaces documents no DELETE record names", document = DocId(docId))
                } else if (!commit.replaces && chain) {
                    issue(VerifyIssueKind.COMMIT, location, "COMMIT of document $docId after DELETE records of its insert", document = DocId(docId))
                }
                if ((commit.key != null) != (options.creation.documentKey != null)) {
                    issue(VerifyIssueKind.COMMIT, location, "COMMIT of document $docId ${if (commit.key == null) "without" else "with"} a document key", document = DocId(docId))
                }
            }
            type == RecordType.ABORT -> replaceChains.remove(abortedDocId)
        }
    }

    /** Decodes the open body by its record type, which checks its fields and length; true for a COMMIT. */
    private fun readBody(type: Int): Boolean {
        when (type) {
            RecordType.COMMIT -> {
                body.readCommit(commit)
                return true
            }
            RecordType.BEGIN -> body.readBegin()
            RecordType.ABORT -> abortedDocId = body.readAbort()
            RecordType.TYPEDEF -> body.readTypeDef()
            RecordType.TYPE -> body.readType(typeRecord)
            RecordType.DELETE -> body.readDelete(deleteRecord)
            RecordType.RELOCATE -> body.readRelocate(relocateInfo)
            RecordType.SEAL -> body.readSeal()
            RecordType.CHECKPOINT -> {
                body.reader.skip(16)
                if (body.reader.remaining != 0) throw CorruptionException("control log", "CHECKPOINT of ${control.bodyLength} bytes")
            }
            else -> throw CorruptionException("control log", "record type 0x${type.toString(16)} isn't supported in the control log")
        }
        return false
    }

    /**
     * Reads the fragment records of the COMMIT's extents: they must all be valid records of this
     * document, as many and with as many payload bytes as the COMMIT recorded.
     */
    private fun checkCommitExtents(location: String) {
        val docId = commit.docId
        var count = 0L
        var payload = 0L
        var damaged = false
        val extents = commit.extents
        for (e in 0 until extents.count) {
            val fileNo = extents.fileNo(e)
            if (!scrubbed.isListedLane(fileNo)) {
                // Space reclamation relocated the document's fragments and retired the file; the fragment index
                // holds their new locations, which phase 3 checks.
                return
            }
            val handle = try {
                scrubbed.lanes.laneReader(fileNo)
            } catch (e2: IOException) {
                issue(VerifyIssueKind.COMMIT, location, "lane file $fileNo can't be read: ${e2.message}", document = DocId(docId))
                return
            }
            val end = extents.end(e)
            if (!aligned(extents.start(e)) || end < extents.start(e)) {
                issue(VerifyIssueKind.COMMIT, location, "extent [${extents.start(e)}, $end) of lane file $fileNo isn't a record range", document = DocId(docId))
                damaged = true
                continue
            }
            try {
                if (end > handle.size()) {
                    issue(VerifyIssueKind.COMMIT, location, "extent ends at $end, past the end of lane file $fileNo", end - handle.size(), DocId(docId))
                    damaged = true
                    continue
                }
                lane.open(handle, extents.start(e), end)
                scan@ while (true) {
                    val at = lane.position
                    when (lane.next()) {
                        RecordReader.RECORD -> {
                            val raw = fragmentRawLength(docId, fileNo, lane)
                            if (raw < 0) {
                                issue(VerifyIssueKind.LOG_RECORD, "lane file $fileNo@$at", "not a valid fragment of document $docId", lane.position - at, DocId(docId))
                                damaged = true
                            } else {
                                count++
                                payload += raw
                                fragments++
                            }
                            bytesChecked += lane.position - at
                            advance(lane.position - at)
                        }
                        RecordReader.END -> {
                            if (at != end) {
                                issue(VerifyIssueKind.LOG_RECORD, "lane file $fileNo@$at", "the extent of document $docId ends early", end - at, DocId(docId))
                                damaged = true
                            }
                            break@scan
                        }
                        else -> {
                            val resumed = lane.hasValidRecordFrom(at + LogFormat.ALIGNMENT)
                            val to = if (resumed) lane.position else end
                            issue(VerifyIssueKind.LOG_RECORD, "lane file $fileNo@$at", "no valid record in the extent of document $docId", to - at, DocId(docId))
                            advance(to - at)
                            damaged = true
                            if (!resumed) break@scan
                        }
                    }
                }
            } catch (e2: IOException) {
                issue(VerifyIssueKind.COMMIT, location, "lane file $fileNo can't be read: ${e2.message}", document = DocId(docId))
                return
            }
        }
        if (!damaged && (count != commit.fragmentCount || payload != commit.encodedBytes)) {
            issue(
                VerifyIssueKind.COMMIT, location,
                "$count fragments of $payload payload bytes, the COMMIT records ${commit.fragmentCount} of ${commit.encodedBytes}",
                document = DocId(docId),
            )
        }
    }

    /** The record's raw payload length when it's a valid FRAGMENT record of [docId], else -1. */
    private fun fragmentRawLength(docId: Long, fileNo: Long, reader: RecordReader): Long {
        if (reader.type != RecordType.FRAGMENT) return -1
        return try {
            FragmentRecords.decode(reader.bytes, reader.bodyOffset, reader.bodyLength, byteReader, view, "lane file $fileNo@${reader.recordStart}", options.split.maxRecordBytes)
            if (view.docId != docId) -1 else view.rawLength.toLong()
        } catch (_: CorruptionException) {
            -1
        }
    }

    // ---- phase 2: segments -------------------------------------------------------------------------------------

    private fun checkSegments(version: Version) {
        val segments = version.postingSegments + version.fragmentSegments + version.typeSegments + version.keySegments
        startPhase(VerifyPhase.INDEX, segments.sumOf { it.reader.fileBytes })
        for (handle in segments) {
            filesChecked++
            checkSegment(handle)
        }
        endPhase()
    }

    /**
     * The document-key index of the snapshot against itself, still in the INDEX phase: walking the keys in order, no two
     * visible documents have one key and each one's key is also indexed by its docId; walking every source's keys by
     * docId, each visible document is the one its key finds. Damage that stops a walk is reported once.
     */
    private fun checkKeyIndex(version: Version, snapshot: Snapshot) {
        if (options.creation.documentKey == null && version.keySegments.isEmpty() && version.memtables.all { it.keys.isEmpty }) return
        val keys = KeyIndexView(version, snapshot.visibility)
        try {
            val scan = keys.scan(ByteArray(0), null, descending = false)
            var previous: ByteArray? = null
            var previousDocId = -1L
            while (scan.valid) {
                val key = scan.key
                val docId = scan.docId
                if (previous != null && previous.contentEquals(key)) {
                    issue(VerifyIssueKind.KEY_INDEX, "key index", "documents $previousDocId and $docId both have the key ${quoted(key)}", document = DocId(docId))
                }
                val stored = keys.keyOf(docId)
                if (stored == null || !stored.contentEquals(key)) {
                    issue(VerifyIssueKind.KEY_INDEX, "key index", "document $docId has the key ${quoted(key)}, but its own entry says ${stored?.let(::quoted) ?: "none"}", document = DocId(docId))
                }
                previous = key
                previousDocId = docId
                scan.next()
            }
            val check = { docId: Long, key: ByteArray ->
                if (snapshot.visibility.isVisible(docId) && keys.docIdOf(key) != docId) {
                    issue(VerifyIssueKind.KEY_INDEX, "key index", "document $docId has the key ${quoted(key)}, which finds document ${keys.docIdOf(key)}", document = DocId(docId))
                }
            }
            for (m in version.memtables) for ((docId, key) in m.keys.keysByDocId()) check(docId, key)
            for (handle in version.keySegments) {
                val segment = handle.reader
                val cursor = segment.cursor()
                var ok = cursor.seekCeiling(KeySegments.docKey(0))
                while (ok) {
                    val docId = KeySegments.docIdOf(cursor.key.array, cursor.key.size)
                    if (docId < 0 || cursor.valueExternal) throw CorruptionException(segment.source, "key-index entry of ${cursor.key.size} bytes")
                    check(docId, cursor.valueBytes.copyOfRange(cursor.valueOffset, cursor.valueOffset + cursor.valueLength))
                    ok = cursor.next()
                }
            }
        } catch (e: CorruptionException) {
            // The segment walk reports damage to a block; anything else is reported here.
            if (issues.none { it.location == e.location }) issue(VerifyIssueKind.KEY_INDEX, e.location, e.detail)
        } catch (e: IOException) {
            issue(VerifyIssueKind.KEY_INDEX, "key index", "can't be read: ${e.message}")
        }
    }

    private fun quoted(key: ByteArray): String = "\"${DocumentKeys.decode(key)}\""

    /**
     * Reads every block, external value and bloom page of one segment; each read checks a CRC. The first damaged one
     * ends the walk: the block index it came from is no longer trustworthy, so the rest of the file is reported as
     * unread rather than guessed at.
     */
    private fun checkSegment(handle: SegmentHandle) {
        val segment = handle.reader
        try {
            segment.checkBloom()
            val cursor = segment.cursor()
            var ok = cursor.first()
            var block = -1
            while (ok) {
                if (cursor.block != block) {
                    block = cursor.block
                    blocks++
                }
                cursor.checkValue()
                ok = cursor.next()
            }
            // Blocks, chunks and pages are read whole, and each at most once: a complete walk read the file.
            bytesChecked += segment.fileBytes
            advance(segment.fileBytes)
        } catch (e: CorruptionException) {
            issue(VerifyIssueKind.SEGMENT, e.location, e.detail)
        } catch (e: IOException) {
            issue(VerifyIssueKind.SEGMENT, segment.source, "can't be read: ${e.message}")
        }
    }

    // ---- phase 3: fragment index against the log ------------------------------------------------------------------

    private fun checkFragmentIndex(version: Version, listed: List<ManifestFile>, snapshot: Snapshot) {
        val lanes = listed.filter { !it.control }.associateBy { it.fileNo }
        // The entries locate lane records, so the lane files bound what this phase reads.
        startPhase(VerifyPhase.FRAGMENTS, lanes.values.sumOf { sizeOf(it) })
        val index = version.fragmentIndex()
        // Space reclamation retires a lane file once its live records were copied: entries that still locate them are
        // shadowed by the copies, or belong to hidden documents, until compaction drops them. Reads never use them.
        val stale = { e: FragmentEntry ->
            try {
                !snapshot.visibility.isVisible(e.docId) || index.find(e.docId, e.pre, e.kind, e.seq)?.location != e.location
            } catch (_: CorruptionException) {
                // The segment phase reports the damage; the entry is reported as it stands.
                false
            } catch (_: IOException) {
                false
            }
        }
        for (entry in version.active.fragments.entries()) checkFragmentEntry(entry, lanes, snapshot.visibility, stale)
        for (memtable in version.frozen) for (entry in memtable.fragments.entries()) checkFragmentEntry(entry, lanes, snapshot.visibility, stale)
        for (handle in version.fragmentSegments) {
            val segment = handle.reader
            try {
                val cursor = segment.cursor()
                var ok = cursor.first()
                while (ok) {
                    checkFragmentEntry(FragmentSegments.decode(cursor, segment.source), lanes, snapshot.visibility, stale)
                    ok = cursor.next()
                }
            } catch (e: CorruptionException) {
                // The segment phase already reported the damage that stops the walk.
                if (issues.none { it.location == e.location }) issue(VerifyIssueKind.SEGMENT, e.location, e.detail)
            } catch (e: IOException) {
                if (issues.none { it.location == segment.source }) issue(VerifyIssueKind.SEGMENT, segment.source, "can't be read: ${e.message}")
            }
        }
        endPhase()
    }

    /**
     * Reads the record a fragment-index entry locates and checks that it's the fragment the entry names. An entry in a
     * lane file the store no longer lists is damage unless it is [stale].
     *
     * Only entries of documents committed by the snapshot are checked, visible or deleted since. A promoted insert
     * indexes its fragments as it appends them, and they may still be in its lane's write buffer: until its COMMIT they
     * may not be in the file, and an aborted one never syncs them. Nothing reads them either: their document isn't
     * visible, and flushes and compactions drop the entries of aborted documents.
     */
    private fun checkFragmentEntry(entry: FragmentEntry, lanes: Map<Long, ManifestFile>, visibility: Visibility, stale: (FragmentEntry) -> Boolean) {
        if (!visibility.isVisible(entry.docId) && !visibility.isDeleted(entry.docId)) return
        val fileNo = LogFragmentStore.fileNoOf(entry.location)
        val offset = LogFragmentStore.offsetOf(entry.location)
        val at = "lane file $fileNo@$offset"
        val document = DocId(entry.docId)
        val key = "(${entry.docId}, ${entry.pre}, ${entry.kind}, ${entry.seq})"
        if (lanes[fileNo] == null) {
            if (stale(entry)) return
            issue(VerifyIssueKind.FRAGMENT_INDEX, at, "fragment $key is indexed in lane file $fileNo, which the store doesn't list", document = document)
            return
        }
        val handle = try {
            scrubbed.lanes.laneReader(fileNo)
        } catch (e: IOException) {
            issue(VerifyIssueKind.FRAGMENT_INDEX, at, "fragment $key: lane file $fileNo can't be read: ${e.message}", document = document)
            return
        }
        if (!aligned(offset) || entry.length <= 0) {
            issue(VerifyIssueKind.FRAGMENT_INDEX, at, "fragment $key is indexed at an offset no record can start at, with ${entry.length} bytes", document = document)
            return
        }
        val end = offset + LogFormat.padded(entry.length)
        val read = try {
            val size = handle.size()
            if (end > size) {
                issue(VerifyIssueKind.FRAGMENT_INDEX, at, "fragment $key ends at $end, past the end of lane file $fileNo", end - size, document)
                return
            }
            lane.open(handle, offset, end)
            lane.next()
        } catch (e: IOException) {
            issue(VerifyIssueKind.FRAGMENT_INDEX, at, "fragment $key: lane file $fileNo can't be read: ${e.message}", document = document)
            return
        }
        if (read != RecordReader.RECORD) {
            issue(VerifyIssueKind.FRAGMENT_INDEX, at, "fragment $key isn't a valid record", (end - offset), document)
            return
        }
        fragments++
        bytesChecked += end - offset
        advance(end - offset)
        if (fragmentRawLength(entry.docId, fileNo, lane) < 0) {
            issue(VerifyIssueKind.FRAGMENT_INDEX, at, "fragment $key isn't a fragment of document ${entry.docId}", (end - offset), document)
            return
        }
        if (view.pre != entry.pre || view.kind != entry.kind || view.seq != entry.seq) {
            issue(VerifyIssueKind.FRAGMENT_INDEX, at, "fragment $key is indexed where (${view.docId}, ${view.pre}, ${view.kind}, ${view.seq}) is stored", document = document)
            return
        }
        if (lane.recordLength != entry.length || view.rawLength != entry.rawLength) {
            issue(
                VerifyIssueKind.FRAGMENT_INDEX, at,
                "fragment $key is indexed as ${entry.length} bytes of ${entry.rawLength} payload, stored are ${lane.recordLength} of ${view.rawLength}",
                document = document,
            )
        }
    }

    // ---- reporting -------------------------------------------------------------------------------------------------

    private fun sizeOf(file: ManifestFile): Long = try {
        files.reader(file.fileNo, file.control, file.laneId).size()
    } catch (_: IOException) {
        0
    }

    /** Whether a record could start at [offset]: records are written at 8-byte aligned offsets after the header. */
    private fun aligned(offset: Long): Boolean =
        offset >= LogFormat.HEADER_BYTES && offset and (LogFormat.ALIGNMENT - 1).toLong() == 0L

    private fun nameOf(file: ManifestFile): String =
        if (file.control) "control file ${file.fileNo}" else "lane file ${file.fileNo}"

    private fun issue(kind: VerifyIssueKind, location: String, detail: String, damagedBytes: Long = 0, document: DocId? = null) {
        if (issues.size < MAX_ISSUES) issues += VerifyIssue(kind, location, detail, damagedBytes, document)
    }

    private fun startPhase(phase: VerifyPhase, total: Long) {
        this.phase = phase
        phaseBytes = 0
        phaseTotal = total
        reportedAt = 0
        progress.onProgress(phase, 0, total)
    }

    private fun advance(bytes: Long) {
        phaseBytes += bytes
        if (phaseBytes - reportedAt >= PROGRESS_BYTES) {
            reportedAt = phaseBytes
            progress.onProgress(phase, phaseBytes, maxOf(phaseTotal, phaseBytes))
        }
    }

    private fun endPhase() {
        progress.onProgress(phase, phaseBytes, maxOf(phaseTotal, phaseBytes))
    }

    private companion object {
        /** Issues kept; damage in one place would otherwise fill the heap with one issue per record. */
        const val MAX_ISSUES = 10_000

        const val PROGRESS_BYTES = 4L shl 20
    }
}

/**
 * What a [Scrub] reads: a [snapshot], the [version] it pins (retained; the scrub releases it), the log files the view
 * lists ([listed], oldest first) and the handles to read them. [isListedLane] tells whether the view lists a lane file,
 * and [controlEnd] where the records of a control file end for the view: the file's size for the writer, and the
 * position its replay reached for a secondary, which never judges bytes it hasn't replayed.
 */
internal class ScrubView(
    val snapshot: Snapshot,
    val version: Version,
    val listed: List<ManifestFile>,
    val logs: LogReaders,
    val lanes: LaneReaders,
    val isListedLane: (Long) -> Boolean,
    val controlEnd: (ManifestFile, FileIo) -> Long,
)
