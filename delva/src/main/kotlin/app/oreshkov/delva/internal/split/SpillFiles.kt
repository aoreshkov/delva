package app.oreshkov.delva.internal.split

import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * Temporary storage for sorted runs that don't fit in memory (duplicate-key hashes). Runs are written once, then
 * read sequentially from the start.
 */
internal interface SpillFiles {
    fun create(): SpillRun
}

/** A run is open (holds resources such as a file handle) only while it is written, and from its first read to its end. */
internal interface SpillRun {
    fun write(bytes: ByteArray, offset: Int, length: Int)

    /** Ends writing; reading starts at the beginning. */
    fun finishWriting()

    /** Reads up to [length] bytes; returns the count, or -1 at the end. */
    fun read(bytes: ByteArray, offset: Int, length: Int): Int

    /** Deletes the run; safe to call more than once, and doesn't throw when the run's file can't be closed or deleted. */
    fun delete()
}

/** Runs kept on the heap; for tests, and to count spills and open runs. */
internal class MemorySpillFiles : SpillFiles {
    /** Runs created so far. */
    var created: Int = 0
        private set

    /** Runs not yet deleted. */
    var live: Int = 0
        private set

    /** Runs open now: being written, or read and not yet at their end or deleted. */
    var open: Int = 0
        private set

    /** The most runs open at once. */
    var peakOpen: Int = 0
        private set

    private fun opened() {
        open++
        peakOpen = maxOf(peakOpen, open)
    }

    override fun create(): SpillRun {
        created++
        live++
        opened()
        return object : SpillRun {
            private var data = ByteArray(1024)
            private var size = 0
            private var position = 0
            private var state = WRITING

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                check(state == WRITING)
                if (size + length > data.size) data = data.copyOf(maxOf(size + length, data.size * 2))
                System.arraycopy(bytes, offset, data, size, length)
                size += length
            }

            override fun finishWriting() {
                check(state == WRITING)
                state = WRITTEN
                open--
                position = 0
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (state == WRITTEN) {
                    state = READING
                    opened()
                }
                if (state != READING) return -1
                if (position == size) {
                    state = ENDED
                    open--
                    return -1
                }
                val n = minOf(length, size - position)
                System.arraycopy(data, position, bytes, offset, n)
                position += n
                return n
            }

            override fun delete() {
                if (state == DELETED) return
                if (state == WRITING || state == READING) open--
                state = DELETED
                data = ByteArray(0)
                live--
            }
        }
    }

    private companion object {
        const val WRITING = 0
        const val WRITTEN = 1
        const val READING = 2
        const val ENDED = 3
        const val DELETED = 4
    }
}

/**
 * Runs as files in [directory] (the store's `tmp/`), deleted when the run is deleted; names are unique within this
 * instance. A run's file is closed between writing and reading, and after its end is read, so the handles open at
 * once are the runs being written or merged. Leftovers after a crash are removed when the store opens.
 */
internal class FileSystemSpillFiles(private val io: FileSystemIo, private val directory: Path) : SpillFiles {
    private val counter = AtomicLong()

    override fun create(): SpillRun {
        io.createDirectories(directory)
        var path: Path
        var created: FileIo
        while (true) {
            path = directory.resolve("dupkeys-${System.identityHashCode(this)}-${counter.incrementAndGet()}.spill")
            if (io.exists(path)) continue
            created = io.open(path, OpenMode.CREATE_NEW)
            break
        }
        return object : SpillRun {
            private var file: FileIo? = created
            private var position = 0L
            private var ended = false
            private var deleted = false

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                file!!.write(position, bytes, offset, length)
                position += length
            }

            override fun finishWriting() {
                position = 0
                closeFile()
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (ended || deleted) return -1
                val open = file ?: io.open(path, OpenMode.READ).also { file = it }
                val n = open.read(position, bytes, offset, length)
                if (n <= 0) {
                    ended = true
                    closeFile()
                    return -1
                }
                position += n
                return n
            }

            override fun delete() {
                if (deleted) return
                deleted = true
                // A file that can't be closed or deleted is removed with the rest of tmp/ when the store opens next.
                try {
                    closeFile()
                } catch (_: IOException) {
                }
                try {
                    io.delete(path)
                } catch (_: IOException) {
                }
            }

            private fun closeFile() {
                val open = file ?: return
                file = null
                open.close()
            }
        }
    }
}
