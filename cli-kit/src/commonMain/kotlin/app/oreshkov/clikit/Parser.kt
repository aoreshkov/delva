package app.oreshkov.clikit

/**
 * The result of parsing: either something to run, or something to say.
 *
 * **The parser never throws to `main`.** A usage error is an ordinary outcome of running a CLI —
 * far more common than a successful `store index create` — and modelling it as a return value is
 * what lets the whole parser be tested as a pure function over `Array<String>`.
 */
public sealed interface ParseResult {
    /** A command was resolved and its options are valid. */
    public class Invocation internal constructor(
        /** The commands from the root down to the one resolved. */
        public val path: List<CommandSpec>,
        /** The option and argument values, converted. */
        public val values: OptionValues,
        /**
         * What the converters said about values they called [ConversionResult.Unset], each after the option,
         * argument or variable it came from, in command-line order and then the variables'. [Program] prints them
         * as warnings; the parser itself never prints.
         */
        public val notices: List<String> = emptyList(),
    ) : ParseResult {
        /** The command resolved: the last of [path]. */
        public val command: CommandSpec get() = path.last()

        /** `demo store index create` — the invocation as the user typed it, for messages. */
        public fun commandLine(): String = commandLine(path)

        override fun equals(other: Any?): Boolean =
            other is Invocation && other.path == path && other.values == values && other.notices == notices

        override fun hashCode(): Int = (31 * path.hashCode() + values.hashCode()) * 31 + notices.hashCode()

        override fun toString(): String = "Invocation(${commandLine()})"
    }

    /** `--help` at any level: the help of the last command of [path], the commands from the root down to it. */
    public data class Help(val path: List<CommandSpec>) : ParseResult

    /** `--version` at any level. */
    public data object Version : ParseResult

    /**
     * Exit [ExitStatus.USAGE], with a message on stderr.
     *
     * [suggestion] carries the nearest match by edit distance — twenty lines of code, and the
     * single highest-value nicety in any CLI.
     */
    public class UsageError internal constructor(
        /** What is wrong with the command line. */
        public val message: String,
        /** The commands resolved before the error, from the root: the help to point at. */
        public val path: List<CommandSpec>,
        /** The nearest valid word to the one given, or `null`. */
        public val suggestion: String? = null,
    ) : ParseResult {
        override fun equals(other: Any?): Boolean =
            other is UsageError && other.message == message && other.path == path && other.suggestion == suggestion

        override fun hashCode(): Int = (31 * message.hashCode() + path.hashCode()) * 31 + suggestion.hashCode()

        override fun toString(): String =
            "UsageError(message=$message, command=${commandLine(path)}${suggestion?.let { ", suggestion=$it" } ?: ""})"
    }
}

/**
 * Parsed option and argument values, already converted, read by the handles the command declared:
 * `values[limit]` for an `Option.Single<Int>` is an `Int?`.
 *
 * Reading with a handle the resolved command does not declare answers "absent" — `false`, `null` or
 * an empty list — which is also the answer for an option that was simply not given. That is what
 * lets code shared between commands read an option only some of them declare.
 */
public class OptionValues internal constructor(
    private val flags: Set<Option.Flag>,
    private val singles: Map<Option.Single<*>, Any?>,
    private val repeated: Map<Option.Repeated<*>, List<Any?>>,
    private val positionals: Map<Argument, List<Any?>>,
) {
    /** Whether [flag] was given. */
    public operator fun get(flag: Option.Flag): Boolean = flag in flags

    /** The value of [option], from the command line or its environment variable; `null` when it has none. */
    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(option: Option.Single<T>): T? = singles[option] as T?

    /** Every value of [option], in the order given; empty when there are none. */
    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(option: Option.Repeated<T>): List<T> = repeated[option].orEmpty() as List<T>

    /** The value of [argument], or `null` when it was not given. */
    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(argument: Argument.Single<T>): T? = positionals[argument]?.firstOrNull() as T?

    /** Every value of [argument], in the order given; empty when there are none. */
    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(argument: Argument.Variadic<T>): List<T> =
        positionals[argument].orEmpty() as List<T>

    /** An option the command declares `required`; the parser has already refused a command line without it. */
    public fun <T> required(option: Option.Single<T>): T =
        checkNotNull(get(option)) { "$option is not a required option of this command" }

    /** An argument the command declares `required`; the parser has already refused a command line without it. */
    public fun <T> required(argument: Argument.Single<T>): T =
        checkNotNull(get(argument)) { "$argument is not a required argument of this command" }

    override fun toString(): String =
        "OptionValues(flags=$flags, singles=$singles, repeated=$repeated, positionals=$positionals)"
}

