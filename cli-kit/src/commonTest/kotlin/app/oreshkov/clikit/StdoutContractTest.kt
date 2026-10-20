package app.oreshkov.clikit

import app.oreshkov.clikit.testing.RecordingTerminalIo
import app.oreshkov.clikit.testing.RunResult
import app.oreshkov.clikit.testing.record
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The kit writes to the stdout it is given only for help, `--version` and `completion`: never for a usage error, a
 * failure, a bug or a notice. Whatever else reaches stdout is what the handler wrote. That is what lets a program's
 * stdout carry a protocol, as an MCP server's does on stdio, and these runs go over every path of a fixture program to
 * hold the kit to it.
 */
class StdoutContractTest {

    private val dir = Option.Single(
        "--dir",
        Converters.string.unsetWhen({ it.startsWith("\${") }, notice = { "ignoring '$it': an unfilled template" }),
        valueName = "dir",
        help = "Working directory",
        env = "FIX_DIR",
        formerNames = listOf("--workdir"),
    )
    private val limit = Option.Single("--limit", Converters.positiveInt, "-n", valueName = "n", required = true, env = "FIX_LIMIT")
    private val from = Option.Single("--from", Converters.string, valueName = "file")
    private val stdin = Option.Flag("--stdin")
    private val key = Argument.Single("key", Converters.string, "Entry key")
    private val format = Argument.Single("format", Converters.choice("text", "json"), "Output format", required = false)

    private val get = CommandSpec("get", "Read one entry", options = listOf(limit), arguments = listOf(key, format))
    private val put = CommandSpec(
        "put",
        "Store one entry",
        options = listOf(from, stdin),
        constraints = listOf(Constraint.Exclusive(from, stdin)),
        environment = listOf(EnvironmentVariable("FIX_EDITOR", "Editor for the entry when neither is given")),
    )
    private val run = CommandSpec("run", "Run until stopped", deprecated = "it stops by itself now")
    private val store = CommandSpec("store", "The store", subcommands = listOf(get, put), aliases = listOf("st"))

    private val root = CommandSpec(
        "fix",
        "A fixture for the stdout contract.",
        options = listOf(
            dir,
            StandardOptions.json,
            StandardOptions.verbose,
            StandardOptions.quiet,
            StandardOptions.noInput,
            StandardOptions.width,
        ),
        subcommands = listOf(store, run, StandardOptions.completion, StandardOptions.help),
        environment = listOf(EnvironmentVariable("FIX_HOME", "Where the program is installed")),
    )

    /** What a handler does, the exit status that leads to, and what it writes to stdout itself. */
    private class Behaviour(val name: String, val status: Int, val stdout: String = "", val act: (CommandContext) -> Unit)

    private val behaviours = listOf(
        Behaviour("returns", ExitStatus.OK) {},
        Behaviour("writes its payload", ExitStatus.OK, stdout = "payload\n") { it.output.text("payload") },
        Behaviour("reports on stderr", ExitStatus.OK) { context ->
            context.output.notice("a notice")
            context.output.warn("a warning")
            context.output.error("an error it recovered from")
            LogLevel.entries.forEach { context.output.log(it, "a log line", IllegalStateException("cause")) }
            context.output.progress("working", 2).apply { step(); step(); finish("done") }
        },
        Behaviour("fails", ExitStatus.DATA_ERR) { throw CommandFailure(ExitStatus.DATA_ERR, "bad data") },
        Behaviour("fails with a usage error", ExitStatus.USAGE) { throw CommandFailure(ExitStatus.USAGE, "bad use") },
        Behaviour("throws what translate maps", ExitStatus.NO_INPUT) { throw NoSuchElementException("gone") },
        Behaviour("has a bug", ExitStatus.SOFTWARE) { error("a bug") },
        Behaviour("asks for confirmation without a terminal", ExitStatus.USAGE) { it.confirm("Delete it?") },
        Behaviour("asks for a secret without a terminal", ExitStatus.USAGE) { it.readSecret("Password: ") },
    )

    private var behaviour: Behaviour = behaviours.first()

    private val program = Program(
        root,
        "fix 1.0",
        root.runnablePaths().associate { it.last() to CommandHandler { context -> behaviour.act(context); behaviour.status } },
        translate = { (it as? NoSuchElementException)?.let { e -> CommandFailure(ExitStatus.NO_INPUT, e.message.orEmpty()) } },
        environment = { name -> environment[name] },
    )

    private var environment: Map<String, String> = emptyMap()

    private fun record(args: List<String>, interactive: Boolean = false, env: Map<String, String> = emptyMap()): RunResult {
        environment = env
        return program.record(*args.toTypedArray(), io = RecordingTerminalIo(interactive = interactive)).assertPipeSafe()
    }

