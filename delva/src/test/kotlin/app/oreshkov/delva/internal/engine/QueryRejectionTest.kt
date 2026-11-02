package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.QueryRejectedException.Reason
import app.oreshkov.delva.StoreOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every refusal of a search states what the store found, and only that: the remedy depends on who is asking — a
 * program through the API, or a person through a command line with options of its own — so it is chosen from
 * [QueryRejectedException.reason], and the detail names no API that a command-line user couldn't act on.
 */
class QueryRejectionTest {

    private fun options(creation: CreationOptions = CreationOptions(fragmentBytes = 1024), maxRangeDocuments: Int = 3) = EngineOptions(
        creation, StoreOptions(encoderBudgetBytes = 4096), sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 5, log = TestEngines.LOG,
        backgroundFlush = false, maxRangeDocuments = maxRangeDocuments,
    )

    private fun rejected(creation: CreationOptions = CreationOptions(fragmentBytes = 1024), search: (Engine) -> Unit): QueryRejectedException =
        TestEngines.open(options(creation)).use { store ->
            for (price in 1..5) store.insert("""[{"price":$price,"note":"n$price"}]""")
            assertFailsWith<QueryRejectedException> { search(store) }
        }

    /** The fields, and the message that is the detail behind a fixed prefix; no API names, no remedy. */
    private fun assertFact(e: QueryRejectedException, reason: Reason, detail: String, query: String?) {
        assertEquals(reason, e.reason)
        assertEquals(detail, e.detail)
        assertEquals(query, e.query)
        assertEquals("Query rejected: $detail", e.message)
        assertTrue(API_NAMES.find(detail) == null, "the detail names API: $detail")
    }

    @Test
    fun `a search no index term narrows names its query`() {
        val e = rejected { it.search(JsonPathQuery(JsonPath.compile("$..price"))) }
        assertFact(e, Reason.NEEDS_SCAN, "no index term narrows the search for $..price", "$..price")
    }

    @Test
    @OptIn(ExperimentalDelvaApi::class)
    fun `the query of a refused search is its path, without the document filter`() {
        val filtered = JsonPathQuery(JsonPath.compile("$..price")).withDocumentFilter(DocumentFilter.compile("@.note != 'n1'"))
        val e = rejected { it.count(filtered) }
        assertFact(e, Reason.NEEDS_SCAN, "no index term narrows the search for $..price", "$..price")
    }

    @Test
    fun `a range too wide says how many documents it would collect`() {
        val text = "$[?@.price > 1]"
        val e = rejected { it.search(JsonPathQuery(JsonPath.compile(text))) }
        assertEquals(Reason.RANGE_TOO_WIDE, e.reason)
        assertTrue(e.detail.startsWith("the range ") && e.detail.endsWith(" of $text matches more than 3 documents"), e.detail)
        assertFact(e, Reason.RANGE_TOO_WIDE, e.detail, text)
    }

    @Test
    fun `a query too long to search says how long it is`() {
        val text = "$" + "[0]".repeat(31) + "[?@.a == 1]"
        val e = rejected { it.search(JsonPathQuery(JsonPath.compile(text))) }
        assertFact(e, Reason.TOO_MANY_SEGMENTS, "$text has 32 segments; a search takes at most 31", text)
    }

    @Test
    fun `a key the store doesn't index is named`() {
        val e = rejected(CreationOptions(fragmentBytes = 1024, excludedKeys = setOf("note"))) { it.search(KeyValueQuery("note", JsonQueryValue.Str("n1"))) }
        assertFact(e, Reason.NOT_INDEXED, "the key \"note\" isn't indexed by this store", null)
    }

    @Test
    fun `a string length the store doesn't index is given with the lengths it does`() {
        fun detail(min: Int, max: Int, value: String): String =
            rejected(CreationOptions(fragmentBytes = 1024, minIndexedStringBytes = min, maxIndexedStringBytes = max)) {
                it.search(KeyValueQuery("note", JsonQueryValue.Str(value)))
            }.also { assertEquals(Reason.NOT_INDEXED, it.reason) }.detail
        val suffix = "aren't indexed by this store, which indexes strings of"
        assertEquals("string values of 2 bytes $suffix 3 to 20 bytes", detail(3, 20, "n1"))
        assertEquals("string values of 2 bytes $suffix at least 3 bytes", detail(3, Int.MAX_VALUE, "n1"))
        assertEquals("string values of 6 bytes $suffix at most 4 bytes", detail(0, 4, "n12345"))
    }

    @Test
    fun `an object or array value in a store without hash terms is refused as a fact`() {
        val e = rejected(CreationOptions(fragmentBytes = 1024, hashTerms = false)) { it.search(KeyValueQuery("note", JsonQueryValue.Json("[1]"))) }
        assertFact(e, Reason.NOT_INDEXED, "object and array values aren't indexed by this store", null)
        assertNull(e.query)
    }

    private companion object {
        /** Identifiers of the library's API, which a remedy in its vocabulary would name. */
        val API_NAMES = Regex("""ScanPolicy|ALLOW_SCAN|CreationOptions|indexedKeys|excludedKeys|IndexedStringBytes|hashTerms|\bselect\b""")
    }
}
