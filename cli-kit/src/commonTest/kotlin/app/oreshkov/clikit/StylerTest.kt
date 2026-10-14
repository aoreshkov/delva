package app.oreshkov.clikit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Did we emit an escape sequence into a pipe" is a test, not a hope. Every assertion here is on
 * the exact byte stream.
 */
class StylerTest {

    @Test
    fun `a disabled styler emits the text and nothing else`() {
        assertEquals("ok", Styler.Plain.green("ok"))
        assertFalse(Styler.ESCAPE_CHAR in Styler.Plain.bold("header"))
    }

    @Test
    fun `an enabled styler wraps in SGR codes and always resets`() {
        assertEquals("\u001B[32mok\u001B[0m", Styler(enabled = true).green("ok"))
        assertTrue(Styler(enabled = true).bold("x").endsWith("\u001B[0m"))
    }

    @Test
    fun `combined styles share one escape`() {
        assertEquals("\u001B[1;31mbad\u001B[0m", Styler(enabled = true).style("bad", Style.Bold, Style.Red))
    }

    @Test
    fun `styling empty text produces empty text, not a bare escape`() {
        assertEquals("", Styler(enabled = true).green(""))
    }

    @Test
    fun `display width ignores escapes`() {
        assertEquals(5, Styler.displayWidth(Styler(enabled = true).green("alice")))
        assertEquals(5, Styler.displayWidth("alice"))
    }

    @Test
    fun `display width counts a surrogate pair as one character`() {
        // Not a claim about East Asian width — only that a two-`Char` code point is one column.
        assertEquals(1, Styler.displayWidth("😀"))
    }

    @Test
    fun `truncation shorter than the text adds an ellipsis and respects the limit`() {
        assertEquals("abc…", Styler.truncate("abcdefgh", 4))
        assertEquals(4, Styler.displayWidth(Styler.truncate("abcdefgh", 4)))
    }

    @Test
    fun `truncation leaves short text alone`() {
        assertEquals("abc", Styler.truncate("abc", 10))
        assertEquals("abc", Styler.truncate("abc", 3))
    }

    @Test
    fun `truncating styled text closes the sequence it cut`() {
        // A truncation that lands inside an open SGR leaves the terminal painting everything after
        // it green — including the next shell prompt.
        val styled = Styler(enabled = true).green("alice-in-wonderland")
        val cut = Styler.truncate(styled, 6)
        assertTrue(cut.endsWith("\u001B[0m"), cut)
        assertEquals(6, Styler.displayWidth(cut))
    }

    @Test
    fun `a zero or negative limit yields nothing`() {
        assertEquals("", Styler.truncate("abc", 0))
        assertEquals("", Styler.truncate("abc", -1))
    }

    @Test
    fun `a limit narrower than the ellipsis degrades rather than overflowing`() {
        assertEquals(1, Styler.displayWidth(Styler.truncate("abcdef", 1)))
    }
}
