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