    /** The command words of [path], below the root. */
    private fun words(path: List<CommandSpec>): List<String> = path.drop(1).map { it.name }

    /** A value each converter accepts: its first choice, or `1`, which every open-ended converter here takes. */
    private fun sample(choices: List<String>): String = choices.firstOrNull() ?: "1"

    /** The shortest command line that runs [path]: every required option and argument, nothing else. */
    private fun valid(path: List<CommandSpec>): List<String> =
        words(path) +
            optionsFor(path).filter { it.required }.flatMap { listOf(it.long, sample(it.choices)) } +
            path.last().arguments.filter { it.required }.map { sample(it.choices) }

    @Test
    fun `help is written to stdout, for every command, and nothing to stderr`() {
        for (path in root.paths()) {
            val expected = HelpRenderer().render(path)
            // The fixture exercises the whole page, the Environment section included.
            assertContains(expected, "Environment:\n")
            val asked = listOf(words(path) + "--help", words(path) + "-h", listOf("help") + words(path)) +
                listOfNotNull(words(path).takeIf { !path.last().runnable })
            for (args in asked) {
                val result = record(args)
                assertEquals(RunResult(ExitStatus.OK, expected, "").toString(), result.toString(), "fix $args")
            }
        }
    }

    @Test
    fun `--version and completion scripts are written to stdout, and nothing to stderr`() {
        assertEquals(RunResult(ExitStatus.OK, "fix 1.0\n", "").toString(), record(listOf("--version")).toString())
        for (shell in Shell.entries) {
            val result = record(listOf("completion", shell.id))
            assertEquals(CompletionGenerator().generate(shell, root), result.stdout, shell.id)
            assertEquals(ExitStatus.OK to "", result.status to result.stderr, shell.id)
        }
    }

    @Test
    fun `a usage error writes nothing to stdout`() {
        val usageErrors = buildList {
            add(listOf("--nope"))
            add(listOf("nope"))
            for (path in root.paths()) {
                if (!path.last().runnable) add(words(path) + "nope")
            }
            for (path in root.runnablePaths()) {
                val valid = valid(path)
                add(valid + "--nope")
                add(valid + "--width=wide")
                add(valid + "--width")
                // Each required operand left out in turn.
                optionsFor(path).filter { it.required }.forEach { option ->
                    val at = valid.indexOf(option.long)
                    add(valid.subList(0, at) + valid.subList(at + 2, valid.size))
                }
                val requiredArguments = path.last().arguments.count { it.required }
                if (requiredArguments > 0) add(valid.dropLast(requiredArguments))
            }
            add(listOf("store", "get", "-n", "0", "k"))
            add(listOf("store", "get", "k", "yaml"))
            add(listOf("store", "put", "--from", "f", "--stdin"))
            add(listOf("completion", "tcsh"))
            add(listOf("help", "nope"))
            add(listOf("help", "store", "nope"))
            add(listOf("help", "st", "nope"))
            add(listOf("--workdir"))
            add(listOf("help", "run", "nope"))
        }
        for (args in usageErrors) {
            val result = record(args)
            assertEquals(ExitStatus.USAGE to "", result.status to result.stdout, "fix $args")
            assertTrue(result.stderr.startsWith("error: "), "fix $args: ${result.stderr}")
        }
        // A bad value from a variable is a usage error too.
        val fromVariable = record(listOf("store", "get", "k"), env = mapOf("FIX_LIMIT" to "many"))
        assertEquals(ExitStatus.USAGE to "", fromVariable.status to fromVariable.stdout)
    }

    @Test
    fun `whatever the handler does, stdout holds only what it wrote`() {
        val extras = listOf(
            emptyList(),
            listOf("--dir", "\${user_config.dir}"),
            listOf("--quiet", "--dir", "\${user_config.dir}"),
            listOf("--workdir", "1"),
            listOf("--verbose"),
            listOf("--no-input"),
        )
        for (path in root.runnablePaths()) {
            for (each in behaviours) {
                behaviour = each
                for (extra in extras) {
                    for (interactive in listOf(false, true)) {
                        val args = valid(path) + extra
                        val result = record(args, interactive, env = mapOf("FIX_DIR" to "\${unfilled}"))
                        val what = "fix $args, interactive=$interactive, when the handler ${each.name}"
                        assertEquals(each.status to each.stdout, result.status to result.stdout, what)
                        // The notices were there to be misplaced: the fixture isn't passing by printing none.
                        if ("--dir" in extra && "--quiet" !in extra) assertContains(result.stderr, "warning: --dir: ", message = what)
                        if ("--workdir" in extra) assertContains(result.stderr, "warning: --workdir is deprecated", message = what)
                        if (path.last() === run && "--quiet" !in extra) {
                            assertContains(result.stderr, "warning: 'fix run' is deprecated", message = what)
                        }
                    }
                }
            }
        }
    }
}
