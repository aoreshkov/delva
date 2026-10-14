package app.oreshkov.delva.examples

import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The typed-search example, kept working. The document has two objects with the same id and only one of them
 * tagged, which is the case the second condition exists for.
 */
class TypedSearchTest {
    private val dir: Path = Files.createTempDirectory("delva-examples")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    private val document = """
        {"entries": [
          {"@type": "Battlefield", "id": "bf-101", "scene": "grass"},
          {"@type": "Battlefield", "id": "bf-102", "scene": "desert"},
          {"@type": "Reward", "id": "bf-101", "amount": 7}
        ]}
    """.trimIndent()

    @Test
    fun `a tagged type narrows the lookup to one object`() {
        val json = dir.resolve("entries.json")
        Files.writeString(json, document)

        JsonStore.create(dir.resolve("store")).use { store ->
            val db = store.blocking()
            db.insert(json)

            val tag = Condition("@type", "Battlefield")
            assertEquals(2, tagObjects(db, tag, tag.value))
            assertEquals(2, db.countOfType(tag.value))

            val hits = findInType(db, tag.value, Condition("id", "bf-101"))
            assertEquals(listOf("""{"@type":"Battlefield","id":"bf-101","scene":"grass"}"""), hits.map { it.second })

            // Without the type filter the same lookup also finds the reward that shares the id.
            val untyped = KeyValueQuery("id", JsonQueryValue.Str("bf-101"), ResultMode.CONTAINING_ELEMENT)
            assertEquals(2, db.search(untyped).use { it.iterator().asSequence().count() })
        }
    }

    @Test
    fun `a condition splits on the first equals sign`() {
        val condition = Condition.parse("id=a=b")
        assertEquals("id", condition.key)
        assertEquals("a=b", condition.value)
        assertFailsWith<IllegalArgumentException> { Condition.parse("=missing-key") }
        assertFailsWith<IllegalArgumentException> { Condition.parse("no-separator") }
    }
}
