package app.oreshkov.clikit

import app.oreshkov.clikit.testing.record
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * An argument that is another spelling of an option, `[forms-dir]` for `--forms-dir`: one value, which the option owns,
 * in the order of precedence of the command line, and before the option's variable and setting.
 */
class OptionSpellingTest {

    private val configured = Converters.string.unsetWhen(
        { it.isBlank() || it.startsWith("\${") },
        notice = { if (it.isBlank()) null else "ignoring '$it'" },
    )

    private val formsDir = Option.Single(
        "--forms-dir",
        configured,
        help = "Directory of the forms",
        valueName = "path",
        required = true,
        env = "DEMO_FORMS_DIR",
        formerNames = listOf("--forms"),
    )
    private val formsDirArgument = Argument.Single.spelling(formsDir)
    private val serve = CommandSpec("serve", "Serve the forms", options = listOf(formsDir), arguments = listOf(formsDirArgument))

    private fun parse(vararg args: String, environment: Map<String, String> = emptyMap()): ParseResult =
        Parser(serve, environment::get).parse(args.toList())

    private fun invocation(vararg args: String, environment: Map<String, String> = emptyMap()): ParseResult.Invocation {
        val result = parse(*args, environment = environment)
        assertIs<ParseResult.Invocation>(result, "${args.joinToString(" ")} -> $result")
        return result
    }

    private fun refused(vararg args: String, environment: Map<String, String> = emptyMap()): String {
        val result = parse(*args, environment = environment)
        assertIs<ParseResult.UsageError>(result, "${args.joinToString(" ")} -> $result")
        return result.message
    }

    // ------------------------------------------------------------------------------------ the declaration

    @Test
    fun `a spelling takes the option's converter and name, is never required, and says what it spells`() {
        assertSame(formsDir, formsDirArgument.spellingOf)
        assertSame(formsDir.converter, formsDirArgument.converter)
        assertEquals("forms-dir", formsDirArgument.name)
        assertFalse(formsDirArgument.required)
        assertEquals("Same as --forms-dir", formsDirArgument.help)
        assertEquals("dir", Argument.Single.spelling(formsDir, name = "dir").name)
        assertNull(Argument.Single("plain", Converters.string).spellingOf)
    }

    // ------------------------------------------------------------------------------------ one value

    @Test
    fun `either spelling gives the option its value, which both handles read`() {
        for (args in listOf(listOf("--forms-dir", "/f"), listOf("--forms=/f"), listOf("/f"), listOf("--", "/f"))) {
            val values = invocation(*args.toTypedArray()).values
            assertEquals("/f", values[formsDir], "$args")
            assertEquals("/f", values[formsDirArgument], "$args")
            assertEquals("/f", values.required(formsDirArgument), "$args")
        }
    }

    @Test
    fun `the command line, by either spelling, wins over the variable, which fills in for neither`() {
        val environment = mapOf("DEMO_FORMS_DIR" to "/env")
        assertEquals("/typed", invocation("--forms-dir", "/typed", environment = environment).values[formsDir])
        assertEquals("/typed", invocation("/typed", environment = environment).values[formsDir])
        assertEquals("/env", invocation(environment = environment).values[formsDirArgument])
    }

    @Test
    fun `an unset value of either spelling gives way to the next source, with its notice`() {
        val environment = mapOf("DEMO_FORMS_DIR" to "/env")

        val positional = invocation("--forms-dir", "\${t}", "/typed", environment = environment)
        assertEquals("/typed", positional.values[formsDir])
        assertEquals(listOf("--forms-dir: ignoring '\${t}'"), positional.notices)

        val variable = invocation("\${p}", "--forms-dir", "\${t}", environment = environment)
        assertEquals("/env", variable.values[formsDir])
        assertEquals(listOf("<forms-dir>: ignoring '\${p}'", "--forms-dir: ignoring '\${t}'"), variable.notices)

        val option = invocation("--forms-dir", "/typed", "\${p}", environment = environment)
        assertEquals("/typed", option.values[formsDir])
        assertEquals(listOf("<forms-dir>: ignoring '\${p}'"), option.notices)
    }

    // ------------------------------------------------------------------------------------ refusals

    @Test
    fun `both spellings with a value is a usage error, whatever the order and even for one value`() {
        val message = "--forms-dir is given twice: as an option and as <forms-dir>"
        assertEquals(message, refused("--forms-dir", "/a", "/b"))
        assertEquals(message, refused("/b", "--forms", "/a"))
        assertEquals(message, refused("/a", "--forms-dir=/a"))
    }

    @Test
    fun `a missing value names every way to give it, and what was given that counts as not set`() {
        assertEquals("missing required option --forms-dir (or <forms-dir>, or DEMO_FORMS_DIR)", refused())
        assertEquals(
            "missing required option --forms-dir (or <forms-dir>, or DEMO_FORMS_DIR; " +
                "given '\${p}' as <forms-dir>, which counts as not set)",
            refused("\${p}"),
        )
        // What was typed is named rather than the variable read after it.
        assertEquals(
            "missing required option --forms-dir (or <forms-dir>, or DEMO_FORMS_DIR; " +
                "given '\${p}' as <forms-dir>, which counts as not set)",
            refused("\${p}", environment = mapOf("DEMO_FORMS_DIR" to "\${e}")),
        )
    }

