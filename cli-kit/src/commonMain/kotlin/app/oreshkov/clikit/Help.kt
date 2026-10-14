package app.oreshkov.clikit

/**
 * Help, generated from the command tree.
 *
 * Generated rather than written, because a help page maintained by hand is a help page that
 * describes last month's flags. It is golden-file tested, which is the mechanism that keeps it
 * honest as the surface grows: adding an option changes a committed file, visibly, in the same
 * commit.
 */
public class HelpRenderer(
    private val styler: Styler = Styler.Plain,
    /** The name printed in usage lines; the root command's own name when not given. */
    private val programName: String? = null,
) {
    /** The help page of the last command of [path], the commands from the root down to it. */
    public fun render(path: List<CommandSpec>): String {
        val command = path.last()
        val invocation = invocation(path)
        val inherited = optionsFor(path).filterNot { it.hidden }
        val own = command.options.map { it.long }.toSet()

        return buildString {
            if (command.help.isNotEmpty()) {
                appendLine(command.help)
                appendLine()
            }

            appendLine(styler.bold("Usage:"))
            append("  ").appendLine(synopsis(path))

            if (command.examples.isNotEmpty()) {
                appendLine()
                appendLine(styler.bold("Examples:"))
                // A comment line, then the command: pasted into a shell as it stands, the pair still runs.
                command.examples.forEachIndexed { i, example ->
                    if (i > 0) appendLine()
                    append("  ").appendLine(styler.dim("# " + example.description))
                    append("  ").appendLine(example.commandLine)
                }
            }

            if (command.subcommands.isNotEmpty()) {
                appendLine()
                appendLine(styler.bold("Commands:"))
                appendTable(command.subcommands.map { it.name to it.help })
            }

            if (command.arguments.isNotEmpty()) {
                appendLine()
                appendLine(styler.bold("Arguments:"))
                appendTable(command.arguments.map { it.name to describe(it) })
            }

            // Own options first, then the inherited globals under their own heading. Merging them
            // makes `demo session login --help` look as though it defines `--data-dir`, and then
            // somebody moves it.
            val ownOptions = inherited.filter { it.long in own }
            val globalOptions = inherited.filterNot { it.long in own }

            if (ownOptions.isNotEmpty()) {
                appendLine()
                appendLine(styler.bold("Options:"))
                appendTable(ownOptions.map { it.render() to describe(it) })
            }
            if (globalOptions.isNotEmpty()) {
                appendLine()
                appendLine(styler.bold("Global options:"))
                appendTable(globalOptions.map { it.render() to describe(it) })
            }

            // A constraint between two options belongs to neither, so it gets its own lines rather than a note on each.
            // One that names a hidden option stays hidden with it.
            val notes = path.flatMap { it.constraints }.filter { constraint ->
                constraint.operands.none { it is Option && it.hidden }
            }
            if (notes.isNotEmpty()) {
                appendLine()
                appendLine(styler.bold("Notes:"))
                notes.forEach { append("  ").appendLine(it.note()) }
            }

            if (command.subcommands.isNotEmpty()) {
                appendLine()
                appendLine("Run '$invocation <command> --help' for more on a command.")
            }

            if (command.epilogue != null) {
                appendLine()
                appendLine(command.epilogue)
            }
        }.trimEnd() + "\n"
    }

    private fun describe(argument: Argument): String = buildString {
        append(argument.help)
        if (argument.choices.isNotEmpty()) {
            if (isNotEmpty()) append(' ')
            append("(").append(argument.choices.joinToString("|")).append(")")
        }
    }

    private fun describe(option: Option): String = buildString {
        append(option.help)
        if (option.choices.isNotEmpty()) {
            if (isNotEmpty()) append(' ')
            append("(").append(option.choices.joinToString("|")).append(")")
        }
        if (option.defaultDisplay != null) {
            if (isNotEmpty()) append(' ')
            append("[default: ").append(option.defaultDisplay).append("]")
        }
        if (option is Option.Single<*> && option.env != null) {
            if (isNotEmpty()) append(' ')
            append("[env: ").append(option.env).append("]")
        }
        if (option.required) {
            if (isNotEmpty()) append(' ')
            append("(required)")
        }
    }

    /** The program name, then the name of every command below the root. */
    private fun invocation(path: List<CommandSpec>): String =
        (listOf(programName ?: path.first().name) + path.drop(1).map { it.name }).joinToString(" ")

    /** Two aligned columns, the left one sized to the longest entry. */
    private fun StringBuilder.appendTable(entries: List<Pair<String, String>>) {
        val widest = entries.maxOfOrNull { Styler.displayWidth(it.first) } ?: 0
        entries.forEach { (left, right) ->
            append("  ").append(left)
            if (right.isNotEmpty()) {
                repeat(widest - Styler.displayWidth(left) + 2) { append(' ') }
                append(right)
            }
            appendLine()
        }
    }

    /** The one-line form printed above a usage error, so the reader is not sent to `--help`. */
    public fun usageLine(path: List<CommandSpec>): String = "usage: " + synopsis(path)

    /** `demo request get [options] <endpoint>`: one spelling for the help page and the usage-error line. */
    private fun synopsis(path: List<CommandSpec>): String = buildString {
        val command = path.last()
        append(invocation(path))
        if (optionsFor(path).any { !it.hidden }) append(" [options]")
        if (command.subcommands.isNotEmpty()) append(" <command>")
        command.arguments.forEach {
            append(' ')
            append(if (it.required) "<${it.name}>" else "[${it.name}]")
            if (it is Argument.Variadic<*>) append("...")
        }
    }
}
