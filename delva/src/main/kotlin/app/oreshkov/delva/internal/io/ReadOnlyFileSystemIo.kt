package app.oreshkov.delva.internal.io

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * [delegate] for a process that only reads a store beside its writer: it can open files for reading, test and list,
 * and nothing else. Every call that would change the directory, and every open for writing, throws
 * [IllegalStateException] and counts in [refused], so a secondary never changes a store by construction.
 *
 * It also refuses to open [writerFiles], the names of the files the writer replaces or locks: on Windows a file another
 * process has open can't be replaced, so opening one of them could fail the writer.
 */
internal class ReadOnlyFileSystemIo(private val delegate: FileSystemIo, private val writerFiles: Set<String>) : FileSystemIo {
    /** Calls refused so far; tests assert that a secondary makes none. */
    val refused = AtomicLong()

    override val isWindows: Boolean get() = delegate.isWindows

    override fun open(path: Path, mode: OpenMode): FileIo {
        if (mode != OpenMode.READ) refuse("open $path for writing")
        if (path.fileName?.toString() in writerFiles) refuse("open $path, which the writer replaces")
        return delegate.open(path, mode)
    }

    override fun exists(path: Path): Boolean = delegate.exists(path)

    override fun list(dir: Path): List<Path> = delegate.list(dir)

    override fun createDirectories(dir: Path): Unit = refuse("create $dir")

    override fun atomicRename(from: Path, to: Path): Unit = refuse("rename $from")

    override fun delete(path: Path): Unit = refuse("delete $path")

    override fun syncDirectory(dir: Path): Unit = refuse("sync $dir")

    override fun tryLock(path: Path): AutoCloseable? = refuse("lock $path")

    private fun refuse(what: String): Nothing {
        refused.incrementAndGet()
        throw IllegalStateException("a secondary only reads the store: it can't $what")
    }
}
