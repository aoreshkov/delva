package app.oreshkov.clikit

import kotlin.test.Test
import kotlin.test.assertContains
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
    fun `the program name is the root's unless one is given`() {
        Shell.entries.forEach { shell ->
            assertContains(generator.generate(shell, TestCommands.root), "demo")
            val renamed = CompletionGenerator("other").generate(shell, TestCommands.root)
            assertTrue("other" in renamed && "demo" !in renamed, "${shell.id} ignores the given name")
        }
    }
}
