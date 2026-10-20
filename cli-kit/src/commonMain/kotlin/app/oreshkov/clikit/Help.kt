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
public class HelpRenderer @ExperimentalCliKitApi constructor(
    private val styler: Styler,
    /** The name printed in usage lines; the root command's own name when `null`. */
    private val programName: String?,
    /** The columns the page is wrapped to. [Program] passes the terminal's width, at most 100. */
    private val width: Int,
    /**
     * Where the program reads settings, for an option's `[config: …]` tag and the Configuration section, which lists
     * the keys and [Settings.locations]; neither is shown for a source with no locations, such as [Settings.None].
     */
    private val settings: Settings,
) {
    /** A renderer for a program that reads no [Settings]. */
    public constructor(
        styler: Styler,
        /** The name printed in usage lines; the root command's own name when `null`. */
        programName: String?,
        /** The columns the page is wrapped to. */
        width: Int,
    ) : this(styler, programName, width, Settings.None)

    /**
     * A renderer that wraps to 80 columns, whatever the terminal: the width golden files are rendered at, so they
     * don't depend on the machine.
     */
    public constructor(
        styler: Styler = Styler.Plain,
        /** The name printed in usage lines; the root command's own name when not given. */
        programName: String? = null,
    ) : this(styler, programName, DEFAULT_WIDTH, Settings.None)

    /**
     * A plain renderer at 80 columns, under the root command's name. Declared, not left to the defaults above, so Java
     * keeps the no-argument constructor that a primary constructor with every parameter defaulted would give it.
     */
    public constructor() : this(Styler.Plain, null, DEFAULT_WIDTH, Settings.None)

    /** Whether the program reads settings at all: otherwise an option's key is never read, and help keeps quiet about it. */
    private val configured: Boolean get() = settings.locations.isNotEmpty()

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
            // First, so nobody reads on to learn a command they should stop using.
            if (command.deprecated != null) {
                appendText(styler.bold("Deprecated:") + " " + command.deprecated)
                appendLine()
            }

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
                // A command's first line is its summary; the rest is for its own page. Former names are left out, so
                // nobody learns them.
                appendTable(
                    command.subcommands.map {
                        Entry((listOf(it.name) + it.aliases).joinToString(", "), HelpText.summary(it.help), it.deprecated)
                    },
                )
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

            val environment = environmentFor(path, inherited)
            if (environment.isNotEmpty()) {
                appendLine()
                appendLine(styler.bold("Environment:"))
                appendTable(environment)
            }

            val configuration = configurationFor(path, inherited)
            if (configuration.isNotEmpty()) {
                appendLine()
                appendLine(styler.bold("Configuration:"))
                appendTable(configuration)
                append(INDENT).appendLine("Read from, highest first:")
                settings.locations.forEach { location ->
                    HelpText.wrap(location, width - HANGING.length - INDENT.length).forEachIndexed { i, line ->
                        append(if (i == 0) HANGING else HANGING + INDENT).appendLine(line)
                    }
                }
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

    /**
     * A row of a two-column table: [name] on the left, then [help] and its [tags], each tag kept on one line, then
     * [deprecated], the message of an entry that is, from a line of its own. A tag can't break, so a message of any
     * length can't be one.
     */
    private class Entry(val name: String, val help: String, val tags: List<String>, val deprecated: String?) {
        constructor(name: String, help: String, deprecated: String? = null) :
            this(name, help, listOfNotNull(DEPRECATED.takeIf { deprecated != null }), deprecated)
    }

    private fun entry(argument: Argument): Entry = Entry(argument.name, argument.help, listOfNotNull(choices(argument.choices)), null)

    private fun entry(option: Option): Entry = Entry(
        option.render(),
        option.help,
        listOfNotNull(
            choices(option.choices),
            option.defaultDisplay?.let { "[default: $it]" },
            (option as? Option.Single<*>)?.env?.let { "[env: $it]" },
            (option as? Option.Single<*>)?.config?.takeIf { configured }?.let { "[config: $it]" },
            "(required)".takeIf { option.required },
            DEPRECATED.takeIf { option.deprecated != null },
        ),
        option.deprecated,
    )

    /**
     * The variables of [path], the command's own first, as the option tables list options: for each command, the
     * variables of its [visible] options, then those it declares. Derived from the same declarations the parser reads,
     * so the section can't drift from them; a variable redeclared further down is listed once, as the deepest one says.
     */
    private fun environmentFor(path: List<CommandSpec>, visible: List<Option>): List<Entry> =
        path.asReversed().flatMap { command ->
            val fromOptions = command.options.filter { option -> visible.any { it === option } }.flatMap { it.variables() }
            (fromOptions + command.environment).map { Entry(it.name, it.help) }
        }.distinctBy { it.name }

    /**
     * The configuration keys of [path], in the order [environmentFor] lists variables: for each command, the keys of
     * its [visible] options; none when the program reads no settings.
     */
    private fun configurationFor(path: List<CommandSpec>, visible: List<Option>): List<Entry> {
        if (!configured) return emptyList()
        return path.asReversed().flatMap { command ->
            command.options.filter { option -> visible.any { it === option } }.mapNotNull { option ->
                (option as? Option.Single<*>)?.config?.let { Entry(it, "Same as ${option.long}") }
            }
        }.distinctBy { it.name }
    }

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
            val lines = HelpText.wrap(entry.help, width - margin.length, entry.tags) +
                entry.deprecated?.let { HelpText.wrap(it, width - margin.length) }.orEmpty()
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

/** The tag of a deprecated option or command. */
private const val DEPRECATED = "(deprecated)"

/** Spaces between the two columns of a table. */
private const val GAP = 2

/** The most of the page a table's left column takes. */
private const val LEFT_SHARE_PERCENT = 40
