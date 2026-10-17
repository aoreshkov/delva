package app.oreshkov.clikit

/**
 * Exit statuses: the ones the runner itself produces, and the `sysexits.h` names a program maps its
 * own failures to. Which failure gets which status is the program's decision; the numbers are
 * shared so that every program built on this module means the same thing by them.
 */
public object ExitStatus {
    /** Success. */
    public const val OK: Int = 0

    /**
     * The program's own negative answer: `compare` found a difference, `verify` found damage. Kept
     * free of every other meaning, which is why a usage error is not 1.
     */
    public const val FAILURE: Int = 1

    /**
     * A usage error: the command line did not parse. 2, the POSIX and GNU convention (`diff`, `grep`,
     * `argparse`, Go's `flag`), so it never collides with a program's own [FAILURE].
     */
    public const val USAGE: Int = 2

    /** `EX_DATAERR`: the input data was incorrect — a malformed document, a value a limit refuses. */
    public const val DATA_ERR: Int = 65

    /** `EX_NOINPUT`: an input did not exist or could not be read — a missing file, a missing store. */
    public const val NO_INPUT: Int = 66

    /** `EX_UNAVAILABLE`: a service the program needs is unavailable. */
    public const val UNAVAILABLE: Int = 69

    /**
     * `EX_SOFTWARE`: an internal error. Deliberately far from a program's own small codes, so a
     * scheduler can tell "it failed the way it says it can" from "it broke".
     */
    public const val SOFTWARE: Int = 70

    /** `EX_IOERR`: an error while doing I/O. */
    public const val IO_ERR: Int = 74

    /** `EX_TEMPFAIL`: a temporary failure — something is busy or locked; trying again later may work. */
    public const val TEMP_FAIL: Int = 75

    /** `EX_NOPERM`: insufficient permission. */
    public const val NO_PERM: Int = 77

    /** `EX_CONFIG`: something was configured wrongly — a setting that contradicts what exists. */
    public const val CONFIG: Int = 78

    /** 128 + SIGINT: what a shell reports for a command stopped with Ctrl-C. */
    public const val INTERRUPTED: Int = 130
}

/**
 * The options and commands [Program] reads for itself, declared once so a program's tree lists
 * the same handles the runner reads.
 */
public object StandardOptions {
    /** `--json`: the command writes JSON on stdout instead of text. */
    public val json: Option.Flag = Option.Flag("--json", help = "Machine-readable output on stdout")

    /** Read by [Program] to decide whether an internal error prints its stack trace. */
    public val verbose: Option.Flag = Option.Flag("--verbose", "-v", help = "More detail on stderr")

    /** Read off the raw command line by [noColor], before parsing. */
    public val noColor: Option.Flag = Option.Flag("--no-color", help = "Never emit colour")

    /** Read by [CommandContext.output]: notices, warnings and progress are left out; errors still print. */
    public val quiet: Option.Flag = Option.Flag("--quiet", "-q", help = "Nothing on stderr but errors")

    /** Read by [confirm]: go ahead without asking. */
    public val force: Option.Flag = Option.Flag("--force", "-f", help = "Don't ask for confirmation")

    /** For a command to read: report what it would change, and change nothing. */
    public val dryRun: Option.Flag = Option.Flag("--dry-run", "-n", help = "Show what would change, and change nothing")

    /** Read by [confirm]: never prompt; where a prompt would be needed, fail and say how to go on. */
    public val noInput: Option.Flag = Option.Flag("--no-input", help = "Never prompt")

    /** Read off the raw command line by [widthFrom], before parsing. */
    public val width: Option.Single<Int> = Option.Single(
        "--width",
        Converters.positiveInt,
        help = "Columns to fit output to",
        valueName = "n",
        defaultDisplay = "\$COLUMNS or 80",
    )

    /** The shell argument of [completion]. */
    public val shell: Argument.Single<Shell> =
        Argument.Single("shell", Converters.enum(Shell.entries) { it.id }, "The shell to write the script for")

    /**
     * `completion <shell>`. [Program] answers it without a handler, so it keeps working on a machine
     * where nothing has been configured.
     *
     * The help text deliberately does not spell the `eval "$(prog completion zsh)"` line, tempting
     * as that is: the first line of a help string is embedded into the fish and zsh scripts as a description,
     * and a generated completion script that appears to shell back into the program is exactly what
     * the "no dynamic values" test looks for.
     */
    public val completion: CommandSpec = CommandSpec(
        "completion",
        "Print a shell completion script to stdout",
        arguments = listOf(shell),
    )