/**
 * GNU/POSIX argument parsing, and nothing beyond it.
 *
 * Supported, because these are the forms users already have in their fingers:
 * `--long value`, `--long=value`, `-s value`, `-svalue`, bundled short flags `-abc`, `--` to end
 * option parsing, and a repeated option that accumulates.
 *
 * Not supported, deliberately: abbreviated long options (`--acc` for `--account`), which turn a
 * new option into a breaking change for anyone who was abbreviating; and interspersed options
 * after a subcommand name is expected, which is ambiguity dressed as convenience.
 *
 * An option that is not on the command line takes its [Option.Single.env] variable from [environment],
 * converted and checked like a value typed on the command line.
 *
 * A value its converter calls [ConversionResult.Unset] counts as not given, as that type describes.
 *
 * Once every value is converted, the command line must keep each [Constraint] on its path, and may read standard
 * input through at most one value (see [ReadsStandardInput]); either failure is a usage error like any other.
 */
public class Parser(
    private val root: CommandSpec,
    private val environment: (String) -> String? = { null },
) {
    /** [args], the words after the program name, parsed against the command tree; never throws for bad input. */
    public fun parse(args: Array<String>): ParseResult = parse(args.toList())

    /** [args], the words after the program name, parsed against the command tree; never throws for bad input. */
    public fun parse(args: List<String>): ParseResult {
        val path = mutableListOf(root)
        var index = 0

        // Walk the subcommand tree first. Options are collected per command, and a command's
        // options are only legal once that command has been named — `demo --account x session login`
        // is a mistake worth reporting rather than silently accepting.
        val collected = mutableListOf<String>()
        while (index < args.size) {
            val arg = args[index]
            val current = path.last()
            if (arg == "--") {
                collected += args.subList(index, args.size)
                break
            }
            if (arg.startsWith("-") && arg != "-") {
                collected += arg
                index++
                // A long or short option that takes a value consumes the next token, so that a
                // value which happens to look like a subcommand name is not mistaken for one.
                // `demo store get some/key` is fine; `demo --data-dir store` must not read `store`
                // as a subcommand.
                if (consumesNextToken(path, arg)) {
                    if (index < args.size) collected += args[index]
                    index++
                }
                continue
            }
            val child = current.subcommand(arg)
            if (child != null) {
                path += child
                index++
                continue
            }
            if (current.subcommands.isNotEmpty()) {
                return ParseResult.UsageError(
                    "unknown subcommand '$arg'",
                    path,
                    nearest(arg, current.subcommands.map { it.name }),
                )
            }
            collected += args.subList(index, args.size)
            break
        }

        return parseOptions(path, collected)
    }

    private fun consumesNextToken(path: List<CommandSpec>, arg: String): Boolean {
        val known = optionsFor(path)
        if (arg.startsWith("--")) {
            if ('=' in arg) return false
            return known.firstOrNull { it.long == arg }?.takesValue == true
        }
        // `-svalue` and `-abc` are self-contained; a bare `-s` may take the next token.
        if (arg.length != 2) return false
        return known.firstOrNull { it.short == arg }?.takesValue == true
    }

    /** What has been read so far; the maps are keyed by handle, so [OptionValues] is read by handle too. */
    private class Collected {
        val flags = mutableSetOf<Option.Flag>()
        val singles = mutableMapOf<Option.Single<*>, Any?>()

        /** The text each of [singles] was converted from, for a message to quote what was typed. */
        val singleTexts = mutableMapOf<Option.Single<*>, String>()
        val repeated = mutableMapOf<Option.Repeated<*>, MutableList<Any?>>()

        /** Each positional token, indexed by its place among the tokens, so its notice keeps command-line order. */
        val positionals = mutableListOf<IndexedValue<String>>()

        /** The values that came from an environment variable rather than the command line, for messages to name. */
        val fromEnvironment = mutableSetOf<Option.Single<*>>()

        /**
         * For each operand a converter called [ConversionResult.Unset], what was given for it: `given 'x'`, or
         * `given 'x' from VAR`. An operand left absent names this in its "missing" message, which would otherwise
         * contradict a command line that plainly holds it.
         */
        val unset = mutableMapOf<Operand, String>()

        /** The converters' notices, each with the place of its token; a variable's sorts after every token. */
        val notices = mutableListOf<IndexedValue<String>>()

        fun given(option: Option): Boolean = when (option) {
            is Option.Flag -> option in flags
            is Option.Single<*> -> option in singles
            is Option.Repeated<*> -> option in repeated
        }

        /**
         * Notes that [operand] was given [what], which its converter called unset, and keeps the converter's [notice]
         * with the name of its [source] in front. The token at [at] is [FROM_ENVIRONMENT] for a variable.
         */
        fun unset(operand: Operand, what: String, source: String, notice: String?, at: Int) {
            // A variable is read only after the command line gave nothing usable, and what was typed is what the
            // reader is looking at, so it's the better thing to name.
            if (at == FROM_ENVIRONMENT) unset.getOrPut(operand) { what } else unset[operand] = what
            notice?.let { notices += IndexedValue(at, "$source: $it") }
        }

        /** `given 'x', which counts as not set` for an [operand] given only unset values; `null` otherwise. */
        fun whyAbsent(operand: Operand): String? = unset[operand]?.let { "$it, which counts as not set" }
    }

    @Suppress("ReturnCount")
    private fun parseOptions(path: List<CommandSpec>, tokens: List<String>): ParseResult {
        val command = path.last()
        val known = optionsFor(path)
        val collected = Collected()

        var index = 0
        var optionsEnded = false

        while (index < tokens.size) {
            val token = tokens[index]
            when {
                optionsEnded -> collected.positionals += IndexedValue(index, token)

                token == "--" -> optionsEnded = true

                token == "--help" || token == "-h" -> return ParseResult.Help(path)

                token == "--version" || token == "-V" -> return ParseResult.Version

                token.startsWith("--") -> {
                    val (name, inlineValue) = token.splitInline()
                    val spec = known.firstOrNull { it.long == name }
                        ?: return ParseResult.UsageError(
                            "unknown option '$name'",
                            path,
                            nearest(name, known.map { it.long }),
                        )
                    if (spec is Option.Flag) {
                        if (inlineValue != null) {
                            return ParseResult.UsageError("option '$name' does not take a value", path)
                        }
                        collected.flags += spec
                    } else {
                        val at = index
                        val value = inlineValue ?: tokens.getOrNull(++index)
                            ?: return ParseResult.UsageError("option '$name' requires a value", path)
                        record(spec, value, collected, at)?.let { return ParseResult.UsageError(it, path) }
                    }
                }

                token.startsWith("-") && token != "-" -> {
                    val outcome = parseShortCluster(token, index, known, collected) { tokens.getOrNull(++index) }
                    when (outcome) {
                        is ShortOutcome.Ok -> Unit
                        is ShortOutcome.Help -> return ParseResult.Help(path)
                        is ShortOutcome.Version -> return ParseResult.Version
                        is ShortOutcome.Error -> return ParseResult.UsageError(
                            outcome.message,
                            path,
                            outcome.suggestion,
                        )
                    }
                }

                else -> collected.positionals += IndexedValue(index, token)
            }
            index++
        }

        if (!command.runnable) return ParseResult.Help(path)

        // The command line wins; a variable fills in only what it left out, or gave only an unset value for.
        for (option in known) {
            if (option !is Option.Single<*> || option.env == null || collected.given(option)) continue
            val value = environment(option.env)?.takeIf { it.isNotEmpty() } ?: continue
            when (val conversion = option.converter.convert(value)) {
                is ConversionResult.Ok -> {
                    collected.singles[option] = conversion.value
                    collected.singleTexts[option] = value
                    collected.fromEnvironment += option
                }
                is ConversionResult.Invalid ->
                    return ParseResult.UsageError("invalid value for ${option.env}: ${conversion.reason}", path)
                is ConversionResult.Unset ->
                    collected.unset(option, "given '$value' from ${option.env}", option.env, conversion.notice, FROM_ENVIRONMENT)
            }
        }

        known.firstOrNull { it.required && !collected.given(it) }?.let { option ->
            val why = collected.whyAbsent(option)?.let { " ($it)" }.orEmpty()
            return ParseResult.UsageError("missing required option ${option.long}$why", path)
        }

        val bound = when (val binding = bindPositionals(command, collected)) {
            is Binding.Bound -> binding.values
            is Binding.Failed -> return ParseResult.UsageError(binding.message, path)
        }

        violation(path, collected, bound)?.let { return ParseResult.UsageError(it, path) }

        val stdinReaders = (collected.singles.values + collected.repeated.values.flatten() + bound.values.flatten())
            .count { (it as? ReadsStandardInput)?.readsStandardInput == true }
        if (stdinReaders > 1) return ParseResult.UsageError("standard input (-) can be read only once", path)

        return ParseResult.Invocation(
            path,
            OptionValues(collected.flags, collected.singles, collected.repeated, bound),
            collected.notices.sortedBy { it.index }.map { it.value },
        )
    }

    /** The first constraint on [path] the command line breaks, as its message; `null` when it keeps them all. */
    private fun violation(path: List<CommandSpec>, collected: Collected, bound: Map<Argument, List<Any?>>): String? {
        val parsed = object : ParsedOperands {
            override fun given(operand: Operand): Boolean = when (operand) {
                is Option -> collected.given(operand)
                is Argument -> !bound[operand].isNullOrEmpty()
            }

            override fun spell(operand: Operand): String =
                if (operand is Option.Single<*> && operand in collected.fromEnvironment) "$operand (from ${operand.env})" else "$operand"

            override fun whyAbsent(operand: Operand): String? = collected.whyAbsent(operand)

            override fun value(option: Option.Single<*>): Any? = collected.singles.getValue(option)

            override fun text(option: Option.Single<*>): String = collected.singleTexts.getValue(option)
        }
        return path.asSequence().flatMap { it.constraints }.firstNotNullOfOrNull { it.violation(parsed) }
    }

    private sealed interface ShortOutcome {
        data object Ok : ShortOutcome
        data object Help : ShortOutcome
        data object Version : ShortOutcome
        data class Error(val message: String, val suggestion: String? = null) : ShortOutcome
    }

    /**
     * `-abc`, `-s value`, `-svalue`.
     *
     * A cluster is read left to right; the first character that takes a value swallows the rest of
     * the token as that value, and only takes the *next* token when the cluster ends there. That is
     * the POSIX rule, and getting it wrong is how `-p page=2` becomes three unknown flags.
     */
    private inline fun parseShortCluster(
        token: String,
        at: Int,
        known: List<Option>,
        collected: Collected,
        nextToken: () -> String?,
    ): ShortOutcome {
        var position = 1
        while (position < token.length) {
            val short = "-" + token[position]
            if (short == "-h") return ShortOutcome.Help
            if (short == "-V") return ShortOutcome.Version
            val spec = known.firstOrNull { it.short == short }
                ?: return if (token.looksNumeric()) {
                    // `search kv score -5`: POSIX reads `-5` as an option, which is right but baffling.
                    ShortOutcome.Error("unknown option '$token'; put '--' before a value that starts with '-'")
                } else {
                    ShortOutcome.Error("unknown option '$short'", nearest(short, known.mapNotNull { it.short }))
                }
            if (spec is Option.Flag) {
                collected.flags += spec
                position++
                continue
            }
            val rest = token.substring(position + 1)
            val value = rest.ifEmpty { nextToken() }
                ?: return ShortOutcome.Error("option '$short' requires a value")
            record(spec, value, collected, at)?.let { return ShortOutcome.Error(it) }
            return ShortOutcome.Ok
        }
        return ShortOutcome.Ok
    }

    /** Converts and stores one option value, whose option is the token at [at], or returns why it could not. */
    private fun record(spec: Option, value: String, collected: Collected, at: Int): String? {
        val converter = when (spec) {
            is Option.Single<*> -> spec.converter
            is Option.Repeated<*> -> spec.converter
            is Option.Flag -> error("a flag has no value")
        }
        val converted = when (val conversion = converter.convert(value)) {
            is ConversionResult.Ok -> conversion.value
            is ConversionResult.Invalid -> return "invalid value for ${spec.long}: ${conversion.reason}"
            is ConversionResult.Unset -> {
                // As if this occurrence weren't there; for a single value, the earlier ones are overridden all the same.
                if (spec is Option.Single<*>) {
                    collected.singles -= spec
                    collected.singleTexts -= spec
                }
                collected.unset(spec, "given '$value'", spec.long, conversion.notice, at)
                return null
            }
        }
        when (spec) {
            is Option.Single<*> -> {
                collected.singles[spec] = converted
                collected.singleTexts[spec] = value
            }
            is Option.Repeated<*> -> collected.repeated.getOrPut(spec) { mutableListOf() } += converted
            is Option.Flag -> Unit
        }
        return null
    }

    private sealed interface Binding {
        class Bound(val values: Map<Argument, List<Any?>>) : Binding
        class Failed(val message: String) : Binding
    }

    private fun bindPositionals(command: CommandSpec, collected: Collected): Binding {
        val given = collected.positionals
        val bound = mutableMapOf<Argument, List<Any?>>()
        var index = 0
        for (spec in command.arguments) {
            val raw = when {
                spec is Argument.Variadic<*> -> given.drop(index)
                index < given.size -> listOf(given[index])
                else -> emptyList()
            }
            if (raw.isEmpty()) {
                if (spec.required) return Binding.Failed(missingArguments(command))
                continue
            }
            // An unset value still takes its place, so the arguments after it don't shift.
            index += raw.size
            val converted = ArrayList<Any?>(raw.size)
            for ((at, value) in raw) {
                when (val conversion = spec.converter.convert(value)) {
                    is ConversionResult.Ok -> converted += conversion.value
                    is ConversionResult.Invalid ->
                        return Binding.Failed("invalid value for $spec: ${conversion.reason}")
                    is ConversionResult.Unset -> collected.unset(spec, "given '$value'", "$spec", conversion.notice, at)
                }
            }
            if (converted.isEmpty()) {
                if (spec.required) return Binding.Failed("${missingArguments(command)} ($spec ${collected.whyAbsent(spec)})")
                continue
            }
            bound[spec] = converted
        }
        // Extra positionals are an error, not something to ignore: silently dropping one is how a
        // typo'd flag becomes a command that ran against the wrong thing.
        if (index < given.size) {
            val expected = expectedArguments(command)
            return Binding.Failed(
                "unexpected argument '${given[index].value}'" + if (expected.isEmpty()) "" else "; expected $expected",
            )
        }
        return Binding.Bound(bound)
    }

    private fun missingArguments(command: CommandSpec): String = "missing argument; expected ${expectedArguments(command)}"

    private fun expectedArguments(command: CommandSpec): String =
        command.arguments.joinToString(" ") { if (it.required) "<${it.name}>" else "[${it.name}]" }

    private fun String.splitInline(): Pair<String, String?> {
        val equals = indexOf('=')
        return if (equals == -1) this to null else substring(0, equals) to substring(equals + 1)
    }

    private fun String.looksNumeric(): Boolean = drop(1).let { it.isNotEmpty() && it.toDoubleOrNull() != null }
}

