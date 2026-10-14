package app.oreshkov.delva.examples

import app.oreshkov.delva.JsonPath
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The records example, kept working: concurrent saves of one record leave one version, and listings page by id. */
class RecordsTest {
    private val dir: Path = Files.createTempDirectory("delva-records")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    @Test
    fun `concurrent saves of one record keep one version`() {
        openRecords(dir.resolve("store")).use { store ->
            val db = store.blocking()
            val executor = Executors.newFixedThreadPool(WRITERS)
            try {
                val writers = (0 until WRITERS).map { w ->
                    executor.submit(Callable { repeat(VERSIONS) { n -> saveRecord(db, """{"id":"cfg","writer":$w,"n":$n}""") } })
                }
                writers.forEach { it.get(60, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
            assertEquals(listOf("cfg"), listRecords(db, ""))
            assertEquals(WRITERS * VERSIONS - 1L, db.info().deletedDocumentCount, "every save after the first replaced one version")
            assertTrue(readRecord(db, "cfg")!!.startsWith("""{"id":"cfg","writer":"""))
        }
    }

    @Test
    fun `records list by id, page by page, and read back`() {
        openRecords(dir.resolve("store")).use { store ->
            val db = store.blocking()
            for (id in listOf("user-3", "order-2", "user-1", "user-20", "order-1")) saveRecord(db, """{"id":"$id"}""")
            assertEquals(listOf("user-1", "user-20", "user-3"), listRecords(db, "user-", pageSize = 2))
            assertEquals(listOf("order-1", "order-2", "user-1", "user-20", "user-3"), listRecords(db, "", pageSize = 1))
            assertEquals("""{"id":"user-20"}""", readRecord(db, "user-20"))
            assertNull(readRecord(db, "user-2"), "a prefix of another id isn't a record")
            assertTrue(hasRecord(db, "order-1"))
            assertFalse(hasRecord(db, "order"))
        }
    }

    @Test
    fun `a search over the records of one kind reads only those`() {
        openRecords(dir.resolve("store")).use { store ->
            val db = store.blocking()
            saveRecord(db, """{"id":"o-1","kind":"order","items":[{"sku":"A"}]}""")
            saveRecord(db, """{"id":"u-1","kind":"user","data":{"kind":"order","sku":"X"}}""")
            saveRecord(db, """{"id":"o-2","kind":"order","items":[{"sku":"B"},{"sku":"C"}]}""")
            saveRecord(db, """{"id":"q-1","kind":"it's \"quoted\"\\","sku":"Q"}""")
            val skus = JsonPath.compile("$..sku")
            assertEquals(listOf("o-1" to "\"A\"", "o-2" to "\"B\"", "o-2" to "\"C\""), findInRecords(db, "order", skus))
            assertEquals(listOf("q-1" to "\"Q\""), findInRecords(db, "it's \"quoted\"\\", skus))
            assertEquals(emptyList(), findInRecords(db, "order\" || @.kind == \"user", skus), "a value can't change the filter")
        }
    }

    private companion object {
        const val WRITERS = 4
        const val VERSIONS = 25
    }
}
