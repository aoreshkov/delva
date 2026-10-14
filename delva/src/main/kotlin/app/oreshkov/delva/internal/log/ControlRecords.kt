package app.oreshkov.delva.internal.log

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.CreationOptions.Companion.MAX_DOCUMENT_KEY_BYTES
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.canon.Utf8
import app.oreshkov.delva.internal.types.TypeNames

/**
 * A document's lane byte ranges: `fileNo`, `start` and `end` (exclusive, padding included) per extent, in write
 * order. Fragments of one document are contiguous within a lane file, so there's one extent per lane file used.
 */
internal class Extents {
    var count: Int = 0
        private set
    private var fileNo = LongArray(2)
    private var start = LongArray(2)
    private var end = LongArray(2)

    fun fileNo(i: Int): Long = fileNo[i]
    fun start(i: Int): Long = start[i]
    fun end(i: Int): Long = end[i]

    fun clear() {
        count = 0
    }

    /** Adds `[recordStart, recordEnd)` of [file], extending the last extent when it continues it. */
    fun add(file: Long, recordStart: Long, recordEnd: Long) {
        if (count > 0 && fileNo[count - 1] == file && end[count - 1] == recordStart) {
            end[count - 1] = recordEnd
            return
        }
        if (count == fileNo.size) {
            fileNo = fileNo.copyOf(count * 2)
            start = start.copyOf(count * 2)
            end = end.copyOf(count * 2)
        }
        fileNo[count] = file
        start[count] = recordStart
        end[count] = recordEnd
        count++
    }

    fun copyFrom(other: Extents) {
        clear()
        for (i in 0 until other.count) add(other.fileNo(i), other.start(i), other.end(i))
    }
}

/** What a TYPE record says. */
internal class TypeRecord {
    var docId: Long = 0
    var nodePre: Long = 0
    var typeId: Int = 0
    var flags: Int = 0

    val atInsert: Boolean get() = flags and ControlRecords.TYPE_AT_INSERT != 0

    val continued: Boolean get() = flags and ControlRecords.TYPE_CONTINUED != 0

    val first: Boolean get() = flags and ControlRecords.TYPE_FIRST != 0
}

/**
 * What a DELETE record says: the first [count] entries of [docIds], ascending; for an `AT_INSERT` record, also the
 * [insertDocId] whose COMMIT it takes effect with.
 */
internal class DeleteRecord {
    var flags: Int = 0
    var count: Int = 0
        private set
    var docIds: LongArray = LongArray(16)
        private set

    /** The inserted document of an `AT_INSERT` record, else -1. */
    var insertDocId: Long = -1

    val atInsert: Boolean get() = flags and ControlRecords.DELETE_AT_INSERT != 0

    val continued: Boolean get() = flags and ControlRecords.DELETE_CONTINUED != 0

    val first: Boolean get() = flags and ControlRecords.DELETE_FIRST != 0

    fun clear() {
        count = 0
    }

    fun add(docId: Long) {
        if (count == docIds.size) docIds = docIds.copyOf(count * 2)
        docIds[count++] = docId
    }
}

/** What a COMMIT record says about the committed document. */
internal class CommitInfo {
    var docId: Long = 0
    var flags: Int = 0
    var fragmentCount: Long = 0
    var rootKind: Int = 0
    var rootHashLow: Long = 0
    var rootHashHigh: Long = 0
    var encodedBytes: Long = 0
    var typeCount: Long = 0
    val extents = Extents()

    val implicitBegin: Boolean get() = flags and ControlRecords.COMMIT_IMPLICIT_BEGIN != 0

    val replaces: Boolean get() = flags and ControlRecords.COMMIT_REPLACES != 0

    /** The document key (UTF-8) in a store with document keys, else null; the record has flag `KEYED` exactly when set. */
    var key: ByteArray? = null
}

