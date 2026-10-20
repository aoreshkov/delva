package app.oreshkov.clikit

import app.oreshkov.clikit.testing.record
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Aliases, former names and deprecation: how they parse, what they warn, what help shows, and the rules on them. */
class RenamingTest {

    private val parser = Parser(TestCommands.root)

    private fun invocation(vararg args: String): ParseResult.Invocation {
        val result = parser.parse(args.toList())
        assertIs<ParseResult.Invocation>(result, "expected an invocation, got $result")
        return result
    }

    private fun usageError(vararg args: String): ParseResult.UsageError {
        val result = parser.parse(args.toList())
        assertIs<ParseResult.UsageError>(result, "expected a usage error, got $result")
        return result
    }

    // ------------------------------------------------------------------------------------ commands

    @Test
    fun `an alias selects its command, with no warning`() {
        val parsed = invocation("req", "raw", "--method", "GET", "--url", "u")
        assertEquals("demo request raw", parsed.commandLine())
        assertEquals(emptyList(), parsed.notices)
    }

    @Test
    fun `a former name selects its command, with a warning that names the new one`() {
        val parsed = invocation("store", "search", "\$.a")
        assertSame(TestCommands.query, parsed.command)
        assertEquals(listOf("\$.a"), parsed.values[TestCommands.paths])
        assertEquals(listOf("'demo store search' is deprecated; use 'demo store query'"), parsed.notices)
    }

    @Test
    fun `a deprecated command still runs, with a warning that says what to do instead`() {
        assertEquals(
            listOf("'demo store purge' is deprecated: use 'demo store prune' instead"),
            invocation("store", "purge").notices,
        )
    }

    @Test
    fun `the help command reads aliases and former names, and warns about neither`() {
        assertEquals(ParseResult.Help(TestCommands.root.pathTo("request", "raw")), parser.parse(listOf("help", "req", "raw")))
        assertEquals(ParseResult.Help(TestCommands.root.pathTo("store", "query")), parser.parse(listOf("help", "store", "search")))
        assertEquals(ParseResult.Help(TestCommands.root.pathTo("store", "query")), parser.parse(listOf("store", "search", "--help")))
    }

    @Test
    fun `a suggestion offers names and aliases, never a former name or a deprecated command`() {
        assertEquals("req", usageError("rq").suggestion)
        // `search` and `purge` are one edit away, and still not offered.
        assertNull(usageError("store", "serch").suggestion)
        assertNull(usageError("store", "purg").suggestion)
        assertEquals("unknown subcommand 'purg'", usageError("help", "store", "purg").message)
    }

    // ------------------------------------------------------------------------------------- options

    @Test
    fun `a former option name takes a value either way, with one warning however often it's typed`() {
        for (args in listOf(listOf("--max", "5"), listOf("--max=5"), listOf("--max", "4", "--max=5"))) {
            val parsed = invocation("session", "list", *args.toTypedArray())
            assertEquals(5, parsed.values[TestCommands.limit], "$args")
            assertEquals(listOf("--max is deprecated; use --limit"), parsed.notices, "$args")
        }
        assertEquals(emptyList(), invocation("session", "list", "-n", "5", "--limit", "5").notices)
    }

    @Test
    fun `a bad value given by a former name is reported under the name typed`() {
        assertContains(usageError("session", "list", "--max", "0").message, "invalid value for --max:")
        assertContains(usageError("session", "list", "--limit", "0").message, "invalid value for --limit:")
    }

    @Test
    fun `a deprecated option is still read, with a warning that says what to do instead`() {
        val parsed = invocation("request", "get", "-a", "alice", "--pretty", "game.startup")
        assertTrue(parsed.values[TestCommands.pretty])
        assertEquals(listOf("--pretty is deprecated: pipe --json to a formatter instead"), parsed.notices)
    }

    @Test
    fun `a suggestion never offers a former option name or a deprecated option`() {
        assertNull(usageError("session", "list", "--maxx", "5").suggestion)
        assertNull(usageError("request", "get", "--prety", "e").suggestion)
    }

