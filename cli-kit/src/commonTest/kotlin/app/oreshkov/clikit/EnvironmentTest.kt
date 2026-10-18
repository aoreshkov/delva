package app.oreshkov.clikit

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The Environment section of help, and the rules that keep each variable declared once. */
class EnvironmentTest {

    private val store = Option.Single("--store", Converters.string, "-s", valueName = "dir", env = "DEMO_STORE")
    private val home = EnvironmentVariable("DEMO_HOME", "Where the program is installed")

    private fun environmentSection(path: List<CommandSpec>, width: Int = 80): String? =
        HelpRenderer(Styler.Plain, null, width).render(path).substringAfter("Environment:\n", "").substringBefore("\n\n")
            .takeIf { it.isNotEmpty() }

    @Test
    fun `the section lists an option's variable as that option, and a declared one with its help`() {
        val root = CommandSpec("demo", options = listOf(store), environment = listOf(home))
        assertEquals(
            "  DEMO_STORE  Same as --store\n  DEMO_HOME   Where the program is installed\n",
            environmentSection(listOf(root)),
        )
        // The option's own tag stays: the section is for a reader looking up a variable, the tag for one reading options.
        assertContains(HelpRenderer().render(listOf(root)), "[env: DEMO_STORE]")
    }

    @Test
    fun `the section follows the options and comes before the notes`() {
        val left = Option.Flag("--left")
        val right = Option.Flag("--right")
        val command = CommandSpec(
            "pick",
            options = listOf(left, right),
            constraints = listOf(Constraint.Exclusive(left, right)),
            environment = listOf(home),
        )
        val page = HelpRenderer().render(listOf(command))
        assertContains(page, "      --right\n\nEnvironment:\n  DEMO_HOME  Where the program is installed\n\nNotes:\n")
    }

    @Test
    fun `a page without variables has no section`() {
        assertFalse("Environment:" in HelpRenderer().render(listOf(CommandSpec("plain", options = listOf(Option.Flag("--x"))))))
    }

    @Test
    fun `variables are inherited, the command's own first, and a redeclared one is listed as the deepest says`() {
        val editor = EnvironmentVariable("DEMO_EDITOR", "Editor for messages")
        val narrowed = EnvironmentVariable("DEMO_HOME", "Where this command's plugins are")
        val leaf = CommandSpec("edit", environment = listOf(narrowed, editor))
        val root = CommandSpec("demo", options = listOf(store), environment = listOf(home), subcommands = listOf(leaf))
        assertEquals(
            "  DEMO_HOME    Where this command's plugins are\n" +
                "  DEMO_EDITOR  Editor for messages\n" +
                "  DEMO_STORE   Same as --store\n",
            environmentSection(listOf(root, leaf)),
        )
    }

    @Test
    fun `a redeclared option's variable is the deepest declaration's, and a hidden option's variable is not listed`() {
        val narrowed = Option.Single("--store", Converters.string, env = "DEMO_LOCAL_STORE")
        val secret = Option.Single("--debug-port", Converters.positiveInt, env = "DEMO_DEBUG_PORT", hidden = true)
        val leaf = CommandSpec("local", options = listOf(narrowed, secret))
        val root = CommandSpec("demo", options = listOf(store), subcommands = listOf(leaf))
        assertEquals("  DEMO_LOCAL_STORE  Same as --store\n", environmentSection(listOf(root, leaf)))
    }

    @Test
    fun `a description wraps under its column`() {
        val long = EnvironmentVariable("DEMO_HOME", "Where the program is installed, which the launcher sets for it")
        assertEquals(
            "  DEMO_HOME  Where the program is installed,\n             which the launcher sets for it\n",
            environmentSection(listOf(CommandSpec("demo", environment = listOf(long))), width = 46),
        )
    }

    @Test
    fun `a variable's name follows the rule an option's does`() {
        assertFailsWith<IllegalArgumentException> { EnvironmentVariable("demo_home", "Lower case") }
        assertFailsWith<IllegalArgumentException> { EnvironmentVariable("1DEMO", "A leading digit") }
        assertFailsWith<IllegalArgumentException> { EnvironmentVariable("", "Empty") }
        assertEquals("DEMO_HOME", home.toString())
    }

    @Test
    fun `a command can't declare a variable twice, or one its own option reads`() {
        val twice = assertFailsWith<IllegalArgumentException> { CommandSpec("demo", environment = listOf(home, home)) }
        assertContains(twice.message.orEmpty(), "DEMO_HOME")
        val asOption = EnvironmentVariable("DEMO_STORE", "The store")
        val shadow = assertFailsWith<IllegalArgumentException> {
            CommandSpec("demo", options = listOf(store), environment = listOf(asOption))
        }
        assertContains(shadow.message.orEmpty(), "DEMO_STORE")
    }

    @Test
    fun `a program refuses a variable declared below an option that reads it`() {
        val leaf = CommandSpec("run", environment = listOf(EnvironmentVariable("DEMO_STORE", "The store")))
        val root = CommandSpec("demo", options = listOf(store), subcommands = listOf(leaf))
        val error = assertFailsWith<IllegalArgumentException> { Program(root, "demo 1.0", mapOf(leaf to CommandHandler { 0 })) }
        assertEquals("'demo run' declares the environment variables [DEMO_STORE], which its options already read", error.message)
    }

    @Test
    fun `--no-color and --width bring the variables the kit reads for them`() {
        val root = CommandSpec("demo", options = listOf(store, StandardOptions.noColor, StandardOptions.width), environment = listOf(home))
        assertEquals(
            "  DEMO_STORE      Same as --store\n" +
                "  NO_COLOR        Not empty: never emit colour, as --no-color does\n" +
                "  FORCE_COLOR     Not empty: emit colour even down a pipe; --no-color and\n" +
                "                  NO_COLOR win\n" +
                "  CLICOLOR_FORCE  1: the same as FORCE_COLOR\n" +
                "  COLUMNS         The default for --width\n" +
                "  DEMO_HOME       Where the program is installed\n",
            environmentSection(listOf(root)),
        )
        assertEquals(null, environmentSection(listOf(CommandSpec("demo", options = listOf(StandardOptions.json)))))
    }

    @Test
    fun `a program can't declare a variable the kit lists for a standard option`() {
        val noColor = EnvironmentVariable("NO_COLOR", "No colour")
        val same = assertFailsWith<IllegalArgumentException> {
            CommandSpec("demo", options = listOf(StandardOptions.noColor), environment = listOf(noColor))
        }
        assertContains(same.message.orEmpty(), "NO_COLOR")
        val leaf = CommandSpec("run", environment = listOf(EnvironmentVariable("COLUMNS", "Width")))
        val root = CommandSpec("demo", options = listOf(StandardOptions.width), subcommands = listOf(leaf))
        val below = assertFailsWith<IllegalArgumentException> { Program(root, "demo 1.0", mapOf(leaf to CommandHandler { 0 })) }
        assertEquals("'demo run' declares the environment variables [COLUMNS], which its options already read", below.message)
    }

    @Test
    fun `two options may still read one variable`() {
        val other = Option.Single("--archive", Converters.string, env = "DEMO_STORE")
        val command = CommandSpec("demo", options = listOf(store, other))
        assertEquals("  DEMO_STORE  Same as --store\n", environmentSection(listOf(command)))
    }
}
