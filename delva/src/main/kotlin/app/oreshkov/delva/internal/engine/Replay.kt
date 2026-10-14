package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bits.DocIdSet
import app.oreshkov.delva.internal.bits.LongHashSet
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.compress.FragmentCodecs
import app.oreshkov.delva.internal.io.readFully
import app.oreshkov.delva.internal.log.CommitInfo
import app.oreshkov.delva.internal.log.ControlRecords
import app.oreshkov.delva.internal.log.DeleteRecord
import app.oreshkov.delva.internal.log.FragmentRecords
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.log.LogReaders
import app.oreshkov.delva.internal.log.Manifest
import app.oreshkov.delva.internal.log.ManifestFile
import app.oreshkov.delva.internal.log.ManifestWatermark
import app.oreshkov.delva.internal.log.RecordReader
import app.oreshkov.delva.internal.log.RecordType
import app.oreshkov.delva.internal.log.RelocateInfo
import app.oreshkov.delva.internal.log.TypeRecord
import app.oreshkov.delva.internal.lsm.FragmentEntry
import app.oreshkov.delva.internal.lsm.Memtable
import app.oreshkov.delva.internal.lsm.PostingRun
import app.oreshkov.delva.internal.lsm.SegmentHandle
import app.oreshkov.delva.internal.store.LogFragmentStore
import app.oreshkov.delva.internal.term.FragmentIndexer
import app.oreshkov.delva.internal.term.SortedTermRun
import app.oreshkov.delva.internal.term.TermSink
import app.oreshkov.delva.internal.term.TermSorter
import app.oreshkov.delva.internal.types.TypeDictionary
import app.oreshkov.delva.internal.types.TypeIndexView
import app.oreshkov.delva.internal.visibility.LateCommits
import java.nio.file.Path
import java.util.TreeSet

/**
 * Replays the control log after a MANIFEST's `indexedUpToLsn` into [memtable], [dictionary] and the docId sets: the
 * state the MANIFEST's indexes plus the log describe. COMMITs index their extents with [FragmentIndexer], the code the
 * live splitter uses, starting at the document's index watermark when the MANIFEST has one, so the rescan is bounded by
 * what wasn't flushed, and add their document keys. TYPEDEFs extend the dictionary; TYPE records rebuild the type
 * memtable in LSN order; DELETE calls add to the deleted docIds, and the DELETE records of a replace do at its insert's
 * COMMIT; RELOCATEs index their copies at the new locations.
 *
 * It only reads, and it is **resumable**: [scanControl] stops where the log stops making sense for the [tail] policy and
 * returns the offset, and a later call from that offset continues with every unfinished call, replace chain and
 * insert still pending, as if the log had been read in one pass. What is still pending when the log ends (an insert
 * in [begun], say) is the caller's to settle, and so is any repair.
 *
 * Damaged fragment records inside replayed extents don't stop the replay: the fragment is skipped and its document is
 * reported in [damaged].
 */