    /** A tree of its own, to put every kind of warning on one command line. */
    private val folder = Option.Single(
        "--dir",
        Converters.string.unsetWhen({ it.startsWith("\${") }, notice = { "ignoring '$it'" }),
        formerNames = listOf("--folder"),
    )
    private val old = Option.Flag("--old", "-o", deprecated = "it does nothing now")
    private val level = Option.Single("--level", Converters.positiveInt, env = "DEMO_LEVEL", deprecated = "use --verbose")
    private val run = CommandSpec("run", formerNames = listOf("go"))
    private val warned = CommandSpec("demo", options = listOf(folder, old, level), subcommands = listOf(run))

    @Test
    fun `warnings come in order, the commands' first, and a deprecated option's variable is warned about too`() {
        val parsed = Parser(warned, mapOf("DEMO_LEVEL" to "3")::get).parse(listOf("-o", "go", "--folder", "\${x}", "--old"))
        assertIs<ParseResult.Invocation>(parsed)
        assertEquals(3, parsed.values[level])
        assertEquals(
            listOf(
                "'demo go' is deprecated; use 'demo run'",
                "-o is deprecated: it does nothing now",
                "--folder is deprecated; use --dir",
                "--folder: ignoring '\${x}'",
                "--old is deprecated: it does nothing now",
                "DEMO_LEVEL: --level is deprecated: use --verbose",
            ),
            parsed.notices,
        )
    }

    @Test
    fun `a value after a former option name is not read as a command`() {
        val parsed = Parser(warned).parse(listOf("--folder", "run", "run"))
        assertIs<ParseResult.Invocation>(parsed)
        assertEquals("demo run", parsed.commandLine())
        assertEquals("run", parsed.values[folder])
    }

    @Test
    fun `a command that narrows an option reads the former names it declares, and only those`() {
        val store = Option.Single("--store", Converters.string, formerNames = listOf("--data-dir"))
        val narrow = Option.Single("--store", Converters.string, formerNames = listOf("--data-dir"), help = "Store to read")
        val renamed = Option.Single("--store", Converters.string, help = "Store to write")
        val get = CommandSpec("get", options = listOf(narrow))
        val put = CommandSpec("put", options = listOf(renamed))
        val parser = Parser(CommandSpec("demo", options = listOf(store), subcommands = listOf(get, put)))

        val parsed = parser.parse(listOf("get", "--data-dir", "x"))
        assertIs<ParseResult.Invocation>(parsed)
        assertEquals("x", parsed.values[narrow])
        assertNull(parsed.values[store])
        assertEquals(listOf("--data-dir is deprecated; use --store"), parsed.notices)

        val refused = parser.parse(listOf("put", "--data-dir", "x"))
        assertIs<ParseResult.UsageError>(refused)
        assertEquals("unknown option '--data-dir'", refused.message)
    }

    // ---------------------------------------------------------------------------------------- help

    @Test
    fun `help lists aliases beside the name, tags what is deprecated, and never shows a former name`() {
        val help = HelpRenderer()
        val root = help.render(TestCommands.root.pathTo())
        assertContains(root, "\n  request, req  Requests\n")
        val store = help.render(TestCommands.root.pathTo("store"))
        assertContains(store, "\n  purge  Delete every entry (deprecated)\n         use 'demo store prune' instead\n")
        val get = help.render(TestCommands.root.pathTo("request", "get"))
        assertContains(get, "      --pretty          Indent the output (deprecated)\n                        pipe --json to a formatter instead\n")
        val pages = TestCommands.root.paths().joinToString("\n") { help.render(it) }
        assertTrue("search" !in pages && "--max" !in pages, pages)
    }

    @Test
    fun `a deprecated command's page starts by saying so`() {
        assertTrue(
            HelpRenderer().render(TestCommands.root.pathTo("store", "purge"))
                .startsWith("Deprecated: use 'demo store prune' instead\n\nDelete every entry\n\nUsage:\n"),
        )
    }

    @Test
    fun `a long deprecation message wraps under its entry`() {
        val legacy = Option.Flag("--legacy", help = "Old output", deprecated = "the new output is the same, only faster, so drop it")
        val page = HelpRenderer(Styler.Plain, null, 40).render(listOf(CommandSpec("demo", options = listOf(legacy))))
        assertContains(
            page,
            "      --legacy  Old output (deprecated)\n                the new output is the\n                same, only faster, so\n                drop it\n",
        )
    }

    // --------------------------------------------------------------------------------------- Program

