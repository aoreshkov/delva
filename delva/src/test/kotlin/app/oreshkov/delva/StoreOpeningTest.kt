package app.oreshkov.delva

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `create`, `open` and `openOrCreate`: one function per intent, so that a mistyped path is an error rather than a
 * new, empty store, and so that opening a store never needs the options it was created with.
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
}