    /** `--no-color`, read straight off the raw command line. */
    public fun noColor(args: Array<String>): Boolean = noColor.long in args

    /**
     * `--width`, read straight off the raw command line.
     *
     * Before the parser, deliberately and unavoidably: the terminal has to exist before parsing
     * so that a *parse error* can be rendered at the right width and in the right colours. The
     * alternative is building one terminal to report usage errors and a second one afterwards,
     * which is how the two halves of a program end up disagreeing about whether colour is on.
     *
     * A malformed value is ignored rather than rejected here — the parser sees the same tokens
     * a moment later and reports it properly, with the command's usage line attached.
     */
    public fun widthFrom(args: Array<String>): Int? {
        val flag = width.long
        val index = args.indexOfFirst { it == flag }
        val raw = when {
            index >= 0 -> args.getOrNull(index + 1)
            else -> args.firstOrNull { it.startsWith("$flag=") }?.substringAfter('=')
        } ?: return null
        return (width.converter.convert(raw) as? ConversionResult.Ok)?.value
    }
}

/** What a handler is given: the parsed invocation, where its output goes, and the environment it runs in. */
public class CommandContext(
    /** The command line, parsed. */
    public val invocation: ParseResult.Invocation,
    /** Where the command's output goes. */
    public val io: TerminalIo,
    /**
     * The environment [Program] was given, the one [Option.Single.env] reads: `System::getenv` in a program, a map in
     * a test, so a handler that reads a variable of its own is as easy to test as an option. Declare such a variable in
     * [CommandSpec.environment], so help lists it.
     */
    public val environment: (String) -> String? = { null },
) {
    /** The parsed values, read by the handles the command declared: `values[limit]`. */
    public val values: OptionValues get() = invocation.values

    /** The stream contract, machine-readable when [StandardOptions.json] was given, and quiet under [StandardOptions.quiet]. */
    public val output: Output by lazy {
        Output(
            io,
            machineReadable = values[StandardOptions.json],
            quiet = values[StandardOptions.quiet],
            verbose = values[StandardOptions.verbose],
        )
    }
}

/**
 * Asks [question] before a change that can't be undone, and answers whether to go ahead.
 *
 * Yes with [StandardOptions.force]; otherwise the reader's `y` or `yes` at a terminal. Never a silent yes without
 * one: a script, or [StandardOptions.noInput], gets a usage error telling it to pass `--force` — a destructive command
 * that went ahead because nobody was there to say no is the failure this exists to prevent.
 */
public fun CommandContext.confirm(question: String): Boolean {
    if (values[StandardOptions.force]) return true
    val answer = if (values[StandardOptions.noInput]) null else io.readLine("$question [y/N] ")
    if (answer == null) {
        throw CommandFailure(
            ExitStatus.USAGE,
            "$question — not asking without a terminal; pass ${StandardOptions.force.long} to go ahead",
        )
    }
    return answer.trim().lowercase() in YES
}

private val YES = setOf("y", "yes")

/**
 * A secret — a password, a token — from the variable [env] when it is set and not empty, or else typed at a terminal
 * after [prompt], without echo.
 *
 * Never from a pipe: without a terminal, or with [StandardOptions.noInput], it fails with a usage error that says
 * which variable to set, as [confirm] says to pass `--force`. An empty entry is refused too, since an empty secret is
 * a mistake far more often than a choice.
 *
 * A `CharArray` so the caller can erase it (`fill('\u0000')`) once used; a `String` can't be.
 */
public fun CommandContext.readSecret(prompt: String, env: String? = null): CharArray {
    env?.let(environment)?.takeIf { it.isNotEmpty() }?.let { return it.toCharArray() }
    // "Password: " as a prompt, "Password" in a message.
    val what = prompt.trim().removeSuffix(":")
    val setIt = env?.let { "; set $it" }.orEmpty()
    val typed = if (values[StandardOptions.noInput]) null else io.readPassword(prompt)
    if (typed == null) throw CommandFailure(ExitStatus.USAGE, "$what — not asking without a terminal$setIt")
    if (typed.isEmpty()) throw CommandFailure(ExitStatus.USAGE, "$what — nothing was entered$setIt")
    return typed
}

