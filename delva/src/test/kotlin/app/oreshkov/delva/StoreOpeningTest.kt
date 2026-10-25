package app.oreshkov.delva

import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.log.withFormatVersion
import app.oreshkov.delva.internal.log.withLogFormatVersion
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.walk
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `create`, `open` and `openOrCreate`: one function per intent, so that a mistyped path is an error rather than a
 * new, empty store, and so that opening a store never needs the options it was created with. And a store of another
 * format version: refused as such by every way of opening, never taken for damage, and told by `probe`.
 */
class StoreOpeningTest {
    private val root: Path = Files.createTempDirectory("delva-opening")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    @Test
    fun `open finds no store in a missing directory, and does not create it`() {
        val dir = root.resolve("missing")
        val e = assertFailsWith<StoreNotFoundException> { JsonStore.open(dir) }
        assertEquals(dir, e.directory)
        assertFalse(dir.exists())
    }

    @Test
    fun `open finds no store in a directory of other files, and leaves it exactly as it was`() {
        val dir = root.resolve("documents").createDirectories()
        dir.resolve("notes.txt").writeText("not a store")
        assertFailsWith<StoreNotFoundException> { JsonStore.open(dir) }
        assertEquals(listOf("notes.txt"), dir.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun `open takes the creation options from the store`() {
        val dir = root.resolve("nfc")
        val creation = CreationOptions(fragmentBytes = 1024, normalization = Normalization.NFC, excludedKeys = setOf("secret"))
        JsonStore.create(dir, creation, StoreOptions(encoderBudgetBytes = 4096)).use { store ->
            store.blocking().insert("""{"name":"café"}""")
        }
        JsonStore.open(dir).use { store ->
            assertEquals(creation, store.creationOptions)
            assertEquals(creation, store.blocking().creationOptions)
            // The stored normalization is in force: the decomposed spelling finds the composed one.
            val hits = store.blocking().search(KeyValueQuery("name", JsonQueryValue.Str("café"))).use { it.iterator().asSequence().count() }
            assertEquals(1, hits)
            assertEquals(QueryRejectedException.Reason.NOT_INDEXED, assertFailsWith<QueryRejectedException> { store.blocking().search(KeyValueQuery("secret", JsonQueryValue.Num(1))) }.reason)
        }
    }

    @Test
    fun `create refuses a directory that holds a store, and leaves it as it was`() {
        val dir = root.resolve("taken")
        JsonStore.create(dir).use { it.blocking().insert("""{"a":1}""") }
        val e = assertFailsWith<StoreExistsException> { JsonStore.create(dir, CreationOptions(hashTerms = false)) }
        assertEquals(dir, e.directory)
        JsonStore.open(dir).use { store ->
            assertTrue(store.creationOptions.hashTerms)
            assertEquals(1, store.blocking().search(KeyValueQuery("a", JsonQueryValue.Num(1))).use { it.iterator().asSequence().count() })
        }
    }

    @Test
    fun `create makes a missing directory, and uses an empty one`() {
        JsonStore.create(root.resolve("a/b/c")).close()
        JsonStore.create(root.resolve("empty").createDirectories()).close()
        JsonStore.open(root.resolve("a/b/c")).close()
    }

    @Test
    fun `openOrCreate creates once, then opens, and refuses other creation options`() {
        val dir = root.resolve("either")
        val creation = CreationOptions(fragmentBytes = 4096)
        JsonStore.openOrCreate(dir, creation).use { it.blocking().insert("""{"a":1}""") }
        JsonStore.openOrCreate(dir, creation).use { assertEquals(creation, it.creationOptions) }
        val e = assertFailsWith<CreationOptionsMismatchException> { JsonStore.openOrCreate(dir) }
        assertEquals(dir, e.directory)
        assertEquals(creation, e.stored)
        assertEquals(CreationOptions(), e.requested)
        assertEquals("The store at $dir was created with fragmentBytes 4096, not 65536", e.message)
    }

    @Test
    fun `an encoder budget too small for the store's fragments is refused at open`() {
        val dir = root.resolve("budget")
        JsonStore.create(dir, CreationOptions(fragmentBytes = 8192)).close()
        val e = assertFailsWith<IllegalArgumentException> { JsonStore.open(dir, StoreOptions(encoderBudgetBytes = 8192)) }
        assertTrue("encoderBudgetBytes" in e.message!!, e.message)
        JsonStore.open(dir, StoreOptions(encoderBudgetBytes = 16384)).close()
    }

    @Test
    fun `info counts the documents a store holds and has deleted, across a reopen`() {
        val dir = root.resolve("counted")
        JsonStore.create(dir).use { store ->
            val blocking = store.blocking()
            assertEquals(0, blocking.info().approximateDocumentCount)
            val ids = (1..5).map { blocking.insert("""{"n":$it}""") }
            blocking.deleteAll(ids.take(2))
            val info = store.info()
            assertEquals(3, info.approximateDocumentCount)
            assertEquals(2, info.deletedDocumentCount)
        }
        JsonStore.open(dir).use { store ->
            assertEquals(3, store.info().approximateDocumentCount)
            assertEquals(2, store.info().deletedDocumentCount)
        }
    }

    @Test
    fun `a store open in one place is locked for every way of opening`() {
        val dir = root.resolve("locked")
        JsonStore.create(dir).use {
            assertFailsWith<StoreLockedException> { JsonStore.open(dir) }
            assertFailsWith<StoreLockedException> { JsonStore.openOrCreate(dir) }
            assertFailsWith<StoreLockedException> { JsonStore.create(dir) }
            assertFailsWith<StoreLockedException> { JsonStore.reset(dir) }
        }
    }

    @Test
    fun `reset empties a store and keeps its creation options, and needs a store that isn't open`() {
        val dir = root.resolve("reset")
        val creation = CreationOptions(fragmentBytes = 1024, normalization = Normalization.NFC)
        JsonStore.create(dir, creation).use { store ->
            val blocking = store.blocking()
            val ids = (1..5).map { blocking.insert("""{"n":$it,"o":{}}""", mapOf("$.o" to "Thing")) }
            blocking.delete(ids.first())
        }
        dir.resolve("notes.txt").writeText("not the store's")

        assertEquals(creation, JsonStore.reset(dir))
        JsonStore.open(dir).use { store ->
            val blocking = store.blocking()
            assertEquals(creation, store.creationOptions)
            assertEquals(0, store.info().approximateDocumentCount)
            assertEquals(0, store.info().deletedDocumentCount)
            assertEquals(emptyList(), blocking.listTypes())
            assertEquals(0, blocking.search(KeyValueQuery("n", JsonQueryValue.Num(2))).use { it.iterator().asSequence().count() })
            assertEquals(1, blocking.insert("""{"n":1}""").raw)
        }
        assertTrue(dir.resolve("notes.txt").exists())
    }

    @Test
    fun `reset finds no store in a missing or foreign directory, and changes nothing`() {
        val missing = root.resolve("missing")
        assertEquals(missing, assertFailsWith<StoreNotFoundException> { JsonStore.reset(missing) }.directory)
        assertFalse(missing.exists())

        val foreign = root.resolve("documents").createDirectories()
        foreign.resolve("notes.txt").writeText("not a store")
        assertFailsWith<StoreNotFoundException> { JsonStore.reset(foreign) }
        assertEquals(listOf("notes.txt"), foreign.listDirectoryEntries().map { it.fileName.toString() })
    }

    /** A closed store with a document, its MANIFESTs then rewritten as format version [version] where [which] says. */
    private fun storeOfVersion(name: String, version: Int, which: (Path) -> Boolean = { true }): Path {
        val dir = root.resolve(name)
        JsonStore.create(dir).use { it.blocking().insert("""{"a":1}""") }
        for (manifest in dir.listDirectoryEntries("MANIFEST-*").filter(which)) manifest.writeBytes(withFormatVersion(manifest.readBytes(), version))
        return dir
    }

    /** Every file under [dir] with a hash of its bytes, to tell that nothing changed; log files are 64 MiB each. */
    private fun contents(dir: Path): Map<String, String> = dir.walk().associate { file ->
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> DigestInputStream(input, digest).transferTo(OutputStream.nullOutputStream()) }
        dir.relativize(file).toString() to HexFormat.of().formatHex(digest.digest())
    }

    @Test
    fun `a store of another format version is refused by every way of opening, and left exactly as it was`() {
        val dir = storeOfVersion("newer", 2)
        val before = contents(dir)

        val e = assertFailsWith<UnsupportedFormatException> { JsonStore.open(dir) }
        assertEquals(dir, e.directory)
        assertEquals(2, e.version)
        assertEquals(listOf(1), e.supportedVersions)
        assertEquals("The store at $dir is in format version 2, written by a newer version of Delva; this one reads format version 1", e.message)
        assertFailsWith<UnsupportedFormatException> { JsonStore.openOrCreate(dir) }
        assertFailsWith<UnsupportedFormatException> { JsonStore.openSecondary(dir) }
        assertFailsWith<UnsupportedFormatException> { JsonStore.reset(dir) }
        assertFailsWith<StoreExistsException> { JsonStore.create(dir) }
        assertEquals(before, contents(dir))

        val format = assertNotNull(JsonStore.probe(dir))
        assertEquals(2, format.version)
        assertEquals(listOf(1), format.supportedVersions)
        assertFalse(format.supported)
        assertEquals(before, contents(dir))

        val older = assertFailsWith<UnsupportedFormatException> { JsonStore.open(storeOfVersion("older", 0)) }
        assertTrue("written by an older version of Delva" in older.message!!, older.message)
    }

    @Test
    fun `a current MANIFEST of another format version is never passed over for an older one`() {
        // The current MANIFEST is the highest-numbered one; the one before it stays as a fallback for damage.
        val dir = root.resolve("upgraded")
        JsonStore.create(dir).use { it.blocking().insert("""{"a":1}""") }
        val manifests = dir.listDirectoryEntries("MANIFEST-*").sorted()
        assertTrue(manifests.size > 1, "no fallback MANIFEST: $manifests")
        val current = manifests.last()
        current.writeBytes(withFormatVersion(current.readBytes(), 2))

        assertFailsWith<UnsupportedFormatException> { JsonStore.open(dir) }
        assertFailsWith<UnsupportedFormatException> { JsonStore.openSecondary(dir) }
        assertFailsWith<UnsupportedFormatException> { JsonStore.reset(dir) }
        assertFalse(assertNotNull(JsonStore.probe(dir)).supported)
    }

    @Test
    fun `probe tells a store's format without opening it, beside its writer too`() {
        val missing = root.resolve("missing")
        assertNull(JsonStore.probe(missing))
        assertFalse(missing.exists())
        val foreign = root.resolve("documents").createDirectories()
        foreign.resolve("notes.txt").writeText("not a store")
        assertNull(JsonStore.probe(foreign))
        assertEquals(listOf("notes.txt"), foreign.listDirectoryEntries().map { it.fileName.toString() })

        val dir = root.resolve("current")
        JsonStore.create(dir).use { store ->
            val format = assertNotNull(JsonStore.probe(dir))
            assertEquals(1, format.version)
            assertTrue(format.supported)
            store.blocking().insert("""{"a":1}""")
        }
        assertTrue(assertNotNull(JsonStore.probe(dir)).supported)
    }

    @Test
    fun `a log file of another format version is refused, never deleted as one whose header was cut off`() {
        val dir = root.resolve("foreign-log")
        JsonStore.create(dir).use { it.blocking().insert("""{"a":1}""") }
        // Numbered above every file the MANIFEST lists, and not listed yet: being created when the writer stopped.
        val unlisted = dir.resolve(LogFiles.DATA_DIR).resolve(LogFiles.laneName(1000, LogFormat.LANE_SMALL))
        val header = ByteArray(LogFormat.HEADER_BYTES)
        LogFormat.writeHeader(header, control = false, fileNo = 1000, createdLsn = 0, laneId = LogFormat.LANE_SMALL)

        // A whole header of another version was written by another version of Delva.
        unlisted.writeBytes(withLogFormatVersion(header, LogFormat.FORMAT_VERSION + 1))
        var before = contents(dir)
        val e = assertFailsWith<CorruptionException> { JsonStore.open(dir) }
        assertEquals(unlisted.toString(), e.location)
        assertEquals("log format version ${LogFormat.FORMAT_VERSION + 1} where the store's format has ${LogFormat.FORMAT_VERSION}", e.detail)
        assertEquals(before, contents(dir))

        // A header that isn't whole never became durable, so nothing was appended to the file, and an open deletes it.
        unlisted.writeBytes(header.copyOf().also { it[16] = 1 })
        JsonStore.open(dir).close()
        assertFalse(unlisted.exists())

        // A listed file of another version; secondaries read control files' headers, which writers read too.
        val listed = dir.resolve(LogFiles.CONTROL_DIR).listDirectoryEntries().filter { LogFiles.parseControl(it.fileName.toString()) != null }.max()
        FileChannel.open(listed, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            val bytes = ByteBuffer.allocate(LogFormat.HEADER_BYTES)
            channel.read(bytes, 0)
            channel.write(ByteBuffer.wrap(withLogFormatVersion(bytes.array(), LogFormat.FORMAT_VERSION + 1)), 0)
        }
        before = contents(dir)
        for (open in listOf<() -> AutoCloseable>({ JsonStore.open(dir) }, { JsonStore.openSecondary(dir) })) {
            val listedError = assertFailsWith<CorruptionException> { open().close() }
            assertEquals(listed.toString(), listedError.location)
            assertEquals(e.detail, listedError.detail)
        }
        assertEquals(before, contents(dir))
    }

    @Test
    fun `a damaged MANIFEST is damage, not another format`() {
        val dir = root.resolve("damaged")
        JsonStore.create(dir).use { it.blocking().insert("""{"a":1}""") }
        for (manifest in dir.listDirectoryEntries("MANIFEST-*")) {
            manifest.writeBytes(manifest.readBytes().also { it[4] = (it[4].toInt() xor 2).toByte() })
        }
        assertFailsWith<CorruptionException> { JsonStore.probe(dir) }
        assertFailsWith<CorruptionException> { JsonStore.open(dir) }
        assertFailsWith<CorruptionException> { JsonStore.openSecondary(dir) }
    }
}
