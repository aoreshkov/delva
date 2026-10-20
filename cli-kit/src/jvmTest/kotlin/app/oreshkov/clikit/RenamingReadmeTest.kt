package app.oreshkov.clikit

import app.oreshkov.clikit.testing.record
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The README's example of renaming and deprecating, which needs the JVM for `Converters.path`. */
class RenamingReadmeTest {

    @Test
    fun `the README's example`() {
        val store = Option.Single("--store", Converters.path, valueName = "dir", formerNames = listOf("--data-dir"))
        val pretty = Option.Flag("--pretty", help = "Indent the output", deprecated = "pipe --json to a formatter instead")
        val list = CommandSpec("list", "List entries", options = listOf(pretty), aliases = listOf("ls"))
        val dump = CommandSpec("dump", "Print every entry", deprecated = "use 'tool list' instead")
        val root = CommandSpec("tool", options = listOf(store), subcommands = listOf(list, dump))

        var seen: Path? = null
        val handlers = mapOf(
            list to CommandHandler { context -> seen = context.values[store]; ExitStatus.OK },
            dump to CommandHandler { ExitStatus.OK },
        )
        val result = Program(root, "tool 1.0", handlers).record("--data-dir", "/srv", "ls", "--pretty")
        assertEquals(ExitStatus.OK, result.status)
        assertEquals(Path.of("/srv"), seen)
        assertEquals(
            "warning: --data-dir is deprecated; use --store\nwarning: --pretty is deprecated: pipe --json to a formatter instead\n",
            result.stderr,
        )

        val help = HelpRenderer()
        assertContains(help.render(listOf(root)), "  list, ls  List entries\n  dump      Print every entry (deprecated)\n")
        assertContains(help.render(listOf(root, list)), "--pretty  Indent the output (deprecated)\n")
        assertContains(help.render(listOf(root, dump)), "Deprecated: use 'tool list' instead\n")
    }
}
