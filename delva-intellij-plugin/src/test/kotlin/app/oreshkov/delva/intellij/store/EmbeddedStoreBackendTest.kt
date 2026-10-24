package app.oreshkov.delva.intellij.store

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