/**
 * Runs [action] if the process is interrupted (Ctrl-C, SIGTERM, or anything else that ends the JVM) while this command
 * runs, in place of the default `interrupted` notice: how a command that runs until it's stopped stops cleanly.
 *
 * Actions run on the terminal's one shutdown hook, which the first registration installs, newest first, after a progress line is cleared and before stdout is
 * flushed. They run on the hook's thread while the handler's thread is still running, so what they touch must be safe
 * to share, and each must bound its own wait: the process ends once the hook returns. The exit status is the
 * platform's (130 for SIGINT and 143 for SIGTERM on a POSIX shell), since a JVM already shutting down can't choose
 * another.
 *
 * Closing the handle unregisters [action]. On a terminal that isn't an [InterruptRegistry], such as one a program
 * builds for itself, this registers nothing.
 */
public fun CommandContext.onInterrupt(action: () -> Unit): AutoCloseable =
    (io as? InterruptRegistry)?.onInterrupt(action) ?: AutoCloseable {}

/** Runs one command and answers its exit status. What it lets escape is a bug (see [Program]). */
public fun interface CommandHandler {
    /** Runs the command of [context] and answers its exit status, one of [ExitStatus]. */
    public fun run(context: CommandContext): Int
}

/**
 * A failure a command expected and can explain — a missing file, a locked resource, a value the command line could
 * not have caught — reported by [Program] as `error: <message>` on stderr and [status], never as a bug.
 */
public class CommandFailure(
    public val status: Int,
    message: String,
    /**
     * Whether the command's usage line follows the message, as it follows a parse error. By default only for
     * [ExitStatus.USAGE]; `false` for a program whose own exit codes give 2 a meaning that isn't a command-line mistake.
     */
    public val showUsage: Boolean = status == ExitStatus.USAGE,
) : RuntimeException(message) {
    init {
        require(status != ExitStatus.OK) { "a failure needs a non-zero status" }
    }
}

/**
 * Parse, answer what the tree can answer by itself, and hand the rest to the command's handler:
 * the part of `main` that is the same for every command tree.
 *
 * Help, `--version` and usage errors are answered here, and so is [StandardOptions.completion] when
 * the tree includes it. Every other runnable command has a handler in [handlers], which is checked
 * when the program is built rather than when somebody first runs the command without one. The parser's
 * [ParseResult.Invocation.notices] are printed as warnings before the command runs.
 *
 * A handler reports an expected failure by throwing [CommandFailure], or by letting an exception
 * escape that [translate] turns into one — the place a program maps the exceptions of the libraries
 * it drives to its exit statuses, once, rather than in every handler. Anything else that escapes is
 * a bug, reported as one line and [ExitStatus.SOFTWARE] rather than a stack trace and whatever the
 * runtime felt like returning.
 */
