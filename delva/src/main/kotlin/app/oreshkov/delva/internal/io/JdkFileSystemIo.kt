package app.oreshkov.delva.internal.io

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * [FileSystemIo] over the JDK's `FileChannel` and `Files`.
 *
 * A `FileChannel` is interruptible: a thread interrupted in a call closes the channel for every thread that shares
 * it. Delva's calls aren't interruptible instead. They run with the caller's interrupt flag cleared and set it again
 * before they return, so an interrupt is never lost, and a channel that an interrupt closed anyway is reopened.
 *
 * With [mmapReads], files opened read-only — the sealed, fsynced segment files — are read through a shared
 * read-only mapping (FFM `FileChannel.map`) instead of `pread`: one page-cache copy less per read, at the price of a
 * mapping per segment. The mapping is unmapped when the file is closed, which the LSM does before it deletes a
 * compacted segment, so Windows can delete it. Off by default.
 *
 * [openChannel] opens every channel; tests replace it to inject failures.
 */
internal class JdkFileSystemIo(
    private val mmapReads: Boolean = false,
    private val openChannel: (Path, Array<OpenOption>) -> FileChannel = { path, options -> FileChannel.open(path, *options) },
) : FileSystemIo {
    override val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    override fun open(path: Path, mode: OpenMode): FileIo = withoutInterrupts {
        val channel = openChannel(path, mode.openOptions())
        try {
            if (mode == OpenMode.READ && mmapReads) {
                val size = channel.size()
                if (size > 0) {
                    try {
                        return MappedFileIo(path, channel, size)
                    } catch (_: IOException) {
                        // Out of address space or an unmappable file: read it the ordinary way, through a fresh channel.
                    } catch (_: UnsupportedOperationException) {
                    }
                    channel.close()
                    return JdkFileIo(path, READ_OPTIONS, openChannel(path, READ_OPTIONS), openChannel)
                }
            }
            JdkFileIo(path, mode.reopenOptions(), channel, openChannel)
        } catch (t: Throwable) {
            channel.close()
            throw t
        }
    }

    override fun exists(path: Path): Boolean = Files.exists(path)

    override fun list(dir: Path): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream -> stream.sorted(compareBy { it.fileName.toString() }).toList() }
    }

    override fun createDirectories(dir: Path) {
        Files.createDirectories(dir)
    }

    override fun atomicRename(from: Path, to: Path) {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun delete(path: Path) {
        Files.deleteIfExists(path)
    }

    override fun syncDirectory(dir: Path) {
        if (isWindows) return
        onOwnThread { openChannel(dir, READ_OPTIONS).use { it.force(true) } }
    }

    override fun tryLock(path: Path): AutoCloseable? = withoutInterrupts {
        val channel = openChannel(path, LOCK_OPTIONS)
        val lock = try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (e: IOException) {
            channel.close()
            throw e
        }
        if (lock == null) {
            channel.close()
            return null
        }
        AutoCloseable {
            try {
                lock.release()
            } finally {
                channel.close()
            }
        }
    }

    private companion object {
        val READ_OPTIONS: Array<OpenOption> = arrayOf(StandardOpenOption.READ)
        val READ_WRITE_OPTIONS: Array<OpenOption> = arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE)
        val CREATE_NEW_OPTIONS: Array<OpenOption> = arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
        val LOCK_OPTIONS: Array<OpenOption> = arrayOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)

        fun OpenMode.openOptions(): Array<OpenOption> = when (this) {
            OpenMode.READ -> READ_OPTIONS
            OpenMode.READ_WRITE -> READ_WRITE_OPTIONS
            OpenMode.CREATE_NEW -> CREATE_NEW_OPTIONS
        }

        /** How a channel that an interrupt closed is opened again: the file exists by then. */
        fun OpenMode.reopenOptions(): Array<OpenOption> = if (this == OpenMode.READ) READ_OPTIONS else READ_WRITE_OPTIONS
    }
}

