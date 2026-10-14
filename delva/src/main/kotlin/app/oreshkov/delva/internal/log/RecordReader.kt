package app.oreshkov.delva.internal.log

import app.oreshkov.delva.internal.bytes.Crc32c
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.io.FileIo

/**
 * Reads framed records ([LogFormat]) one after another from a byte range of a file, through a read-ahead buffer.
 * [next] tells a valid record apart from the logical end (zero bytes, or the end of the range) and from bytes that
 * aren't a valid record (a torn write or damage); [hasValidRecordFrom] probes aligned offsets for a valid record, which
 * decides whether invalid bytes are a torn tail or damage before the tail.
 *
 * The body of the last record is `bytes[bodyOffset, bodyOffset + bodyLength)`, valid until the next call. Reusable;
 * not thread-safe.
 */
internal class RecordReader(private val maxBodyBytes: Int) {
    private var io: FileIo? = null
    private var buffer = ByteArray(64 * 1024)
    private var bufferStart = 0L
    private var bufferLength = 0
    private val crc = Crc32c()

    /** Scan limit (exclusive). */
    var end: Long = 0
        private set

    /** Where the next record starts. */
    var position: Long = 0
        private set

    val bytes: ByteArray get() = buffer

    var type: Int = 0
        private set
    var flags: Int = 0
        private set
    var recordStart: Long = 0
        private set

    /** Framed length of the last record, without padding. */
    var recordLength: Int = 0
        private set
    var bodyOffset: Int = 0
        private set
    var bodyLength: Int = 0
        private set

    fun open(io: FileIo, from: Long, to: Long) {
        require(from >= 0 && from <= to && from and (LogFormat.ALIGNMENT - 1).toLong() == 0L)
        this.io = io
        end = to
        position = from
        bufferStart = 0
        bufferLength = 0
    }

    /** Reads the record at [position]: [RECORD], [END] or [INVALID] (position unchanged). */
    fun next(): Int {
        val at = position
        if (at == end) return END
        if (end - at < LogFormat.RECORD_HEADER_BYTES) return if (allZero(at, (end - at).toInt())) END else INVALID
        if (!load(at, LogFormat.RECORD_HEADER_BYTES)) return INVALID
        var p = (at - bufferStart).toInt()
        val magic = LittleEndian.getShort(buffer, p).toInt() and 0xFFFF
        // A zero magic ends the log only where the bytes after it are zero too; one zeroed header is damage.
        if (magic == 0) return if (allZero(at, minOf(END_PROBE_BYTES.toLong(), end - at).toInt())) END else INVALID
        if (magic != LogFormat.RECORD_MAGIC) return INVALID
        val length = LittleEndian.getInt(buffer, p + 4)
        if (length < 0 || length > maxBodyBytes) return INVALID
        val padded = LogFormat.padded(LogFormat.RECORD_HEADER_BYTES + length)
        if (padded > end - at || !load(at, padded)) return INVALID
        p = (at - bufferStart).toInt()
        if (!LogFormat.recordCrcMatches(buffer, p, length, crc)) return INVALID
        type = buffer[p + 2].toInt() and 0xFF
        flags = buffer[p + 3].toInt() and 0xFF
        recordStart = at
        recordLength = LogFormat.RECORD_HEADER_BYTES + length
        bodyOffset = p + LogFormat.RECORD_HEADER_BYTES
        bodyLength = length
        position = at + padded
        return RECORD
    }

    /**
     * Whether a valid record starts at any aligned offset in `[from, end)`. If so, [position] is set to it (not yet
     * read); otherwise [position] is unchanged.
     */
    fun hasValidRecordFrom(from: Long): Boolean {
        val saved = position
        var at = (from + LogFormat.ALIGNMENT - 1) and -LogFormat.ALIGNMENT.toLong()
        while (end - at >= LogFormat.RECORD_HEADER_BYTES) {
            if (!load(at, 2)) break
            val p = (at - bufferStart).toInt()
            if ((LittleEndian.getShort(buffer, p).toInt() and 0xFFFF) == LogFormat.RECORD_MAGIC) {
                position = at
                if (next() == RECORD) {
                    position = at
                    return true
                }
            }
            at += LogFormat.ALIGNMENT
        }
        position = saved
        return false
    }

    /** Makes `[at, at + length)` available in the buffer; false if the file is shorter. */
    private fun load(at: Long, length: Int): Boolean {
        if (at >= bufferStart && at + length <= bufferStart + bufferLength) return true
        if (length > buffer.size) buffer = ByteArray(maxOf(length, buffer.size * 2))
        val want = minOf(buffer.size.toLong(), end - at).toInt()
        bufferStart = at
        bufferLength = io!!.read(at, buffer, 0, maxOf(want, length))
        return bufferLength >= length
    }

    private fun allZero(at: Long, length: Int): Boolean {
        if (!load(at, length)) return false
        val p = (at - bufferStart).toInt()
        for (i in p until p + length) if (buffer[i].toInt() != 0) return false
        return true
    }

    companion object {
        const val RECORD: Int = 1
        const val END: Int = 2
        const val INVALID: Int = 3

        /** How many bytes from a zero magic on must be zero for [next] to call it the end. */
        const val END_PROBE_BYTES: Int = 4096
    }
}