/**
 * What a RELOCATE record says: [fragmentCount] live fragment records of lane file [oldFileNo],
 * copied unchanged to the GC lane at [extents].
 */
internal class RelocateInfo {
    var oldFileNo: Long = 0
    var fragmentCount: Long = 0
    val extents = Extents()
}

/**
 * Bodies of control-log records, little-endian; every body except SEAL starts with `lsn:u64`.
 * ```
 * BEGIN      := lsn | docId:u64 | laneId:u32 | flags:u8
 * COMMIT     := lsn | docId:u64 | flags:u8 | fragmentCount:u32 | rootKind:u8 | rootHash:16B | encodedBytes:u64
 *               | typeCount:u32 | extentCount:varint | { fileNo:u32 startOffset:u64 endOffset:u64 }*
 *               | [ keyLength:varint | utf8 ]                  key only with KEYED, of at most 1024 bytes
 * ABORT      := lsn | docId:u64 | reason:u16
 * TYPEDEF    := lsn | typeId:u32 | nameLength:varint | utf8
 * TYPE       := lsn | docId:u64 | nodePre:u64 | typeId:u32 (0 = none) | flags:u8
 * DELETE     := lsn | flags:u8 | [ insertDocId:u64 ] | count:varint (≥ 1) | { docId:u64 }*
 *               insertDocId only with AT_INSERT; docIds strictly ascending
 * RELOCATE   := lsn | oldFileNo:u32 | fragmentCount:u32 (≥ 1) | extentCount:varint (≥ 1)
 *               | { fileNo:u32 startOffset:u64 endOffset:u64 }*
 * CHECKPOINT := lsn | indexedUpToLsn:u64 | manifestNo:u64
 * SEAL       := fileNo:u64                                  (lane and control files; no lsn)
 * ```
 * `rootHash` is high 64 bits then low 64 bits, big-endian, like every stored hash. COMMIT flag bit 0 is
 * `IMPLICIT_BEGIN` (a small insert without BEGIN), bit 1 `REPLACES` (`AT_INSERT` DELETE records of the document come
 * before it), bit 2 `KEYED` (the document key follows the extents; set exactly in a store with document keys); other
 * bits are refused.
 *
 * TYPE flag bit 0 is `AT_INSERT`: the assignment was given with the insert of `docId`. Such records come right before
 * that document's COMMIT, whose `typeCount` counts them, and take effect with it; without the COMMIT they have none.
 * Other TYPE records are assignments to committed documents. The records of one assignment call are contiguous: the
 * first has flag bit 2 `FIRST`, all but the last have bit 1 `CONTINUED`, and they take effect together at the last one.
 * A call cut off by a crash has no effect; its records stay in the log, followed by whatever the reopened store appends,
 * so a `FIRST` record or a record of another type ends an unfinished call. The TYPEDEFs a call needs come before its
 * first TYPE record. `AT_INSERT` is never combined with the other flags.
 *
 * DELETE records delete committed documents. A delete call's docIds are sorted and split into records of a
 * bounded count; as with assignment calls, the first record has flag bit 2 `FIRST`, all but the last have bit 1
 * `CONTINUED`, and the call takes effect at its last record. The docIds ascend across the call's records.
 *
 * DELETE flag bit 0 `AT_INSERT` marks the deletes of a replace: the records name `insertDocId`, form one chain as a
 * delete call's do, and come before that document's TYPE records and COMMIT, which has flag `REPLACES`. They take effect
 * with the COMMIT, and without it they have none, like `AT_INSERT` TYPE records.
 *
 * RELOCATE records move fragments: the fragment records in its extents, all in GC-lane files, are
 * byte-for-byte copies of records in lane file `oldFileNo` that were live when copied, and the fragment index now
 * locates each of their keys there. Each record stands alone; one reclamation of a file may write several.
 */
