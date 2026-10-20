package app.oreshkov.clikit

import app.oreshkov.clikit.testing.record
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Values from settings: the order of precedence, when they are read, what their messages say, the format of a file,
 * and what help and `Program` make of them. The files themselves, and where they are, are `ConfigurationFilesTest`'s.
 */
class SettingsTest {

    /** Settings held in memory, highest first, counting how often they are read; [failure] makes every read fail. */
    private class Held(vararg settings: Setting, private val failure: String? = null) : Settings {
        private val settings = settings.toList()
        var reads = 0
            private set

        override val locations: List<String> = listOf("the project file", "the user file")

        override fun read(): List<Setting> {
            reads++
            failure?.let { throw SettingsException(it) }
            return settings
        }
    }

    private val unfilled = Converters.string.unsetWhen({ it.startsWith("\${") }, notice = { "ignoring '$it': an unfilled template" })
    private val store = Option.Single("--store", unfilled, "-s", valueName = "dir", env = "DEMO_STORE", config = "store")
    private val limit = Option.Single("--limit", Converters.positiveInt, valueName = "n", config = "limit")
    private val from = Option.Single("--from", Converters.string, valueName = "file")
    private val indent = Option.Single("--indent", Converters.positiveInt, config = "indent", deprecated = "pipe --json to a formatter")
    private val user = Option.Single("--user", unfilled, required = true, config = "user")

    private val list = CommandSpec(
        "list",
        "List entries",
        options = listOf(limit, from, indent),
        constraints = listOf(Constraint.Exclusive(limit, from)),
    )
    private val login = CommandSpec("login", "Log in", options = listOf(user))
    private val root = CommandSpec(
        "demo",
        options = listOf(store, StandardOptions.quiet),
        subcommands = listOf(list, login, StandardOptions.completion, StandardOptions.help),
    )

    private fun parse(settings: Settings, vararg args: String, env: Map<String, String> = emptyMap()): ParseResult =
        Parser(root, env::get, settings).parse(args.toList())

    private fun invocation(settings: Settings, vararg args: String, env: Map<String, String> = emptyMap()): ParseResult.Invocation {
        val result = parse(settings, *args, env = env)
        assertIs<ParseResult.Invocation>(result, "expected an invocation, got $result")
        return result
    }

    private fun program(settings: Settings, env: Map<String, String> = emptyMap()) =
        Program(root, "demo 1.0", mapOf(list to CommandHandler { 0 }, login to CommandHandler { 0 }), environment = env::get, settings = settings)

    // ------------------------------------------------------------------------------------ precedence

    @Test
    fun `the command line wins, then the variable, then the settings, highest first`() {
        val settings = Held(Setting("store", "/project", "p:1"), Setting("store", "/user", "u:1"))
        assertEquals("/typed", invocation(settings, "--store", "/typed", "list").values[store])
        assertEquals("/env", invocation(settings, "list", env = mapOf("DEMO_STORE" to "/env")).values[store])
        assertEquals("/project", invocation(settings, "list").values[store])
        assertEquals("/user", invocation(Held(Setting("store", "/user", "u:1")), "list").values[store])
        assertEquals(null, invocation(Held(), "list").values[store])
    }

    @Test
    fun `a value the converter calls unset moves on to the next layer, with its notice`() {
        val settings = Held(Setting("store", "\${store}", "p:1"), Setting("store", "/user", "u:2"))
        val parsed = invocation(settings, "list")
        assertEquals("/user", parsed.values[store])
        assertEquals(listOf("p:1: ignoring '\${store}': an unfilled template"), parsed.notices)
        // An unset value on the command line or in the variable falls through to the settings the same way.
        assertEquals("/user", invocation(settings, "--store", "\${typed}", "list").values[store])
        assertEquals("/user", invocation(settings, "list", env = mapOf("DEMO_STORE" to "\${env}")).values[store])
    }

    @Test
    fun `an empty value counts as not set, as an empty variable does`() {
        val parsed = invocation(Held(Setting("store", "", "p:1"), Setting("store", "/user", "u:1")), "list")
        assertEquals("/user", parsed.values[store])
        assertEquals(emptyList(), parsed.notices)
    }

    @Test
    fun `a required option can be given by a setting`() {
        assertEquals("ana", invocation(Held(Setting("user", "ana", "u:1")), "login").values.required(user))
        val missing = parse(Held(Setting("user", "\${user}", "u:4")), "login")
        assertEquals(
            ParseResult.UsageError(
                "missing required option --user (given '\${user}' from u:4, which counts as not set)",
                root.pathTo("login"),
            ),
            missing,
        )
    }

    // ------------------------------------------------------------------------------------ errors and warnings

