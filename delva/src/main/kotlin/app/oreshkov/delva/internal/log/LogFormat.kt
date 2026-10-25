package app.oreshkov.delva.internal.log

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.bytes.Crc32c
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian

/**
 * Byte layout of fragment-log files. Stored integers are little-endian.
 *
 * ```
 * file header (64 B) := magic:4B ("FLOG" lane, "CLOG" control) | formatVersion:u16 | flags:u16 | fileNo:u64
 *                      | createdLsn:u64 | laneId:u32 | dictSetId:u32 | reserved:24B | headerCrc32c:u32 | padding:u32
 * record            := magic:u16 = 0xF7A5 | type:u8 | flags:u8 | length:u32 | crc32c:u32 | body[length]
 *                      | zero padding to an 8-byte boundary
 * header flags      := bit 0 lane file, bit 1 control log, bit 2 GC lane (with bit 0; laneId is then LANE_GC)
 * record flags      := bit 0 AFTER_SYNC (control log only)
 * ```
 * The header CRC covers bytes 0–55; a record CRC covers type, flags, length and body. Records start at 8-byte
 * aligned offsets from the header's end; a zero `magic` marks the logical end of a file (zero-filled preallocation).
 */
internal object LogFormat {
    const val FORMAT_VERSION: Int = 2
    const val HEADER_BYTES: Int = 64
    const val RECORD_HEADER_BYTES: Int = 12
    const val RECORD_MAGIC: Int = 0xF7A5
    const val ALIGNMENT: Int = 8

    const val FLAG_LANE: Int = 1
    const val FLAG_CONTROL: Int = 2
    const val FLAG_GC_LANE: Int = 4

    /** Lane ids: the small-insert lane, then large lanes L1, L2 … */
    const val LANE_SMALL: Int = 0

    /** Lane id of the GC lane, which space reclamation copies live fragments to. */
    const val LANE_GC: Int = Int.MAX_VALUE

    /** Largest control record body; COMMIT extent lists stay far below it. */
    const val MAX_CONTROL_BODY_BYTES: Int = 1 shl 20

    private const val MAGIC_LANE = 0x474F4C46 // "FLOG" read little-endian
    private const val MAGIC_CONTROL = 0x474F4C43 // "CLOG"

    fun padded(length: Int): Int = (length + ALIGNMENT - 1) and -ALIGNMENT

    fun writeHeader(out: ByteArray, control: Boolean, fileNo: Long, createdLsn: Long, laneId: Int) {
        out.fill(0, 0, HEADER_BYTES)
        LittleEndian.putInt(out, 0, if (control) MAGIC_CONTROL else MAGIC_LANE)
        LittleEndian.putShort(out, 4, FORMAT_VERSION.toShort())
        LittleEndian.putShort(out, 6, (if (control) FLAG_CONTROL else laneFlags(laneId)).toShort())
        LittleEndian.putLong(out, 8, fileNo)
        LittleEndian.putLong(out, 16, createdLsn)
        LittleEndian.putInt(out, 24, laneId)
        LittleEndian.putInt(out, 56, Crc32c.of(out, 0, 56))
    }

    /** A validated file header. */
    class Header(val control: Boolean, val fileNo: Long, val createdLsn: Long, val laneId: Int)

    /**
     * Parses the header of log file [location]; null when the bytes aren't a whole header (torn or never written).
     *
     * A header whose checksum and magic hold but whose version isn't [FORMAT_VERSION] was written whole, by another
     * version of Delva, so it throws [CorruptionException] instead: a null makes recovery delete a file it doesn't list
     * as one created but never written. It's damage rather than another format, because the store's MANIFEST, read
     * first, has a format version that fixes this one.
     */
    fun readHeader(bytes: ByteArray, location: String): Header? {
        if (bytes.size < HEADER_BYTES) return null
        if (LittleEndian.getInt(bytes, 56) != Crc32c.of(bytes, 0, 56)) return null
        val magic = LittleEndian.getInt(bytes, 0)
        val flags = LittleEndian.getShort(bytes, 6).toInt()
        val laneId = LittleEndian.getInt(bytes, 24)
        val control = when {
            magic == MAGIC_CONTROL && flags == FLAG_CONTROL -> true
            magic == MAGIC_LANE && flags == laneFlags(laneId) -> false
            else -> return null
        }
        val version = LittleEndian.getShort(bytes, 4).toInt() and 0xFFFF
        if (version != FORMAT_VERSION) throw CorruptionException(location, "log format version $version where the store's format has $FORMAT_VERSION")
        return Header(control, LittleEndian.getLong(bytes, 8), LittleEndian.getLong(bytes, 16), laneId)
    }

    private fun laneFlags(laneId: Int): Int = if (laneId == LANE_GC) FLAG_LANE or FLAG_GC_LANE else FLAG_LANE

    /**
     * Record flag of control records: the first record appended after the control file was synced. A valid record with
     * it after damaged bytes proves the damaged bytes were synced (damage, not a torn tail).
     */
    const val RECORD_FLAG_AFTER_SYNC: Int = 1

    /** Sets [flags] in the framed record at [offset] and updates its CRC. */
    fun addRecordFlags(bytes: ByteArray, offset: Int, flags: Int) {
        bytes[offset + 3] = (bytes[offset + 3].toInt() or flags).toByte()
        val bodyLength = LittleEndian.getInt(bytes, offset + 4)
        LittleEndian.putInt(bytes, offset + 8, Crc32c().update(bytes, offset + 2, 6).update(bytes, offset + RECORD_HEADER_BYTES, bodyLength).value)
    }

    /** Starts a record in [out]; write the body, then call [endRecord] with the returned start. */
    fun beginRecord(out: GrowableBytes): Int = out.reserve(RECORD_HEADER_BYTES)

    /** Fills in the record header of the body written since [start], pads, and returns the padded record length. */
    fun endRecord(out: GrowableBytes, start: Int, type: Int, flags: Int = 0): Int {
        val bodyLength = out.size - start - RECORD_HEADER_BYTES
        val a = out.array
        LittleEndian.putShort(a, start, RECORD_MAGIC.toShort())
        a[start + 2] = type.toByte()
        a[start + 3] = flags.toByte()
        LittleEndian.putInt(a, start + 4, bodyLength)
        val crc = Crc32c().update(a, start + 2, 6).update(a, start + RECORD_HEADER_BYTES, bodyLength).value
        LittleEndian.putInt(a, start + 8, crc)
        val padding = padded(out.size - start) - (out.size - start)
        val p = out.reserve(padding)
        out.array.fill(0, p, p + padding)
        return out.size - start
    }

    /** Whether `bytes[offset, offset + length)` holds a record header with a valid CRC over the body that follows it. */
    fun recordCrcMatches(bytes: ByteArray, offset: Int, bodyLength: Int, crc: Crc32c): Boolean =
        crc.reset().update(bytes, offset + 2, 6).update(bytes, offset + RECORD_HEADER_BYTES, bodyLength).value == LittleEndian.getInt(bytes, offset + 8)

}

/** Record types. */
internal object RecordType {
    const val FRAGMENT: Int = 0x01
    const val SEAL: Int = 0x02
    const val BEGIN: Int = 0x10
    const val COMMIT: Int = 0x11
    const val ABORT: Int = 0x12
    const val TYPEDEF: Int = 0x20
    const val TYPE: Int = 0x21
    const val DELETE: Int = 0x30
    const val RELOCATE: Int = 0x40
    const val CHECKPOINT: Int = 0x50
}