    @Test
    fun `the warnings go to stderr before the command runs, and --quiet silences them`() {
        val serve = CommandSpec("serve", formerNames = listOf("start"))
        val root = CommandSpec("demo", options = listOf(old, StandardOptions.quiet), subcommands = listOf(serve))
        val program = Program(root, "demo 1.0", mapOf(serve to CommandHandler { context -> context.io.errLine("ran"); 0 }))

        val warned = program.record("start", "--old")
        assertEquals(ExitStatus.OK, warned.status)
        assertEquals(
            "warning: 'demo start' is deprecated; use 'demo serve'\nwarning: --old is deprecated: it does nothing now\nran\n",
            warned.stderr,
        )
        assertEquals("", warned.stdout)
        assertEquals("ran\n", program.record("start", "--old", "--quiet").stderr)
    }

    // ----------------------------------------------------------------------------------------- rules

    @Test
    fun `an option's former names are long names, and no spelling is given twice`() {
        assertFailsWith<IllegalArgumentException> { Option.Flag("--new", formerNames = listOf("-o")) }
        assertFailsWith<IllegalArgumentException> { Option.Flag("--new", formerNames = listOf("old")) }
        assertFailsWith<IllegalArgumentException> { Option.Flag("--new", formerNames = listOf("--new")) }
        assertFailsWith<IllegalArgumentException> { Option.Flag("--new", formerNames = listOf("--old", "--old")) }
    }

    @Test
    fun `a deprecation says what to do instead, and a required option can't be deprecated`() {
        assertFailsWith<IllegalArgumentException> { Option.Flag("--x", deprecated = " ") }
        assertFailsWith<IllegalArgumentException> { CommandSpec("x", deprecated = "") }
        assertFailsWith<IllegalArgumentException> { Option.Single("--x", Converters.string, required = true, deprecated = "no") }
        assertFailsWith<IllegalArgumentException> { Option.Repeated("--x", Converters.string, required = true, deprecated = "no") }
    }

    @Test
    fun `a command's aliases and former names are words, and none is given twice`() {
        assertFailsWith<IllegalArgumentException> { CommandSpec("list", aliases = listOf(" ")) }
        assertFailsWith<IllegalArgumentException> { CommandSpec("list", aliases = listOf("list")) }
        assertFailsWith<IllegalArgumentException> { CommandSpec("list", aliases = listOf("ls"), formerNames = listOf("ls")) }
    }

    @Test
    fun `no word selects two subcommands, and no spelling names two options`() {
        val error = assertFailsWith<IllegalArgumentException> {
            CommandSpec("demo", subcommands = listOf(CommandSpec("list"), CommandSpec("list")))
        }
        assertEquals("command 'demo' has more than one subcommand called [list]", error.message)
        assertFailsWith<IllegalArgumentException> {
            CommandSpec("demo", subcommands = listOf(CommandSpec("list", aliases = listOf("ls")), CommandSpec("ls")))
        }
        assertFailsWith<IllegalArgumentException> {
            CommandSpec("demo", subcommands = listOf(CommandSpec("a", formerNames = listOf("b")), CommandSpec("c", aliases = listOf("b"))))
        }
        assertFailsWith<IllegalArgumentException> {
            CommandSpec("demo", options = listOf(Option.Flag("--new", formerNames = listOf("--old")), Option.Flag("--old")))
        }
    }

    @Test
    fun `the root is the program's own name, so a program refuses one with an alias, a former name or a deprecation`() {
        for (root in listOf(CommandSpec("demo", aliases = listOf("d")), CommandSpec("demo", formerNames = listOf("dmo")), CommandSpec("demo", deprecated = "no"))) {
            val error = assertFailsWith<IllegalArgumentException> { Program(root, "demo 1.0", mapOf(root to CommandHandler { 0 })) }
            assertContains(error.message.orEmpty(), "the root command 'demo'")
        }
    }

    @Test
    fun `the standard declarations have no other names, so reading them off the raw command line stays right`() {
        val options = listOf(
            StandardOptions.json, StandardOptions.verbose, StandardOptions.noColor, StandardOptions.quiet, StandardOptions.force,
            StandardOptions.dryRun, StandardOptions.noInput, StandardOptions.width,
        )
        assertTrue(options.all { it.formerNames.isEmpty() && it.deprecated == null })
        val commands = listOf(StandardOptions.completion, StandardOptions.help)
        assertTrue(commands.all { it.aliases.isEmpty() && it.formerNames.isEmpty() && it.deprecated == null })
    }
}
