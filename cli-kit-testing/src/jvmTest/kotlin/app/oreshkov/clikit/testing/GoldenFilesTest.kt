package app.oreshkov.clikit.testing

import app.oreshkov.clikit.CommandSpec
import app.oreshkov.clikit.Option
import app.oreshkov.clikit.Shell
import app.oreshkov.clikit.StandardOptions
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalPathApi::class)
class GoldenFilesTest {

    private val directory: Path = createTempDirectory("golden")

    /** A property of this test's own, so a run with the build's update switch on doesn't make every check pass. */
    private val update = "clikit.test.update.${System.identityHashCode(this)}"

    private val golden = GoldenFiles(directory, update)

    private val get = CommandSpec("get", "Read one entry", options = listOf(Option.Flag("--raw", help = "As stored")))

    private val root = CommandSpec(
        "demo",
        subcommands = listOf(CommandSpec("store", "The store", subcommands = listOf(get)), StandardOptions.completion),
    )

    private val surface = setOf(
        "help-root.txt", "help-store.txt", "help-store-get.txt", "help-completion.txt",
        "completion-bash.sh", "completion-zsh.sh", "completion-fish.sh", "completion-powershell.ps1",
    )

    @AfterTest
    fun cleanUp() {
        System.clearProperty(update)
        directory.deleteRecursively()
    }

    private fun write(block: () -> Unit) {
        System.setProperty(update, "true")
        try {
            block()
        } finally {
            System.clearProperty(update)
        }
    }

    private fun files(): Set<String> = directory.listDirectoryEntries().map { it.name }.toSet()

    @Test
    fun `assertSurface writes one file per help page and completion script, then compares against them`() {
        write { golden.assertSurface(root) }
        assertEquals(surface, files())
        assertContains(directory.resolve("help-store-get.txt").readText(), "--raw")

        golden.assertSurface(root)
    }

    @Test
    fun `a changed page fails, and every other changed page is reported with it`() {
        write { golden.assertSurface(root) }
        directory.resolve("help-store.txt").writeText("stale\n")
        directory.resolve("completion-fish.sh").writeText("stale\n")

        val failure = assertFailsWith<AssertionError> { golden.assertSurface(root) }
        assertContains(failure.message.orEmpty(), "help-store.txt")
        assertEquals(1, failure.suppressed.size)
        assertContains(failure.suppressed.single().message.orEmpty(), "completion-fish.sh")
    }

    @Test
    fun `a golden file nothing asserts is an orphan, unless the caller names it as its own`() {
        write { golden.assertSurface(root) }
        directory.resolve("help-store-put.txt").writeText("gone\n")

        val failure = assertFailsWith<AssertionError> { golden.assertSurface(root) }
        assertContains(failure.message.orEmpty(), "help-store-put.txt")

        golden.assertSurface(root, extra = setOf("help-store-put.txt"))
    }

    @Test
    fun `file names follow the path below the root, and the shell`() {
        assertEquals("help-root.txt", GoldenFiles.helpFileName(listOf(root)))
        assertEquals("help-store-get.txt", GoldenFiles.helpFileName(listOf(root, root.subcommands[0], get)))
        assertEquals("completion-powershell.ps1", GoldenFiles.completionFileName(Shell.PowerShell))
    }
}
