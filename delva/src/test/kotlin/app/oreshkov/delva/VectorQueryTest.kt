package app.oreshkov.delva

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** [VectorQuery] is a value: validated, holding its own copy of the vector, equal by the vector's bits. */
class VectorQueryTest {
    @Test
    fun `the query keeps its own copy of the vector and hands out copies`() {
        val vector = floatArrayOf(1f, 2f, 3f)
        val query = VectorQuery(vector)
        vector[0] = 9f
        assertEquals(listOf(1f, 2f, 3f), query.vector.toList())
        query.vector[1] = 9f
        assertEquals(listOf(1f, 2f, 3f), query.vector.toList())
        val copied = query.copy(vector = vector)
        vector[1] = 9f
        assertEquals(listOf(9f, 2f, 3f), copied.vector.toList())
        assertEquals(3, query.dimensions)
    }

    @Test
    fun `queries with the same settings are equal, the vector compared bit by bit`() {
        val filter = DocumentFilter.compile("@.a == 1")
        val a = VectorQuery(floatArrayOf(1f, 0f), k = 3, maxDistance = 0.5f).withDocumentFilter(filter)
        val b = VectorQuery(floatArrayOf(1f, 0f), k = 3, maxDistance = 0.5f).withDocumentFilter(DocumentFilter.compile("@.a == 1"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, VectorQuery(floatArrayOf(1f, -0f), k = 3, maxDistance = 0.5f).withDocumentFilter(filter))
        assertNotEquals(a, a.copy(k = 4))
        assertNotEquals(a, a.withDocumentFilter(null))
        assertEquals(filter, a.copy(scan = ScanPolicy.ALLOW_SCAN).documentFilter)
        assertNull(VectorQuery(floatArrayOf(1f)).documentFilter)
    }

    @Test
    fun `toString names the vector by its dimensions and the settings that aren't defaults`() {
        assertEquals("VectorQuery(dimensions=2, k=10)", VectorQuery(floatArrayOf(1f, 0f)).toString())
        assertEquals(
            "VectorQuery(dimensions=1, k=1, maxDistance=2.5, scan=ALLOW_SCAN, documentFilter=@.a == 1)",
            VectorQuery(floatArrayOf(1f), 1, 2.5f, ScanPolicy.ALLOW_SCAN).withDocumentFilter(DocumentFilter.compile("@.a == 1")).toString(),
        )
    }

    @Test
    fun `an empty or non-finite vector, k out of range and a NaN maxDistance are refused`() {
        assertFailsWith<IllegalArgumentException> { VectorQuery(FloatArray(0)) }
        assertEquals(
            "element 1 of the query vector is NaN, not a finite number",
            assertFailsWith<IllegalArgumentException> { VectorQuery(floatArrayOf(0f, Float.NaN)) }.message,
        )
        assertFailsWith<IllegalArgumentException> { VectorQuery(floatArrayOf(Float.POSITIVE_INFINITY)) }
        assertFailsWith<IllegalArgumentException> { VectorQuery(floatArrayOf(1f), k = 0) }
        assertFailsWith<IllegalArgumentException> { VectorQuery(floatArrayOf(1f), k = VectorQuery.MAX_K + 1) }
        assertEquals(VectorQuery.MAX_K, VectorQuery(floatArrayOf(1f), k = VectorQuery.MAX_K).k)
        assertFailsWith<IllegalArgumentException> { VectorQuery(floatArrayOf(1f), maxDistance = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { VectorQuery(floatArrayOf(1f)).copy(k = -1) }
        // Distances can be negative under DOT_PRODUCT, and infinite.
        VectorQuery(floatArrayOf(1f), maxDistance = Float.NEGATIVE_INFINITY)
    }
}
