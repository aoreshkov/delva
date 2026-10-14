package app.oreshkov.delva.testkit.generate

import app.oreshkov.delva.testkit.json.ONum
import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs under `heapCappedTest` (256 MB heap): generating and checking a 1 GiB document never materializes it. */
class GeneratedJsonHeapCappedTest {
    @Test
    fun `a 1 GiB document streams through in bounded memory`() {
        val expectation = StreamingExpectation("qty", ONum("1"))
        val bytes = GeneratedJson(42, BigDocProfile.MIXED, 1L shl 30, expectation).drain()
        assertTrue(bytes >= 1L shl 30)
        // Long strings take most of the bytes in MIXED, so hits are in the thousands.
        assertTrue(expectation.containingHits > 5_000, "hits ${expectation.containingHits}")
        assertTrue(expectation.maxDepth > 100)
    }
}
