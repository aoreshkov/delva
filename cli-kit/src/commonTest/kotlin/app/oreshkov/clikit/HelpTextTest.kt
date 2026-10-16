package app.oreshkov.clikit

import kotlin.test.Test
import kotlin.test.assertEquals

class HelpTextTest {

    @Test
    fun `words fill a line up to the width, and the next one starts a new line`() {
        assertEquals(listOf("one two", "three"), HelpText.wrap("one two three", 7))
        assertEquals(listOf("one two three"), HelpText.wrap("one two three", 13))
    }

    @Test
    fun `any run of spaces or tabs is one place to break`() {
        assertEquals(listOf("one two", "three"), HelpText.wrap("  one \t two   three  ", 9))
    }

    @Test
    fun `a blank line starts a paragraph, however many there are, and none is kept at either end`() {
        assertEquals(listOf("one", "", "two"), HelpText.wrap("\n\none\n\n  \n\ntwo\n\n", 80))
    }

    @Test
    fun `a single line break is kept`() {
        assertEquals(listOf("one", "two three"), HelpText.wrap("one\ntwo three", 80))
        assertEquals(listOf("one", "two"), HelpText.wrap("one\r\ntwo", 80))
    }

    @Test
    fun `a word wider than the width overflows its own line rather than being split`() {
        val path = "/var/lib/delva/stores/orders"
        assertEquals(listOf("in", path, "or here"), HelpText.wrap("in $path or here", 10))
    }

    @Test
    fun `styled words are measured by what they show`() {
        val bold = Styler(enabled = true).bold("bold")
        assertEquals(listOf("a $bold b", "c"), HelpText.wrap("a $bold b c", 8))
    }

    @Test
    fun `a tail follows the last word, each piece kept whole`() {
        assertEquals(
            listOf("one", "", "two (a|b)", "[default: a]"),
            HelpText.wrap("one\n\ntwo\n\n", 12, listOf("(a|b)", "[default: a]")),
        )
        assertEquals(listOf("[default: a]"), HelpText.wrap("", 5, listOf("[default: a]")))
    }

    @Test
    fun `the summary is the first line that has a word, its whitespace made single spaces`() {
        assertEquals("Copy the files", HelpText.summary("\n  Copy\tthe   files\nand their dates\n\nMore."))
        assertEquals("", HelpText.summary(" \n "))
    }
}