public class Program(
    private val root: CommandSpec,
    /** What `--version` prints: `demo 0.1.0`. */
    private val version: String,
    private val handlers: Map<CommandSpec, CommandHandler>,
    /** Turns an exception a handler let escape into the failure it stands for, or `null` for a bug. */
    private val translate: (Exception) -> CommandFailure? = { null },
    /**
     * Where an option's [Option.Single.env] variable is read: `System::getenv` in a real program, a map in a test.
     * No environment at all by default, so a program that wants one says so.
     */
    private val environment: (String) -> String? = { null },
) {
    init {
        val runnable = root.runnablePaths().map { it.last() }.toList()
        val missing = runnable.filter { it !in handlers }
        require(missing.isEmpty()) { "no handler for ${missing.joinToString { it.name }}" }
        val strangers = handlers.keys.filter { it !in runnable }
        require(strangers.isEmpty()) {
            "handlers for ${strangers.joinToString { it.name }}, which the tree does not run"
        }
        root.runnablePaths().forEach(::checkConstraints)
        root.paths().forEach(::checkEnvironment)
    }

    /**
     * No command on [path] declares a variable that an option the command reads already takes its value from: help
     * would describe the one variable twice, in two ways that can drift apart.
     */
    private fun checkEnvironment(path: List<CommandSpec>) {
        val optionVariables = optionsFor(path).mapNotNull { (it as? Option.Single<*>)?.env }.toSet()
        val twice = path.flatMap { it.environment }.map { it.name }.filter { it in optionVariables }.distinct()
        require(twice.isEmpty()) {
            "'${commandLine(path)}' declares the environment variables $twice, which its options already read"
        }
    }

    /**
     * Every operand of every constraint on [path] must be a handle the command reads: an option it declares or inherits,
     * and not one a command further down redeclared, or one of its arguments. Anything else could never be given, and
     * the constraint would hold, or fail, whatever the command line said.
     */
    private fun checkConstraints(path: List<CommandSpec>) {
        val visible: List<Operand> = optionsFor(path) + path.last().arguments
        for (constraint in path.flatMap { it.constraints }) {
            val unseen = constraint.operands.filter { operand -> visible.none { it === operand } }
            require(unseen.isEmpty()) {
                "'${commandLine(path)}' has no $unseen, which its constraint '${constraint.note()}' names"
            }
        }
    }

    /** The program's name, its root command's. */
    internal val name: String get() = root.name

    /**
     * Runs the command line [args] against [io] and answers the exit status; stdout is flushed before it returns. A
     * command that succeeded but whose output stdout didn't take exits with [ExitStatus.IO_ERR] (see
     * [TerminalIo.stdoutState]); one that failed keeps its own status.
     */
    public fun run(args: Array<String>, io: TerminalIo): Int {
        val status = try {
            dispatch(args, io)
        } finally {
            io.flush()
        }
        val state = io.stdoutState()
        if (state == StdoutState.Failed) io.errLine(io.styler.red("error: ") + "could not write to standard output")
        return if (state != StdoutState.Written && status == ExitStatus.OK) ExitStatus.IO_ERR else status
    }

    private fun dispatch(args: Array<String>, io: TerminalIo): Int {
        // Prose past 100 columns is hard to read, however wide the terminal.
        val help = HelpRenderer(io.styler, null, minOf(io.width, MAX_HELP_WIDTH))
        return when (val parsed = Parser(root, environment).parse(args)) {
            is ParseResult.Help -> {
                // Help is the payload when it was asked for, and noise when it follows an error —
                // which is why the error branch below writes to stderr instead.
                io.out(help.render(parsed.path))
                ExitStatus.OK
            }

            ParseResult.Version -> {
                io.outLine(version)
                ExitStatus.OK
            }

            is ParseResult.UsageError -> {
                io.errLine(io.styler.red("error: ") + TerminalText.escape(parsed.message))
                parsed.suggestion?.let { io.errLine("       did you mean ${TerminalText.escape(it)}?") }
                io.errLine(help.usageLine(parsed.path))
                ExitStatus.USAGE
            }

            is ParseResult.Invocation -> invoke(parsed, io)
        }
    }

    private fun invoke(invocation: ParseResult.Invocation, io: TerminalIo): Int = try {
        val context = CommandContext(invocation, io, environment)
        // Before the command runs, so a warning about a value it ignored isn't lost among its own output.
        invocation.notices.forEach(context.output::warn)
        // The one command with no dependency on anything: it walks the same tree the parser does.
        if (invocation.command === StandardOptions.completion) {
            io.out(CompletionGenerator().generate(invocation.values.required(StandardOptions.shell), root))
            ExitStatus.OK
        } else {
            handlers.getValue(invocation.command).run(context)
        }
    } catch (failure: CommandFailure) {
        report(failure, invocation, io)
    } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
        // An Error too, a StackOverflowError say: it is still one line and a defined exit code.
        val failure = if (error is Exception) translated(error) else null
        if (failure != null) {
            report(failure, invocation, io)
        } else {
            // The net under the program's own taxonomy. Anything arriving here is a bug, but a bug
            // must still leave the operator with a line of output and a defined exit code.
            reportBug(error, root.name, invocation.values[StandardOptions.verbose], io)
        }
    }

    /** [translate]'s answer for [error]; `null`, a bug, when [translate] itself throws, which the trace then shows. */
    private fun translated(error: Exception): CommandFailure? = try {
        translate(error)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        error.addSuppressed(e)
        null
    }

    private fun report(failure: CommandFailure, invocation: ParseResult.Invocation, io: TerminalIo): Int {
        io.errLine(io.styler.red("error: ") + TerminalText.escape(failure.message.orEmpty()))
        if (failure.showUsage) io.errLine(HelpRenderer(io.styler).usageLine(invocation.path))
        return failure.status
    }
}

/** The widest a help page [Program] prints is wrapped to. */
private const val MAX_HELP_WIDTH = 100

/**
 * Reports [error], which the program didn't expect, as a bug in [program]: one line, then the stack trace when
 * [verbose] or how to get it; answers [ExitStatus.SOFTWARE].
 */
internal fun reportBug(error: Throwable, program: String, verbose: Boolean, io: TerminalIo): Int {
    io.errLine(io.styler.red("internal error: ") + TerminalText.escape(error.message ?: error::class.simpleName.orEmpty()))
    if (verbose) {
        io.errLine(TerminalText.stackTrace(error))
    } else {
        io.errLine("This is a bug in $program. Re-run with ${StandardOptions.verbose.long} for the stack trace.")
    }
    return ExitStatus.SOFTWARE
}
