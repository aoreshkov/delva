import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckSelfContainedCommentsTest {
    @Test
    fun findsAnIdInAComment() {
        assertEquals("G-26", outsideCitation("// Decided in G-26."))
        assertEquals("N-04.4", outsideCitation("// See N-04.4 for why."))
        assertEquals("Phase 11", outsideCitation("// Added in Phase 11."))
    }

    @Test
    fun findsAnIdAfterAnEscapeSequence() {
        assertEquals("T-10", outsideCitation("""println("\nT-10 benchmark report")"""))
        assertEquals("T-10", outsideCitation("""println("\tT-10")"""))
        assertEquals("G-26", outsideCitation("""println("\u00a0G-26")"""))
        assertEquals("Phase 3", outsideCitation("""println("\r\nPhase 3")"""))
    }

    @Test
    fun findsAnIdAfterAnUnderscore() {
        assertEquals("G-26", outsideCitation("val x = \"see_G-26\""))
    }

    @Test
    fun ignoresAnIdInsideAWord() {
        assertNull(outsideCitation("val utf8 = UTF-8"))
        assertNull(outsideCitation("// A SHA-256 digest of each block."))
        assertNull(outsideCitation("// ISO-8601 dates; B-tree pages"))
    }

    @Test
    fun findsASectionOfAnythingButTheSpecOrAnRfc() {
        assertEquals("§3", outsideCitation("// As §3 of the plan says."))
        assertNull(outsideCitation("// As SPEC.md §3 says."))
        assertNull(outsideCitation("A Normalized Path (RFC 9535 §2.7)"))
    }
}
