package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DocId
import app.oreshkov.delva.InvalidCursorException
import app.oreshkov.delva.InvalidTypeNameException
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pages of the objects of a type, and clearing a type, on the durable engine. */
class TypePagesEngineTest {
    private fun options(startWriterThread: Boolean = true) = EngineOptions(
        CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024),
        sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 13, log = TestEngines.LOG, backgroundFlush = false,
        startWriterThread = startWriterThread,
    )

    // [0]{"items":[1][[2]{"k":[3]1},[4]{"k":[5]2},[6]{"k":[7]3}]}
    private val doc = """{"items":[{"k":1},{"k":2},{"k":3}]}"""
    private val types = mapOf("$.items[0]" to "Item", "$.items[1]" to "Item", "$.items[2]" to "Other")

    private fun node(docId: Long, pre: Long) = NodeRef(DocId(docId), pre)

    private fun Engine.allPages(type: String, limit: Int): List<List<NodeRef>> {
        val pages = ArrayList<List<NodeRef>>()
        var cursor: Cursor? = null
        do {
            val page = objectsOfTypePage(type, cursor, limit)
            pages += page.nodes
            cursor = page.cursor
        } while (cursor != null)
        return pages
    }

    @Test
    fun `pages of a type hold every object once and end with a null cursor, with no empty page after a full one`() {
        TestEngines.open(options()).use { store ->
            val docIds = (0 until 5).map { store.insert(doc, types).raw }
            val all = docIds.flatMap { listOf(node(it, 2), node(it, 4)) }
            assertEquals(all, store.objectsOfType("Item", limit = 100))
            assertEquals(listOf(4, 4, 2), store.allPages("Item", 4).map { it.size })
            assertEquals(all, store.allPages("Item", 4).flatten())
            assertEquals(listOf(all), store.allPages("Item", 10), "a page that takes the last object says so")
            assertEquals(listOf(all), store.allPages("Item", 11))

            val first = store.objectsOfTypePage("Item", limit = 3)
            val cursor = assertNotNull(first.cursor)
            assertEquals(all[2], NodeRef(cursor.docId, cursor.nodePre))
            assertEquals(cursor, Cursor.parse(cursor.toString()))
            assertEquals(all.drop(3), store.objectsOfTypePage("Item", Cursor.parse(cursor.toString()), 100).nodes)
        }
    }

    @Test
    fun `a type cursor resumes only a type page, and a search cursor only a search`() {
        TestEngines.open(options()).use { store ->
            repeat(3) { store.insert(doc, types) }
            val typeCursor = assertNotNull(store.objectsOfTypePage("Item", limit = 1).cursor)
            val searchCursor = store.search(KeyValueQuery("k", JsonQueryValue.Num(1), limit = 1)).use { assertNotNull(it.nextPage()?.cursor) }
            assertFailsWith<InvalidCursorException> { store.objectsOfTypePage("Item", searchCursor) }
            assertFailsWith<InvalidCursorException> { KeyValueQuery("k", JsonQueryValue.Num(1), after = typeCursor) }
            assertFailsWith<IllegalArgumentException> { store.objectsOfTypePage("Item", limit = 0) }
        }
    }

    @Test
    fun `a type nobody assigned gives an empty last page, and an invalid name is refused`() {
        TestEngines.open(options()).use { store ->
            store.insert(doc, types)
            val page = store.objectsOfTypePage("Missing")
            assertEquals(emptyList(), page.nodes)
            assertNull(page.cursor)
            assertEquals(0, store.clearType("Missing"))
            assertFailsWith<InvalidTypeNameException> { store.objectsOfTypePage("") }
            assertFailsWith<InvalidTypeNameException> { store.clearType("") }
        }
    }

    @Test
    fun `clearType removes the type from every visible object, keeps other types and the name, and survives recovery`() {
        val fs = SimulatedFileSystemIo()
        val docIds = TestEngines.open(options(), fs).use { store ->
            val docIds = (0 until 3).map { store.insert(doc, types).raw }
            store.delete(DocId(docIds[1]))
            assertEquals(4, store.clearType("Item"), "two objects in each of the two visible documents")
            assertEquals(emptyList(), store.objectsOfType("Item"))
            assertEquals(0, store.countOfType("Item"))
            assertEquals(listOf("Item", "Other"), store.listTypes().map { it.name }, "the name stays")
            assertEquals(0, store.clearType("Item"), "clearing it again changes nothing")
            docIds
        }
        fs.restart()
        TestEngines.open(options(), fs).use { store ->
            assertEquals(emptyList(), store.objectsOfType("Item"))
            assertEquals(listOf(node(docIds[0], 6), node(docIds[2], 6)), store.objectsOfType("Other"))
            store.flush()
            assertEquals(emptyList(), store.objectsOfType("Item"), "after the flush too")
        }
    }

    @Test
    fun `in one batch, a clear sees the assignments before it, and the assignments after it see the clear`() {
        val fs = SimulatedFileSystemIo()
        val docIds = TestEngines.open(options(), fs).use { store -> (0 until 2).map { store.insert(doc, types).raw } }
        fs.restart()
        TestEngines.open(options(startWriterThread = false), fs).use { store ->
            val writer = store.writer
            val item = store.dictionary.idOf("Item")
            val before = writer.submit(WriteRequest.AssignTypes(longArrayOf(docIds[0]), longArrayOf(6), arrayOf("Item")))
            val clear = writer.submit(WriteRequest.ClearType(item))
            val after = writer.submit(WriteRequest.AssignTypes(longArrayOf(docIds[0]), longArrayOf(2), arrayOf("Item")))
            val delete = writer.submit(WriteRequest.Delete(longArrayOf(docIds[1])))
            val again = writer.submit(WriteRequest.ClearType(item))
            writer.runBatch(wait = false)
            listOf(before, clear, after, delete, again).forEach { it.done.get() }
            assertEquals(3, clear.cleared, "items 0 and 1 of the first document, and the one typed before the clear")
            assertEquals(1, again.cleared, "only the object typed again after the first clear")
        }
        fs.restart()
        TestEngines.open(options(), fs).use { store ->
            assertEquals(emptyList(), store.objectsOfType("Item"))
            assertEquals(0, store.countOfType("Item"))
            assertTrue(store.objectsOfType("Other").all { it.docId.raw == docIds[0] })
        }
    }
}
