package app.oreshkov.delva.internal.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PatternCacheTest {
    @Test
    fun `a short pattern is compiled once and kept`() {
        val cache = PatternCache()
        val first = cache.compile("a+b")
        assertSame(first, cache.compile("a+b"))
        assertEquals(1, cache.size)
        assertTrue(first!!.matches("aab"))
    }

    @Test
    fun `patterns longer than the bound are compiled but never kept, so stored text can't fill the heap`() {
        val cache = PatternCache()
        repeat(200) { i ->
            val pattern = "x".repeat(PatternCache.MAX_CHARS) + i
            val compiled = cache.compile(pattern)!!
            assertTrue(compiled.matches(pattern))
            assertFalse(compiled.matches("x"))
        }
        assertEquals(0, cache.size)
    }

    @Test
    fun `at most MAX_ENTRIES patterns are kept, and an invalid one compiles to null`() {
        val cache = PatternCache()
        repeat(PatternCache.MAX_ENTRIES * 2) { cache.compile("p$it") }
        assertEquals(PatternCache.MAX_ENTRIES, cache.size)
        assertNull(cache.compile("("))
        assertEquals(PatternCache.MAX_ENTRIES, cache.size)
    }
}
