package app.oreshkov.delva.intellij.store

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EmbeddedStoreBackendTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val backend = EmbeddedStoreBackend()

    @After
    fun closeBackend() = backend.close()

    @Test
    fun `reads a store beside its open writer and catches up with later inserts`() = runBlocking {
        val dir = temp.newFolder("store").toPath()
        JsonStore.create(dir).use { writer ->
            writer.insert("""{"item": {"name": "a", "qty": 1}}""")
            writer.insert("""{"item": {"name": "b", "qty": 5}}""")

            val query = path("$..[?@.qty > 2].name")
            val first = backend.search(dir, query, after = null, limit = 10)
            assertEquals(listOf("\"b\""), first.hits.map { it.preview })
            assertNull(first.next)

            writer.insert("""{"item": {"name": "c", "qty": 9}}""")
            val second = backend.search(dir, query, after = null, limit = 10)
            assertEquals(listOf("\"b\"", "\"c\""), second.hits.map { it.preview })

            val info = backend.info(dir)
            assertEquals(3, info.documents)
            assertNotNull("the open writer is named", info.writer)
        }
    }

    @Test
    fun `pages hits and ends on the last page`() = runBlocking {
        val dir = temp.newFolder("store").toPath()
        JsonStore.create(dir).use { writer -> repeat(5) { writer.insert("""{"n": $it}""") } }

        val query = path("$.n")
        val first = backend.search(dir, query, after = null, limit = 3)
        val second = backend.search(dir, query, first.next, limit = 2)
        assertEquals(listOf("0", "1", "2", "3", "4"), (first.hits + second.hits).map { it.preview })
        assertNull("a page that ends with the last hit is the last", second.next)
        assertEquals("$['n']", first.hits[0].path)
        assertTrue(first.hits.all { it.nodePre > 0 && !it.previewCut })
    }

    @Test
    fun `searches the documents a filter accepts, and gives whole documents`() = runBlocking {
        val dir = temp.newFolder("store").toPath()
        val ids = JsonStore.create(dir).use { writer ->
            listOf(
                writer.insert("""{"kind": "order", "items": [{"sku": "a"}, {"sku": "b"}]}"""),
                writer.insert("""{"kind": "quote", "items": [{"sku": "c"}]}"""),
            ).map { it.raw }
        }

        val nodes = backend.search(dir, path("$..sku", where = "@.kind == 'order'"), after = null, limit = 10)
        assertEquals(listOf("\"a\"", "\"b\""), nodes.hits.map { it.preview })

        val documents = backend.search(dir, path("$..sku", wholeDocuments = true), after = null, limit = 10)
        assertEquals(ids, documents.hits.map { it.docId })
        assertEquals(listOf(0L, 0L), documents.hits.map { it.nodePre })
        assertEquals(listOf("$", "$"), documents.hits.map { it.path })
    }

    @Test
    fun `searches by key and value, as JSON or as text`() = runBlocking {
        val dir = temp.newFolder("store").toPath()
        JsonStore.create(dir).use { writer ->
            writer.insert("""{"order": {"sku": "B2", "qty": 3}}""")
            writer.insert("""{"order": {"sku": "3", "qty": 1}}""")
        }

        val asJson = backend.search(dir, StoreQuery.ByKeyValue("qty", "3", valueIsText = false, wholeDocuments = false), null, 10)
        assertEquals(listOf("""{"sku":"B2","qty":3}"""), asJson.hits.map { it.preview })
        assertEquals("$['order']", asJson.hits.single().path)

        val asText = backend.search(dir, StoreQuery.ByKeyValue("sku", "3", valueIsText = true, wholeDocuments = true), null, 10)
        assertEquals("""{"order":{"sku":"3","qty":1}}""", asText.hits.single().preview)
    }

    @Test
    fun `explains a value that isn't JSON and a search that would read every document`() {
        val dir = temp.newFolder("store").toPath()
        runBlocking { JsonStore.create(dir).use { writer -> writer.insert("""{"sku": "B2"}""") } }

        val notJson = assertThrows(StoreAccessException::class.java) {
            runBlocking { backend.search(dir, StoreQuery.ByKeyValue("sku", "B2", valueIsText = false, wholeDocuments = true), null, 10) }
        }
        assertEquals("The value B2 isn't JSON. Tick Text to search for it as a string.", notJson.message)

        val scan = assertThrows(StoreAccessException::class.java) {
            runBlocking { backend.search(dir, path("$..sku", scan = false), null, 10) }
        }
        assertTrue(scan.message, scan.message!!.startsWith("No index term narrows this query"))
    }

    @Test
    fun `explains how a search finds its documents`() = runBlocking {
        val dir = temp.newFolder("store").toPath()
        JsonStore.create(dir).close()

        val scan = backend.explain(dir, path("$..sku"))
        assertEquals(SearchPlan(fullScan = true, terms = null, within = null), scan)

        val narrowed = backend.explain(dir, path("$.orders[?@.sku == 'B2']", where = "@.kind == 'order'"))
        assertFalse(narrowed.fullScan)
        assertTrue(narrowed.terms!!, "'sku' == 'B2'" in narrowed.terms && "'kind' == 'order'" in narrowed.terms)
        assertEquals("$['orders']", narrowed.within)
    }

    @Test
    fun `previews the start of a long value, without cutting a character in two`() = runBlocking {
        val dir = temp.newFolder("store").toPath()
        // Each 😀 takes four bytes, which the preview's limit doesn't divide.
        val text = "x" + "😀".repeat(200)
        JsonStore.create(dir).use { writer -> writer.insert("""{"text": "$text"}""") }

        val hit = backend.search(dir, path("$.text"), after = null, limit = 10).hits.single()
        assertTrue(hit.previewCut)
        assertTrue(hit.preview, hit.preview.startsWith("\"x😀😀"))
        assertTrue(hit.preview, text.startsWith(hit.preview.removePrefix("\"")))
        assertTrue(hit.encodedSize >= 800)
    }

    @Test
    fun `pages documents in id order and ends on the last page`() = runBlocking {
        val dir = temp.newFolder("store").toPath()
        val ids = JsonStore.create(dir).use { writer -> (1..5).map { writer.insert("""{"n": $it}""").raw } }

        val first = backend.documents(dir, after = null, limit = 2)
        val second = backend.documents(dir, first.next, limit = 2)
        val third = backend.documents(dir, second.next, limit = 2)
        assertEquals(ids, (first.documents + second.documents + third.documents).map { it.docId })
        assertNull("a page that ends with the last document is the last", third.next)
        assertTrue(first.documents.all { it.key == null && it.encodedSize > 0 })
        assertNull(backend.documents(dir, after = null, limit = 5).next)
        assertNull(backend.info(dir).documentKey)
    }

    @Test
    fun `pages documents of a keyed store in key order`() = runBlocking {
        val dir = temp.newFolder("store").toPath()
        @OptIn(ExperimentalDelvaApi::class)
        JsonStore.create(dir, CreationOptions().withDocumentKey("$.name")).use { writer ->
            for (name in listOf("carol", "alice", "bob")) writer.insert("""{"name": "$name"}""")
        }

        val first = backend.documents(dir, after = null, limit = 2)
        val second = backend.documents(dir, first.next, limit = 2)
        assertEquals(listOf("alice", "bob", "carol"), (first.documents + second.documents).map { it.key })
        assertNull(second.next)
        assertEquals("$.name", backend.info(dir).documentKey)
    }

    @Test
    fun `reads a document as indented JSON, or nothing when it's longer than allowed`() = runBlocking {
        val dir = temp.newFolder("store").toPath()
        val id = JsonStore.create(dir).use { writer -> writer.insert("""{"item": {"name": "a"}, "text": "${"x".repeat(500)}"}""").raw }

        val json = backend.readNode(dir, id, nodePre = 0, maxChars = 10_000)!!
        assertTrue(json, json.startsWith("{\n") && "\"name\": \"a\"" in json)
        assertNull(backend.readNode(dir, id, nodePre = 0, maxChars = 100))
    }

    @Test
    fun `reads the node a hit stands for`() = runBlocking {
        val dir = temp.newFolder("store").toPath()
        JsonStore.create(dir).use { writer -> writer.insert("""{"order": {"items": [{"sku": "A1", "qty": 0}]}}""") }

        val hit = backend.search(dir, path("$..[?@.qty < 1]"), after = null, limit = 10).hits.single()
        assertEquals("$['order']['items'][0]", hit.path)
        assertEquals("""{"sku": "A1", "qty": 0}""", backend.readNode(dir, hit.docId, hit.nodePre, maxChars = 1000))
    }

    @Test
    fun `reports a document that is no longer in the store`() {
        val dir = temp.newFolder("store").toPath()
        val id = runBlocking {
            JsonStore.create(dir).use { writer -> writer.insert("""{"n": 1}""").also { writer.delete(it) } }
        }
        val e = assertThrows(StoreAccessException::class.java) { runBlocking { backend.readNode(dir, id.raw, 0, 1000) } }
        assertEquals("Document ${id.raw} is no longer in the store.", e.message)
    }

    @Test
    fun `reports a directory without a store`() {
        val dir = temp.newFolder("empty").toPath()
        val e = assertThrows(StoreAccessException::class.java) { runBlocking { backend.info(dir) } }
        assertTrue(e.message, e.message!!.startsWith("There is no Delva store at"))
    }

    @Test
    fun `reports an invalid query or filter with its position`() {
        val error = backend.checkQuery("$.")
        assertTrue(error, error!!.startsWith("Invalid JSONPath at 2: "))
        assertNull(backend.checkQuery("$..book[?@.price < 10]"))
        val query = assertThrows(StoreAccessException::class.java) {
            runBlocking { backend.search(temp.root.toPath(), path("$["), after = null, limit = 1) }
        }
        assertTrue(query.message, query.message!!.startsWith("Invalid JSONPath at "))

        val filterError = backend.checkFilter("@.kind ==")
        assertTrue(filterError, filterError!!.startsWith("Invalid filter at "))
        assertNull(backend.checkFilter("@.kind == 'order' && @.version >= 2"))
        val filter = assertThrows(StoreAccessException::class.java) {
            runBlocking { backend.explain(temp.root.toPath(), path("$", where = "@.kind ==")) }
        }
        assertTrue(filter.message, filter.message!!.startsWith("Invalid filter at "))
    }

    @Test
    fun `opens a released store again on the next read`() = runBlocking {
        val dir = temp.newFolder("store").toPath()
        JsonStore.create(dir).use { writer -> writer.insert("""{"n": 1}""") }
        assertEquals(1, backend.info(dir).documents)

        backend.release(dir)
        backend.release(dir)
        backend.release(temp.root.toPath())
        assertEquals(1, backend.info(dir).documents)
    }

    @Test
    fun `refuses reads after close`() {
        val dir = temp.newFolder("store").toPath()
        JsonStore.create(dir).close()
        backend.close()
        assertThrows(IllegalStateException::class.java) { runBlocking { backend.info(dir) } }
    }

    private fun path(query: String, where: String? = null, wholeDocuments: Boolean = false, scan: Boolean = true) =
        StoreQuery.ByPath(query, where, wholeDocuments, scan)
}
