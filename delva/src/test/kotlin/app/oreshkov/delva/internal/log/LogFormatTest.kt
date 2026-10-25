package app.oreshkov.delva.internal.log

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.UnsupportedFormatException
import app.oreshkov.delva.internal.bits.DocIdSet
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.Crc32c
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.compress.CompressionDictionary
import app.oreshkov.delva.internal.compress.CompressionOptions
import app.oreshkov.delva.internal.compress.DictionaryFiles
import app.oreshkov.delva.internal.compress.FragmentCodecs
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.lsm.SegmentFormat
import app.oreshkov.delva.internal.payload.FragmentContext
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.PayloadFormat
import app.oreshkov.delva.internal.types.TypeDictionary
import app.oreshkov.delva.testkit.Golden
import app.oreshkov.delva.testkit.toHex
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val MANIFEST_PATH: Path = Path.of("db", "MANIFEST-000006")

class LogFormatTest {
    private fun commit(): CommitInfo = CommitInfo().apply {
        docId = 42
        flags = ControlRecords.COMMIT_IMPLICIT_BEGIN
        fragmentCount = 3
        rootKind = FragmentKind.SUBTREE
        rootHashHigh = 0x0123456789ABCDEFL
        rootHashLow = -2
        encodedBytes = 1000
        extents.add(5, 64, 128)
        extents.add(5, 128, 200)
        extents.add(9, 64, 96)
    }

    private fun relocate(): RelocateInfo = RelocateInfo().apply {
        oldFileNo = 5
        fragmentCount = 3
        extents.add(12, 64, 1024)
        extents.add(13, 64, 200)
    }

    private val stored = FragmentCodecs(CompressionOptions.DISABLED)

    private fun record(write: (GrowableBytes) -> Unit): ByteArray = GrowableBytes(64).also(write).toByteArray()