/** The place of a value read from an environment variable, which comes after every token of the command line. */
private const val FROM_ENVIRONMENT = Int.MAX_VALUE

/**
 * Every option legal at this point in the tree: the command's own, plus everything inherited from
 * its ancestors.
 *
 * Global flags are declared once on the root — `--json`, `--data-dir`, `--no-color` — and remain
 * legal after a subcommand has been named, which is what `demo --json store query …` and
 * `demo store query --json` both being accepted means. The deepest declaration wins, so a
 * subcommand can narrow one.
 */
internal fun optionsFor(path: List<CommandSpec>): List<Option> =
    path.asReversed().flatMap { it.options }.distinctBy { it.long }

/**
 * The nearest candidate within a small edit distance, or `null`.
 *
 * The threshold rises with length so that `--sesion` finds `--session` while a three-letter typo
 * does not confidently propose an unrelated flag.
 */
internal fun nearest(typed: String, candidates: Collection<String>): String? {
    val budget = when {
        typed.length <= 4 -> 1
        typed.length <= 8 -> 2
        else -> 3
    }
    return candidates
        .map { it to editDistance(typed, it) }
        .filter { it.second <= budget }
        .minByOrNull { it.second }
        ?.first
}

/**
 * Levenshtein with transpositions (optimal string alignment): swapping two neighbouring letters, the commonest typo
 * there is (`inof` for `info`), costs one edit rather than two. Three rows rather than a matrix; inputs here are
 * command and flag names, so this is free.
 */
internal fun editDistance(left: String, right: String): Int {
    if (left == right) return 0
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length

    var beforePrevious = IntArray(right.length + 1)
    var previous = IntArray(right.length + 1) { it }
    var current = IntArray(right.length + 1)
    for (i in 1..left.length) {
        current[0] = i
        for (j in 1..right.length) {
            val substitution = previous[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1
            current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            if (i > 1 && j > 1 && left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1]) {
                current[j] = minOf(current[j], beforePrevious[j - 2] + 1)
            }
        }
        val recycled = beforePrevious
        beforePrevious = previous
        previous = current
        current = recycled
    }
    return previous[right.length]
}
