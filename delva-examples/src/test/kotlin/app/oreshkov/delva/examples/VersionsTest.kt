package app.oreshkov.delva.examples

import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The versions example, kept working: writers saving versions of one key at once leave exactly one visible. */
class VersionsTest {
    private val dir: Path = Files.createTempDirectory("delva-versions")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    @Test
    fun `concurrent writers keep one current version`() {
        JsonStore.create(dir.resolve("store")).use { store ->
            val db = store.blocking()
            saveVersion(db, "id", "cfg", """{"id":"cfg","writer":-1,"n":0}""")
            val executor = Executors.newFixedThreadPool(WRITERS)
            try {
                val writers = (0 until WRITERS).map { w ->
                    executor.submit(Callable { repeat(VERSIONS) { n -> saveVersion(db, "id", "cfg", """{"id":"cfg","writer":$w,"n":${n + 1}}""") } })
                }
                writers.forEach { it.get(60, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
            val visible = db.search(KeyValueQuery("id", JsonQueryValue.Str("cfg"))).use { result -> result.iterator().asSequence().map { it.docId }.toList() }
            assertEquals(1, visible.size, "visible versions: $visible")
            assertEquals(WRITERS * VERSIONS.toLong(), db.info().deletedDocumentCount, "every save after the first replaced one version")
        }
    }

    private companion object {
        const val WRITERS = 4
        const val VERSIONS = 25
    }
}
