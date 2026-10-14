package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.StoreNotFoundException
import app.oreshkov.delva.internal.compress.DictionaryFiles
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.discard
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.Manifest
import app.oreshkov.delva.internal.log.ManifestStore
import java.nio.file.Path

/**
 * Empties a closed store, leaving it as creating it did: the same creation options, no documents, types or
 * compression dictionaries, docIds from 1 and LSNs from 0.
 *
 * The commit point is one MANIFEST that lists nothing, with `nextFileNo` above every numbered file on disk, made
 * current the way every MANIFEST is. A crash before `CURRENT` names it leaves the old store as it was; after, the store
 * opens empty whatever else survived, because recovery deletes unlisted log files numbered below `nextFileNo` and index
 * files no MANIFEST lists. Then every file the store owns and the new MANIFEST doesn't name is deleted. Only the
 * MANIFEST is read, so a store whose logs or indexes are too damaged to open can still be reset; foreign files are
 * left alone.
 *
 * File and MANIFEST numbers continue from the old store: restarting them would take a second MANIFEST switch once the
 * deletions are durable, for a difference nothing can observe.
 */
internal object StoreReset {
    /** Resets the store in [dir]; returns the creation options it keeps. Nothing is written when [dir] holds no store. */
    fun run(dir: Path, io: FileSystemIo): CreationOptions {
        val current = dir.resolve(ManifestStore.CURRENT)
        if (!io.exists(current)) throw StoreNotFoundException(dir)
        val lock = StoreLock.acquire(dir, io)
        lock.use {
            val manifests = ManifestStore(io, dir)
            val old = manifests.peek() ?: throw StoreNotFoundException(dir)
            val dirs = STORE_DIRS.map(dir::resolve)
            for (d in dirs) io.createDirectories(d)
            val manifestNo = manifests.highestOnDisk()
            val fileNo = dirs.flatMap(io::list).maxOfOrNull { fileNoOf(it.fileName.toString()) ?: 0L } ?: 0L
            val empty = Manifest(
                manifestNo = manifestNo + 1,
                fragmentBytes = old.fragmentBytes,
                encoderBudgetBytes = old.encoderBudgetBytes,
                hashTerms = old.hashTerms,
                nextFileNo = maxOf(old.nextFileNo, fileNo + 1),
                nextDocId = 1,
                lastLsn = 0,
                files = emptyList(),
                indexPolicy = old.indexPolicy,
                normalization = old.normalization,
                documentKey = old.documentKey,
            )
            // The commit point.
            manifests.write(empty)
            manifests.sweep()
            // A file another process holds open may stay behind (see discard); the next open deletes it.
            val graveyard = dir.resolve(LogFiles.TMP_DIR)
            for (d in dirs) {
                val tmp = d == graveyard
                for (path in io.list(d)) if (tmp || isStoreFile(path.fileName.toString())) io.discard(path, graveyard)
                io.syncDirectory(d)
            }
            for (path in io.list(dir)) if (path.fileName.toString().endsWith(".tmp")) io.discard(path, graveyard)
            io.syncDirectory(dir)
            return empty.creationOptions()
        }
    }

    private val STORE_DIRS = listOf(
        LogFiles.CONTROL_DIR, LogFiles.DATA_DIR, LogFiles.INDEX_DIR, LogFiles.BITMAP_DIR, LogFiles.DICTIONARY_DIR, LogFiles.TMP_DIR,
    )

    /** The file number in a log, segment, bitmap or type-dictionary file name, or null for any other name. */
    private fun fileNoOf(name: String): Long? = LogFiles.parseControl(name)
        ?: LogFiles.parseLane(name)?.first
        ?: LogFiles.parseSegment(name)?.first
        ?: LogFiles.parseBitmap(name)?.first
        ?: LogFiles.parseTypeDictionary(name)

    private fun isStoreFile(name: String): Boolean = fileNoOf(name) != null || DictionaryFiles.parseName(name) != null || name.endsWith(".tmp")
}