    @Test
    fun `record layouts match the golden file`() {
        val lines = ArrayList<String>()
        val header = ByteArray(LogFormat.HEADER_BYTES)
        LogFormat.writeHeader(header, control = true, fileNo = 2, createdLsn = 17, laneId = 0)
        lines += "header-control ${header.toHex()}"
        LogFormat.writeHeader(header, control = false, fileNo = 3, createdLsn = 0, laneId = 1)
        lines += "header-lane-L1 ${header.toHex()}"
        LogFormat.writeHeader(header, control = false, fileNo = 12, createdLsn = 0, laneId = LogFormat.LANE_GC)
        lines += "header-lane-G ${header.toHex()}"
        lines += "begin ${record { ControlRecords.writeBegin(it, 7, 42, 1) }.toHex()}"
        lines += "commit ${record { ControlRecords.writeCommit(it, 8, commit()) }.toHex()}"
        lines += "abort ${record { ControlRecords.writeAbort(it, 9, 42, 4) }.toHex()}"
        lines += "seal ${record { ControlRecords.writeSeal(it, 3) }.toHex()}"
        lines += "typedef ${record { ControlRecords.writeTypeDef(it, 11, 2, "Orderé") }.toHex()}"
        lines += "type-at-insert ${record { ControlRecords.writeType(it, 12, 42, 7, 2, ControlRecords.TYPE_AT_INSERT) }.toHex()}"
        lines += "type-none-first ${record { ControlRecords.writeType(it, 13, 42, 7, 0, ControlRecords.TYPE_FIRST or ControlRecords.TYPE_CONTINUED) }.toHex()}"
        lines += "begin-after-sync ${record { ControlRecords.writeBegin(it, 10, 43, 1) }.also { LogFormat.addRecordFlags(it, 0, LogFormat.RECORD_FLAG_AFTER_SYNC) }.toHex()}"
        val deleted = longArrayOf(7, 42, 300)
        lines += "delete ${record { ControlRecords.writeDelete(it, 14, deleted, 0, 3, ControlRecords.DELETE_FIRST) }.toHex()}"
        lines += "delete-continued ${record { ControlRecords.writeDelete(it, 15, deleted, 1, 2, ControlRecords.DELETE_CONTINUED) }.toHex()}"
        lines += "delete-at-insert ${record { ControlRecords.writeDelete(it, 17, deleted, 0, 2, ControlRecords.DELETE_AT_INSERT or ControlRecords.DELETE_FIRST, insertDocId = 301) }.toHex()}"
        lines += "commit-replaces ${record { ControlRecords.writeCommit(it, 18, commit().also { c -> c.flags = ControlRecords.COMMIT_IMPLICIT_BEGIN or ControlRecords.COMMIT_REPLACES }) }.toHex()}"
        val key = "items".toByteArray()
        val context = FragmentContext().set(4, 0, key, 0, key.size, 1900)
        lines += "fragment ${record { FragmentRecords.write(it, 42, 5, FragmentKind.PART, 0, context, byteArrayOf(0, 0x30, 0), 0, 3, stored) }.toHex()}"
        val manifest = Manifest(
            6, 64 * 1024, 8 shl 20, true, nextFileNo = 10, nextDocId = 43, lastLsn = 9,
            files = listOf(ManifestFile(2, true, 0, true), ManifestFile(3, false, 1, false, committedEnd = 4096, discardedBytes = 1800)),
            indexedUpToLsn = 8, abortedFileNo = 7, typeDictionaryFileNo = 4, deletedFileNo = 9,
            segments = listOf(ManifestSegment(5, 1, 70_000), ManifestSegment(6, 2, 1234), ManifestSegment(8, 3, 512)),
            watermarks = listOf(ManifestWatermark(42, 3, 1024), ManifestWatermark(44, 0, 0)),
            dictionaries = listOf(1, 3),
        )
        lines += "relocate ${record { ControlRecords.writeRelocate(it, 16, relocate()) }.toHex()}"
        lines += "manifest ${manifest.encode().toHex()}"
        lines += "commit-keyed ${record { ControlRecords.writeCommit(it, 19, commit().also { c -> c.flags = ControlRecords.COMMIT_KEYED; c.key = "ké\u0000y".toByteArray() }) }.toHex()}"
        val keyed = Manifest(1, 4096, 8192, false, nextFileNo = 2, nextDocId = 1, lastLsn = 0, files = emptyList(), segments = listOf(ManifestSegment(1, 4, 99)), documentKey = "$.id")
        lines += "manifest-keyed ${keyed.encode().toHex()}"
        lines += "dictionary ${DictionaryFiles.encode(CompressionDictionary(3, "{\"id\":".toByteArray())).toHex()}"
        Golden.verify("log-records.txt", "Fragment-log file headers, records and MANIFEST (LogFormat FORMAT_VERSION 2, Manifest FORMAT_VERSION 1): <case> <hex>", lines)
    }

    @Test
    fun `records decode to what was written`() {
        val bytes = record { ControlRecords.writeCommit(it, 8, commit()) }
        val body = ControlRecords.Body("test")
        body.open(bytes, LogFormat.RECORD_HEADER_BYTES, lengthOf(bytes), RecordType.COMMIT)
        val read = CommitInfo()
        body.readCommit(read)
        assertEquals(8, body.lsn)
        assertEquals(42, read.docId)
        assertTrue(read.implicitBegin)
        assertEquals(listOf(Triple(5L, 64L, 200L), Triple(9L, 64L, 96L)), (0 until read.extents.count).map { Triple(read.extents.fileNo(it), read.extents.start(it), read.extents.end(it)) })
        assertEquals(0x0123456789ABCDEFL, read.rootHashHigh)
        assertEquals(null, read.key)

        val keyed = record { ControlRecords.writeCommit(it, 19, commit().also { c -> c.flags = ControlRecords.COMMIT_KEYED; c.key = "ké\u0000y".toByteArray() }) }
        body.open(keyed, LogFormat.RECORD_HEADER_BYTES, lengthOf(keyed), RecordType.COMMIT)
        body.readCommit(read)
        assertEquals("ké\u0000y", read.key?.toString(Charsets.UTF_8))
        val keyedManifest = Manifest(1, 4096, 8192, false, nextFileNo = 2, nextDocId = 1, lastLsn = 0, files = emptyList(), documentKey = "$['the id']")
        assertEquals("$['the id']", Manifest.decode(keyedManifest.encode(), MANIFEST_PATH).creationOptions().documentKey)

        val typeDef = record { ControlRecords.writeTypeDef(it, 11, 2, "Orderé") }
        body.open(typeDef, LogFormat.RECORD_HEADER_BYTES, lengthOf(typeDef), RecordType.TYPEDEF)
        assertEquals(2, body.readTypeDef())
        assertEquals("Orderé", body.typeName)
        val type = record { ControlRecords.writeType(it, 12, 42, 7, 2, ControlRecords.TYPE_AT_INSERT) }
        body.open(type, LogFormat.RECORD_HEADER_BYTES, lengthOf(type), RecordType.TYPE)
        val typeRecord = TypeRecord()
        body.readType(typeRecord)
        assertEquals(listOf(42L, 7L, 2L), listOf(typeRecord.docId, typeRecord.nodePre, typeRecord.typeId.toLong()))
        assertTrue(typeRecord.atInsert)
        for (bad in listOf(record { ControlRecords.writeTypeDef(it, 11, 3, "a") }, record { ControlRecords.writeTypeDef(it, 11, 0, "a") })) {
            body.open(bad, LogFormat.RECORD_HEADER_BYTES, lengthOf(bad), RecordType.TYPEDEF)
            assertFailsWith<CorruptionException> { body.readTypeDef() }
        }
        val removalAtInsert = record { ControlRecords.writeType(it, 12, 42, 7, 0, ControlRecords.TYPE_AT_INSERT) }
        body.open(removalAtInsert, LogFormat.RECORD_HEADER_BYTES, lengthOf(removalAtInsert), RecordType.TYPE)
        assertFailsWith<CorruptionException> { body.readType(typeRecord) }
        for (flags in 0..8) {
            val bytes = record { ControlRecords.writeType(it, 12, 42, 7, 1, flags) }
            body.open(bytes, LogFormat.RECORD_HEADER_BYTES, lengthOf(bytes), RecordType.TYPE)
            if (flags in listOf(0, 1, 2, 4, 6)) {
                body.readType(typeRecord)
                assertEquals(listOf(flags and 1 != 0, flags and 2 != 0, flags and 4 != 0), listOf(typeRecord.atInsert, typeRecord.continued, typeRecord.first), "flags $flags")
            } else {
                assertFailsWith<CorruptionException>("flags $flags") { body.readType(typeRecord) }
            }
        }

        val docIds = longArrayOf(0, 9, 1L shl 40)
        val delete = record { ControlRecords.writeDelete(it, 16, docIds, 0, 3, ControlRecords.DELETE_FIRST or ControlRecords.DELETE_CONTINUED) }
        body.open(delete, LogFormat.RECORD_HEADER_BYTES, lengthOf(delete), RecordType.DELETE)
        val deleteRecord = DeleteRecord()
        body.readDelete(deleteRecord)
        assertEquals(16, body.lsn)
        assertEquals(docIds.toList(), deleteRecord.docIds.copyOf(deleteRecord.count).toList())
        assertTrue(deleteRecord.first && deleteRecord.continued)
        for (flags in 0..8) {
            val insertDocId = if (flags and ControlRecords.DELETE_AT_INSERT != 0) 42L else -1L
            val bytes = record { ControlRecords.writeDelete(it, 16, docIds, 1, 2, flags, insertDocId) }
            body.open(bytes, LogFormat.RECORD_HEADER_BYTES, lengthOf(bytes), RecordType.DELETE)
            if (flags < 8) {
                body.readDelete(deleteRecord)
                assertEquals(listOf(9L), deleteRecord.docIds.copyOf(deleteRecord.count).toList())
                assertEquals(listOf(flags and 1 != 0, flags and 2 != 0, flags and 4 != 0), listOf(deleteRecord.atInsert, deleteRecord.continued, deleteRecord.first), "flags $flags")
                assertEquals(insertDocId, deleteRecord.insertDocId)
            } else {
                assertFailsWith<CorruptionException>("flags $flags") { body.readDelete(deleteRecord) }
            }
        }
        assertFailsWith<IllegalArgumentException>("AT_INSERT without its insert") { record { ControlRecords.writeDelete(it, 16, docIds, 0, 1, ControlRecords.DELETE_AT_INSERT) } }
        assertFailsWith<IllegalArgumentException>("an insert without AT_INSERT") { record { ControlRecords.writeDelete(it, 16, docIds, 0, 1, 0, insertDocId = 42) } }
        val ownInsert = record { ControlRecords.writeDelete(it, 16, docIds, 1, 2, ControlRecords.DELETE_AT_INSERT, insertDocId = 9) }
        body.open(ownInsert, LogFormat.RECORD_HEADER_BYTES, lengthOf(ownInsert), RecordType.DELETE)
        assertFailsWith<CorruptionException>("a replace deleting its own insert") { body.readDelete(deleteRecord) }
        for (flags in 0..8) {
            val key = if (flags and ControlRecords.COMMIT_KEYED != 0) "k".toByteArray() else null
            val bytes = record { ControlRecords.writeCommit(it, 8, commit().also { c -> c.flags = flags; c.key = key }) }
            body.open(bytes, LogFormat.RECORD_HEADER_BYTES, lengthOf(bytes), RecordType.COMMIT)
            if (flags < 8) {
                body.readCommit(read)
                assertEquals(listOf(flags and 1 != 0, flags and 2 != 0, key != null), listOf(read.implicitBegin, read.replaces, read.key != null), "COMMIT flags $flags")
            } else {
                assertFailsWith<CorruptionException>("COMMIT flags $flags") { body.readCommit(read) }
            }
        }
        for (bad in listOf(longArrayOf(5, 5), longArrayOf(9, 7), longArrayOf(-1))) {
            val bytes = record { ControlRecords.writeDelete(it, 16, bad, 0, bad.size, ControlRecords.DELETE_FIRST) }
            body.open(bytes, LogFormat.RECORD_HEADER_BYTES, lengthOf(bytes), RecordType.DELETE)
            assertFailsWith<CorruptionException>(bad.toList().toString()) { body.readDelete(deleteRecord) }
        }
        val empty = record { out ->
            val start = LogFormat.beginRecord(out)
            out.writeLongLE(16)
            out.writeByte(ControlRecords.DELETE_FIRST)
            out.writeVarLong(0)
            LogFormat.endRecord(out, start, RecordType.DELETE)
        }
        body.open(empty, LogFormat.RECORD_HEADER_BYTES, lengthOf(empty), RecordType.DELETE)
        assertFailsWith<CorruptionException>("no docIds") { body.readDelete(deleteRecord) }

        val relocateBytes = record { ControlRecords.writeRelocate(it, 17, relocate()) }
        body.open(relocateBytes, LogFormat.RECORD_HEADER_BYTES, lengthOf(relocateBytes), RecordType.RELOCATE)
        val relocateRecord = RelocateInfo()
        body.readRelocate(relocateRecord)
        assertEquals(listOf(17L, 5L, 3L), listOf(body.lsn, relocateRecord.oldFileNo, relocateRecord.fragmentCount))
        assertEquals(listOf(Triple(12L, 64L, 1024L), Triple(13L, 64L, 200L)), (0 until relocateRecord.extents.count).map { Triple(relocateRecord.extents.fileNo(it), relocateRecord.extents.start(it), relocateRecord.extents.end(it)) })
        val intoOldFile = relocate().apply { extents.add(5, 64, 128) }
        val noFragments = relocate().apply { fragmentCount = 0 }
        for (bad in listOf(intoOldFile, noFragments)) {
            // writeRelocate refuses a record without fragments; write it by hand.
            val bytes = record { out ->
                val start = LogFormat.beginRecord(out)
                out.writeLongLE(17)
                out.writeIntLE(bad.oldFileNo.toInt())
                out.writeIntLE(bad.fragmentCount.toInt())
                out.writeVarLong(bad.extents.count.toLong())
                for (i in 0 until bad.extents.count) {
                    out.writeIntLE(bad.extents.fileNo(i).toInt())
                    out.writeLongLE(bad.extents.start(i))
                    out.writeLongLE(bad.extents.end(i))
                }
                LogFormat.endRecord(out, start, RecordType.RELOCATE)
            }
            body.open(bytes, LogFormat.RECORD_HEADER_BYTES, lengthOf(bytes), RecordType.RELOCATE)
            assertFailsWith<CorruptionException> { body.readRelocate(relocateRecord) }
        }
        assertEquals(12L to LogFormat.LANE_GC, LogFiles.parseLane(LogFiles.laneName(12, LogFormat.LANE_GC)))
        assertEquals("lane-G-000012.frag", LogFiles.laneName(12, LogFormat.LANE_GC))
        assertEquals(4L to 3, LogFiles.parseLane(LogFiles.laneName(4, 3)))
        val gcHeader = ByteArray(LogFormat.HEADER_BYTES)
        LogFormat.writeHeader(gcHeader, control = false, fileNo = 12, createdLsn = 0, laneId = LogFormat.LANE_GC)
        assertEquals(LogFormat.LANE_GC, LogFormat.readHeader(gcHeader)!!.laneId)
        // A GC-lane flag with another lane id isn't a valid header.
        LogFormat.writeHeader(gcHeader, control = false, fileNo = 12, createdLsn = 0, laneId = 2)
        gcHeader[6] = (gcHeader[6].toInt() or LogFormat.FLAG_GC_LANE).toByte()
        LittleEndian.putInt(gcHeader, 56, Crc32c.of(gcHeader, 0, 56))
        assertNull(LogFormat.readHeader(gcHeader))

        val key = "k".toByteArray()
        val fragment = record { FragmentRecords.write(it, 7, 9, FragmentKind.SUBTREE, 0, FragmentContext().set(3, 3, key, 0, 1, 0), byteArrayOf(0, 1), 0, 2, stored) }
        val view = FragmentView()
        FragmentRecords.decode(fragment, LogFormat.RECORD_HEADER_BYTES, lengthOf(fragment), ByteReader(fragment), view, "test")
        assertEquals(listOf(7L, 9L, FragmentKind.SUBTREE.toLong(), 3L, 3L, 2L), listOf(view.docId, view.pre, view.kind.toLong(), view.context.parentPre, view.context.ownerObjectPre, view.payloadLength.toLong()))

        val manifest = Manifest(6, 1024, 4096, false, 10, 43, 9, listOf(ManifestFile(2, true, 0, true), ManifestFile(4, false, 0, false, 512, 64)), 7, 8, 5, 6, listOf(ManifestSegment(9, 2, 100), ManifestSegment(3, 3, 64)), listOf(ManifestWatermark(40, 4, 64)))
        val decoded = Manifest.decode(manifest.encode(), MANIFEST_PATH)
        assertEquals(listOf(6L, 1024L, 10L, 43L, 9L, 7L, 8L), listOf(decoded.manifestNo, decoded.fragmentBytes.toLong(), decoded.nextFileNo, decoded.nextDocId, decoded.lastLsn, decoded.indexedUpToLsn, decoded.abortedFileNo))
        assertEquals(512L, decoded.files[1].committedEnd)
        assertEquals(64L, decoded.files[1].discardedBytes)
        assertEquals(listOf(listOf(9L, 2L, 100L), listOf(3L, 3L, 64L)), decoded.segments.map { listOf(it.fileNo, it.index.toLong(), it.fileBytes) })
        assertEquals(5L, decoded.typeDictionaryFileNo)
        assertEquals(6L, decoded.deletedFileNo)
        assertFailsWith<CorruptionException> { Manifest.decode(Manifest(6, 1024, 4096, false, 10, 43, 9, emptyList(), deletedFileNo = 10).encode(), MANIFEST_PATH) }
        assertEquals(listOf(40L, 4L, 64L), decoded.watermarks.single().let { listOf(it.docId, it.fileNo, it.offset) })
        assertFailsWith<CorruptionException> { Manifest.decode(Manifest(6, 1024, 4096, false, 10, 43, 9, emptyList(), indexedUpToLsn = 10).encode(), MANIFEST_PATH) }
        assertFalse(decoded.hashTerms)
        val damaged = manifest.encode().also { it[20] = (it[20] + 1).toByte() }
        assertFailsWith<CorruptionException> { Manifest.decode(damaged, MANIFEST_PATH) }
        assertFailsWith<CorruptionException> { Manifest.decode(ByteArray(3), MANIFEST_PATH) }
    }

    @Test
    fun `an intact MANIFEST of another format version is another format, and a damaged one is damage`() {
        val bytes = Manifest(6, 1024, 4096, false, 10, 43, 9, emptyList()).encode()
        for (version in listOf(0, Manifest.FORMAT_VERSION + 1, 0xFFFF)) {
            val e = assertFailsWith<UnsupportedFormatException> { Manifest.decode(withFormatVersion(bytes, version), MANIFEST_PATH) }
            assertEquals(MANIFEST_PATH.parent, e.directory)
            assertEquals(version, e.version)
            assertEquals(listOf(Manifest.FORMAT_VERSION), e.supportedVersions)
        }
        // The same version field changed without the checksum: a bit flipped in a MANIFEST of this format.
        val flipped = bytes.copyOf().also { it[4] = (it[4].toInt() xor 2).toByte() }
        assertEquals("checksum mismatch", assertFailsWith<CorruptionException> { Manifest.decode(flipped, MANIFEST_PATH) }.detail)
    }

    /**
     * Every format version the store's files are written in, by file. A change to one changes the store's format, so
     * [Manifest.FORMAT_VERSION] must change with it, or an older Delva would read the store's MANIFEST and then misread
     * or refuse its other files as damage; then update this list.
     */
    @Test
    fun `a file format changes only with the store's format version`() {
        val versions = mapOf(
            "store (MANIFEST)" to Manifest.FORMAT_VERSION,
            "log files" to LogFormat.FORMAT_VERSION,
            "segments" to SegmentFormat.FORMAT_VERSION,
            "payloads" to PayloadFormat.FORMAT_VERSION,
            "compression dictionaries" to DictionaryFiles.FORMAT_VERSION,
            "type dictionary" to TypeDictionary.FORMAT_VERSION,
            "docId bitmaps" to DocIdSet.FORMAT_VERSION,
        )
        val store1 = mapOf(
            "store (MANIFEST)" to 1, "log files" to 2, "segments" to 2, "payloads" to 1, "compression dictionaries" to 1,
            "type dictionary" to 1, "docId bitmaps" to 1,
        )
        assertEquals(store1, versions)
    }

    private fun lengthOf(record: ByteArray): Int = (record[4].toInt() and 0xFF) or ((record[5].toInt() and 0xFF) shl 8)

    @Test
    fun `the record reader tells records, the end and invalid bytes apart`() {
        val fs = SimulatedFileSystemIo()
        val path = Path.of("d", "f")
        fs.createDirectories(path.parent)
        val records = GrowableBytes(256)
        ControlRecords.writeBegin(records, 1, 1, 0)
        val second = records.size
        ControlRecords.writeAbort(records, 2, 1, 0)
        val third = records.size
        ControlRecords.writeBegin(records, 3, 2, 0)
        fs.open(path, OpenMode.CREATE_NEW).use { file ->
            file.write(0, records.array, 0, records.size)
            file.write(records.size.toLong(), ByteArray(64), 0, 64)
            file.force(true)
        }
        val io = fs.open(path, OpenMode.READ)
        val reader = RecordReader(1024)
        reader.open(io, 0, io.size())
        assertEquals(RecordReader.RECORD, reader.next())
        assertEquals(RecordType.BEGIN, reader.type)
        assertEquals(RecordReader.RECORD, reader.next())
        assertEquals(RecordReader.RECORD, reader.next())
        assertEquals(RecordReader.END, reader.next())
        assertFalse(reader.hasValidRecordFrom(reader.position + 8))

        fs.flipBit(path, second + 14L, 0)
        reader.open(io, 0, io.size())
        assertEquals(RecordReader.RECORD, reader.next())
        assertEquals(RecordReader.INVALID, reader.next())
        assertEquals(second.toLong(), reader.position)
        assertTrue(reader.hasValidRecordFrom(second + 8L))
        assertEquals(third.toLong(), reader.position, "positioned at the found record, not past it")
        assertEquals(RecordReader.RECORD, reader.next())
        assertEquals(RecordType.BEGIN, reader.type)

        reader.open(io, 0, third + 5L)
        reader.next()
        reader.next()
        assertEquals(RecordReader.INVALID, reader.next(), "a record cut by the end of the range")
    }

    @Test
    fun `log files preallocate, roll and seal`() {
        val fs = SimulatedFileSystemIo()
        fs.createDirectories(Path.of("d"))
        val options = LogOptions(laneFileBytes = 4096, controlFileBytes = 4096, preallocateBytes = 1024, writeBufferBytes = 4096)
        val io = fs.open(Path.of("d/f"), OpenMode.CREATE_NEW)
        val file = LogFile.create(io, 1, control = true, laneId = 0, createdLsn = 1, options)
        assertEquals(LogFormat.HEADER_BYTES.toLong(), fs.bytes(Path.of("d/f")).size.toLong())
        val record = record { ControlRecords.writeBegin(it, 1, 1, 0) }
        var appended = 0
        while (file.fits(record.size.toLong())) {
            file.append(record, 0, record.size)
            appended++
        }
        file.force()
        assertEquals((4096 - LogFormat.HEADER_BYTES - ControlRecords.SEAL_RECORD_BYTES) / record.size, appended)
        assertTrue(fs.bytes(Path.of("d/f")).size <= 4096 && fs.bytes(Path.of("d/f")).size % 1024 == 0, "preallocated in 1 KiB steps")
        file.seal(GrowableBytes(64))
        val size = fs.bytes(Path.of("d/f")).size.toLong()
        assertEquals(file.end, size)
        assertTrue(size <= 4096)
        val reader = RecordReader(1024)
        reader.open(io, LogFormat.HEADER_BYTES.toLong(), size)
        repeat(appended) { assertEquals(RecordReader.RECORD, reader.next()) }
        assertEquals(RecordReader.RECORD, reader.next())
        assertEquals(RecordType.SEAL, reader.type)
        assertEquals(RecordReader.END, reader.next())
        assertNotNull(LogFormat.readHeader(fs.bytes(Path.of("d/f")).copyOf(LogFormat.HEADER_BYTES)))
        assertNull(LogFormat.readHeader(ByteArray(LogFormat.HEADER_BYTES)))
    }
}
