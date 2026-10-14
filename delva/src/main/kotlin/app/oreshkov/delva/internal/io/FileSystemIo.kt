package app.oreshkov.delva.internal.io

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.DelvaException
import app.oreshkov.delva.StoreClosedException
import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.nio.file.Path

/** How [FileSystemIo.open] opens a file. */
internal enum class OpenMode {
    /** An existing file, read only. */
    READ,

    /** An existing file, for reading and writing. */
    READ_WRITE,

    /** A new file for reading and writing; fails if the file exists. */
    CREATE_NEW,
}

/**
 * The file system as the engine uses it: the only way the engine touches files, so tests can replace it with a
 * simulated disk that loses unsynced writes, tears records and fails `fsync`.
 *
 * Paths name files and directories; implementations don't need a real file system behind them. Every method may
 * throw [IOException]. Thread-safe.
 */
internal interface FileSystemIo {
    /** Whether directory entries are durable without [syncDirectory] and open files can't be deleted. */
    val isWindows: Boolean

    fun open(path: Path, mode: OpenMode): FileIo

    fun exists(path: Path): Boolean

    /** Files and directories directly in [dir], sorted by name; empty when [dir] doesn't exist. */
    fun list(dir: Path): List<Path>

    fun createDirectories(dir: Path)

    /** Renames [from] to [to] atomically, replacing [to] if it exists. */
    fun atomicRename(from: Path, to: Path)

    /** Deletes [path] if it exists. */
    fun delete(path: Path)

    /** Makes the creations, renames and deletions of entries in [dir] durable; a no-op on Windows. */
    fun syncDirectory(dir: Path)

    /**
     * Locks [path] (created if needed) for this process; the lock is released by closing the result. Returns null
     * when another holder has it.
     */
    fun tryLock(path: Path): AutoCloseable?
}

/**
 * An open file with positional reads and writes. Writes may be lost in a crash until [force] returns; a failed
 * [force] leaves the written bytes in an unknown state, so the engine never retries it. Thread-safe for
 * positional reads alongside writes.
 */
internal interface FileIo : AutoCloseable {
    val path: Path

    /** Reads up to [length] bytes at [position]; returns the count, which is less than [length] only at the end. */
    fun read(position: Long, destination: ByteArray, offset: Int, length: Int): Int

    fun write(position: Long, source: ByteArray, offset: Int, length: Int)

    /** Makes everything written durable; [metadata] also covers file size and times. */
    fun force(metadata: Boolean)

    fun truncate(size: Long)

    fun size(): Long

    override fun close()
}

/** Reads exactly [length] bytes at [position]; throws [java.io.EOFException] if the file ends before. */
internal fun FileIo.readFully(position: Long, destination: ByteArray, offset: Int, length: Int) {
    val n = read(position, destination, offset, length)
    if (n < length) throw java.io.EOFException("$path: $length bytes at $position, file ends after $n")
}

/**
 * The error for a read of [source] that failed with [e]. [FileIo] reopens a channel that an interrupt closed, so a
 * closed channel means the store closed it: [StoreClosedException]. Anything else leaves stored bytes unreadable.
 */
internal fun unreadable(source: String, detail: String, e: IOException): DelvaException =
    if (e is ClosedChannelException) StoreClosedException().apply { addSuppressed(e) } else CorruptionException(source, detail, e)