    @Test
    fun `a value the converter refuses names the spelling it was typed as`() {
        val limit = Option.Single("--limit", Converters.positiveInt)
        val command = CommandSpec("list", options = listOf(limit), arguments = listOf(Argument.Single.spelling(limit)))
        val result = Parser(command).parse(listOf("x"))
        assertIs<ParseResult.UsageError>(result)
        assertContains(result.message, "invalid value for <limit>: ")
    }

    @Test
    fun `positionals are bound before any variable is read, so the command line's mistakes come first`() {
        val limit = Option.Single("--limit", Converters.positiveInt, env = "DEMO_LIMIT")
        val command = CommandSpec("list", options = listOf(limit), arguments = listOf(Argument.Single.spelling(limit)))
        val result = Parser(command, mapOf("DEMO_LIMIT" to "x")::get).parse(listOf("1", "2"))
        assertIs<ParseResult.UsageError>(result)
        assertEquals("unexpected argument '2'; expected [limit]", result.message)
    }

    // ------------------------------------------------------------------------------------ other rules

    @Test
    fun `a constraint on the option sees its positional spelling, and names it`() {
        val out = Option.Single("--out", Converters.string)
        val stdout = Option.Flag("--stdout")
        val render = CommandSpec(
            "render",
            options = listOf(out, stdout),
            arguments = listOf(Argument.Single.spelling(out)),
            constraints = listOf(Constraint.Exclusive(stdout, out)),
        )
        val result = Parser(render).parse(listOf("--stdout", "page.html"))
        assertIs<ParseResult.UsageError>(result)
        assertEquals("--stdout cannot be combined with --out (from <out>)", result.message)
    }

    @Test
    fun `the positional spelling of a deprecated option is warned about, as its variable is`() {
        val old = Option.Single("--old", Converters.string, deprecated = "use --new instead")
        val command = CommandSpec("run", options = listOf(old), arguments = listOf(Argument.Single.spelling(old)))
        val result = Parser(command).parse(listOf("x"))
        assertIs<ParseResult.Invocation>(result)
        assertEquals(listOf("<old>: --old is deprecated: use --new instead"), result.notices)
    }

    @Test
    fun `a spelling can't be a constraint's operand, so the rule is stated once, on the option`() {
        val other = Option.Flag("--other")
        val error = assertFailsWith<IllegalArgumentException> { Constraint.Exclusive(other, formsDirArgument) }
        assertEquals("<forms-dir> is another spelling of --forms-dir; name --forms-dir in the constraint", error.message)
    }

    @Test
    fun `a command may spell an option positionally only once`() {
        val error = assertFailsWith<IllegalArgumentException> {
            CommandSpec("serve", options = listOf(formsDir), arguments = listOf(formsDirArgument, Argument.Single.spelling(formsDir, "dir")))
        }
        assertEquals("command 'serve' has more than one argument spelling [--forms-dir]", error.message)
    }

    @Test
    fun `the program refuses a spelling of an option the command doesn't read`() {
        val elsewhere = Option.Single("--elsewhere", Converters.string)
        val run = CommandSpec("run", arguments = listOf(Argument.Single.spelling(elsewhere)))
        val root = CommandSpec("demo", subcommands = listOf(run))
        val error = assertFailsWith<IllegalArgumentException> { Program(root, "demo 1.0", mapOf(run to CommandHandler { 0 })) }
        assertEquals("'demo run' has no --elsewhere, which its argument <elsewhere> is another spelling of", error.message)

        // An inherited option is read, so its spelling is fine.
        val inherited = CommandSpec("run", arguments = listOf(Argument.Single.spelling(elsewhere)))
        Program(CommandSpec("demo", options = listOf(elsewhere), subcommands = listOf(inherited)), "demo 1.0", mapOf(inherited to CommandHandler { 0 }))
    }

    // ------------------------------------------------------------------------------------ help and completions

    @Test
    fun `help shows the positional as optional, says what it spells, and keeps the option's tags`() {
        assertEquals(
            """
            Serve the forms

            Usage:
              serve [options] [forms-dir]

            Arguments:
              forms-dir  Same as --forms-dir

            Options:
                  --forms-dir <path>  Directory of the forms [env: DEMO_FORMS_DIR]
                                      (required)

            Environment:
              DEMO_FORMS_DIR  Same as --forms-dir

            """.trimIndent(),
            HelpRenderer().render(listOf(serve)),
        )
    }

    @Test
    fun `the positional slot completes the option's choices`() {
        val format = Option.Single("--format", Converters.choice("csv", "json"))
        val export = CommandSpec("export", options = listOf(format), arguments = listOf(Argument.Single.spelling(format)))
        val root = CommandSpec("demo", subcommands = listOf(export))
        assertContains(CompletionGenerator().generate(Shell.Bash, root), "\"export\") candidates=\"csv json --format --help\" ;;")
    }

    @Test
    fun `a program hands the handler one value, whichever spelling gave it`() {
        var seen: String? = null
        val root = CommandSpec("demo", subcommands = listOf(serve))
        val program = Program(root, "demo 1.0", mapOf(serve to CommandHandler { context -> seen = context.values[formsDir]; 0 }))
        assertEquals(ExitStatus.OK, program.record("serve", "/srv/forms").status)
        assertEquals("/srv/forms", seen)
    }
}
