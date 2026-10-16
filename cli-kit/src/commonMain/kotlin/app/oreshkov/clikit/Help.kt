package app.oreshkov.clikit

/**
 * Help, generated from the command tree.
 *
 * Generated rather than written, because a help page maintained by hand is a help page that
 * describes last month's flags. It is golden-file tested, which is the mechanism that keeps it
 * honest as the surface grows: adding an option changes a committed file, visibly, in the same
 * commit.
 *
 * The page is wrapped to [width] columns. A `help`, a description or an epilogue is text, not a line: a blank line
 * starts a paragraph, a single line break is kept, and any other run of whitespace is a place to break the line. A
 * word wider than the room left, such as a path or a URL, overflows its line rather than being split. In the
 * two-column tables, continuation lines align under the description, and an entry wider than about 40% of the page
 * starts its description on the next line. The usage line and example command lines are never wrapped, since they
 * are meant to be copied.
 */
public class HelpRenderer(
    private val styler: Styler,
    /** The name printed in usage lines; the root command's own name when `null`. */
    private val programName: String?,
    /** The columns the page is wrapped to. [Program] passes the terminal's width, at most 100. */
    private val width: Int,
) {
    /**
     * A renderer that wraps to 80 columns, whatever the terminal: the width golden files are rendered at, so they
     * don't depend on the machine.
     */
    public constructor(
        styler: Styler = Styler.Plain,
        /** The name printed in usage lines; the root command's own name when not given. */
        programName: String? = null,
    ) : this(styler, programName, DEFAULT_WIDTH)

    /**
     * A plain renderer at 80 columns, under the root command's name. Declared, not left to the defaults above, so Java
     * keeps the no-argument constructor that a primary constructor with every parameter defaulted would give it.
     */
    public constructor() : this(Styler.Plain, null, DEFAULT_WIDTH)

    init {
        require(width > 0) { "help needs a width of at least one column, not $width" }
    }

    /** The help page of the last command of [path], the commands from the root down to it. */
    public fun render(path: List<CommandSpec>): String {
        val command = path.last()
        val invocation = invocation(path)
        val inherited = optionsFor(path).filterNot { it.hidden }
        val own = command.options.map { it.long }.toSet()

        return buildString {
            if (command.help.isNotBlank()) {
                appendText(command.help)
                appendLine()
            }

            appendLine(styler.bold("Usage:"))
            append(INDENT).appendLine(synopsis(path))

            if (command.examples.isNotEmpty()) {
                appendLine()
                appendLine(styler.bold("Examples:"))
                // A comment line, then the command: pasted into a shell as it stands, the pair still runs. So every
                // line of a wrapped description is a comment of its own.
                command.examples.forEachIndexed { i, example ->
                    if (i > 0) appendLine()
                    HelpText.wrap(example.description, width - INDENT.length - COMMENT.length).forEach {
                        append(INDENT).appendLine(styler.dim((COMMENT + it).trimEnd()))
                    }
                    append(INDENT).appendLine(example.commandLine)
                }
            }

            if (command.subcommands.isNotEmpty()) {
                appendLine()
                appendLine(styler.bold("Commands:"))
                // A command's first line is its summary; the rest is for its own page.
                appendTable(command.subcommands.map { Entry(it.name, HelpText.summary(it.help)) })
            }

            if (command.arguments.isNotEmpty()) {
                appendLine()
                appendLine(styler.bold("Arguments:"))
                appendTable(command.arguments.map(::entry))
            }

            // Own options first, then the inherited globals under their own heading. Merging them
            // makes `demo session login --help` look as though it defines `--data-dir`, and then
            // somebody moves it.
            val ownOptions = inherited.filter { it.long in own }
            val globalOptions = inherited.filterNot { it.long in own }

            if (ownOptions.isNotEmpty()) {
                appendLine()
                appendLine(styler.bold("Options:"))
                appendTable(ownOptions.map(::entry))
            }
            if (globalOptions.isNotEmpty()) {
                appendLine()
                appendLine(styler.bold("Global options:"))
                appendTable(globalOptions.map(::entry))
            }

            // A constraint between two options belongs to neither, so it gets its own lines rather than a note on each.
            // One that names a hidden option stays hidden with it.
            val notes = path.flatMap { it.constraints }.filter { constraint ->
                constraint.operands.none { it is Option && it.hidden }
            }
            if (notes.isNotEmpty()) {
                appendLine()
                appendLine(styler.bold("Notes:"))
                // A hanging indent, so a note that wraps doesn't read as two.
                notes.forEach { note ->
                    HelpText.wrap(note.note(), width - HANGING.length).forEachIndexed { i, line ->
                        append(if (i == 0) INDENT else HANGING).appendLine(line)
                    }
                }
            }

            if (command.subcommands.isNotEmpty()) {
                appendLine()
                appendText("Run '$invocation <command> --help' for more on a command.")
            }

            if (command.epilogue != null) {
                appendLine()
                appendText(command.epilogue)
            }
        }.trimEnd() + "\n"
    }

    /** A row of a two-column table: [name] on the left, then [help] and its [tags], each tag kept on one line. */
    private class Entry(val name: String, val help: String, val tags: List<String> = emptyList())

    private fun entry(argument: Argument): Entry = Entry(argument.name, argument.help, listOfNotNull(choices(argument.choices)))

    private fun entry(option: Option): Entry = Entry(
        option.render(),
        option.help,
        listOfNotNull(
            choices(option.choices),
            option.defaultDisplay?.let { "[default: $it]" },
            (option as? Option.Single<*>)?.env?.let { "[env: $it]" },
            "(required)".takeIf { option.required },
        ),
    )

    private fun choices(choices: List<String>): String? = choices.takeIf { it.isNotEmpty() }?.joinToString("|", "(", ")")

    /** The program name, then the name of every command below the root. */
    private fun invocation(path: List<CommandSpec>): String =
        (listOf(programName ?: path.first().name) + path.drop(1).map { it.name }).joinToString(" ")

    /** [text] wrapped to the page, from the left margin. */
    private fun StringBuilder.appendText(text: String) {
        HelpText.wrap(text, width).forEach { appendLine(it) }
    }

    /**
     * Two aligned columns. The left one is as wide as its widest entry that fits in [LEFT_SHARE_PERCENT] of the page; a
     * wider entry has its description start on the next line, so one long option doesn't squeeze every description.
     */
    private fun StringBuilder.appendTable(entries: List<Entry>) {
        val cap = width * LEFT_SHARE_PERCENT / 100
        val left = entries.map { Styler.displayWidth(it.name) }.filter { it <= cap }.maxOrNull() ?: 0
        val margin = " ".repeat(INDENT.length + left + GAP)
        entries.forEach { entry ->
            val name = entry.name
            append(INDENT).append(name)
            val lines = HelpText.wrap(entry.help, width - margin.length, entry.tags)
            val nameWidth = Styler.displayWidth(name)
            if (lines.isNotEmpty() && nameWidth <= left) {
                repeat(left - nameWidth + GAP) { append(' ') }
                append(lines.first())
            }
            appendLine()
            val rest = if (nameWidth <= left) lines.drop(1) else lines
            // A paragraph break is an empty line, with no margin to leave trailing spaces.
            rest.forEach { line -> if (line.isNotEmpty()) append(margin).appendLine(line) else appendLine() }
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

/** The width a [HelpRenderer] built without one wraps to. */
private const val DEFAULT_WIDTH = TableRenderer.DEFAULT_WIDTH

private const val INDENT = "  "
private const val HANGING = "    "
private const val COMMENT = "# "

/** Spaces between the two columns of a table. */
private const val GAP = 2

/** The most of the page a table's left column takes. */
private const val LEFT_SHARE_PERCENT = 40
