package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.Normalization
import app.oreshkov.delva.StoreLockedException
import app.oreshkov.delva.StoreNotFoundException
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.compress.DictionaryFiles
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogOptions
import app.oreshkov.delva.internal.log.ManifestStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [StoreReset]: a store with every kind of file — several control and lane files, segments after flushes and
 * compactions, bitmaps of deleted docIds, a type dictionary, compression dictionaries — comes out of a reset as a store
 * just created with the same options, and a crash at any I/O call of the reset leaves it either as it was or empty.
 */
class StoreResetTest {
    private val dir = TestEngines.DIR
    private val creation = CreationOptions(fragmentBytes = 1024, normalization = Normalization.NFC, excludedKeys = setOf("secret"))

    private fun options() = EngineOptions(
        creation, StoreOptions(encoderBudgetBytes = 2048),
        sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 7, log = LogOptions(laneFileBytes = 4096, controlFileBytes = 4096, preallocateBytes = 1024, writeBufferBytes = 4096),
        memtableBytes = 8L shl 10, backgroundFlush = false, compression = TestEngines.compression(2),
    )

    private fun doc(i: Int) =
        """{"all":true,"i":$i,"sku":"SKU-${i % 20}","order":{"line":"${"repeated text ".repeat(i % 5)}","qty":$i},"secret":1}"""

    /** A closed store on [fs] with [count] documents, a third of them deleted, typed, flushed and compacted. */
    private fun populated(fs: SimulatedFileSystemIo, count: Int = 120): Map<Long, String> {
        Engine.open(dir, options(), fs, Opening.CREATE).use { store ->
            for (i in 0 until count) store.insert(doc(i), mapOf("$.order" to "Order"))
            store.flush()
            store.deleteAll(store.documents().keys.filter { it % 3 == 0L }.map { DocId(it) })
            store.flush()
            store.compact()
            return store.documents()
        }
    }

    /** The store's file names with their numbers blanked, so stores that differ only in file numbers compare equal. */
    private fun shape(fs: SimulatedFileSystemIo): List<String> =
        fs.files().map { dir.relativize(it).toString().replace('\\', '/').replace(Regex("\\d+"), "#") }.sorted()

    private fun names(fs: SimulatedFileSystemIo, sub: String, parse: (String) -> Any?): List<String> =
        fs.list(dir.resolve(sub)).map { it.fileName.toString() }.filter { parse(it) != null }

    @Test
    fun `a reset store is a store just created with the same options`() {
        val fs = SimulatedFileSystemIo()
        populated(fs)
        // Everything a reset has to remove is there to begin with.
        assertTrue(names(fs, LogFiles.DICTIONARY_DIR, DictionaryFiles::parseName).isNotEmpty(), "no compression dictionary")
        assertTrue(names(fs, LogFiles.INDEX_DIR, LogFiles::parseSegment).isNotEmpty(), "no segment")
        assertTrue(names(fs, LogFiles.INDEX_DIR, LogFiles::parseTypeDictionary).isNotEmpty(), "no type dictionary")
        assertTrue(names(fs, LogFiles.BITMAP_DIR, LogFiles::parseBitmap).isNotEmpty(), "no bitmap")
        assertTrue(names(fs, LogFiles.CONTROL_DIR, LogFiles::parseControl).isNotEmpty(), "no control file")
        assertTrue(names(fs, LogFiles.DATA_DIR, LogFiles::parseLane).size > 1, "one lane file")

        assertEquals(creation, StoreReset.run(dir, fs))
        for (sub in listOf(LogFiles.CONTROL_DIR, LogFiles.DATA_DIR, LogFiles.INDEX_DIR, LogFiles.BITMAP_DIR, LogFiles.DICTIONARY_DIR, LogFiles.TMP_DIR)) {
            assertEquals(emptyList(), fs.list(dir.resolve(sub)), sub)
        }
        assertEquals(listOf("CURRENT", "MANIFEST-#"), shape(fs))

        val fresh = SimulatedFileSystemIo()
        Engine.open(dir, options(), fresh, Opening.CREATE).close()
        Engine.open(dir, options(), fs, Opening.OPEN).use { store ->
            assertEquals(creation, Engine.storedCreation(dir, fs))
            assertEquals(0, store.info().approximateDocumentCount)
            assertEquals(0, store.info().deletedDocumentCount)
            assertEquals(emptyList(), store.listTypes())
            assertEquals(emptyMap(), store.documents())
            assertEquals(0, store.codecs.dictionaryCount)
            assertEquals(0, store.replayedCommits)
            // DocIds start again at 1, and the store works as a new one does.
            assertEquals(1, store.insert(doc(1), mapOf("$.order" to "Order")).raw)
            assertEquals(2, store.insert(doc(2)).raw)
            assertEquals(1, store.countOfType("Order"))
            assertEquals(listOf(1L, 2L), store.documents().keys.sorted())
        }
        Engine.open(dir, options(), fresh, Opening.OPEN).use { store ->
            store.insert(doc(1), mapOf("$.order" to "Order"))
            store.insert(doc(2))
        }
        assertEquals(shape(fresh), shape(fs), "the reset store's files are a fresh store's")
    }

