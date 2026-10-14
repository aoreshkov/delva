package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.query.QueryTerm
import kotlin.test.Test
import kotlin.test.assertEquals

/** Object and array query values hash like the stored values they find, at any depth. */
class LiteralHashTest {
    @Test
    fun `a literal nested 200 000 deep hashes without recursion`() {
        val depth = 200_000
        val deepArray = "[".repeat(depth) + "]".repeat(depth)
        val deepObject = """{"k":""".repeat(depth) + "1" + "}".repeat(depth)
        for (literal in listOf(deepArray, deepObject)) {
            val term = QueryTerm.of("a", JsonQueryValue.Json(literal), hashTerms = true, maxDepth = 1_000_000)
            assertEquals(true, term.lossy)
        }
    }

    @Test
    fun `nested object and array literals find the values they equal`() {
        val options = EngineOptions(
            CreationOptions(fragmentBytes = 64, hashTerms = true), StoreOptions(encoderBudgetBytes = 1024),
            sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 5, log = TestEngines.LOG, backgroundFlush = false,
        )
        val values = listOf(
            """{"x":{},"y":[[],[{}]],"z":[1,"s",null,true,false,1.5e3]}""",
            """[[[[1]]],{"a":{"b":{"c":[]}}}]""",
            """{"b":1,"a":2}""",
            "[]",
            "{}",
        )
        TestEngines.open(options).use { store ->
            for (value in values) store.insert("""{"v":$value}""")
            for (value in values) {
                val hits = store.search(KeyValueQuery("v", JsonQueryValue.Json(value), ResultMode.CONTAINING_ELEMENT)).use { it.iterator().asSequence().count() }
                assertEquals(1, hits, value)
            }
            // Member order doesn't matter.
            val reordered = store.search(KeyValueQuery("v", JsonQueryValue.Json("""{"a":2,"b":1}"""))).use { it.iterator().asSequence().count() }
            assertEquals(1, reordered)
        }
    }
}
