package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.CreationOptionsMismatchException
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Index size controls — which keys and string lengths are indexed is fixed when a store is
 * created, leaves the documents themselves untouched, and makes searches it can't answer fail loudly.
 */
class IndexControlsTest {
    private val dir = TestEngines.DIR

    private fun options(creation: CreationOptions) = EngineOptions(
        creation, StoreOptions(encoderBudgetBytes = 4096), sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 3, log = TestEngines.LOG,
        backgroundFlush = false,
    )

    private fun doc(i: Int) = """{"all":true,"sku":"S$i","note":"${"n".repeat(i % 40)}","qty":$i,"tags":["red","green"]}"""

    private fun hits(store: Engine, key: String, value: JsonQueryValue): Int =
        store.search(KeyValueQuery(key, value, ResultMode.CONTAINING_ELEMENT, limit = 1000)).use { it.iterator().asSequence().count() }

    /** Posting bytes on disk after a flush, as a measure of index size. */
    private fun postingBytes(store: Engine): Long {
        store.flush()
        return store.indexes.versions.current().postingSegments.sumOf { it.fileBytes }
    }

    @Test
    fun `excluded keys are not indexed, their searches are rejected, and the rest is unchanged`() {
        val documents: Map<Long, String>
        val fullBytes: Long
        TestEngines.open(options(CreationOptions(fragmentBytes = 1024))).use { store ->
            for (i in 0 until 200) store.insert(doc(i))
            documents = store.documents()
            fullBytes = postingBytes(store)
        }
        val store = CreationOptions(fragmentBytes = 1024, excludedKeys = setOf("note", "tags"))
        val fs = SimulatedFileSystemIo()
        Engine.open(dir, options(store), fs).use { engine ->
            for (i in 0 until 200) engine.insert(doc(i))
            // Documents keep every member, only the index is smaller.
            assertEquals(documents, engine.documents())
            assertTrue(postingBytes(engine) < fullBytes, "excluding keys didn't shrink the index")
            assertEquals(1, hits(engine, "sku", JsonQueryValue.Str("S7")))
            assertEquals(1, hits(engine, "qty", JsonQueryValue.Num(7)))
            for (key in listOf("note", "tags")) {
                val e = assertFailsWith<QueryRejectedException> { hits(engine, key, JsonQueryValue.Str("red")) }
                assertEquals(QueryRejectedException.Reason.NOT_INDEXED, e.reason)
                assertTrue("isn't indexed" in e.message!!, e.message)
            }
        }
        // The controls are fixed at creation.
        fs.restart()
        val e = assertFailsWith<CreationOptionsMismatchException> { Engine.open(dir, options(CreationOptions(fragmentBytes = 1024)), fs).close() }
        assertEquals(store, e.stored)
        assertEquals("The store at $dir was created with excludedKeys [note, tags], not []", e.message)
        fs.restart()
        Engine.open(dir, options(store), fs).use { engine ->
            assertEquals(documents, engine.documents())
            assertEquals(1, hits(engine, "sku", JsonQueryValue.Str("S7")))
        }
    }

    @Test
    fun `only included keys are indexed`() {
        val store = CreationOptions(fragmentBytes = 1024, indexedKeys = setOf("all", "sku"))
        TestEngines.open(options(store)).use { engine ->
            for (i in 0 until 200) engine.insert(doc(i))
            assertEquals(200, engine.documents().size)
            assertEquals(1, hits(engine, "sku", JsonQueryValue.Str("S7")))
            assertFailsWith<QueryRejectedException> { hits(engine, "qty", JsonQueryValue.Num(7)) }
        }
    }

    @Test
    fun `string values outside the indexed lengths are left out`() {
        val store = CreationOptions(fragmentBytes = 1024, minIndexedStringBytes = 3, maxIndexedStringBytes = 20)
        TestEngines.open(options(store)).use { engine ->
            for (i in 0 until 200) engine.insert(doc(i))
            assertEquals(200, engine.documents().size)
            // Strings of 3..20 bytes are indexed; "S7" (2 bytes) and a 30-byte note aren't searchable at all.
            assertEquals(200, hits(engine, "tags", JsonQueryValue.Str("green")))
            // Five of the 200 documents carry each note length.
            assertEquals(5, hits(engine, "note", JsonQueryValue.Str("n".repeat(9))))
            assertEquals(5, hits(engine, "note", JsonQueryValue.Str("n".repeat(20))))
            for (value in listOf("S7", "n".repeat(21), "n".repeat(30))) {
                assertFailsWith<QueryRejectedException> { hits(engine, "note", JsonQueryValue.Str(value)) }
            }
            // Numbers, booleans and hash terms are indexed whatever the string limits are.
            assertEquals(1, hits(engine, "qty", JsonQueryValue.Num(7)))
            assertEquals(200, hits(engine, "all", JsonQueryValue.Bool(true)))
            assertEquals(200, hits(engine, "tags", JsonQueryValue.Json("""["red","green"]""")))
        }
    }
}