internal class Replay(
    private val files: LogReaders,
    private val memtable: Memtable,
    manifest: Manifest,
    private val persistedAborted: DocIdSet,
    private val persistedDeleted: DocIdSet,
    private val dictionary: TypeDictionary,
    typeSegments: List<SegmentHandle>,
    private val options: EngineOptions,
    private val codecs: FragmentCodecs,
    private val stats: RecoveryStats,
    private val tail: Tail,
    /** Where the docIds of replayed COMMITs are recorded, before they're indexed; for a process that only reads. */
    private val late: LateCommits? = null,
) : TermSink {
    /** How the end of the control log is read. */
    enum class Tail {
        /**
         * Recovery after the writer stopped: the log ends at the first invalid bytes of the newest control file, or at a
         * COMMIT of the unsynced tail whose fragments didn't survive. Invalid bytes followed by a record appended after a
         * sync are damage, because a sync covered them.
         */
        RECOVER,

        /**
         * A reader beside a writer that may be appending: the newest control file ends, for now, at the first invalid
         * bytes, or at a COMMIT or RELOCATE above the MANIFEST's `lastLsn` whose records aren't all there yet or that
         * names a lane file or compression dictionary the reader doesn't know yet. Nothing there is judged damage: while
         * a page is being copied a reader can see invalid bytes before valid ones.
         */
        FOLLOW,
    }

    /** The newest types so far: segments, then what replay applied. */
    private val types = TypeIndexView(listOf(memtable), typeSegments, null, Long.MAX_VALUE)
    private val typeRecord = TypeRecord()

    /** TYPE records of inserts whose COMMIT hasn't been replayed yet: docId → `(pre, typeId, lsn)` triples. */
    private val pendingTypes = HashMap<Long, MutableList<LongArray>>()
    private val indexedUpToLsn = manifest.indexedUpToLsn

    /** Whether every COMMIT carries a document key: the store has a key path. */
    private val keyed = options.creation.documentKey != null
    private val durableLsn = manifest.lastLsn
    private val watermarks: Map<Long, ManifestWatermark> = manifest.watermarks.associateBy { it.docId }

    /** Lane files COMMIT and RELOCATE records may name, by file number. */
    private val laneFiles = HashMap<Long, ManifestFile>()

    /** LSN of the last record applied, or skipped because the MANIFEST's indexes hold it. */
    var lsn = 0L

    /** With [Tail.RECOVER]: LSN of the newest control file's last record appended after a sync; COMMITs from here on may be an unsynced tail. */
    var lastSyncMarkLsn = 0L
    var maxDocId = 0L

    /** Inserts begun and not ended yet. */
    val begun = TreeSet<Long>(watermarks.keys)
    val committed = HashSet<Long>()
    val aborted = LongHashSet()
    val deleted = LongHashSet()
    val damaged = TreeSet<Long>()

    /** Committed end of lane files by file number: the MANIFEST's, raised by the replayed COMMIT and RELOCATE records. */
    val committedEnd = HashMap<Long, Long>().apply { for (f in manifest.files) if (!f.control && f.committedEnd > 0) put(f.fileNo, f.committedEnd) }
    val usage = LaneUsage()

    private val reader = RecordReader(LogFormat.MAX_CONTROL_BODY_BYTES)
    private val laneReader = RecordReader(options.split.maxRecordBytes)
    private val body = ControlRecords.Body("control log")
    private val commit = CommitInfo()
    private val sorter = TermSorter(options.split.sorterBytes, this)
    private val indexer = FragmentIndexer(options.creation.hashTerms, options.indexPolicy)
    private val view = FragmentView()
    private val byteReader = ByteReader(ByteArray(0))
    private val inflated = GrowableBytes(0)
    private val runs = ArrayList<PostingRun>()
    private val runScratch = GrowableBytes(1024)

    /** Makes the lane files among [listed] known: the files listed when the replay starts, and each one listed later. */
    fun listLaneFiles(listed: Collection<ManifestFile>) {
        for (f in listed) if (!f.control) laneFiles[f.fileNo] = f
    }

    /**
     * Replays the records of control file [file] from offset [from]: its first record, or where the last call on it
     * stopped. Returns -1 after its SEAL, and otherwise the offset where the records applied end: where appending
     * continues ([Tail.RECOVER]) or where the next call resumes ([Tail.FOLLOW]). Only the [last] file may end without
     * a SEAL.
     */
    fun scanControl(file: ManifestFile, last: Boolean, from: Long = LogFormat.HEADER_BYTES.toLong()): Long {
        val path = files.pathOf(file.fileNo, true, 0)
        val handle = files.reader(file.fileNo, true, 0)
        if (from == LogFormat.HEADER_BYTES.toLong()) {
            val header = ByteArray(LogFormat.HEADER_BYTES)
            if (handle.size() < header.size) throw CorruptionException(path.toString(), "invalid file header")
            handle.readFully(0, header, 0, header.size)
            val createdLsn = (LogFormat.readHeader(header) ?: throw CorruptionException(path.toString(), "invalid file header")).createdLsn
            if (createdLsn != lsn + 1) throw CorruptionException(path.toString(), "starts at LSN $createdLsn, expected ${lsn + 1}")
        }
        reader.open(handle, from, handle.size())
        while (true) {
            val at = reader.position
            when (reader.next()) {
                RecordReader.RECORD -> {
                    stats.controlRecordsScanned++
                    if (reader.type == RecordType.SEAL) {
                        body.open(reader.bytes, reader.bodyOffset, reader.bodyLength, RecordType.SEAL)
                        if (body.readSeal() != file.fileNo) throw CorruptionException("$path@$at", "SEAL of another file")
                        if (reader.next() == RecordReader.RECORD || reader.hasValidRecordFrom(reader.position)) {
                            throw CorruptionException("$path@${reader.position}", "records after SEAL")
                        }
                        return -1
                    }
                    if (!apply(path, at, last)) {
                        // A COMMIT whose fragments aren't there: an unsynced tail that lost them, or (following) not written yet.
                        return at
                    }
                }
                else -> {
                    if (!last) throw CorruptionException("$path@$at", "control file ends without SEAL")
                    if (tail == Tail.FOLLOW) return at
                    // Valid records after invalid bytes are a torn unsynced tail, unless one of them was appended
                    // after a sync: then the invalid bytes had been synced, and they're damage.
                    var next = at + LogFormat.ALIGNMENT
                    while (reader.hasValidRecordFrom(next)) {
                        reader.next()
                        if (reader.flags and LogFormat.RECORD_FLAG_AFTER_SYNC != 0) {
                            throw CorruptionException("$path@$at", "damaged record before synced records of the control log")
                        }
                        next = reader.position
                    }
                    return at
                }
            }
        }
    }

    /**
     * Applies the record just read. Returns false, applying nothing, for a COMMIT whose fragments aren't all there and
     * that may be past what the writer synced. In recovery that is a crash: with
     * [Durability.Periodic][app.oreshkov.delva.Durability.Periodic] or `None` a crash can keep a later write of the control
     * log while losing earlier lane writes, and such a COMMIT and everything after it are a torn tail, like invalid bytes
     * at the end. Following a live writer it is a COMMIT whose fragments are not visible yet.
     */
    private fun apply(path: Path, at: Long, lastFile: Boolean): Boolean {
        val type = reader.type
        val location = "$path@$at"
        if (type != RecordType.BEGIN && type != RecordType.COMMIT && type != RecordType.ABORT && type != RecordType.CHECKPOINT && type != RecordType.TYPEDEF && type != RecordType.TYPE && type != RecordType.DELETE && type != RecordType.RELOCATE) {
            throw CorruptionException(location, "record type 0x${type.toString(16)} isn't supported in the control log")
        }
        body.open(reader.bytes, reader.bodyOffset, reader.bodyLength, type)
        if (body.lsn != lsn + 1) throw CorruptionException(location, "LSN ${body.lsn} after ${lsn}")
        val unsynced = body.lsn > durableLsn && (tail == Tail.FOLLOW || (lastFile && body.lsn >= lastSyncMarkLsn))
        if (type == RecordType.COMMIT && body.lsn > indexedUpToLsn && unsynced) {
            body.readCommit(commit)
            if (!extentsIntact(watermarks[commit.docId])) {
                stats.truncatedCommits++
                return false
            }
            body.open(reader.bytes, reader.bodyOffset, reader.bodyLength, type)
        }
        if (type == RecordType.RELOCATE && body.lsn > indexedUpToLsn && unsynced && tail == Tail.FOLLOW) {
            body.readRelocate(relocate)
            if (!relocationIntact()) return false
            body.open(reader.bytes, reader.bodyOffset, reader.bodyLength, type)
        }
        dropUnfinishedCalls(type)
        // Records up to indexedUpToLsn have all their effects in segments and the MANIFEST.
        if (body.lsn <= indexedUpToLsn) {
            lsn = body.lsn
            return true
        }
        lsn = body.lsn
        when (type) {
            RecordType.BEGIN -> {
                val docId = body.readBegin()
                if (docId in begun || docId in committed || hasEnded(docId)) throw CorruptionException(location, "BEGIN of document $docId again")
                begun += docId
                maxDocId = maxOf(maxDocId, docId)
            }
            RecordType.COMMIT -> {
                body.readCommit(commit)
                val docId = commit.docId
                if (docId in committed || hasEnded(docId)) throw CorruptionException(location, "COMMIT of document $docId again")
                if (!commit.implicitBegin && docId !in begun) throw CorruptionException(location, "COMMIT of document $docId without BEGIN")
                if (commit.implicitBegin && docId in begun) throw CorruptionException(location, "implicit COMMIT of begun document $docId")
                if ((commit.key != null) != keyed) {
                    throw CorruptionException(location, if (keyed) "COMMIT of document $docId without a document key" else "COMMIT of document $docId with a document key")
                }
                begun -= docId
                committed += docId
                maxDocId = maxOf(maxDocId, docId)
                late?.committed(docId, lsn)
                replayCommit(location, watermarks[docId])
                commit.key?.let { memtable.keys.add(it, docId) }
                val replaced = pendingReplaces.remove(docId)
                if (commit.replaces != (replaced != null)) {
                    throw CorruptionException(location, if (commit.replaces) "COMMIT of document $docId replaces documents no DELETE record names" else "COMMIT of document $docId after DELETE records of its insert")
                }
                replaced?.let { applyReplaces(location, it) }
                val assigned = pendingTypes.remove(docId)
                if ((assigned?.size ?: 0).toLong() != commit.typeCount) {
                    throw CorruptionException(location, "COMMIT of document $docId counts ${commit.typeCount} types, ${assigned?.size ?: 0} were logged")
                }
                assigned?.forEach { (pre, typeId, lsn) -> memtable.types.assign(docId, pre, lsn, typeId.toInt(), 0) }
            }
            RecordType.TYPEDEF -> {
                val typeId = body.readTypeDef()
                dictionary.addStored(typeId, body.typeName, location)
            }
            RecordType.TYPE -> replayType(location)
            RecordType.DELETE -> replayDelete(location)
            RecordType.RELOCATE -> replayRelocate(location)
            RecordType.ABORT -> {
                val docId = body.readAbort()
                if (docId in committed || hasEnded(docId)) throw CorruptionException(location, "ABORT of document $docId after its end")
                // The deletes of an aborted replace never take effect.
                pendingReplaces.remove(docId)
                begun -= docId
                aborted += docId
                maxDocId = maxOf(maxDocId, docId)
            }
            RecordType.CHECKPOINT -> {
                body.reader.skip(16)
                if (body.reader.remaining != 0) throw CorruptionException(location, "CHECKPOINT of ${reader.bodyLength} bytes")
            }
        }
        return true
    }

    /**
     * Whether the lane records the COMMIT just read names are all there: valid FRAGMENT records of its document, as
     * many and as large as it says (from its watermark on, when a flush holds the rest). Reads without indexing.
     */
    private fun extentsIntact(mark: ManifestWatermark?): Boolean {
        val docId = commit.docId
        val partial = mark != null && mark.fileNo != 0L
        var count = 0L
        var payload = 0L
        val extents = commit.extents
        for (e in 0 until extents.count) {
            val fileNo = extents.fileNo(e)
            val laneFile = laneFiles[fileNo] ?: return false
            var start = extents.start(e)
            if (partial) {
                if (fileNo < mark.fileNo) continue
                if (fileNo == mark.fileNo) start = maxOf(start, mark.offset)
                if (start >= extents.end(e)) continue
            }
            val handle = files.reader(fileNo, false, laneFile.laneId)
            if (extents.end(e) > handle.size()) return false
            laneReader.open(handle, start, extents.end(e))
            while (true) {
                val at = laneReader.position
                when (laneReader.next()) {
                    RecordReader.RECORD -> {
                        if (laneReader.type != RecordType.FRAGMENT) return false
                        try {
                            FragmentRecords.decode(laneReader.bytes, laneReader.bodyOffset, laneReader.bodyLength, byteReader, view, "lane file $fileNo", options.split.maxRecordBytes)
                        } catch (_: CorruptionException) {
                            return false
                        }
                        if (view.docId != docId) return false
                        // A dictionary is durable before any fragment uses it, but this reader may not have loaded it yet.
                        if (tail == Tail.FOLLOW && view.codecId == FragmentCodecs.DEFLATE_DICT && codecs.dictionary(view.dictId) == null) return false
                        count++
                        payload += view.rawLength
                    }
                    RecordReader.END -> {
                        if (at != laneReader.end) return false
                        break
                    }
                    else -> return false
                }
            }
        }
        return partial || (count == commit.fragmentCount && payload == commit.encodedBytes)
    }

    /**
     * Whether the records the RELOCATE just read names are all there ([Tail.FOLLOW]): in GC-lane files this reader
     * knows, valid FRAGMENT records, as many as it says. Reads without indexing.
     */
    private fun relocationIntact(): Boolean {
        val extents = relocate.extents
        var count = 0L
        for (e in 0 until extents.count) {
            val fileNo = extents.fileNo(e)
            val laneFile = laneFiles[fileNo] ?: return false
            if (laneFile.laneId != LogFormat.LANE_GC) return false
            val handle = files.reader(fileNo, false, laneFile.laneId)
            if (extents.end(e) > handle.size()) return false
            laneReader.open(handle, extents.start(e), extents.end(e))
            while (true) {
                val at = laneReader.position
                when (laneReader.next()) {
                    RecordReader.RECORD -> {
                        if (laneReader.type != RecordType.FRAGMENT) return false
                        count++
                    }
                    RecordReader.END -> {
                        if (at != laneReader.end) return false
                        break
                    }
                    else -> return false
                }
            }
        }
        return count == relocate.fragmentCount
    }

    /** Whether [docId] was aborted or deleted. */
    private fun hasEnded(docId: Long): Boolean = docId in aborted || docId in deleted || persistedAborted.contains(docId) || persistedDeleted.contains(docId)

    /**
     * A TYPE record: one given with an insert waits for the document's COMMIT (and is dropped without one); the
     * assignments of one call wait for the call's last record, then take effect in order against the types replayed
     * so far, so their tombstones are the ones the writer wrote. A call cut off by the end of the log has no effect.
     */
    private fun replayType(location: String) {
        val r = typeRecord
        body.readType(r)
        if (r.typeId > dictionary.size) throw CorruptionException(location, "TYPE of undefined type ${r.typeId}")
        val docId = r.docId
        maxDocId = maxOf(maxDocId, docId)
        if (r.atInsert) {
            if (docId in committed || hasEnded(docId)) throw CorruptionException(location, "TYPE of document $docId after its end")
            pendingTypes.getOrPut(docId) { ArrayList() } += longArrayOf(r.nodePre, r.typeId.toLong(), body.lsn)
            return
        }
        if (docId in begun || hasEnded(docId)) throw CorruptionException(location, "TYPE assigned to uncommitted or deleted document $docId")
        if (r.first) {
            // A call before this one that never got its last record was cut off by a crash.
            pendingAssignment.clear()
        } else if (pendingAssignment.isEmpty()) {
            throw CorruptionException(location, "TYPE record continues no assignment call")
        }
        pendingAssignment += longArrayOf(docId, r.nodePre, r.typeId.toLong(), body.lsn)
        if (r.continued) return
        for ((d, pre, typeId, lsn) in pendingAssignment) {
            val old = types.typeIdOf(d, pre)
            if (old != typeId.toInt()) memtable.types.assign(d, pre, lsn, typeId.toInt(), old)
        }
        pendingAssignment.clear()
    }

    /** TYPE records of an assignment call whose last record hasn't been read: `(docId, pre, typeId, lsn)`. */
    private val pendingAssignment = ArrayList<LongArray>()

    /**
     * A DELETE record: the docIds of one call wait for the call's last record, then are deleted together. A
     * call cut off by the end of the log has no effect. Every docId must name a committed document that wasn't deleted.
     */
    private fun replayDelete(location: String) {
        val r = deleteRecord
        body.readDelete(r)
        if (r.atInsert) {
            // A delete call's records are contiguous: this one ends an unfinished call.
            pendingDeletes.clear()
            replayReplace(location, r)
            return
        }
        dropOpenReplace()
        if (r.first) {
            pendingDeletes.clear()
        } else if (pendingDeletes.count == 0) {
            throw CorruptionException(location, "DELETE record continues no delete call")
        }
        // Committed documents have docIds below the MANIFEST's next docId or were committed in the replayed log.
        val docIdEnd = maxOf(manifestNextDocId, maxDocId + 1)
        for (i in 0 until r.count) {
            val docId = r.docIds[i]
            if (docId >= docIdEnd || docId in begun || hasEnded(docId)) throw CorruptionException(location, "DELETE of uncommitted or deleted document $docId")
            if (pendingDeletes.count > 0 && docId <= pendingDeletes.docIds[pendingDeletes.count - 1]) {
                throw CorruptionException(location, "DELETE of document $docId after ${pendingDeletes.docIds[pendingDeletes.count - 1]}")
            }
            pendingDeletes.add(docId)
        }
        if (r.continued) return
        for (i in 0 until pendingDeletes.count) deleted += pendingDeletes.docIds[i]
        pendingDeletes.clear()
    }

    /**
     * The `AT_INSERT` DELETE records of a replace: its chain collects the docIds, which wait for the insert's COMMIT
     * ([applyReplaces]) and are dropped without it. A chain cut off by a crash is dropped at the next record that
     * isn't part of it; its insert never commits.
     */
    private fun replayReplace(location: String, r: DeleteRecord) {
        val insertDocId = r.insertDocId
        if (insertDocId in committed || hasEnded(insertDocId)) throw CorruptionException(location, "DELETE with the insert of document $insertDocId after its end")
        maxDocId = maxOf(maxDocId, insertDocId)
        val chain: DeleteRecord
        if (r.first) {
            dropOpenReplace()
            if (insertDocId in pendingReplaces) throw CorruptionException(location, "a second DELETE with the insert of document $insertDocId")
            chain = DeleteRecord()
            pendingReplaces[insertDocId] = chain
            openReplace = insertDocId
        } else {
            chain = pendingReplaces[insertDocId]?.takeIf { openReplace == insertDocId }
                ?: throw CorruptionException(location, "DELETE record continues no delete with the insert of document $insertDocId")
        }
        val docIdEnd = maxOf(manifestNextDocId, maxDocId + 1)
        for (i in 0 until r.count) {
            val docId = r.docIds[i]
            if (docId >= docIdEnd || docId in begun || hasEnded(docId)) throw CorruptionException(location, "DELETE of uncommitted or deleted document $docId")
            if (chain.count > 0 && docId <= chain.docIds[chain.count - 1]) {
                throw CorruptionException(location, "DELETE of document $docId after ${chain.docIds[chain.count - 1]}")
            }
            chain.add(docId)
        }
        if (!r.continued) openReplace = -1
    }

    /** Deletes the documents of a replace whose COMMIT was just replayed. */
    private fun applyReplaces(location: String, chain: DeleteRecord) {
        for (i in 0 until chain.count) {
            val docId = chain.docIds[i]
            if (docId in begun || hasEnded(docId)) throw CorruptionException(location, "a replace deletes uncommitted or deleted document $docId")
            deleted += docId
        }
    }

    /** Drops the replace chain whose last record hasn't been read: a crash cut it off. */
    private fun dropOpenReplace() {
        if (openReplace < 0) return
        pendingReplaces.remove(openReplace)
        openReplace = -1
    }

    /** Replace chains by the docId of their insert; a chain is complete unless its insert is [openReplace]. */
    private val pendingReplaces = HashMap<Long, DeleteRecord>()

    /** The insert whose replace chain hasn't read its last record, or -1. */
    private var openReplace = -1L

    private val deleteRecord = DeleteRecord()
    private val relocate = RelocateInfo()

    /**
     * A RELOCATE record: indexes the fragment records in its extents at their new locations, replacing
     * older entries of their keys. A damaged record is skipped, so its fragment keeps its old location, whose file may
     * be gone: reads of it fail.
     */
    private fun replayRelocate(location: String) {
        body.readRelocate(relocate)
        stats.replayedRelocations++
        val extents = relocate.extents
        usage.used(extents, lsn)
        var count = 0L
        var damage = false
        for (e in 0 until extents.count) {
            val fileNo = extents.fileNo(e)
            val laneFile = laneFiles[fileNo]
            if (laneFile == null || laneFile.laneId != LogFormat.LANE_GC) throw CorruptionException(location, "RELOCATE to lane file $fileNo, which isn't a listed GC-lane file")
            committedEnd[fileNo] = maxOf(committedEnd[fileNo] ?: 0L, extents.end(e))
            val handle = files.reader(fileNo, false, laneFile.laneId)
            if (extents.end(e) > handle.size()) {
                damage = true
                continue
            }
            laneReader.open(handle, extents.start(e), extents.end(e))
            scan@ while (true) {
                val at = laneReader.position
                when (laneReader.next()) {
                    RecordReader.RECORD -> if (relocatedFragment(fileNo)) count++ else damage = true
                    RecordReader.END -> {
                        if (at != laneReader.end) damage = true
                        break@scan
                    }
                    else -> {
                        damage = true
                        if (!laneReader.hasValidRecordFrom(at + LogFormat.ALIGNMENT)) break@scan
                    }
                }
            }
        }
        if (damage) {
            stats.damagedRelocatedRecords += maxOf(1, relocate.fragmentCount - count)
        } else if (count != relocate.fragmentCount) {
            throw CorruptionException(location, "RELOCATE of ${relocate.fragmentCount} fragments, its extents hold $count")
        }
    }

    /** Indexes the lane record just read at its location; false if it isn't a valid fragment record. */
    private fun relocatedFragment(fileNo: Long): Boolean {
        if (laneReader.type != RecordType.FRAGMENT) return false
        try {
            FragmentRecords.decode(laneReader.bytes, laneReader.bodyOffset, laneReader.bodyLength, byteReader, view, "lane file $fileNo@${laneReader.recordStart}", options.split.maxRecordBytes)
        } catch (_: CorruptionException) {
            return false
        }
        val recordOffset = laneReader.bodyOffset - LogFormat.RECORD_HEADER_BYTES
        memtable.fragments.put(
            FragmentEntry(view.docId, view.pre, view.kind, view.seq, LogFragmentStore.location(fileNo, laneReader.recordStart), laneReader.recordLength, view.payloadOffset - recordOffset, view.rawLength),
        )
        return true
    }

    /** DocIds of a delete call whose last record hasn't been read. */
    private val pendingDeletes = DeleteRecord()
    private val manifestNextDocId = manifest.nextDocId

    /** A call's records are contiguous: another record after an unfinished call means a crash cut the call off. */
    private fun dropUnfinishedCalls(type: Int) {
        if (type != RecordType.TYPE) pendingAssignment.clear()
        if (type != RecordType.DELETE) {
            pendingDeletes.clear()
            dropOpenReplace()
        }
    }

    /** Indexes the committed document's extents, from its watermark when a flush already holds a prefix. */
    private fun replayCommit(location: String, mark: ManifestWatermark?) {
        val docId = commit.docId
        stats.replayedCommits++
        usage.used(commit.extents, lsn)
        sorter.reset(docId)
        var count = 0L
        var payload = 0L
        var damage = false
        val partial = mark != null && mark.fileNo != 0L
        if (partial && mark.offset and (LogFormat.ALIGNMENT - 1).toLong() != 0L) throw CorruptionException(location, "watermark of document $docId at unaligned offset ${mark.offset}")
        val extents = commit.extents
        for (e in 0 until extents.count) {
            val fileNo = extents.fileNo(e)
            val laneFile = laneFiles[fileNo] ?: throw CorruptionException(location, "COMMIT of document $docId refers to unknown lane file $fileNo")
            committedEnd[fileNo] = maxOf(committedEnd[fileNo] ?: 0L, extents.end(e))
            var start = extents.start(e)
            if (partial) {
                if (fileNo < mark.fileNo) continue
                if (fileNo == mark.fileNo) start = maxOf(start, mark.offset)
                if (start >= extents.end(e)) continue
            }
            val handle = files.reader(fileNo, false, laneFile.laneId)
            val size = handle.size()
            if (extents.end(e) > size) {
                damage = true
                continue
            }
            stats.replayedBytes += extents.end(e) - start
            laneReader.open(handle, start, extents.end(e))
            scan@ while (true) {
                val at = laneReader.position
                when (laneReader.next()) {
                    RecordReader.RECORD -> if (indexFragment(docId, fileNo)) {
                        count++
                        payload += view.rawLength
                    } else {
                        damage = true
                    }
                    RecordReader.END -> {
                        if (at != laneReader.end) damage = true
                        break@scan
                    }
                    else -> {
                        damage = true
                        if (!laneReader.hasValidRecordFrom(at + LogFormat.ALIGNMENT)) break@scan
                    }
                }
            }
        }
        sorter.finish()
        stats.replayedFragments += count
        if (damage || (!partial && (count != commit.fragmentCount || payload != commit.encodedBytes))) damaged += docId
    }

    /** Indexes the lane record just read; false if it isn't a valid fragment of [docId]. */
    private fun indexFragment(docId: Long, fileNo: Long): Boolean {
        if (laneReader.type != RecordType.FRAGMENT) return false
        val source = "lane file $fileNo@${laneReader.recordStart}"
        try {
            FragmentRecords.decode(laneReader.bytes, laneReader.bodyOffset, laneReader.bodyLength, byteReader, view, source, options.split.maxRecordBytes)
        } catch (_: CorruptionException) {
            return false
        }
        val index = memtable.fragments
        if (view.docId != docId || index.find(docId, view.pre, view.kind, view.seq) != null) return false
        val recordOffset = laneReader.bodyOffset - LogFormat.RECORD_HEADER_BYTES
        index.add(
            FragmentEntry(docId, view.pre, view.kind, view.seq, LogFragmentStore.location(fileNo, laneReader.recordStart), laneReader.recordLength, view.payloadOffset - recordOffset, view.rawLength),
        )
        try {
            view.inflate(codecs, inflated, source)
            indexer.index(view.pre, view.kind, view.seq, view.context, view.bytes, view.payloadOffset, view.payloadLength, sorter, source)
        } catch (_: CorruptionException) {
            return false
        }
        return true
    }

    override fun run(docId: Long, runSeq: Int, run: SortedTermRun) {
        runs.clear()
        PostingRun.fromSortedRun(docId, runSeq, run, runScratch, runs)
        for (r in runs) memtable.postings.add(r)
    }
}
