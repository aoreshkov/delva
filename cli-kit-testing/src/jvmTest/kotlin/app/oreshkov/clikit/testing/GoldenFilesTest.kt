package app.oreshkov.clikit.testing

import app.oreshkov.clikit.CommandHandler
import app.oreshkov.clikit.CommandSpec
import app.oreshkov.clikit.Converters
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.ExperimentalCliKitApi
import app.oreshkov.clikit.Option
import app.oreshkov.clikit.Program
import app.oreshkov.clikit.Setting
import app.oreshkov.clikit.Settings
import app.oreshkov.clikit.Shell
import app.oreshkov.clikit.StandardOptions
import app.oreshkov.clikit.withArgumentFiles
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
    @OptIn(ExperimentalCliKitApi::class)
    fun `with the program's settings, the pages show the configuration it reads`() {
        val settings = object : Settings {
            override val locations: List<String> = listOf("the user file")

            override fun read(): List<Setting> = emptyList()
        }
        val key = Option.Single("--store", Converters.string, config = "store")
        val tree = CommandSpec("demo", options = listOf(key))
        write { golden.assertSurface(tree, settings = settings) }
        assertContains(directory.resolve("help-root.txt").readText(), "Configuration:\n  store  Same as --store\n")

        golden.assertSurface(tree, settings = settings)
        assertFailsWith<AssertionError> { golden.assertSurface(tree) }
    }

    private val program = Program(root, "demo 1.0", mapOf(get to CommandHandler { ExitStatus.OK }))

    @Test
    fun `a program prints the pages its tree renders, so both forms read the same files`() {
        write { golden.assertSurface(program) }
        assertEquals(surface, files())

        golden.assertSurface(root)
        golden.assertSurface(program)
    }

    @Test
    @OptIn(ExperimentalCliKitApi::class)
    fun `a program's files show that it has argument files on, which its tree can't say`() {
        val withFiles = program.withArgumentFiles()
        write { golden.assertSurface(withFiles) }
        val footer = "Start with @file to read arguments from it, one per line; @@ for a literal @."
        assertContains(directory.resolve("help-store-get.txt").readText(), footer)
        assertContains(directory.resolve("completion-bash.sh").readText(), "-*|@*)")

        golden.assertSurface(withFiles)
        assertFailsWith<AssertionError> { golden.assertSurface(program) }
        assertFailsWith<AssertionError> { golden.assertSurface(root) }
    }

    @Test
    @OptIn(ExperimentalCliKitApi::class)
    fun `a program's files show the configuration it reads, with nothing more to pass`() {
        val settings = object : Settings {
            override val locations: List<String> = listOf("the user file")

            override fun read(): List<Setting> = emptyList()
        }
        val key = Option.Single("--store", Converters.string, config = "store")
        val tree = CommandSpec("demo", options = listOf(key), subcommands = listOf(get, StandardOptions.completion))
        val reading = Program(tree, "demo 1.0", mapOf(get to CommandHandler { ExitStatus.OK }), settings = settings)
        write { golden.assertSurface(reading) }
        assertContains(directory.resolve("help-root.txt").readText(), "Configuration:\n  store  Same as --store\n")

        golden.assertSurface(tree, settings = settings, extra = emptySet())
    }

    @Test
    fun `a program without the completion command has no script to pin, and one left on disk is an orphan`() {
        val tree = CommandSpec("demo", subcommands = listOf(get))
        val plain = Program(tree, "demo 1.0", mapOf(get to CommandHandler { ExitStatus.OK }))
        write { golden.assertSurface(plain) }
        assertEquals(setOf("help-root.txt", "help-get.txt"), files())

        directory.resolve("completion-bash.sh").writeText("gone\n")
        val failure = assertFailsWith<AssertionError> { golden.assertSurface(plain) }
        assertContains(failure.message.orEmpty(), "completion-bash.sh")
    }

    @Test
    fun `a page the program won't print fails, naming its command line, and the others are reported with it`() {
        // A name that reads as an option: the tree can hold the command, but no command line can select it.
        val untypable = listOf(CommandSpec("-x"), CommandSpec("-y"))
        val tree = CommandSpec("demo", subcommands = untypable)
        val refusing = Program(tree, "demo 1.0", untypable.associateWith { CommandHandler { ExitStatus.OK } })

        val failure = assertFailsWith<AssertionError> { write { golden.assertSurface(refusing) } }
        assertContains(failure.message.orEmpty(), "'demo -x --help' exited ${ExitStatus.USAGE}")
        assertEquals(1, failure.suppressed.size)
        assertContains(failure.suppressed.single().message.orEmpty(), "'demo -y --help'")
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
