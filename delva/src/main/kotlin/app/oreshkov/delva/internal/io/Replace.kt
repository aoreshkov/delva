package app.oreshkov.delva.internal.io

import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Path

/** Waits before each retry of [replace], doubling: about a second in all. */
private val REPLACE_BACKOFF_MILLIS = longArrayOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512)

/**
 * [FileSystemIo.atomicRename] of [from] onto [to], a name that other processes may have open, such as `CURRENT`. On
 * Windows a target that another handle has open can't be replaced: the rename fails with `AccessDeniedException`, or
 * with a sharing violation when that handle didn't allow deletion. Readers, antivirus and backup tools hold such a
 * handle only for a moment, so the rename is retried with backoff for about a second before the failure is passed on.
 * A failed rename changes nothing, so retrying it is safe. Elsewhere it is a plain [FileSystemIo.atomicRename].
 */
internal fun FileSystemIo.replace(from: Path, to: Path, sleep: (Long) -> Unit = Thread::sleep) {
    var attempt = 0
    while (true) {
        try {
            atomicRename(from, to)
            return
        } catch (e: FileSystemException) {
            if (!isWindows || !isTransientlyInUse(e) || attempt == REPLACE_BACKOFF_MILLIS.size) throw e
            try {
                sleep(REPLACE_BACKOFF_MILLIS[attempt++])
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                e.addSuppressed(interrupted)
                throw e
            }
        }
    }
}

/** Access denied, or a sharing violation (which the JDK reports as a plain [FileSystemException]). */
private fun isTransientlyInUse(e: FileSystemException): Boolean = e is AccessDeniedException || e.javaClass == FileSystemException::class.java
