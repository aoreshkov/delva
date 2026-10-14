package app.oreshkov.delva

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** Queries are values: equal settings make equal queries, and `copy` derives a validated variant. */
class QueriesTest {
    @Test
    fun `key-value queries are values`() {
        val query = KeyValueQuery("k", JsonQueryValue.Num(1), limit = 5)
        assertEquals(query, KeyValueQuery("k", JsonQueryValue.Num(1), limit = 5))
        assertEquals(query.hashCode(), KeyValueQuery("k", JsonQueryValue.Num(1), limit = 5).hashCode())
        assertNotEquals(query, query.copy(type = "T"))
        assertEquals(KeyValueQuery("k", JsonQueryValue.Num(1), ResultMode.CONTAINING_ELEMENT, limit = 5), query.copy(mode = ResultMode.CONTAINING_ELEMENT))
        assertFailsWith<IllegalArgumentException> { query.copy(limit = 0) }
        assertEquals("KeyValueQuery(key=k, value=Num(value=1), mode=WHOLE_DOCUMENT, nested=ALL, limit=5)", query.toString())
        assertNotEquals(query, query.copy(order = SearchOrder.DESCENDING))
        assertEquals(KeyValueQuery("k", JsonQueryValue.Num(1), limit = 5, order = SearchOrder.DESCENDING), query.copy(order = SearchOrder.DESCENDING))
        assertEquals("KeyValueQuery(key=k, value=Num(value=1), mode=WHOLE_DOCUMENT, nested=ALL, limit=5, order=DESCENDING)", query.copy(order = SearchOrder.DESCENDING).toString())
    }

    @Test
    fun `JSONPath queries are values`() {
        val query = JsonPathQuery(JsonPath.compile("$.a"), scan = ScanPolicy.ALLOW_SCAN)
        assertEquals(query, JsonPathQuery(JsonPath.compile("$.a"), scan = ScanPolicy.ALLOW_SCAN))
        assertEquals(query.hashCode(), JsonPathQuery(JsonPath.compile("$.a"), scan = ScanPolicy.ALLOW_SCAN).hashCode())
        assertNotEquals(query, query.copy(scan = ScanPolicy.REJECT))
        assertFailsWith<IllegalArgumentException> { query.copy(maxBytes = 0) }
        assertEquals("JsonPathQuery(path=$.a, mode=CONTAINING_ELEMENT, nested=ALL, limit=100, scan=ALLOW_SCAN)", query.toString())
        assertNotEquals(query.hashCode(), query.copy(order = SearchOrder.DESCENDING).hashCode())
        assertEquals("JsonPathQuery(path=$.a, mode=CONTAINING_ELEMENT, nested=ALL, limit=100, scan=ALLOW_SCAN, order=DESCENDING)", query.copy(order = SearchOrder.DESCENDING).toString())
    }

    @Test
    fun `a document filter is part of the query's value and survives copy`() {
        val query = JsonPathQuery(JsonPath.compile("$.a"))
        val filter = DocumentFilter.compile("@.kind == 'x'")
        val filtered = query.withDocumentFilter(filter)
        assertEquals(filter, filtered.documentFilter)
        assertEquals(filtered, JsonPathQuery(JsonPath.compile("$.a"), documentFilter = DocumentFilter.compile("@.kind == 'x'")))
        assertEquals(filtered.hashCode(), query.withDocumentFilter(DocumentFilter.compile("@.kind == 'x'")).hashCode())
        assertNotEquals(query, filtered)
        assertEquals(filter, filtered.copy(limit = 3).documentFilter)
        assertEquals(query, filtered.withDocumentFilter(null))
        assertEquals("JsonPathQuery(path=$.a, mode=CONTAINING_ELEMENT, nested=ALL, limit=100, documentFilter=@.kind == 'x')", filtered.toString())
        // Filters are equal by text, as JSONPath queries are.
        assertNotEquals(filter, DocumentFilter.compile("@.kind=='x'"))
        assertEquals("@.kind == 'x'", filter.toString())
    }
}
