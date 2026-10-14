package app.oreshkov.delva.internal.io

import java.io.IOException
import java.nio.file.Path

/** Suffix of a file [discard] moved into a graveyard directory. */
internal const val DISCARDED_SUFFIX: String = ".discarded"

/**
 * Removes [path], a file the store no longer needs, without ever failing: returns whether it is gone.
 *
 * The file is renamed into [graveyard] (the store's `tmp/`, which every open empties) and deleted there. Another
 * process may have it open: renaming an open file works on every platform the JDK supports, while deleting one can
 * leave its name behind until the last handle closes (legacy Windows semantics, on FAT32/exFAT, older systems and
 * possibly ReFS). In the graveyard such a name is harmless; in the store's own directories a recovery listing would
 * take it for a real file. When the rename fails the file is deleted where it is, and when the delete fails too the
 * file is left to the next open's sweep. A file already in [graveyard] is just deleted.
 *
 * Neither step is synced: a crash can bring the file back under its old name, where the next open's sweep finds it as
 * it finds a file a crash kept from being deleted.
 */
internal fun FileSystemIo.discard(path: Path, graveyard: Path): Boolean {
    var doomed = path
    if (path.parent != graveyard) {
        val target = graveyard.resolve("${path.fileName}$DISCARDED_SUFFIX")
        try {
            atomicRename(path, target)
            doomed = target
        } catch (_: IOException) {
            // The file is gone already, or the graveyard name is itself still held open: delete it in place.
        }
    }
    return try {
        delete(doomed)
        true
    } catch (e: IOException) {
        System.getLogger("app.oreshkov.delva").log(System.Logger.Level.WARNING, "deleting $doomed failed, the next open deletes it: $e")
        false
    }
}
