package app.oreshkov.clikit

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The four generators over the fixture tree: their shape pinned, and their coverage asserted. */
class CompletionGeneratorTest {

    private val generator = CompletionGenerator()

    @Test
    fun `bash`() {
        Golden.assertMatches("completion-bash.sh", generator.generate(Shell.Bash, TestCommands.root))
    }

    @Test
    fun `zsh`() {
        Golden.assertMatches("completion-zsh.sh", generator.generate(Shell.Zsh, TestCommands.root))
    }

    @Test
    fun `fish`() {
        Golden.assertMatches("completion-fish.sh", generator.generate(Shell.Fish, TestCommands.root))
    }

    @Test
    fun `powershell`() {
        Golden.assertMatches("completion-powershell.ps1", generator.generate(Shell.PowerShell, TestCommands.root))
    }

    @Test
    fun `every generated script covers the whole tree`() {
        // The property that matters is coverage, not shape: a generator that silently stopped at
        // the second level would still produce a plausible script and a passing golden file.
        val names = buildList {
            fun walk(command: CommandSpec) {
                add(command.name)
                command.subcommands.forEach(::walk)
            }
            TestCommands.root.subcommands.forEach(::walk)
        }
        Shell.entries.forEach { shell ->
            val script = generator.generate(shell, TestCommands.root)
            names.forEach { name ->
                assertContains(script, name, message = "${shell.id} script omits '$name'")
            }
        }
    }

    @Test
    fun `a help of several lines gives the scripts its first line alone gives`() {
        // A zsh description or a fish `-d` can't hold a line break, so the scripts take the summary.
        fun tree(help: String) = CommandSpec(
            "demo",
            options = listOf(Option.Flag("--fast", help = help)),
            subcommands = listOf(CommandSpec("run", help = help), CommandSpec("stop")),
        )
        val long = "Runs it now.\nThen waits for it to finish.\n\nA paragraph that says more."
        Shell.entries.forEach { shell ->
            assertEquals(generator.generate(shell, tree("Runs it now.")), generator.generate(shell, tree(long)), shell.id)
        }
    }

    @Test
    fun `aliases at two levels add ways to type a place, not lines for each`() {
        fun tree(aliased: Boolean): CommandSpec {
            fun others(vararg names: String) = if (aliased) names.toList() else emptyList()
            val list = CommandSpec("list", "List entries", aliases = others("ls"), formerNames = others("l"))
            val store = CommandSpec("store", "The store", subcommands = listOf(list), aliases = others("st", "s"))
            // An option, so that every place has something to offer, and fish a line for it.
            return CommandSpec("demo", options = listOf(Option.Flag("--fast")), subcommands = listOf(store))
        }
        Shell.entries.forEach { shell ->
            val plain = generator.generate(shell, tree(aliased = false)).lines().size
            val aliased = generator.generate(shell, tree(aliased = true))
            // PowerShell's switch takes one entry per way to type a place: two more for `store`, eight for `store list`.
            val entries = if (shell == Shell.PowerShell) 2 + 8 else 0
            assertEquals(plain + entries, aliased.lines().size, shell.id)
            assertContains(aliased, "s l", message = "${shell.id} misses the last combination")
        }
    }

    @Test
    fun `the program name is the root's unless one is given`() {
        Shell.entries.forEach { shell ->
            assertContains(generator.generate(shell, TestCommands.root), "demo")
            val renamed = CompletionGenerator("other").generate(shell, TestCommands.root)
            assertTrue("other" in renamed && "demo" !in renamed, "${shell.id} ignores the given name")
        }
    }
}