    @Test
    fun `a value the converter refuses is a configuration error that names the option and the setting`() {
        assertEquals(
            ParseResult.ConfigurationError("invalid value for --limit (from u:3): 'many' is not an integer"),
            parse(Held(Setting("limit", "many", "u:3")), "list"),
        )
    }

    @Test
    fun `a source that can't be read is a configuration error with its message`() {
        assertEquals(ParseResult.ConfigurationError("u:7: expected 'key = value'"), parse(Held(failure = "u:7: expected 'key = value'"), "list"))
    }

    @Test
    fun `a constraint names the setting a value came from`() {
        val result = parse(Held(Setting("limit", "5", "u:2")), "list", "--from", "f")
        assertIs<ParseResult.UsageError>(result)
        assertContains(result.message, "--limit (from u:2)")
    }

    @Test
    fun `a key no option of the tree declares is warned about, with the nearest key`() {
        val settings = Held(Setting("stroe", "/x", "u:1"), Setting("colour", "red", "u:2"), Setting("user", "ana", "u:3"))
        // `user` is `login`'s, not `list`'s: known to the tree, so no warning, and not read here.
        val parsed = invocation(settings, "list")
        assertEquals(listOf("u:1: unknown setting 'stroe'; did you mean 'store'?", "u:2: unknown setting 'colour'"), parsed.notices)
        assertEquals(null, parsed.values[store])
    }

    @Test
    fun `the suggestion for an unknown key is never a deprecated option's`() {
        assertEquals(listOf("u:1: unknown setting 'indet'"), invocation(Held(Setting("indet", "2", "u:1")), "list").notices)
    }

    @Test
    fun `a deprecated option set by a setting is warned about, naming the setting`() {
        val parsed = invocation(Held(Setting("indent", "2", "p:1"), Setting("indent", "3", "u:1")), "list")
        assertEquals(2, parsed.values[indent])
        assertEquals(listOf("p:1: --indent is deprecated: pipe --json to a formatter"), parsed.notices)
    }

    @Test
    fun `warnings about settings follow the command line's and the variables'`() {
        val parsed = invocation(Held(Setting("store", "\${s}", "u:1"), Setting("nope", "1", "u:2")), "list", "--indent", "2")
        assertEquals(
            listOf("--indent is deprecated: pipe --json to a formatter", "u:1: ignoring '\${s}': an unfilled template", "u:2: unknown setting 'nope'"),
            parsed.notices,
        )
    }

    // ------------------------------------------------------------------------------------ when they're read

    @Test
    fun `settings are read once a parse, and only when a value is wanted from them`() {
        val broken = Held(failure = "broken")
        val answered = listOf(
            listOf("--help"),
            listOf("list", "--help"),
            listOf("help", "list"),
            listOf("--version"),
            listOf("completion", "bash"),
            // A group runs nothing, so it needs no value.
            listOf(),
            // Every configurable option on the path given already.
            listOf("--store", "s", "login", "--user", "ana"),
        )
        for (args in answered) assertFalse(parse(broken, *args.toTypedArray()) is ParseResult.ConfigurationError, "demo $args")
        assertEquals(0, broken.reads)

        val held = Held(Setting("store", "/s", "u:1"), Setting("limit", "3", "u:2"))
        invocation(held, "list")
        assertEquals(1, held.reads)
    }

    @Test
    fun `a path with no configurable option reads nothing`() {
        val leaf = CommandSpec("leaf", options = listOf(from))
        val broken = Held(failure = "broken")
        assertIs<ParseResult.Invocation>(Parser(CommandSpec("demo", subcommands = listOf(leaf)), settings = broken).parse(listOf("leaf")))
        assertEquals(0, broken.reads)
    }

    // ------------------------------------------------------------------------------------ the rules

    @Test
    fun `a key is lower case, digits, dashes and dots`() {
        Option.Single("--a", Converters.string, config = "store.dir-2")
        for (bad in listOf("", "Store", "store dir", "-store", "store=x", "ключ")) {
            assertFailsWith<IllegalArgumentException>(bad) { Option.Single("--a", Converters.string, config = bad) }
        }
    }

    @Test
    fun `a command can't give two of its options one key, nor can a path`() {
        val other = Option.Single("--other", Converters.string, config = "store")
        assertFailsWith<IllegalArgumentException> { CommandSpec("demo", options = listOf(store, other)) }
        val leaf = CommandSpec("leaf", options = listOf(other))
        val error = assertFailsWith<IllegalArgumentException> {
            Program(CommandSpec("demo", options = listOf(store), subcommands = listOf(leaf)), "demo 1.0", mapOf(leaf to CommandHandler { 0 }))
        }
        assertContains(error.message.orEmpty(), "'demo leaf' reads the configuration keys [store] for more than one option")
        // Narrowing the option itself, key and all, is fine.
        val narrowed = Option.Single("--store", Converters.string, config = "store")
        val below = CommandSpec("leaf", options = listOf(narrowed))
        Program(CommandSpec("demo", options = listOf(store), subcommands = listOf(below)), "demo 1.0", mapOf(below to CommandHandler { 0 }))
    }

