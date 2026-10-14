package app.oreshkov.clikit

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The help layout, over the fixture tree. A program's own pages are golden-file tested where its
 * tree lives; these pin the layout itself, so a renderer change shows up here first.
 */
class HelpRendererTest {

    private val help = HelpRenderer(Styler.Plain)

    private fun pathTo(vararg names: String): List<CommandSpec> = TestCommands.root.pathTo(*names)

    @Test
    fun `root help`() {
        Golden.assertMatches("help-root.txt", help.render(pathTo()))
    }

    @Test
    fun `a leaf with a repeated option, a positional and inherited globals`() {
        Golden.assertMatches("help-request-get.txt", help.render(pathTo("request", "get")))
    }

    @Test
    fun `help lists a command's own options separately from the inherited globals`() {
        val rendered = help.render(pathTo("session", "login"))
        assertContains(rendered, "Options:")
        assertContains(rendered, "Global options:")
        // `--force` belongs to this command; `--json` is inherited. Merging them makes the page
        // read as though `session login` defines `--json`, and then somebody moves it.
        val ownSection = rendered.substringAfter("Options:").substringBefore("Global options:")
        assertContains(ownSection, "--force")
        assertFalse("--json" in ownSection)
    }

    @Test
    fun `the program name is the root's unless one is given`() {
        assertContains(help.render(pathTo("store", "prune")), "demo store prune")
        assertContains(HelpRenderer(programName = "other").render(pathTo("store", "prune")), "other store prune")
        assertEquals("usage: demo store get [options] <key>", help.usageLine(pathTo("store", "get")))
    }

    @Test
    fun `help never emits an escape sequence when colour is off`() {
        assertFalse(Styler.hasEscapes(help.render(pathTo())))
    }

    @Test
    fun `help does emit escapes when colour is on`() {
        assertTrue(Styler.hasEscapes(HelpRenderer(Styler(enabled = true)).render(pathTo())))
    }

    @Test
    fun `every golden file on disk is asserted by some test`() {
        // Catches the leftover: a test renamed, its golden file orphaned, and nothing failing.
        val asserted = setOf(
            "help-root.txt",
            "help-request-get.txt",
            "completion-bash.sh",
            "completion-zsh.sh",
            "completion-fish.sh",
            "completion-powershell.ps1",
        )
        Golden.assertNoOrphans(asserted)
    }
}
