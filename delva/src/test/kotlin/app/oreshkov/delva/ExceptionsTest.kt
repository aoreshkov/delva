package app.oreshkov.delva

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExceptionsTest {
    @Test
    fun `messages carry the location and reason`() {
        val corrupt = CorruptionException("segment-000012.seg@4096", "CRC mismatch")
        assertEquals("Corrupt data at segment-000012.seg@4096: CRC mismatch", corrupt.message)

        val rejected = DocumentRejectedException(RejectReason.DUPLICATE_KEY, byteOffset = 812, jsonPath = "$.items[3]", detail = "key \"sku\"")
        assertEquals("Document rejected: DUPLICATE_KEY at byte 812 ($.items[3]): key \"sku\"", rejected.message)
        assertEquals("Document rejected: LIMIT_EXCEEDED", DocumentRejectedException(RejectReason.LIMIT_EXCEEDED, -1).message)

        assertEquals("Document 42 doesn't exist or isn't visible", DocumentNotFoundException(DocId(42)).message)
    }

    @Test
    fun `every Delva exception is a DelvaException`() {
        val all: List<RuntimeException> = listOf(
            CorruptionException("x", "y"), EngineFailedException("fsync failed"),
            DocumentRejectedException(RejectReason.MALFORMED_JSON, 0), DocumentNotFoundException(DocId(1)),
            StoreClosedException(), InvalidTypeNameException("empty"),
        )
        all.forEach { assertIs<DelvaException>(it) }
    }

    @Test
    fun `an options mismatch names every option that differs`() {
        val dir = Path.of("db")
        val stored = CreationOptions(fragmentBytes = 4096, excludedKeys = setOf("b", "a"))
        val e = CreationOptionsMismatchException(dir, stored, CreationOptions(normalization = Normalization.NFC))
        assertEquals(
            "The store at db was created with fragmentBytes 4096, not 65536; excludedKeys [a, b], not []; normalization NONE, not NFC",
            e.message,
        )
        assertEquals(stored, e.stored)
    }

    @Test
    fun `another format version says which side is newer and what this version reads`() {
        val dir = Path.of("db")
        assertEquals(
            "The store at db is in format version 3, written by a newer version of Delva; this one reads format versions 1, 2",
            UnsupportedFormatException(dir, 3, listOf(1, 2)).message,
        )
        assertEquals(
            "The store at db is in format version 1, written by an older version of Delva; this one reads format version 2",
            UnsupportedFormatException(dir, 1, listOf(2)).message,
        )
        assertEquals(
            "The store at db is in format version 2, written by another version of Delva; this one reads format versions 1, 3",
            UnsupportedFormatException(dir, 2, listOf(1, 3)).message,
        )
        assertIs<DelvaException>(UnsupportedFormatException(dir, 2, listOf(1)))
    }

    @Test
    fun `a rejected query carries its reason, the fact apart from the prefix, and the query's text`() {
        val e = QueryRejectedException(QueryRejectedException.Reason.NOT_INDEXED, "the key \"k\" isn't indexed by this store")
        assertEquals("Query rejected: the key \"k\" isn't indexed by this store", e.message)
        assertEquals(QueryRejectedException.Reason.NOT_INDEXED, e.reason)
        assertEquals("the key \"k\" isn't indexed by this store", e.detail)
        assertNull(e.query)

        val path = QueryRejectedException(QueryRejectedException.Reason.NEEDS_SCAN, "no index term narrows the search for $..a", "$..a")
        assertEquals("Query rejected: no index term narrows the search for $..a", path.message)
        assertEquals("no index term narrows the search for $..a", path.detail)
        assertEquals("$..a", path.query)
    }

    @Test
    fun `an invalid query or cursor stays an IllegalArgumentException, with the details as fields`() {
        val path = assertFailsWith<InvalidJsonPathException> { JsonPath.compile("$[0 1]") }
        assertEquals("$[0 1]", path.text)
        assertEquals(4, path.offset)
        assertEquals("expected ',' or ']'", path.reason)
        assertIs<IllegalArgumentException>(path)
        assertIs<InvalidJsonPathException>(assertFailsWith<IllegalArgumentException> { JsonPath.compile("$[?@.a]]") })

        val cursor = assertFailsWith<InvalidCursorException> { Cursor.parse("not a cursor") }
        assertIs<IllegalArgumentException>(cursor)
        assertTrue(cursor.message!!.startsWith("not a cursor"), cursor.message)
    }
}
