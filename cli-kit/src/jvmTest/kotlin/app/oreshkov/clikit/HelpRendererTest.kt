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
    fun `the help command, a variadic argument that may be left out`() {
        Golden.assertMatches("help-help.txt", help.render(pathTo("help")))
    }

    @Test
    fun `a group with a deprecated command and one with a former name`() {
        Golden.assertMatches("help-store.txt", help.render(pathTo("store")))
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

    private val copy = CommandSpec(
        "copy",
        help = "Copies files from one place to another, keeping their dates.\n\nA second paragraph.",
        options = listOf(
            Option.Flag("--all", "-a", help = "Copy hidden files as well, which are left out otherwise"),
            Option.Single(
                "--configuration-directory",
                Converters.string,
                valueName = "dir",
                help = "Where settings are read from",
            ),
        ),
    )

    @Test
    fun `prose wraps to the width, and a table's descriptions wrap under their own column`() {
        val expected = """
            |Copies files from one place to another,
            |keeping their dates.
            |
            |A second paragraph.
            |
            |Usage:
            |  copy [options]
            |
            |Options:
            |  -a, --all  Copy hidden files as well,
            |             which are left out
            |             otherwise
            |      --configuration-directory <dir>
            |             Where settings are read
            |             from
            |
        """.trimMargin()
        assertEquals(expected, HelpRenderer(Styler.Plain, null, 40).render(listOf(copy)))
    }

    @Test
    fun `a renderer built without a width wraps to 80 columns`() {
        val long = CommandSpec("long", help = List(30) { "word" }.joinToString(" "))
        val lines = HelpRenderer().render(listOf(long)).lines()
        assertEquals(listOf(79, 69), lines.take(2).map { it.length })
    }

    @Test
    fun `a paragraph break in a description is an empty line, and a line break is kept`() {
        val option = Option.Flag("--fast", help = "Skips the checks.\n\nUse it only for a store you trust.\nOr a copy.")
        val page = HelpRenderer().render(listOf(CommandSpec("check", options = listOf(option))))
        assertContains(
            page,
            "      --fast  Skips the checks.\n\n              Use it only for a store you trust.\n              Or a copy.\n",
        )
    }

    @Test
    fun `a list of commands shows each one's first line, and its own page the rest`() {
        val child = CommandSpec("child", help = "Does one thing.\nAnd here is how, at length.")
        val parent = CommandSpec("parent", subcommands = listOf(child))
        assertContains(help.render(listOf(parent)), "Commands:\n  child  Does one thing.\n\n")
        assertContains(help.render(listOf(parent, child)), "Does one thing.\nAnd here is how, at length.\n")
    }

    @Test
    fun `an example's description wraps into comment lines, and its command line never wraps`() {
        val commandLine = "demo copy --all " + "very-long-file-name ".repeat(4).trim()
        val example = Example(commandLine, "Copies every file under the current directory, hidden ones too")
        val page = HelpRenderer(Styler.Plain, null, 40).render(listOf(CommandSpec("demo", examples = listOf(example))))
        assertContains(
            page,
            "Examples:\n  # Copies every file under the current\n  # directory, hidden ones too\n  $commandLine\n",
        )
    }

    @Test
    fun `a note that wraps has a hanging indent`() {
        val left = Option.Flag("--left-hand-side")
        val right = Option.Flag("--right-hand-side")
        val command = CommandSpec(
            "pick",
            options = listOf(left, right),
            constraints = listOf(Constraint.Exclusive(left, right)),
        )
        val notes = HelpRenderer(Styler.Plain, null, 30).render(listOf(command)).substringAfter("Notes:\n")
        assertEquals("  --left-hand-side and\n    --right-hand-side cannot\n    be combined\n", notes)
    }

    @Test
    fun `at 20 columns every line fits, except a command line, a table entry, a single word or a tag`() {
        val path = pathTo("request", "get")
        val entries = optionsFor(path).map { it.render().trim() } + path.last().arguments.map { it.name }
        val unit = Regex("""\S+|\[[^]]*]|\([^)]*\)""")
        HelpRenderer(Styler.Plain, null, 20).render(path).lines().filter { it.length > 20 }.forEach { line ->
            val text = line.trim()
            // An entry wider than its column stands alone; a description word too wide for its column may follow one.
            val rest = entries.firstOrNull { text.startsWith(it) }?.let { text.removePrefix(it).trim() } ?: text
            assertTrue(rest.isEmpty() || unit.matches(rest) || line.startsWith("  demo "), "'$line' is too wide")
        }
    }

    @Test
    fun `a tag such as a default is never split across lines`() {
        val option = Option.Single("--order", Converters.string, help = "Oldest first, or newest", defaultDisplay = "asc")
        val page = HelpRenderer(Styler.Plain, null, 60).render(listOf(CommandSpec("list", options = listOf(option))))
        // `[default:` would still fit after "newest", and `asc]` would start the next line.
        assertContains(page, "      --order <value>  Oldest first, or newest\n${" ".repeat(23)}[default: asc]\n")
    }

    @Test
    fun `a styled page wraps where the plain one does`() {
        val plain = HelpRenderer(Styler.Plain, null, 40).render(listOf(copy))
        val styled = HelpRenderer(Styler(enabled = true), null, 40).render(listOf(copy))
        assertTrue(Styler.hasEscapes(styled))
        assertEquals(plain, styled.replace(Regex(Char(27) + "\\[[0-9;]*m"), ""))
    }

    @Test
    fun `every golden file on disk is asserted by some test`() {
        // Catches the leftover: a test renamed, its golden file orphaned, and nothing failing.
        val asserted = setOf(
            "help-root.txt",
            "help-request-get.txt",
            "help-help.txt",
            "help-store.txt",
            "completion-bash.sh",
            "completion-zsh.sh",
            "completion-fish.sh",
            "completion-powershell.ps1",
        )
        Golden.assertNoOrphans(asserted)
    }
}
