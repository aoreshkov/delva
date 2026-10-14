package app.oreshkov.delva

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * [JsonStore.blocking] is a view: it can't close the store, not even through a runtime `AutoCloseable` check such as
 * a DI container's inferred destroy method, and it stops working when the store is closed.
 */
class BlockingViewTest {
    private val dir: Path = Files.createTempDirectory("delva-blocking")

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    @Test
    fun `the view is not closeable`() {
        JsonStore.create(dir).use { store ->
            val blocking = store.blocking()
            assertFalse(blocking is AutoCloseable)
            assertSame(blocking, store.blocking())
        }
    }

    @Test
    fun `the view works until the store is closed`() {
        val store = JsonStore.create(dir)
        val blocking = store.blocking()
        val id = blocking.insert("""{"a":"x"}""")
        store.close()
        assertFailsWith<StoreClosedException> { blocking.insert("""{"a":"y"}""") }

        // Closing the store released the directory, and the insert through the view is in it.
        JsonStore.open(dir).use { reopened ->
            val hits = reopened.blocking().search(KeyValueQuery("a", JsonQueryValue.Str("x"))).use { result ->
                result.iterator().asSequence().map { it.node }.toList()
            }
            assertEquals(listOf(NodeRef(id, 0)), hits)
        }
    }
}
