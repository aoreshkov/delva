package app.oreshkov.delva.internal.store

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.bytes.Crc32c
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.compress.FragmentCodecs
import app.oreshkov.delva.internal.io.readFully
import app.oreshkov.delva.internal.io.unreadable
import app.oreshkov.delva.internal.log.FragmentRecords
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.log.LaneReaders
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.log.RecordType
import app.oreshkov.delva.internal.lsm.ByteCache
import app.oreshkov.delva.internal.lsm.FragmentEntry
import app.oreshkov.delva.internal.lsm.FragmentIndex
import java.io.IOException

/**
 * Fragments stored in lane files, located through a [FragmentIndex] whose entries hold [location]s.
 * Every read checks the record's frame and CRC; damage throws [CorruptionException] for that read only.
 * Verified records are kept in the byte-bounded fragment [cache] and never modified, so views of them stay
 * valid; a compressed record is kept inflated, as the image of a NONE record with the same payload offset (only its
 * frame header still describes the stored record). Thread-safe.
 */
internal class LogFragmentStore(
    private val files: LaneReaders,
    val index: FragmentIndex,
    private val cache: ByteCache,
    private val codecs: FragmentCodecs,
    private val maxRawLength: Int,
) : FragmentStore {
    override fun find(docId: Long, pre: Long, kind: Int, seq: Int, into: FragmentView): Boolean {
        val entry = index.find(docId, pre, kind, seq) ?: return false
        read(entry, into)
        return true
    }

    override fun floor(docId: Long, pre: Long, kind: Int, seq: Int, into: FragmentView): Boolean {
        val entry = index.floor(docId, pre, kind, seq) ?: return false
        read(entry, into)
        return true
    }

    override fun payloadBytes(docId: Long, fromPre: Long, fromKind: Int, toPre: Long): Long {
        if (toPre < fromPre) return 0
        var total = 0L
        for (entry in index.range(docId, fromPre, fromKind, toPre)) total += entry.rawLength
        return total
    }

    private fun read(entry: FragmentEntry, into: FragmentView) {
        val fileNo = fileNoOf(entry.location)
        val offset = offsetOf(entry.location)
        val source = "lane file $fileNo@$offset"
        val cached = cache.get(entry.location)
        if (cached != null && cached.size == entry.payloadOffset + entry.rawLength) {
            decode(entry, cached, into, source)
            return
        }
        // The length comes from an index file: no record is shorter than its header or longer than the largest fragment.
        if (entry.length < LogFormat.RECORD_HEADER_BYTES || entry.length > LogFormat.RECORD_HEADER_BYTES + maxRawLength) {
            throw CorruptionException(source, "fragment (${entry.docId}, ${entry.pre}, ${entry.kind}, ${entry.seq}) is indexed with ${entry.length} record bytes")
        }
        val bytes = ByteArray(entry.length)
        try {
            files.laneReader(fileNo).readFully(offset, bytes, 0, bytes.size)
        } catch (e: IOException) {
            throw unreadable(source, "unreadable fragment record", e)
        }
        val bodyLength = entry.length - LogFormat.RECORD_HEADER_BYTES
        if ((LittleEndian.getShort(bytes, 0).toInt() and 0xFFFF) != LogFormat.RECORD_MAGIC || bytes[2].toInt() != RecordType.FRAGMENT ||
            LittleEndian.getInt(bytes, 4) != bodyLength || !LogFormat.recordCrcMatches(bytes, 0, bodyLength, Crc32c())
        ) {
            throw CorruptionException(source, "fragment record of fragment (${entry.docId}, ${entry.pre}, ${entry.kind}, ${entry.seq}) is damaged")
        }
        decode(entry, bytes, into, source)
        val image = if (into.compressed) inflated(entry, bytes, into, source) else bytes
        cache.put(entry.location, image)
    }

    /** Inflates the record [into] was decoded from into the image of a NONE record and points [into] at it. */
    private fun inflated(entry: FragmentEntry, bytes: ByteArray, into: FragmentView, source: String): ByteArray {
        val image = ByteArray(entry.payloadOffset + into.rawLength)
        System.arraycopy(bytes, 0, image, 0, entry.payloadOffset)
        val codec = entry.payloadOffset - FragmentCodecs.CODEC_HEADER_BYTES
        image[codec] = FragmentCodecs.NONE.toByte()
        image[codec + 1] = 0
        image[codec + 2] = 0
        codecs.inflate(into.codecId, into.dictId, bytes, into.payloadOffset, into.payloadLength, image, entry.payloadOffset, into.rawLength, source)
        decode(entry, image, into, source)
        return image
    }

    /** Decodes a record or a cached image (whose frame header may describe the stored record instead). */
    private fun decode(entry: FragmentEntry, bytes: ByteArray, into: FragmentView, source: String) {
        FragmentRecords.decode(bytes, LogFormat.RECORD_HEADER_BYTES, bytes.size - LogFormat.RECORD_HEADER_BYTES, ByteReader(bytes), into, source, maxRawLength)
        if (into.docId != entry.docId || into.pre != entry.pre || into.kind != entry.kind || into.seq != entry.seq || into.payloadOffset != entry.payloadOffset ||
            into.rawLength != entry.rawLength
        ) {
            throw CorruptionException(source, "holds fragment (${into.docId}, ${into.pre}, ${into.kind}, ${into.seq}), index says (${entry.docId}, ${entry.pre}, ${entry.kind}, ${entry.seq})")
        }
    }

    companion object {
        private const val OFFSET_BITS = 40

        /** Packs a lane file position into [FragmentEntry.location]. */
        fun location(fileNo: Long, offset: Long): Long {
            require(fileNo in 1..LogFiles.MAX_FILE_NO && offset >= 0 && offset < (1L shl OFFSET_BITS)) { "location ($fileNo, $offset)" }
            return (fileNo shl OFFSET_BITS) or offset
        }

        fun fileNoOf(location: Long): Long = location ushr OFFSET_BITS

        fun offsetOf(location: Long): Long = location and ((1L shl OFFSET_BITS) - 1)
    }
}