    @Test
    fun `a reset refuses an open store, and a directory without one changes nothing`() {
        val fs = SimulatedFileSystemIo()
        populated(fs, count = 10)
        Engine.open(dir, options(), fs, Opening.OPEN).use { store ->
            assertFailsWith<StoreLockedException> { StoreReset.run(dir, fs) }
            assertEquals(7, store.documents().size)
        }

        val empty = SimulatedFileSystemIo()
        assertFailsWith<StoreNotFoundException> { StoreReset.run(dir, empty) }
        assertEquals(emptyList(), empty.files())
    }

    @Test
    fun `a store too damaged to open is reset, and files that aren't the store's are kept`() {
        val fs = SimulatedFileSystemIo()
        populated(fs, count = 30)
        val control = fs.list(dir.resolve(LogFiles.CONTROL_DIR)).last()
        fs.delete(control)
        assertFailsWith<CorruptionException> { Engine.open(dir, options(), fs, Opening.OPEN).close() }
        fs.open(dir.resolve("notes.txt"), OpenMode.CREATE_NEW).close()
        fs.open(dir.resolve(LogFiles.INDEX_DIR).resolve("README"), OpenMode.CREATE_NEW).close()

        StoreReset.run(dir, fs)
        Engine.open(dir, options(), fs, Opening.OPEN).use { store -> assertEquals(emptyMap(), store.documents()) }
        assertTrue(fs.exists(dir.resolve("notes.txt")))
        assertTrue(fs.exists(dir.resolve(LogFiles.INDEX_DIR).resolve("README")))
    }

    @Test
    fun `a store whose MANIFEST can't be read isn't reset`() {
        val fs = SimulatedFileSystemIo()
        populated(fs, count = 5)
        val manifests = fs.list(dir).filter { ManifestStore.manifestNo(it) != null }
        for (m in manifests) fs.flipBit(m, 12, 0)
        assertFailsWith<CorruptionException> { StoreReset.run(dir, fs) }
    }

    @Test
    fun `a crash at any I-O call of a reset leaves the store as it was or empty`() {
        for (windows in listOf(false, true)) {
            val dry = SimulatedFileSystemIo(isWindows = windows)
            val documents = populated(dry)
            val before = dry.calls
            StoreReset.run(dir, dry)
            val calls = dry.calls - before
            assertTrue(calls > 10, "only $calls I/O calls")
            var kept = 0
            var emptied = 0
            for (n in 1..calls) {
                val fs = SimulatedFileSystemIo(isWindows = windows)
                populated(fs)
                fs.crashAfter(n)
                // The disk going down while the MANIFEST is read is reported as an unreadable MANIFEST. The last call
                // deletes the lock's owner record, which is advisory: a crash there comes after a reset that succeeded.
                val reset = runCatching { StoreReset.run(dir, fs) }
                assertEquals(n == calls, reset.isSuccess, "crash at call $n of $calls (windows=$windows): $reset")
                fs.crash(Random(n))
                val found = Engine.open(dir, options(), fs, Opening.OPEN).use { it.documents() }
                when {
                    found.isEmpty() -> emptied++
                    else -> {
                        assertEquals(documents, found, "crash at call $n of $calls (windows=$windows)")
                        kept++
                    }
                }
                // Resetting again finishes the job: a store just created, whatever the crash left.
                StoreReset.run(dir, fs)
                assertEquals(listOf("CURRENT", "MANIFEST-#"), shape(fs), "crash at call $n of $calls (windows=$windows)")
                Engine.open(dir, options(), fs, Opening.OPEN).use { store ->
                    assertEquals(emptyMap(), store.documents())
                    assertEquals(1, store.insert(doc(0)).raw)
                }
            }
            assertTrue(kept > 0 && emptied > 0, "kept $kept, emptied $emptied")
        }
    }
}
