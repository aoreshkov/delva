package app.oreshkov.delva.internal.log

import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.io.FileIo

/**
 * Sizes of fragment-log files.
 *
 * - [laneFileBytes], [controlFileBytes]: a file rolls when the next record wouldn't fit (a single oversized record
 *   still gets a file of its own).
 * - [preallocateBytes]: files grow in zero-filled steps of this size, so syncs rarely change the file size; 0 turns it
 *   off.
 * - [writeBufferBytes]: appends are buffered up to this size before they're written.
 * - [largeLaneSyncBytes]: a large lane syncs after this many appended bytes, so unsynced data and the
 *   sync before its COMMIT stay bounded; 0 syncs only before the COMMIT.
 */
internal class LogOptions(
    val laneFileBytes: Long = 256L * 1024 * 1024,
    val controlFileBytes: Long = 64L * 1024 * 1024,
    val preallocateBytes: Long = 64L * 1024 * 1024,
    val writeBufferBytes: Int = 1024 * 1024,
    val largeLaneSyncBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(laneFileBytes >= 4096 && controlFileBytes >= 4096) { "log files must be at least 4 KiB" }
        require(preallocateBytes >= 0 && writeBufferBytes >= 4096 && largeLaneSyncBytes >= 0)
    }
}

/**
 * The append end of one log file: records are buffered, written at the logical [end], and made durable
 * by [force]. [seal] ends the file with a SEAL record, truncates the preallocated rest and syncs. Not thread-safe; the
 * owner of a lane or the control log serializes access.
 */
internal class LogFile(
    val io: FileIo,
    val fileNo: Long,
    val control: Boolean,
    val laneId: Int,
    end: Long,
    private val options: LogOptions,
) {
    /** Logical end: everything appended, written or still buffered. */
    var end: Long = end
        private set

    var sealed: Boolean = false
        private set

    private val buffer = ByteArray(options.writeBufferBytes)
    private var buffered = 0
    private var allocated = io.size()
    private var sizeChanged = false

    private val maxBytes: Long get() = if (control) options.controlFileBytes else options.laneFileBytes

    /** Whether [length] more bytes fit before the file should roll, keeping room for its SEAL. */
    fun fits(length: Long): Boolean = end == LogFormat.HEADER_BYTES.toLong() || end + length + ControlRecords.SEAL_RECORD_BYTES <= maxBytes

    /** Appends padded record bytes; returns the offset of their first byte. */
    fun append(bytes: ByteArray, offset: Int, length: Int): Long {
        check(!sealed) { "file $fileNo is sealed" }
        require(length and (LogFormat.ALIGNMENT - 1) == 0) { "unaligned append of $length bytes" }
        val at = end
        if (length > buffer.size - buffered) {
            flush()
            if (length > buffer.size) {
                writeOut(at, bytes, offset, length)
                end += length
                return at
            }
        }
        System.arraycopy(bytes, offset, buffer, buffered, length)
        buffered += length
        end += length
        return at
    }

    /** Writes buffered bytes to the file (not durably). */
    fun flush() {
        if (buffered == 0) return
        writeOut(end - buffered, buffer, 0, buffered)
        buffered = 0
    }

    fun force() {
        flush()
        io.force(sizeChanged)
        sizeChanged = false
    }

    /** Appends SEAL, drops the preallocated rest and syncs. */
    fun seal(scratch: GrowableBytes) {
        scratch.clear()
        ControlRecords.writeSeal(scratch, fileNo)
        append(scratch.array, 0, scratch.size)
        flush()
        io.truncate(end)
        allocated = end
        io.force(true)
        sizeChanged = false
        sealed = true
    }

    fun close() = io.close()

    private fun writeOut(at: Long, bytes: ByteArray, offset: Int, length: Int) {
        val needed = at + length
        if (needed > allocated) {
            if (options.preallocateBytes > 0) {
                val step = options.preallocateBytes
                val target = maxOf(needed, minOf((needed + step - 1) / step * step, maxBytes))
                zeroFill(maxOf(allocated, at), target)
                allocated = target
            } else {
                allocated = needed
            }
            sizeChanged = true
        }
        io.write(at, bytes, offset, length)
    }

    private fun zeroFill(from: Long, to: Long) {
        var at = from
        while (at < to) {
            val n = minOf(ZEROS.size.toLong(), to - at).toInt()
            io.write(at, ZEROS, 0, n)
            at += n
        }
    }

    companion object {
        private val ZEROS = ByteArray(1024 * 1024)

        /** Writes the header of a new, empty file and syncs it. */
        fun create(io: FileIo, fileNo: Long, control: Boolean, laneId: Int, createdLsn: Long, options: LogOptions): LogFile {
            val header = ByteArray(LogFormat.HEADER_BYTES)
            LogFormat.writeHeader(header, control, fileNo, createdLsn, laneId)
            io.write(0, header, 0, header.size)
            io.force(true)
            return LogFile(io, fileNo, control, laneId, LogFormat.HEADER_BYTES.toLong(), options)
        }

        /** Continues appending at [end] of an existing file, dropping whatever follows it. */
        fun reopen(io: FileIo, fileNo: Long, control: Boolean, laneId: Int, end: Long, options: LogOptions): LogFile {
            if (io.size() != end) {
                io.truncate(end)
                io.force(true)
            }
            return LogFile(io, fileNo, control, laneId, end, options)
        }
    }
}
