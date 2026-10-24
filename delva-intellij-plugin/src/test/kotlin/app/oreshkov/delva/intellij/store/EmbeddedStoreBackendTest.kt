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

            val first = backend.searchPath(dir, "$[?@.qty > 2].name", limit = 10, maxChars = 1000)
            assertEquals(listOf("\"b\""), first.hits.map { it.json })
            assertFalse(first.more)

            writer.insert("""{"item": {"name": "c", "qty": 9}}""")
            val second = backend.searchPath(dir, "$[?@.qty > 2].name", limit = 10, maxChars = 1000)
            assertEquals(listOf("\"b\"", "\"c\""), second.hits.map { it.json })

            val info = backend.info(dir)
            assertEquals(3, info.documents)
            assertNotNull("the open writer is named", info.writer)
        }
    }

    @Test
    fun `stops at the limit and says more hits are left`() = runBlocking {
        val dir = temp.newFolder("store").toPath()
        JsonStore.create(dir).use { writer -> repeat(5) { writer.insert("""{"n": $it}""") } }

        val outcome = backend.searchPath(dir, "$.n", limit = 3, maxChars = 1000)
        assertEquals(listOf("0", "1", "2"), outcome.hits.map { it.json })
        assertTrue(outcome.more)
        assertEquals(listOf("$['n']", "$['n']", "$['n']"), outcome.hits.map { it.path })
    }

    @Test
    fun `omits a value longer than allowed but keeps its size`() = runBlocking {
        val dir = temp.newFolder("store").toPath()
        JsonStore.create(dir).use { writer -> writer.insert("""{"text": "${"x".repeat(500)}"}""") }

        val hit = backend.searchPath(dir, "$.text", limit = 10, maxChars = 100).hits.single()
        assertNull(hit.json)
        assertTrue(hit.encodedSize >= 500)
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

        val json = backend.readDocument(dir, id, maxChars = 10_000)!!
        assertTrue(json, json.startsWith("{\n") && "\"name\": \"a\"" in json)
        assertNull(backend.readDocument(dir, id, maxChars = 100))
    }

    @Test
    fun `reports a document that is no longer in the store`() {
        val dir = temp.newFolder("store").toPath()
        val id = runBlocking {
            JsonStore.create(dir).use { writer -> writer.insert("""{"n": 1}""").also { writer.delete(it) } }
        }
        val e = assertThrows(StoreAccessException::class.java) { runBlocking { backend.readDocument(dir, id.raw, 1000) } }
        assertEquals("Document ${id.raw} is no longer in the store.", e.message)
    }

    @Test
    fun `reports a directory without a store`() {
        val dir = temp.newFolder("empty").toPath()
        val e = assertThrows(StoreAccessException::class.java) { runBlocking { backend.info(dir) } }
        assertTrue(e.message, e.message!!.startsWith("There is no Delva store at"))
    }

    @Test
    fun `reports an invalid query with its position`() {
        val error = backend.checkQuery("$.")
        assertTrue(error, error!!.startsWith("Invalid JSONPath at 2: "))
        assertNull(backend.checkQuery("$..book[?@.price < 10]"))
        assertThrows(StoreAccessException::class.java) {
            runBlocking { backend.searchPath(temp.root.toPath(), "$[", limit = 1, maxChars = 1) }
        }
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
}