internal object ControlRecords {
    const val COMMIT_IMPLICIT_BEGIN: Int = 1
    const val COMMIT_REPLACES: Int = 2
    const val COMMIT_KEYED: Int = 4
    const val TYPE_AT_INSERT: Int = 1
    const val TYPE_CONTINUED: Int = 2
    const val TYPE_FIRST: Int = 4
    const val DELETE_AT_INSERT: Int = 1
    const val DELETE_CONTINUED: Int = 2
    const val DELETE_FIRST: Int = 4

    /** ABORT reasons: 0 when the insert failed for another reason than a rejection, else the rejection's ordinal + 1. */
    const val ABORT_OTHER: Int = 0

    fun writeBegin(out: GrowableBytes, lsn: Long, docId: Long, laneId: Int) {
        val start = LogFormat.beginRecord(out)
        out.writeLongLE(lsn)
        out.writeLongLE(docId)
        out.writeIntLE(laneId)
        out.writeByte(0)
        LogFormat.endRecord(out, start, RecordType.BEGIN)
    }

    fun writeCommit(out: GrowableBytes, lsn: Long, commit: CommitInfo) {
        val start = LogFormat.beginRecord(out)
        out.writeLongLE(lsn)
        out.writeLongLE(commit.docId)
        out.writeByte(commit.flags)
        out.writeIntLE(commit.fragmentCount.toInt())
        out.writeByte(commit.rootKind)
        out.writeLongBE(commit.rootHashHigh)
        out.writeLongBE(commit.rootHashLow)
        out.writeLongLE(commit.encodedBytes)
        out.writeIntLE(commit.typeCount.toInt())
        writeExtents(out, commit.extents)
        val key = commit.key
        check((key != null) == (commit.flags and COMMIT_KEYED != 0)) { "COMMIT flag KEYED and key disagree" }
        if (key != null) {
            out.writeVarLong(key.size.toLong())
            out.writeBytes(key)
        }
        LogFormat.endRecord(out, start, RecordType.COMMIT)
    }

    fun writeRelocate(out: GrowableBytes, lsn: Long, relocate: RelocateInfo) {
        require(relocate.fragmentCount > 0 && relocate.extents.count > 0) { "a RELOCATE record moves at least one fragment" }
        val start = LogFormat.beginRecord(out)
        out.writeLongLE(lsn)
        out.writeIntLE(relocate.oldFileNo.toInt())
        out.writeIntLE(relocate.fragmentCount.toInt())
        writeExtents(out, relocate.extents)
        LogFormat.endRecord(out, start, RecordType.RELOCATE)
    }

    private fun writeExtents(out: GrowableBytes, extents: Extents) {
        out.writeVarLong(extents.count.toLong())
        for (i in 0 until extents.count) {
            out.writeIntLE(extents.fileNo(i).toInt())
            out.writeLongLE(extents.start(i))
            out.writeLongLE(extents.end(i))
        }
    }

    fun writeAbort(out: GrowableBytes, lsn: Long, docId: Long, reason: Int) {
        val start = LogFormat.beginRecord(out)
        out.writeLongLE(lsn)
        out.writeLongLE(docId)
        out.writeShortLE(reason)
        LogFormat.endRecord(out, start, RecordType.ABORT)
    }

    fun writeTypeDef(out: GrowableBytes, lsn: Long, typeId: Int, name: String) {
        val bytes = name.toByteArray(Charsets.UTF_8)
        val start = LogFormat.beginRecord(out)
        out.writeLongLE(lsn)
        out.writeIntLE(typeId)
        out.writeVarLong(bytes.size.toLong())
        out.writeBytes(bytes)
        LogFormat.endRecord(out, start, RecordType.TYPEDEF)
    }

    fun writeType(out: GrowableBytes, lsn: Long, docId: Long, nodePre: Long, typeId: Int, flags: Int) {
        val start = LogFormat.beginRecord(out)
        out.writeLongLE(lsn)
        out.writeLongLE(docId)
        out.writeLongLE(nodePre)
        out.writeIntLE(typeId)
        out.writeByte(flags)
        LogFormat.endRecord(out, start, RecordType.TYPE)
    }

