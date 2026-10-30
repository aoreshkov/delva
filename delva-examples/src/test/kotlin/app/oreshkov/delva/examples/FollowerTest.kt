package app.oreshkov.delva.examples

import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The follower example, kept working: a secondary waits for what its writer writes, and counts as the writer does. */
class FollowerTest {
    private val dir: Path = Files.createTempDirectory("delva-follower")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    @Test
    fun `a secondary waits for documents its writer is still writing`() {
        val path = dir.resolve("store")
        val done = KeyValueQuery("state", JsonQueryValue.Str("done"))
        JsonStore.create(path).use { writer ->
            JsonStore.openSecondary(path).use { reader ->
                val db = reader.blocking()
                assertFalse(awaitHits(db, done, atLeast = 1, poll = 10.milliseconds, timeout = 50.milliseconds))
                val writing = thread {
                    repeat(20) { writer.blocking().insert("""{"job":$it,"state":"done"}""") }
                }
                assertTrue(awaitHits(db, done, atLeast = 20, poll = 10.milliseconds, timeout = 30.seconds))
                writing.join()
                assertEquals(hitCount(writer.blocking(), done), hitCount(db, done))
            }
        }
    }
}
