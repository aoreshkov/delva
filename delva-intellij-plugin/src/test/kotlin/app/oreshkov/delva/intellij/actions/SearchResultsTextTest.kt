package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.intellij.store.StoreHit
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchResultsTextTest {
    @Test
    fun `renders hits as a JSON array, escaping keys and paths and indenting values`() {
        val hits = listOf(
            StoreHit(docId = 1, documentKey = "a\"b", path = "$['x\\y']", json = "{\n  \"n\": 1\n}", encodedSize = 8),
            StoreHit(docId = 2, documentKey = null, path = "$['big']", json = null, encodedSize = 123456),
        )
        val expected = """
            [
              {
                "docId": 1,
                "key": "a\"b",
                "path": "$['x\\y']",
                "value": {
                  "n": 1
                }
              },
              {
                "docId": 2,
                "path": "$['big']",
                "valueOmittedBytes": 123456
              }
            ]

        """.trimIndent()
        assertEquals(expected, searchResultsText(hits))
    }

    @Test
    fun `renders no hits as an empty array`() {
        assertEquals("[]\n", searchResultsText(emptyList()))
    }
}