    /**
     * Writes a DELETE record of `docIds[from until to]`, which must ascend strictly; with [DELETE_AT_INSERT] in [flags],
     * one that takes effect with the COMMIT of [insertDocId].
     */
    fun writeDelete(out: GrowableBytes, lsn: Long, docIds: LongArray, from: Int, to: Int, flags: Int, insertDocId: Long = -1) {
        require(to > from) { "a DELETE record names at least one document" }
        require((flags and DELETE_AT_INSERT != 0) == (insertDocId >= 0)) { "an AT_INSERT DELETE record names its insert, no other does" }
        val start = LogFormat.beginRecord(out)
        out.writeLongLE(lsn)
        out.writeByte(flags)
        if (insertDocId >= 0) out.writeLongLE(insertDocId)
        out.writeVarLong((to - from).toLong())
        for (i in from until to) out.writeLongLE(docIds[i])
        LogFormat.endRecord(out, start, RecordType.DELETE)
    }

    fun writeSeal(out: GrowableBytes, fileNo: Long) {
        val start = LogFormat.beginRecord(out)
        out.writeLongLE(fileNo)
        LogFormat.endRecord(out, start, RecordType.SEAL)
    }

    /** Framed length of a SEAL record, padding included. */
    val SEAL_RECORD_BYTES: Int = LogFormat.padded(LogFormat.RECORD_HEADER_BYTES + 8)

    /** A reader over a record body; [lsn] is read first for every type but SEAL. */
    class Body(private val source: String) {
        val reader = ByteReader(ByteArray(0))
        var lsn: Long = 0
            private set

        fun open(bytes: ByteArray, offset: Int, length: Int, type: Int) {
            reader.reset(bytes, offset, length, source)
            lsn = if (type == RecordType.SEAL) -1 else nonNegative(reader.readLongLE(), "lsn")
        }

        fun readBegin(): Long {
            val docId = nonNegative(reader.readLongLE(), "docId")
            reader.readIntLE()
            reader.readByte()
            end()
            return docId
        }

        fun readCommit(into: CommitInfo) {
            into.docId = nonNegative(reader.readLongLE(), "docId")
            into.flags = reader.readByte()
            if (into.flags and (COMMIT_IMPLICIT_BEGIN or COMMIT_REPLACES or COMMIT_KEYED).inv() != 0) throw CorruptionException(source, "COMMIT record with flags ${into.flags}")
            into.fragmentCount = reader.readIntLE().toLong() and 0xFFFFFFFFL
            into.rootKind = reader.readByte()
            into.rootHashHigh = reader.readLongBE()
            into.rootHashLow = reader.readLongBE()
            into.encodedBytes = nonNegative(reader.readLongLE(), "encodedBytes")
            into.typeCount = reader.readIntLE().toLong() and 0xFFFFFFFFL
            readExtents(into.extents)
            into.key = if (into.flags and COMMIT_KEYED != 0) {
                val length = reader.readVarInt(MAX_DOCUMENT_KEY_BYTES)
                ByteArray(length).also { reader.readBytes(it, 0, length) }.also {
                    if (!Utf8.isValid(it)) throw CorruptionException(source, "COMMIT record with a key that isn't UTF-8")
                }
            } else {
                null
            }
            end()
        }

        /** Reads a RELOCATE body into [into]; it moves at least one fragment, and never within its old file. */
        fun readRelocate(into: RelocateInfo) {
            into.oldFileNo = reader.readIntLE().toLong() and 0xFFFFFFFFL
            into.fragmentCount = reader.readIntLE().toLong() and 0xFFFFFFFFL
            readExtents(into.extents)
            end()
            val extents = into.extents
            if (into.fragmentCount == 0L || extents.count == 0 || (0 until extents.count).any { extents.fileNo(it) == into.oldFileNo }) {
                throw CorruptionException(source, "RELOCATE of ${into.fragmentCount} fragments from file ${into.oldFileNo} to ${extents.count} extents")
            }
        }