    // ------------------------------------------------------------------------------------ help

    @Test
    fun `help tags the key and lists the keys and where they're read, when the program reads settings`() {
        val page = HelpRenderer(Styler.Plain, null, 80, Held()).render(root.pathTo("list"))
        assertContains(page, "      --limit <n>       [config: limit]\n")
        assertContains(
            page,
            "Configuration:\n" +
                "  limit   Same as --limit\n" +
                "  indent  Same as --indent\n" +
                "  store   Same as --store\n" +
                "  Read from, highest first:\n" +
                "    the project file\n" +
                "    the user file\n",
        )
    }

    @Test
    fun `without settings, help says nothing about keys`() {
        for (help in listOf(HelpRenderer(), HelpRenderer(Styler.Plain, null, 80, Settings.None))) {
            val page = help.render(root.pathTo("list"))
            assertFalse("config" in page.lowercase(), page)
        }
    }

    @Test
    fun `a page whose options have no key has no Configuration section`() {
        val leaf = CommandSpec("leaf", options = listOf(from))
        assertFalse("Configuration:" in HelpRenderer(Styler.Plain, null, 80, Held()).render(listOf(leaf)))
    }

    @Test
    fun `a long location wraps under itself`() {
        val long = object : Settings {
            override val locations = listOf(List(12) { "word" }.joinToString(" "))
            override fun read(): List<Setting> = emptyList()
        }
        val page = HelpRenderer(Styler.Plain, null, 40, long).render(root.pathTo("login"))
        assertContains(page, "  Read from, highest first:\n    word word word word word word word\n      word word word word word\n")
    }

    // ------------------------------------------------------------------------------------ Program

    @Test
    fun `a configuration error exits CONFIG, with no usage line, and never reaches the handler`() {
        val result = program(Held(Setting("limit", "many", "u:3"))).record("list")
        assertEquals(ExitStatus.CONFIG, result.status)
        assertEquals("", result.stdout)
        assertEquals("error: invalid value for --limit (from u:3): 'many' is not an integer\n", result.stderr)
    }

    @Test
    fun `help, version and completion answer whatever the settings say`() {
        val program = program(Held(failure = "u:1: broken"))
        for (args in listOf(arrayOf("--help"), arrayOf("help", "list"), arrayOf("--version"), arrayOf("completion", "zsh"))) {
            val result = program.record(*args)
            assertEquals(ExitStatus.OK to "", result.status to result.stderr, args.joinToString(" "))
        }
        assertContains(program.record("list", "--help").stdout, "Configuration:\n")
    }

    @Test
    fun `warnings about settings go to stderr before the command runs, and --quiet silences them`() {
        val program = program(Held(Setting("stroe", "/x", "u:1")))
        assertEquals("warning: u:1: unknown setting 'stroe'; did you mean 'store'?\n", program.record("list").stderr)
        assertEquals("", program.record("--quiet", "list").stderr)
    }

    // ------------------------------------------------------------------------------------ the format

    @Test
    fun `a file is key = value lines, trimmed, the value verbatim`() {
        val text = "\uFEFF# a comment\r\n; another\r\n\r\n  store =  C:\\work\\store  \r\nquery = \$.a == 'b'\r\nempty =\r\n"
        assertEquals(
            listOf(
                Setting("store", "C:\\work\\store", "f:4"),
                Setting("query", "\$.a == 'b'", "f:5"),
                Setting("empty", "", "f:6"),
            ),
            parseSettings(text, "f"),
        )
        assertEquals(emptyList(), parseSettings("", "f"))
    }

    @Test
    fun `a malformed line is an error that names the file and line`() {
        val errors = mapOf(
            "a = 1\n[list]\n" to "f:2: sections aren't supported; write 'key = value' lines",
            "a = 1\njust words\n" to "f:2: expected 'key = value'",
            " = 1\n" to "f:1: expected 'key = value'",
            "Store = 1\n" to "f:1: 'Store' is not a key; keys are lower case, digits, '-' and '.'",
            "a = 1\n\na = 2\n" to "f:3: 'a' is set twice, here and on line 1",
        )
        for ((text, message) in errors) {
            assertEquals(message, assertFailsWith<SettingsException>(text) { parseSettings(text, "f") }.message)
        }
    }
}
