package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.DocId
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.compress.CompressionDictionary
import app.oreshkov.delva.internal.compress.CompressionOptions
import app.oreshkov.delva.internal.compress.DictionaryFiles
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.Manifest
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Compressed fragment payloads and the dictionaries they use on the durable engine. The
 * oracle comparisons run with compression in [EngineModelTest], crashes in [ReclaimCrashSimTest].
 */
class CompressionEngineTest {
    private val dir = TestEngines.DIR
    private val dictionaryDir = dir.resolve(LogFiles.DICTIONARY_DIR)

    private fun options(
        t: Int = 1024,
        threshold: Long = EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD,
        compression: CompressionOptions = CompressionOptions(sampleBytes = 16 shl 10, sampleChunkBytes = 1024, firstTrainingBytes = 8L shl 10, retrainingBytes = 32L shl 10, maxDictionaryBytes = 4 shl 10),
    ) = EngineOptions(
        CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024)),
        sorterBytes = 4096, keyArenaBytes = 1024, smallInsertThreshold = threshold, keySeed = 11, log = TestEngines.LOG,
        memtableBytes = EngineOptions.DEFAULT_MEMTABLE_BYTES, backgroundFlush = false, compression = compression,
    )

    // Documents that share keys and text, so a dictionary has something to find.
    private fun doc(i: Int) = """{"all":true,"sku":"SKU-${i % 50}","name":"a widget for department ${i % 7}","tags":["catalogue","widget","department ${i % 7}"],"qty":$i,"note":"${"repeated text ".repeat(4)}"}"""

    /** The document [i] as a search for its `qty` reads it back, or null when nothing matches. */
    private fun read(store: Engine, i: Int): String? =
        store.search(KeyValueQuery("qty", JsonQueryValue.Num(i.toLong()), ResultMode.WHOLE_DOCUMENT, limit = 10)).use { result ->
            result.iterator().asSequence().map { it.readJsonAsString() }.firstOrNull()
        }

    private fun dictionaryFiles(fs: SimulatedFileSystemIo): List<Path> = fs.files().filter { DictionaryFiles.parseName(it.fileName.toString()) != null }

    private fun listedDictionaries(fs: SimulatedFileSystemIo): List<Int> {
        val current = String(fs.bytes(dir.resolve("CURRENT")), Charsets.US_ASCII).trim()
        return Manifest.decode(fs.bytes(dir.resolve(current)), current).dictionaries
    }

    @Test
    fun `payloads are compressed, dictionaries become durable before they are used, and reads stay exact`() {
        val fs = SimulatedFileSystemIo()
        val options = options()
        val documents: Map<Long, String>
        val sizes: List<Long>
        Engine.open(dir, options, fs).use { store ->
            for (i in 0 until 400) store.insert(doc(i))
            documents = store.documents()
            assertEquals(400, documents.size)
            assertTrue(store.codecs.compressedFragments.get() > 0, "nothing was compressed")
            assertTrue(store.codecs.dictionaryCount > 0, "no dictionary was built")
            // A dictionary is listed in a durable MANIFEST before a fragment uses it, and its file is on disk.
            val listed = listedDictionaries(fs)
            assertEquals(dictionaryFiles(fs).size, listed.size)
            assertTrue(store.codecs.current!!.id in listed)
            // encodedSize counts payload bytes before compression (SearchHit.encodedSize).
            sizes = store.search(KeyValueQuery("qty", JsonQueryValue.Num(7), ResultMode.WHOLE_DOCUMENT, limit = 10)).use { result ->
                result.iterator().asSequence().map { it.encodedSize }.toList()
            }
            assertTrue(sizes.single() > doc(7).length / 2, "encodedSize ${sizes.single()} for a document of ${doc(7).length} characters")
        }
        // Replaying compressed fragments indexes them: the same documents and hits after a reopen, and again from segments.
        for (round in 0..1) {
            fs.restart()
            Engine.open(dir, options, fs).use { store ->
                assertEquals(0, store.damagedDocIds.size)
                assertEquals(documents, store.documents())
                assertTrue(store.codecs.inflatedFragments.sum() > 0, "nothing was inflated")
                // Eight of the 400 documents carry this sku.
                assertEquals(8, store.search(KeyValueQuery("sku", JsonQueryValue.Str("SKU-3"), ResultMode.CONTAINING_ELEMENT, limit = 1000)).use { it.iterator().asSequence().count() })
                if (round == 0) store.flush()
            }
        }
        // The same documents without compression take more lane bytes.
        val plain = SimulatedFileSystemIo()
        Engine.open(dir, options(compression = CompressionOptions.DISABLED), plain).use { store ->
            for (i in 0 until 400) store.insert(doc(i))
            assertEquals(0, store.codecs.compressedFragments.get())
            assertEquals(emptyList(), dictionaryFiles(plain))
        }
        fun laneBytes(fs: SimulatedFileSystemIo) = fs.files().filter { it.parent?.fileName?.toString() == LogFiles.DATA_DIR }.sumOf { fs.bytes(it).size.toLong() }
        assertTrue(laneBytes(fs) * 2 < laneBytes(plain), "compressed ${laneBytes(fs)} bytes, stored ${laneBytes(plain)}")
    }

    @Test
    fun `when a dictionary a document needs is missing, only that document fails to read`() {
        val fs = SimulatedFileSystemIo()
        val options = options()
        Engine.open(dir, options, fs).use { store ->
            // The first documents are written before any dictionary exists, the later ones with one.
            for (i in 0 until 400) store.insert(doc(i))
            store.flush()
            assertTrue(store.codecs.dictionaryCount > 0)
        }
        val removed = dictionaryFiles(fs).first()
        val id = DictionaryFiles.parseName(removed.fileName.toString())!!
        // Damage it past its checksum: the file exists but can't be decoded.
        fs.open(removed, OpenMode.READ_WRITE).use { it.write(20, ByteArray(8), 0, 8) }
        fs.restart()
        Engine.open(dir, options, fs).use { store ->
            assertTrue(id in listedDictionaries(fs), "the dictionary stays listed")
            assertEquals(doc(0), read(store, 0), "a document that needs no dictionary still reads")
            val failures = (1 until 400).count { i ->
                try {
                    read(store, i)
                    false
                } catch (e: CorruptionException) {
                    assertTrue("dictionary $id is damaged" in e.message!!, e.message)
                    true
                }
            }
            assertTrue(failures > 0, "no document needed the damaged dictionary")
        }
    }

    @Test
    fun `an unlisted dictionary file is loaded when it is valid and deleted when it is not`() {
        val fs = SimulatedFileSystemIo()
        val options = options()
        Engine.open(dir, options, fs).use { store -> for (i in 0 until 400) store.insert(doc(i)) }
        // A dictionary written just before a crash, before any MANIFEST listed it: kept, loaded and listed next time.
        val unlisted = 60
        val valid = DictionaryFiles.encode(CompressionDictionary(unlisted, doc(1).toByteArray()))
        fs.open(dictionaryDir.resolve(DictionaryFiles.name(unlisted)), OpenMode.CREATE_NEW).use { it.write(0, valid, 0, valid.size) }
        // One whose write was cut off: deleted, its id free again.
        val torn = 61
        fs.open(dictionaryDir.resolve(DictionaryFiles.name(torn)), OpenMode.CREATE_NEW).use { it.write(0, valid, 0, valid.size / 2) }
        fs.restart()
        Engine.open(dir, options, fs).use { store ->
            assertContentEquals(doc(1).toByteArray(), store.codecs.dictionary(unlisted)!!.bytes)
            assertEquals(null, store.codecs.dictionary(torn))
            assertTrue(fs.files().none { it.fileName.toString() == DictionaryFiles.name(torn) }, "the cut-off file is deleted")
            // Listing it costs a MANIFEST, written with the next one.
            store.flush()
            assertTrue(unlisted in listedDictionaries(fs))
            assertEquals(doc(5), read(store, 5))
            // The next dictionary gets an id above every one known.
            assertNotEquals(unlisted, store.codecs.current!!.id)
        }
    }

    @Test
    fun `a dictionary file that can't be read just now fails the open and is neither damaged nor deleted`() {
        val fs = SimulatedFileSystemIo()
        val options = options()
        val documents: Map<Long, String>
        Engine.open(dir, options, fs).use { store ->
            for (i in 0 until 400) store.insert(doc(i))
            documents = store.documents()
        }
        // An unlisted file too, which an open treating every I/O error as damage would delete.
        val unlisted = 60
        DictionaryFiles.write(fs, dictionaryDir, CompressionDictionary(unlisted, doc(1).toByteArray()))
        val files = dictionaryFiles(fs)
        assertTrue(files.size >= 2, "files $files")
        fs.restart()
        val denied = object : FileSystemIo by fs {
            override fun open(path: Path, mode: OpenMode): FileIo {
                if (path.parent == dictionaryDir) throw AccessDeniedException(path.toString())
                return fs.open(path, mode)
            }
        }
        assertFailsWith<AccessDeniedException> { Engine.open(dir, options, denied).close() }
        assertEquals(files, dictionaryFiles(fs))
        fs.restart()
        Engine.open(dir, options, fs).use { store ->
            assertEquals(0, store.damagedDocIds.size)
            assertEquals(documents, store.documents())
            assertContentEquals(doc(1).toByteArray(), store.codecs.dictionary(unlisted)!!.bytes)
        }
    }

    @Test
    fun `a store opened with compression off still reads compressed fragments`() {
        val fs = SimulatedFileSystemIo()
        val documents: Map<Long, String>
        Engine.open(dir, options(), fs).use { store ->
            for (i in 0 until 400) store.insert(doc(i))
            documents = store.documents()
        }
        fs.restart()
        Engine.open(dir, options(compression = CompressionOptions.DISABLED), fs).use { store ->
            assertEquals(documents, store.documents())
            store.insert(doc(1000))
            assertEquals(0, store.codecs.compressedFragments.get())
            assertTrue(store.codecs.inflatedFragments.sum() > 0)
        }
    }

    @Test
    fun `a compressed fragment whose record is damaged on disk is reported, not decoded`() {
        val fs = SimulatedFileSystemIo()
        val options = options()
        Engine.open(dir, options, fs).use { store ->
            for (i in 0 until 400) store.insert(doc(i))
            store.flush()
        }
        // Flip bytes in the middle of a lane file: the record CRC32C catches what deflate can't.
        val lane = fs.files().first { it.parent?.fileName?.toString() == LogFiles.DATA_DIR }
        val bytes = fs.bytes(lane)
        fs.open(lane, OpenMode.READ_WRITE).use { it.write(bytes.size / 2L, ByteArray(64) { 0x5A }, 0, 64) }
        fs.restart()
        Engine.open(dir, options, fs).use { store ->
            var corrupt = 0
            var read = 0
            for (i in 0 until 400) {
                try {
                    store.search(KeyValueQuery("qty", JsonQueryValue.Num(i.toLong()), ResultMode.WHOLE_DOCUMENT, limit = 10)).use { result ->
                        result.iterator().asSequence().forEach { it.readJsonAsString() }
                    }
                    read++
                } catch (_: CorruptionException) {
                    corrupt++
                }
            }
            assertTrue(corrupt > 0 && read > 300, "$corrupt documents damaged, $read read")
        }
    }
}