/**
 * Runs [block] with the interrupt flag cleared, again when an interrupt that came in during it closed a channel, and
 * sets the flag again before returning if it was set. For calls whose channels are their own, so a retry starts over.
 */
private inline fun <T> withoutInterrupts(block: () -> T): T {
    var interrupted = Thread.interrupted()
    try {
        while (true) {
            try {
                return block()
            } catch (_: ClosedByInterruptException) {
                Thread.interrupted()
                interrupted = true
            }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

/**
 * A file read and written through a channel that every thread shares. Positional reads and writes can be repeated, so
 * a call that finds the channel closed by an interrupt (its own or another thread's) reopens it and runs again, unless
 * [close] closed it. A force can't: an interrupted one loses its result. So it runs on its own thread, which no
 * interrupt reaches, and fails if another thread's interrupt closed the channel while it ran.
 */
private class JdkFileIo(
    override val path: Path,
    private val reopenOptions: Array<OpenOption>,
    @Volatile private var channel: FileChannel,
    private val openChannel: (Path, Array<OpenOption>) -> FileChannel,
) : FileIo {
    private val lock = Any()

    @Volatile
    private var closed = false

    override fun read(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
        val buffer = ByteBuffer.wrap(destination, offset, length)
        var at = position
        while (buffer.hasRemaining()) {
            val n = call { it.read(buffer, at) }
            if (n < 0) break
            at += n
        }
        return buffer.position() - offset
    }

    override fun write(position: Long, source: ByteArray, offset: Int, length: Int) {
        val buffer = ByteBuffer.wrap(source, offset, length)
        var at = position
        while (buffer.hasRemaining()) at += call { it.write(buffer, at) }
    }

    override fun force(metadata: Boolean) = onOwnThread {
        call { channel ->
            try {
                channel.force(metadata)
            } catch (e: AsynchronousCloseException) {
                if (closed) throw e
                throw IOException("$path was closed by an interrupt during a force; the written bytes are in an unknown state", e)
            }
        }
    }

    override fun truncate(size: Long) {
        call { it.truncate(size) }
    }

    override fun size(): Long = call { it.size() }

    override fun close() = synchronized(lock) {
        closed = true
        channel.close()
    }

    private inline fun <T> call(block: (FileChannel) -> T): T {
        var interrupted = Thread.interrupted()
        try {
            while (true) {
                val current = channel
                try {
                    return block(current)
                } catch (e: ClosedChannelException) {
                    // A ClosedByInterruptException leaves the flag set; the retry runs without it.
                    if (Thread.interrupted()) interrupted = true
                    reopen(current, e)
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** Replaces [failed] by a new channel, unless another caller did already, or [close] closed the file. */
    private fun reopen(failed: FileChannel, cause: ClosedChannelException) = synchronized(lock) {
        if (closed) throw cause
        if (channel === failed) channel = openChannel(path, reopenOptions)
    }
}

/**
 * A read-only file read through a shared mapping. The mapping lives in an `Arena` closed by [close], which
 * releases the addresses: a read after that throws, so the LSM must close a segment before deleting its file, as it
 * already does. Reads copy into the caller's array, so nothing refers to the mapping afterwards, and can't be
 * interrupted. Thread-safe.
 */
private class MappedFileIo(override val path: Path, private val channel: FileChannel, private val size: Long) : FileIo {
    private val arena: Arena = Arena.ofShared()
    private val segment: MemorySegment = try {
        channel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena)
    } catch (t: Throwable) {
        arena.close()
        throw t
    }

    override fun read(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return 0
        val n = minOf(length.toLong(), size - position).toInt()
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, position, destination, offset, n)
        return n
    }

    override fun write(position: Long, source: ByteArray, offset: Int, length: Int): Unit = throw IOException("$path is mapped read-only")

    override fun force(metadata: Boolean): Unit = throw IOException("$path is mapped read-only")

    override fun truncate(size: Long): Unit = throw IOException("$path is mapped read-only")

    override fun size(): Long = size

    override fun close() {
        try {
            arena.close()
        } finally {
            channel.close()
        }
    }
}