        private fun readExtents(into: Extents) {
            val count = reader.readVarInt(reader.remaining / EXTENT_BYTES)
            into.clear()
            for (i in 0 until count) {
                val fileNo = reader.readIntLE().toLong() and 0xFFFFFFFFL
                val start = reader.readLongLE()
                val end = reader.readLongLE()
                if (start < LogFormat.HEADER_BYTES || end <= start || (start or end) and (LogFormat.ALIGNMENT - 1).toLong() != 0L) {
                    throw CorruptionException(source, "extent [$start, $end) of file $fileNo")
                }
                into.add(fileNo, start, end)
            }
        }

        /** Returns `docId`; the reason goes to [abortReason]. */
        fun readAbort(): Long {
            val docId = nonNegative(reader.readLongLE(), "docId")
            abortReason = reader.readShortLE()
            end()
            return docId
        }

        var abortReason: Int = 0
            private set

        /** Returns the typeId; the name goes to [typeName]. */
        fun readTypeDef(): Int {
            val typeId = reader.readIntLE()
            val length = reader.readVarInt(TypeNames.MAX_BYTES)
            val at = reader.position
            reader.skip(length)
            if (typeId <= 0 || !TypeNames.isValid(reader.bytes, at, length)) throw CorruptionException(source, "invalid TYPEDEF of type $typeId")
            typeName = String(reader.bytes, at, length, Charsets.UTF_8)
            end()
            return typeId
        }

        var typeName: String = ""
            private set

        /** Reads a TYPE body into [into]. */
        fun readType(into: TypeRecord) {
            into.docId = nonNegative(reader.readLongLE(), "docId")
            into.nodePre = nonNegative(reader.readLongLE(), "nodePre")
            into.typeId = reader.readIntLE()
            into.flags = reader.readByte()
            val valid = into.flags == TYPE_AT_INSERT || into.flags and (TYPE_FIRST or TYPE_CONTINUED).inv() == 0
            if (into.typeId < 0 || !valid || (into.flags == TYPE_AT_INSERT && into.typeId == 0)) {
                throw CorruptionException(source, "TYPE record with type ${into.typeId} and flags ${into.flags}")
            }
            end()
        }

        /**
         * Reads a DELETE body into [into]; its flags must be valid and its docIds ascend strictly, and an `AT_INSERT`
         * record's insert isn't one of them.
         */
        fun readDelete(into: DeleteRecord) {
            into.flags = reader.readByte()
            if (into.flags and (DELETE_AT_INSERT or DELETE_FIRST or DELETE_CONTINUED).inv() != 0) throw CorruptionException(source, "DELETE record with flags ${into.flags}")
            into.insertDocId = if (into.atInsert) nonNegative(reader.readLongLE(), "docId") else -1
            val count = reader.readVarInt(reader.remaining / 8)
            if (count == 0) throw CorruptionException(source, "DELETE record without documents")
            into.clear()
            var previous = -1L
            repeat(count) {
                val docId = reader.readLongLE()
                if (docId <= previous) throw CorruptionException(source, "DELETE of document $docId after $previous")
                if (docId == into.insertDocId) throw CorruptionException(source, "DELETE of document $docId with its own insert")
                into.add(docId)
                previous = docId
            }
            end()
        }

        fun readSeal(): Long {
            val fileNo = reader.readLongLE()
            end()
            return fileNo
        }

        private fun end() {
            if (reader.remaining != 0) throw CorruptionException(source, "${reader.remaining} trailing bytes in a control record")
        }

        private fun nonNegative(value: Long, what: String): Long {
            if (value < 0) throw CorruptionException(source, "negative $what $value")
            return value
        }
    }

    private const val EXTENT_BYTES = 20
}
