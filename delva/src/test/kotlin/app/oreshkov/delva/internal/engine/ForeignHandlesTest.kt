package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.Durability
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.StoreLockedException
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.compress.CompressionOptions
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogOptions
import app.oreshkov.delva.internal.log.ManifestStore
import java.io.IOException
import java.nio.file.Path
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A writer whose files other processes open: on Windows a name another handle has open can't be replaced, so nothing
 * that doesn't hold the lock may open `CURRENT`, the writer waits out a short-lived handle on it, and no MANIFEST name
 * is ever written twice. A file another process holds open never fails the writer, whatever the platform does with it.
 */
class ForeignHandlesTest {
    private val dir = TestEngines.DIR
    private val options = EngineOptions(CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024), log = TestEngines.LOG)
    private val current = dir.resolve(ManifestStore.CURRENT)

    /** Records the files opened and the names renamed onto; [onRename] may fail a rename before it happens. */
    private class Recording(private val fs: FileSystemIo, private val onRename: (Path, Path) -> Unit = { _, _ -> }) : FileSystemIo by fs {
        val opened: MutableList<Path> = Collections.synchronizedList(ArrayList())

        override fun open(path: Path, mode: OpenMode): FileIo {
            opened.add(path.normalize())
            return fs.open(path, mode)
        }

        override fun atomicRename(from: Path, to: Path) {
            onRename(from.normalize(), to.normalize())
            fs.atomicRename(from, to)
        }
    }

    private fun isMetadata(path: Path) = path == current || ManifestStore.manifestNo(path) != null

    @Test
    fun `opening a store another process has open reads neither CURRENT nor a MANIFEST`() {
        val fs = SimulatedFileSystemIo(isWindows = true)
        TestEngines.open(options, fs).use { writer ->
            writer.insert("""{"all":true}""")
            val other = Recording(fs)
            // The path JsonStore.open takes.
            assertFailsWith<StoreLockedException> { JsonStore.open(dir, options.store, other) }
            assertEquals(emptyList(), other.opened.filter(::isMetadata))
        }
        Engine.openStored(dir, fs) { creation ->
            assertEquals(options.creation, creation, "read from the store, under the lock")
            EngineOptions(creation, options.store, log = TestEngines.LOG)
        }.use { assertEquals(1, it.documents().size) }
    }

    @Test
    fun `the writer waits out a short-lived handle on CURRENT`() {
        val fs = SimulatedFileSystemIo(isWindows = true)
        val store = TestEngines.open(options, fs)
        store.insert("""{"all":true}""")
        // Another process reads CURRENT while the writer replaces it: closing the store writes a MANIFEST.
        val reader = fs.open(current, OpenMode.READ)
        val closer = thread { Thread.sleep(50); reader.close() }
        store.close()
        closer.join()
        TestEngines.open(options, fs).use { assertEquals(1, it.documents().size) }
    }

    @Test
    fun `a process holding the store's files open never fails the writer`() {
        // Small files and explicit maintenance, so flushes, compactions and reclamation delete files the reader holds.
        val options = EngineOptions(
            CreationOptions(fragmentBytes = 64), StoreOptions(encoderBudgetBytes = 1024, durability = Durability.SyncEachBatch),
            sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 17,
            log = LogOptions(laneFileBytes = 8L shl 10, controlFileBytes = 8L shl 10, preallocateBytes = 4L shl 10, writeBufferBytes = 4096),
            backgroundFlush = false, segmentBlockBytes = 512,
            compaction = CompactionOptions(bytesPerSecond = 0, deletedRatio = 0.1), reclaim = ReclaimOptions(bytesPerSecond = 0),
            compression = CompressionOptions.DISABLED,
        )
        for (platform in SimulatedFileSystemIo.Platform.entries) {
            val fs = SimulatedFileSystemIo(platform)
            val reader = fs.process()
            val held = HashSet<Path>()
            // Every file but the ones a reader of the store never opens (CURRENT and the lock's).
            fun holdEverything() {
                for (d in listOf(dir) + reader.list(dir)) {
                    for (path in reader.list(d)) {
                        val name = path.fileName.toString()
                        if (name == ManifestStore.CURRENT || name.startsWith(LogFiles.LOCK_FILE) || path in held) continue
                        runCatching { reader.open(path, OpenMode.READ) }.onSuccess { held.add(path) }
                    }
                }
            }
            val expected = Engine.open(dir, options, fs).use { store ->
                val docIds = ArrayList<DocId>()
                for (round in 0 until 6) {
                    repeat(40) { docIds += store.insert("""{"all":true,"round":$round,"s":"${"x".repeat(50 + it * 7)}"}""") }
                    holdEverything()
                    store.deleteAll(docIds.filterIndexed { i, _ -> i % 3 != 0 }.also { docIds.removeAll(it.toSet()) })
                    store.flush()
                    holdEverything()
                    store.compact()
                    store.reclaim()
                    holdEverything()
                }
                assertTrue(store.reclaimer.retiredFiles > 0 && store.files.deletedControlFiles > 0, "$platform: files were deleted")
                store.documents()
            }
            if (platform == SimulatedFileSystemIo.Platform.WINDOWS_LEGACY) assertTrue(fs.deletePending().isNotEmpty(), "$platform: deletes are pending")
            // Opening sweeps what couldn't be deleted, and can't delete what is still held either.
            Engine.open(dir, options, fs).use { store ->
                assertEquals(expected, store.documents(), platform.name)
                store.verify().let { assertTrue(it.intact, "$platform: ${it.issues.take(5)}") }
            }
            reader.exit()
            Engine.open(dir, options, fs).use { store ->
                assertEquals(expected, store.documents(), platform.name)
                store.verify().let { assertTrue(it.intact, "$platform: ${it.issues.take(5)}") }
                // Everything left behind is gone now.
                val listed = store.files.listed().map { it.fileNo }.toSet() + store.files.index.segments.map { it.fileNo }
                for (path in fs.files()) {
                    val name = path.fileName.toString()
                    val fileNo = LogFiles.parseControl(name) ?: LogFiles.parseLane(name)?.first ?: LogFiles.parseSegment(name)?.first ?: continue
                    assertTrue(fileNo in listed, "$platform: $path isn't listed")
                }
            }
            assertEquals(emptyList(), fs.deletePending(), platform.name)
            assertEquals(emptyList(), fs.files().filter { it.parent == dir.resolve(LogFiles.TMP_DIR) }, "$platform: the graveyard is empty")
            assertEquals(2, fs.files().count { ManifestStore.manifestNo(it) != null }, "$platform: the current MANIFEST and its fallback")
        }
    }

    @Test
    fun `a MANIFEST that CURRENT never named keeps its name`() {
        for (windows in listOf(false, true)) {
            val fs = SimulatedFileSystemIo(isWindows = windows)
            TestEngines.open(options, fs).use { it.insert("""{"all":true}""") }
            // A crash after a MANIFEST was renamed into place, before CURRENT named it.
            var crash = false
            val crashing = Recording(fs) { _, to -> if (to == current && crash) throw IOException("simulated crash") }
            val store = Engine.open(dir, options, crashing)
            store.insert("""{"all":true}""")
            crash = true
            // Closing writes a MANIFEST.
            assertTrue(runCatching { store.close() }.isFailure, "windows=$windows: the crash")
            crash = false
            fs.restart()
            val orphan = fs.files().mapNotNull { ManifestStore.manifestNo(it) }.max()
            val written = ArrayList<Path>()
            val reopened = Recording(fs) { _, to ->
                if (ManifestStore.manifestNo(to) != null) {
                    assertTrue(!fs.exists(to), "windows=$windows: $to is written again")
                    written.add(to)
                }
            }
            Engine.open(dir, options, reopened).use { it.insert("""{"all":true}""") }
            assertTrue(written.all { ManifestStore.manifestNo(it)!! > orphan }, "windows=$windows: $written after $orphan")
        }
    }
}
