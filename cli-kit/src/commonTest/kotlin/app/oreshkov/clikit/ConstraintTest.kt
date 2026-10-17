package app.oreshkov.clikit

import app.oreshkov.clikit.testing.record
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ConstraintTest {

    private val pretty = Option.Flag("--pretty")
    private val compact = Option.Flag("--compact")
    private val maxLevels = Option.Single("--max-levels", Converters.nonNegativeInt)
    private val site = Option.Single("--site", Converters.string, env = "DEMO_SITE")
    private val all = Option.Flag("--all")
    private val tag = Option.Repeated("--tag", Converters.string)
    private val file = Option.Single("--file", Converters.string)
    private val right = Argument.Single("right", Converters.string, required = false)

    private val show = CommandSpec(
        "show",
        options = listOf(pretty, compact, maxLevels, site, all, tag),
        constraints = listOf(
            Constraint.Exclusive(pretty, compact),
            Constraint.Exclusive(StandardOptions.json, maxLevels),
            Constraint.Exclusive(site, all),
            Constraint.Requires(tag, site),
        ),
    )

    private val diff = CommandSpec(
        "diff",
        options = listOf(file),
        arguments = listOf(right),
        constraints = listOf(Constraint.ExactlyOne(right, file)),
    )

    private val root = CommandSpec("demo", options = listOf(StandardOptions.json), subcommands = listOf(show, diff))

    private fun parse(vararg args: String, environment: Map<String, String> = emptyMap()): ParseResult =
        Parser(root, environment::get).parse(args.toList())

    private fun refused(vararg args: String, environment: Map<String, String> = emptyMap()): String {
        val parsed = parse(*args, environment = environment)
        assertIs<ParseResult.UsageError>(parsed, args.joinToString(" "))
        return parsed.message
    }

    private fun accepted(vararg args: String, environment: Map<String, String> = emptyMap()) {
        assertIs<ParseResult.Invocation>(parse(*args, environment = environment), args.joinToString(" "))
    }

    @Test
    fun `exclusive flags`() {
        assertEquals("--pretty cannot be combined with --compact", refused("show", "--pretty", "--compact"))
        accepted("show", "--pretty")
        accepted("show", "--compact")
        accepted("show")
    }

    @Test
    fun `a constraint may name an inherited option, given before or after the command`() {
        assertEquals("--json cannot be combined with --max-levels", refused("--json", "show", "--max-levels", "2"))
        assertEquals("--json cannot be combined with --max-levels", refused("show", "--max-levels", "2", "--json"))
        accepted("--json", "show")
    }

    @Test
    fun `a value from the environment counts as given, and the message names the variable`() {
        val environment = mapOf("DEMO_SITE" to "eu")
        assertEquals("--site (from DEMO_SITE) cannot be combined with --all", refused("show", "--all", environment = environment))
        assertEquals("--site cannot be combined with --all", refused("show", "--site", "us", "--all", environment = environment))
        accepted("show", "--all", environment = mapOf("DEMO_SITE" to ""))
    }

    @Test
    fun `requires, with a repeated option as the subject`() {
        assertEquals("--tag requires --site", refused("show", "--tag", "a", "--tag", "b"))
        accepted("show", "--tag", "a", "--site", "eu")
        accepted("show", "--tag", "a", environment = mapOf("DEMO_SITE" to "eu"))
        accepted("show", "--site", "eu")
    }

    @Test
    fun `exactly one of an argument and an option`() {
        assertEquals("exactly one of <right> and --file is required", refused("diff"))
        assertEquals("<right> cannot be combined with --file", refused("diff", "x.json", "--file", "y.json"))
        accepted("diff", "x.json")
        accepted("diff", "--file", "y.json")
    }

    @Test
    fun `an unset value doesn't count as given, and the message says what was given for it`() {
        val configured = Converters.string.unsetWhen({ it.startsWith("\${") })
        val formsDir = Option.Single("--forms-dir", configured, env = "DEMO_FORMS_DIR")
        val formsDirArgument = Argument.Single("forms-dir", configured, required = false)
        val strict = Option.Flag("--strict")
        val serve = CommandSpec(
            "serve",
            options = listOf(formsDir, strict),
            arguments = listOf(formsDirArgument),
            constraints = listOf(Constraint.ExactlyOne(formsDir, formsDirArgument), Constraint.Requires(strict, formsDir)),
        )
        fun parse(vararg args: String, environment: Map<String, String> = emptyMap()) =
            Parser(serve, environment::get).parse(args.toList())

        assertIs<ParseResult.Invocation>(parse("--forms-dir", "\${dir}", "/forms"))
        assertIs<ParseResult.Invocation>(parse("--forms-dir", "/forms", "\${dir}"))
        val neither = parse("--forms-dir", "\${a}", "\${b}")
        assertIs<ParseResult.UsageError>(neither)
        assertEquals(
            "exactly one of --forms-dir and <forms-dir> is required " +
                "(--forms-dir given '\${a}', which counts as not set; <forms-dir> given '\${b}', which counts as not set)",
            neither.message,
        )
        val strictWithout = parse("--strict", "/forms", environment = mapOf("DEMO_FORMS_DIR" to "\${c}"))
        assertIs<ParseResult.UsageError>(strictWithout)
        assertEquals(
            "--strict requires --forms-dir (--forms-dir given '\${c}' from DEMO_FORMS_DIR, which counts as not set)",
            strictWithout.message,
        )
    }

    @Test
    fun `a broken constraint is a usage error with the usage line, and the handler never runs`() {
        val program = Program(root, "demo 1.0", mapOf(show to CommandHandler { error("ran") }, diff to CommandHandler { 0 }))
        val result = program.record("show", "--pretty", "--compact")
        assertEquals(ExitStatus.USAGE, result.status)
        assertEquals("error: --pretty cannot be combined with --compact\nusage: demo show [options]\n", result.stderr)
    }

    @Test
    fun `help lists the constraints on the path under Notes`() {
        val page = HelpRenderer().render(root.pathTo("show"))
        assertContains(
            page,
            "Notes:\n" +
                "  --pretty and --compact cannot be combined\n" +
                "  --json and --max-levels cannot be combined\n" +
                "  --site and --all cannot be combined\n" +
                "  --tag requires --site\n",
        )
        assertContains(HelpRenderer().render(root.pathTo("diff")), "Notes:\n  exactly one of <right> and --file\n")
        assertFalse("Notes:" in HelpRenderer().render(root.pathTo()))
    }

    @Test
    fun `notes for three operands, and none for a constraint on a hidden option`() {
        val debug = Option.Flag("--debug", hidden = true)
        val a = Option.Flag("--a")
        val b = Option.Flag("--b")
        val c = Option.Flag("--c")
        val command = CommandSpec(
            "x",
            options = listOf(a, b, c, debug),
            constraints = listOf(Constraint.Exclusive(a, b, c), Constraint.ExactlyOne(a, b, c), Constraint.Exclusive(a, debug)),
        )
        val page = HelpRenderer().render(listOf(command))
        assertContains(page, "Notes:\n  at most one of --a, --b and --c\n  exactly one of --a, --b and --c\n")
        assertFalse("--debug" in page)
        assertEquals("--a cannot be combined with --c", (Parser(command).parse(listOf("--c", "--a")) as ParseResult.UsageError).message)
    }

    @Test
    fun `a constraint names two operands or more, each once, none of them required`() {
        assertFailsWith<IllegalArgumentException> { Constraint.Exclusive(pretty) }
        assertContains(assertFailsWith<IllegalArgumentException> { Constraint.Exclusive(pretty, pretty) }.message.orEmpty(), "twice")
        val required = Option.Single("--url", Converters.string, required = true)
        assertContains(assertFailsWith<IllegalArgumentException> { Constraint.Exclusive(required, pretty) }.message.orEmpty(), "--url")
        val needed = Argument.Single("needed", Converters.string)
        assertFailsWith<IllegalArgumentException> { Constraint.ExactlyOne(needed, file) }
    }

    @Test
    fun `a command can constrain only its own arguments`() {
        val error = assertFailsWith<IllegalArgumentException> {
            CommandSpec("other", options = listOf(file), constraints = listOf(Constraint.ExactlyOne(right, file)))
        }
        assertContains(error.message.orEmpty(), "<right>")
    }

    @Test
    fun `a program refuses a constraint on an option its command can't see`() {
        val stray = CommandSpec("stray", options = listOf(pretty), constraints = listOf(Constraint.Exclusive(pretty, all)))
        val error = assertFailsWith<IllegalArgumentException> {
            Program(CommandSpec("demo", subcommands = listOf(stray)), "demo 1.0", mapOf(stray to CommandHandler { 0 }))
        }
        assertContains(error.message.orEmpty(), "'demo stray' has no [--all]")
    }

    @Test
    fun `a program refuses a constraint on a handle a subcommand redeclared`() {
        // The group's constraint names its own --site; the leaf reads its own, so the group's is never given there.
        val narrowed = Option.Single("--site", Converters.choice("eu", "us"))
        val leaf = CommandSpec("leaf", options = listOf(narrowed))
        val group = CommandSpec(
            "group",
            options = listOf(site, all),
            subcommands = listOf(leaf),
            constraints = listOf(Constraint.Exclusive(site, all)),
        )
        val error = assertFailsWith<IllegalArgumentException> {
            Program(CommandSpec("demo", subcommands = listOf(group)), "demo 1.0", mapOf(leaf to CommandHandler { 0 }))
        }
        assertContains(error.message.orEmpty(), "'demo group leaf' has no [--site]")
    }

    // ------------------------------------------------------------------------------------------ a required value

    private val transport = Option.Single(
        "--transport",
        Converters.choice("stdio", "http", "sse").unsetWhen({ it.startsWith("\${") }),
        env = "DEMO_TRANSPORT",
    )
    private val port = Option.Single("--port", Converters.positiveInt)
    private val serve = CommandSpec(
        "serve",
        options = listOf(transport, port),
        constraints = listOf(Constraint.RequiresValue(port, transport, "http", "sse")),
    )

    private fun serve(vararg args: String, environment: Map<String, String> = emptyMap()): ParseResult =
        Parser(serve, environment::get).parse(args.toList())

    private fun serveRefused(vararg args: String, environment: Map<String, String> = emptyMap()): String {
        val parsed = serve(*args, environment = environment)
        assertIs<ParseResult.UsageError>(parsed, args.joinToString(" "))
        return parsed.message
    }

    @Test
    fun `requires a value, from the command line or the variable`() {
        assertIs<ParseResult.Invocation>(serve("--port", "8080", "--transport", "http"))
        assertIs<ParseResult.Invocation>(serve("--transport", "sse", "--port", "8080"))
        assertIs<ParseResult.Invocation>(serve("--port", "8080", environment = mapOf("DEMO_TRANSPORT" to "http")))
        assertIs<ParseResult.Invocation>(serve("--transport", "stdio"))
        assertIs<ParseResult.Invocation>(serve())
    }

    @Test
    fun `another value breaks it, and the message quotes the value and names its variable`() {
        assertEquals("--port requires --transport http or sse, but --transport is 'stdio'", serveRefused("--port", "1", "--transport", "stdio"))
        assertEquals(
            "--port requires --transport http or sse, but --transport is 'stdio'",
            serveRefused("--transport", "http", "--transport", "stdio", "--port", "1"),
        )
        assertEquals(
            "--port requires --transport http or sse, but --transport (from DEMO_TRANSPORT) is 'stdio'",
            serveRefused("--port", "1", environment = mapOf("DEMO_TRANSPORT" to "stdio")),
        )
        assertEquals(
            "--port requires --transport http or sse, but --transport is 'stdio'",
            serveRefused("--port", "1", "--transport", "stdio", environment = mapOf("DEMO_TRANSPORT" to "http")),
        )
    }

    @Test
    fun `an absent option doesn't match, and an unset one says what was given`() {
        assertEquals("--port requires --transport http or sse", serveRefused("--port", "1"))
        assertEquals(
            "--port requires --transport http or sse (--transport given '\${t}', which counts as not set)",
            serveRefused("--port", "1", "--transport", "http", "--transport", "\${t}"),
        )
    }

    @Test
    fun `the README's example`() {
        val transport = Option.Single("--transport", Converters.choice("stdio", "http"), env = "TRANSPORT")
        val port = Option.Single("--port", Converters.positiveInt)
        val serve = CommandSpec(
            "serve",
            options = listOf(transport, port),
            constraints = listOf(Constraint.RequiresValue(port, transport, "http")),
        )
        assertContains(HelpRenderer().render(listOf(serve)), "Notes:\n  --port requires --transport http\n")
        val refused = Parser(serve).parse(listOf("--port", "8080", "--transport", "stdio"))
        assertIs<ParseResult.UsageError>(refused)
        assertEquals("--port requires --transport http, but --transport is 'stdio'", refused.message)
    }

    @Test
    fun `values are compared converted, not as text`() {
        val level = Option.Single("--level", Converters.nonNegativeInt)
        val verbose = Option.Flag("--verbose")
        val command = CommandSpec("x", options = listOf(level, verbose), constraints = listOf(Constraint.RequiresValue(verbose, level, "2")))
        assertIs<ParseResult.Invocation>(Parser(command).parse(listOf("--verbose", "--level", "02")))
        val refused = Parser(command).parse(listOf("--verbose", "--level", "3"))
        assertIs<ParseResult.UsageError>(refused)
        assertEquals("--verbose requires --level 2, but --level is '3'", refused.message)
    }

    @Test
    fun `help notes a required value, quoting one that couldn't stand alone`() {
        assertContains(HelpRenderer().render(listOf(serve)), "Notes:\n  --port requires --transport http or sse\n")
        val name = Option.Single("--name", Converters.string)
        val a = Option.Flag("--a")
        val command = CommandSpec("x", options = listOf(name, a), constraints = listOf(Constraint.RequiresValue(a, name, "one", "two words", "")))
        assertContains(HelpRenderer().render(listOf(command)), "  --a requires --name one, 'two words' or ''\n")
    }

    @Test
    fun `a required value is declared with values the converter accepts, each once`() {
        assertContains(
            assertFailsWith<IllegalArgumentException> { Constraint.RequiresValue(port, transport, "ftp") }.message.orEmpty(),
            "'ftp' is not a value of --transport",
        )
        assertContains(
            assertFailsWith<IllegalArgumentException> { Constraint.RequiresValue(port, transport, "\${t}") }.message.orEmpty(),
            "counts as not set",
        )
        assertContains(assertFailsWith<IllegalArgumentException> { Constraint.RequiresValue(port, transport) }.message.orEmpty(), "at least one")
        assertContains(
            assertFailsWith<IllegalArgumentException> { Constraint.RequiresValue(port, transport, "http", "http") }.message.orEmpty(),
            "twice",
        )
    }

    @Test
    fun `the option whose value is required may be required, the subject may not`() {
        val mode = Option.Single("--mode", Converters.choice("fast", "safe"), required = true)
        val sync = Option.Flag("--sync")
        val command = CommandSpec("x", options = listOf(mode, sync), constraints = listOf(Constraint.RequiresValue(sync, mode, "safe")))
        assertEquals("--sync requires --mode safe, but --mode is 'fast'", (Parser(command).parse(listOf("--sync", "--mode", "fast")) as ParseResult.UsageError).message)
        assertIs<ParseResult.Invocation>(Parser(command).parse(listOf("--mode", "fast")))
        val required = Option.Single("--url", Converters.string, required = true)
        assertContains(
            assertFailsWith<IllegalArgumentException> { Constraint.RequiresValue(required, transport, "http") }.message.orEmpty(),
            "--url",
        )
    }

    // ------------------------------------------------------------------------------------------ standard input

    private object Dash : ReadsStandardInput {
        override val readsStandardInput: Boolean get() = true
    }

    private data class Named(val name: String) : ReadsStandardInput {
        override val readsStandardInput: Boolean get() = false
    }

    private val source = ValueConverter<ReadsStandardInput> { if (it == "-") ConversionResult.Ok(Dash) else ConversionResult.Ok(Named(it)) }

    private val input = Option.Repeated("--input", source)
    private val files = Argument.Variadic("file", source, required = false)
    private val cat = CommandSpec("cat", options = listOf(input), arguments = listOf(files))

    private fun cat(vararg args: String): ParseResult = Parser(cat).parse(args.toList())

    @Test
    fun `standard input is read by one value at most, across options and arguments`() {
        assertIs<ParseResult.Invocation>(cat("-", "a", "b"))
        assertIs<ParseResult.Invocation>(cat("--input", "-", "a"))
        for (args in listOf(arrayOf("-", "-"), arrayOf("--input", "-", "--input", "-"), arrayOf("--input", "-", "a", "-"))) {
            val parsed = cat(*args)
            assertIs<ParseResult.UsageError>(parsed, args.joinToString(" "))
            assertEquals("standard input (-) can be read only once", parsed.message)
        }
    }
}
