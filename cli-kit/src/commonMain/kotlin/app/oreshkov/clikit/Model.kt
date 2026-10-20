package app.oreshkov.clikit

/**
 * The command model.
 *
 * Commands are declared once and that single declaration drives parsing, help *and* completions.
 * That one property is what makes a hand-rolled parser maintainable rather than a slow-motion
 * source of drift: there is no second place where the list of options lives, so there is no second
 * place for it to be wrong.
 *
 * Options and arguments are typed handles as well as declarations. The object a command lists is
 * the object its handler reads the value with — `call.values[limit]` is an `Int?` because `limit`
 * is an `Option.Single<Int>` — so a misspelt name is a compile error rather than a silent `null`,
 * and the converter is given once, where the option is declared.
 */

/** A value converter: `String` to `T`, or a message explaining what was wrong with the input. */
public fun interface ValueConverter<out T> {
    /**
     * [raw], the text as given, converted; or [ConversionResult.Invalid] with what is wrong with it; or
     * [ConversionResult.Unset] when the text means "not given".
     */
    public fun convert(raw: String): ConversionResult<T>

    /**
     * The fixed set of values this converter accepts, for help and completions to list; empty when
     * it accepts open-ended text. Declared on the converter so an enumeration is written once.
     */
    public val choices: List<String> get() = emptyList()
}

/** What a [ValueConverter] made of the text it was given. */
public sealed interface ConversionResult<out T> {
    /** The text converted to [value]. */
    public data class Ok<T>(val value: T) : ConversionResult<T>

    /** Why the text was refused, [reason]: a message, not an exception, since a bad `--limit foo` is a usage error. */
    public data class Invalid(val reason: String) : ConversionResult<Nothing>

    /**
     * The text means "not given", such as a configuration template nobody filled in: the parser treats the option or
     * argument as absent, for `required`, for [Constraint]s, for the [Option.Single.env] fallback and for
     * [OptionValues]. A single-valued option given such a value loses any value given before it; a repeated option or
     * a variadic argument drops just that one value; an argument still takes its position, so the ones after it
     * don't shift.
     *
     * [notice], when not `null`, is printed by [Program] as a warning before the command runs, and silenced by
     * [StandardOptions.quiet]; the parser puts the option, argument or variable it came from in front of it. Make
     * converters that answer this with [unsetWhen].
     */
    public class Unset(
        /** What to tell the user about the value, such as `ignoring '${user.dir}': an unfilled template`. */
        public val notice: String? = null,
    ) : ConversionResult<Nothing> {
        override fun equals(other: Any?): Boolean = other is Unset && other.notice == notice

        override fun hashCode(): Int = notice.hashCode()

        override fun toString(): String = "Unset(notice=$notice)"
    }
}

/**
 * The converted value passed through [transform], or the same [ConversionResult.Invalid] or [ConversionResult.Unset]:
 * a converter built on another.
 */
public inline fun <T, R> ConversionResult<T>.map(transform: (T) -> R): ConversionResult<R> = when (this) {
    is ConversionResult.Ok -> ConversionResult.Ok(transform(value))
    is ConversionResult.Invalid -> this
    is ConversionResult.Unset -> this
}

/**
 * This converter, except that a value matching [predicate] is [ConversionResult.Unset], with [notice]'s text for it;
 * `null`, the default, says nothing. The [ValueConverter.choices] stay this converter's.
 *
 * Nothing counts as unset unless a converter says so, since an empty `--prefix ""` can mean something:
 *
 * ```kotlin
 * val unfilled = Regex("""\$\{[^}]*}""")
 * val formsDir = Converters.path.unsetWhen(
 *     { it.isBlank() || unfilled.matches(it.trim()) },
 *     notice = { if (it.isBlank()) null else "ignoring '$it': an unfilled template" },
 * )
 * ```
 */
public fun <T> ValueConverter<T>.unsetWhen(
    predicate: (String) -> Boolean,
    notice: (String) -> String? = { null },
): ValueConverter<T> {
    val converter = this
    return object : ValueConverter<T> {
        override fun convert(raw: String): ConversionResult<T> =
            if (predicate(raw)) ConversionResult.Unset(notice(raw)) else converter.convert(raw)

        override val choices: List<String> get() = converter.choices
    }
}

/**
 * A converted value that may read standard input, such as `-` for a file. Standard input can be read only once, so
 * the parser refuses a command line on which more than one value, across all options and arguments, reads it.
 * Implemented by the kit's own `InputSource`, and by a program's value types that wrap one.
 */
public interface ReadsStandardInput {
    /** Whether this value reads standard input: `true` for `-`, `false` for a file. */
    public val readsStandardInput: Boolean
}

/**
 * A declared option.
 *
 * Identity is the object: two options are the same option only if they are the same instance, which
 * is what lets [OptionValues] be read by handle. A command may redeclare an inherited long name to
 * narrow it; the deepest declaration wins, and its handle is the one that reads the value.
 */
public sealed class Option(
    /** The long name, with its dashes: `--limit`. */
    public val long: String,
    /** The short name, with its dash, such as `-n`, or `null`. */
    public val short: String?,
    /**
     * What help says about it, wrapped as [HelpRenderer] describes, so it can run to paragraphs; empty for none. The
     * completion scripts show its first line.
     */
    public val help: String,
    /** Kept out of `--help` and completions, for a debug flag. */
    public val hidden: Boolean,
    /**
     * The long names it had before it was renamed, with their dashes, such as `--data-dir` for an option now called
     * `--store`. The parser still reads them, with a warning that names [long]; help and completions show only [long],
     * so a script that types an old name keeps working while people learn the new one. A command that narrows the
     * option declares them again, since its declaration replaces the inherited one.
     */
    public val formerNames: List<String>,
    /**
     * What to do instead, such as `use 'tool export' instead`, for an option that is going away; `null` for one that
     * isn't. The parser still reads it, with a warning that gives this; help tags it `(deprecated)` with this on the
     * line below, and completions leave it out.
     */
    public val deprecated: String?,
) : Operand {
    init {
        require(long.startsWith("--") && long.length > 2) { "long option '$long' must start with '--'" }
        require(short == null || (short.length == 2 && short[0] == '-' && short[1] != '-')) {
            "short option '$short' must be a single dash and one character"
        }
        for (former in formerNames) {
            require(former.startsWith("--") && former.length > 2) { "former name '$former' of $long must start with '--'" }
        }
        require(spellings.distinct().size == spellings.size) { "option $long names a spelling twice: $spellings" }
        require(deprecated == null || deprecated.isNotBlank()) { "deprecated option $long needs to say what to do instead" }
    }

    /** Every long name the parser reads it by: [long], then the [formerNames]. */
    internal val spellings: List<String> get() = listOf(long) + formerNames

    /**
     * The warning for [spelling], a name this option was typed by: that it is [deprecated], or that it is a former
     * name; `null` when it's neither. The deprecation says more, so it's the one given when both apply.
     */
    internal fun warning(spelling: String): String? = when {
        deprecated != null -> "$spelling is deprecated: $deprecated"
        spelling in formerNames -> "$spelling is deprecated; use $long"
        else -> null
    }

    /** Whether the option carries a value, as opposed to being a flag. */
    public abstract val takesValue: Boolean

    /** Whether the command line must give it. */
    public abstract val required: Boolean

    internal open val valueName: String? get() = null
    internal open val choices: List<String> get() = emptyList()
    internal open val defaultDisplay: String? get() = null

    /** `--limit <n>` / `-a, --account <name>` — the left column of the help table. */
    public fun render(): String = buildString {
        if (short != null) append(short).append(", ") else append("    ")
        append(long)
        if (takesValue) append(' ').append("<${valueName ?: "value"}>")
    }

    override fun toString(): String = long

    /** Present or absent; never takes a value. */
    public class Flag(
        long: String,
        short: String? = null,
        help: String = "",
        hidden: Boolean = false,
        formerNames: List<String> = emptyList(),
        deprecated: String? = null,
    ) : Option(long, short, help, hidden, formerNames, deprecated) {
        /** The constructor of 0.2.0, before [formerNames] and [deprecated], kept so code compiled against it still links. */
        @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
        public constructor(
            long: String,
            short: String? = null,
            help: String = "",
            hidden: Boolean = false,
        ) : this(long, short, help, hidden, emptyList(), null)

        override val takesValue: Boolean get() = false
        override val required: Boolean get() = false
    }

    /** One value; given twice, the last one wins. */
    public class Single<out T>(
        long: String,
        /** Run on the value at parse time, so a malformed one is a usage error rather than a handler's problem. */
        public val converter: ValueConverter<T>,
        short: String? = null,
        help: String = "",
        override val valueName: String? = null,
        override val required: Boolean = false,
        /** Shown in help as the fallback when the option is omitted; the handler supplies the value itself. */
        override val defaultDisplay: String? = null,
        hidden: Boolean = false,
        /**
         * An environment variable that gives the value when the command line doesn't: the command line wins, then
         * the variable, then the [config] files, then the handler's default. Read through [Program]'s environment, so
         * a test controls it. A variable set to an empty string counts as not set, and so does one [converter] calls
         * [ConversionResult.Unset]. Help shows it in the option's `[env: …]` tag and in the Environment section.
         */
        public val env: String? = null,
        formerNames: List<String> = emptyList(),
        deprecated: String? = null,
        /**
         * The key that gives the value in a configuration file, such as `store` for `store = /srv/store`, when neither
         * the command line nor [env] does: lower case, digits, `-` and `.`. Read through [Program]'s [Settings], the
         * layers highest first, and a value [converter] calls [ConversionResult.Unset] moves on to the next layer.
         * Help shows it in the option's `[config: …]` tag and in the Configuration section, when the program reads
         * any settings at all. Only an option that declares a key can be set from a file, so give none to one that
         * takes a secret, a command to run or code to load.
         */
        public val config: String? = null,
    ) : Option(long, short, help, hidden, formerNames, deprecated) {
        /** The constructor of 0.2.0, before [formerNames] and [deprecated], kept so code compiled against it still links. */
        @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
        public constructor(
            long: String,
            converter: ValueConverter<T>,
            short: String? = null,
            help: String = "",
            valueName: String? = null,
            required: Boolean = false,
            defaultDisplay: String? = null,
            hidden: Boolean = false,
            env: String? = null,
        ) : this(long, converter, short, help, valueName, required, defaultDisplay, hidden, env, emptyList(), null, null)

        override val takesValue: Boolean get() = true
        override val choices: List<String> get() = converter.choices

        init {
            require(env == null || isEnvironmentName(env)) { "environment variable '$env' must be upper case, digits and '_'" }
            require(config == null || isSettingKey(config)) { "configuration key '$config' must be lower case, digits, '-' and '.'" }
            require(!required || deprecated == null) { "option $long can't be both required and deprecated" }
        }
    }

    /**
     * Any number of values, accumulated in order.
     *
     * Here rather than emulated by callers because "the last one wins" is the wrong answer for the
     * options that take it, and a silent one: `-p page=2 -p limit=50` is two parameters, and keeping
     * only the last would answer a different question than the operator asked, without saying so.
     */
    public class Repeated<out T>(
        long: String,
        /** Converts each value. */
        public val converter: ValueConverter<T>,
        short: String? = null,
        help: String = "",
        override val valueName: String? = null,
        /** At least one value must be given. */
        override val required: Boolean = false,
        hidden: Boolean = false,
        formerNames: List<String> = emptyList(),
        deprecated: String? = null,
    ) : Option(long, short, help, hidden, formerNames, deprecated) {
        /** The constructor of 0.2.0, before [formerNames] and [deprecated], kept so code compiled against it still links. */
        @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
        public constructor(
            long: String,
            converter: ValueConverter<T>,
            short: String? = null,
            help: String = "",
            valueName: String? = null,
            required: Boolean = false,
            hidden: Boolean = false,
        ) : this(long, converter, short, help, valueName, required, hidden, emptyList(), null)

        override val takesValue: Boolean get() = true
        override val choices: List<String> get() = converter.choices

        init {
            require(!required || deprecated == null) { "option $long can't be both required and deprecated" }
        }
    }
}

/** A declared positional argument; like [Option], its identity is the object. */
public sealed class Argument(
    /** The name help shows, as `<name>`. */
    public val name: String,
    /** What help says about it, wrapped as [HelpRenderer] describes; empty for none. */
    public val help: String,
    /** Whether the command line must give it. */
    public val required: Boolean,
) : Operand {
    init {
        require(name.isNotBlank()) { "an argument needs a name" }
    }

    /** Run on the value at parse time; its [ValueConverter.choices] are what help and completions list. */
    public abstract val converter: ValueConverter<*>

    internal val choices: List<String> get() = converter.choices

    override fun toString(): String = "<$name>"

    /** An argument that takes one value. */
    public class Single<out T>(
        name: String,
        public override val converter: ValueConverter<T>,
        help: String = "",
        required: Boolean = true,
    ) : Argument(name, help, required)

    /** The last argument may absorb the rest — `store query <path>...` and friends. At least one when [required]. */
    public class Variadic<out T>(
        name: String,
        public override val converter: ValueConverter<T>,
        help: String = "",
        required: Boolean = true,
    ) : Argument(name, help, required)
}

/**
 * An environment variable a command reads for itself rather than as an option's [Option.Single.env], such as
 * `ORACLE_HOME`. Declared on a [CommandSpec] so help lists it in the Environment section; reading it is the
 * handler's business, through [CommandContext.environment].
 */
public class EnvironmentVariable(
    /** The name, upper case, digits and `_`, as for [Option.Single.env]. */
    public val name: String,
    /** What help says about it, wrapped as [HelpRenderer] describes. */
    public val help: String,
) {
    init {
        require(isEnvironmentName(name)) { "environment variable '$name' must be upper case, digits and '_'" }
    }

    override fun toString(): String = name
}

/**
 * The variables help lists for this option: its [Option.Single.env], and those the kit reads for a standard option,
 * such as `NO_COLOR` for [StandardOptions.noColor]. A command may not declare any of them again.
 */
internal fun Option.variables(): List<EnvironmentVariable> =
    listOfNotNull((this as? Option.Single<*>)?.env?.let { EnvironmentVariable(it, "Same as $long") }) +
        StandardOptions.variablesOf(this)

/** The portable spelling of a variable's name, which every shell can export: no lower case, no leading digit. */
internal fun isEnvironmentName(name: String): Boolean = ENVIRONMENT_NAME.matches(name)

private val ENVIRONMENT_NAME = Regex("[A-Z_][A-Z0-9_]*")

/** The spelling of a configuration key, which a file gives on the left of `=`: no upper case, no space, no `=`. */
internal fun isSettingKey(key: String): Boolean = SETTING_KEY.matches(key)

private val SETTING_KEY = Regex("[a-z0-9][a-z0-9.-]*")

/**
 * A command line as help shows it, [commandLine], never wrapped, with a comment saying what it does, [description],
 * wrapped as [HelpRenderer] describes.
 */
public class Example(public val commandLine: String, public val description: String) {
    init {
        require(commandLine.isNotBlank() && '\n' !in commandLine) { "an example is one command line" }
    }
}

/**
 * A node in the command tree: either a group with subcommands, or a leaf that runs.
 *
 * One type for both because a surface can be four levels deep (`demo store index create`), and a
 * separate group type would mean two renderers, two completion walks and two ways to be
 * inconsistent about `--help`.
 *
 * Identity is the object, as for options: a program maps each runnable command to its handler by
 * the instance it declared.
 */
public class CommandSpec(
    /** The word that selects the command; the program's name for the root. */
    public val name: String,
    /**
     * What the command does, at the top of its help page, wrapped as [HelpRenderer] describes. Its first line is the
     * summary that the command list of the command above it and the completion scripts show.
     */
    public val help: String = "",
    /** Its own options; those of the commands above it apply too. */
    public val options: List<Option> = emptyList(),
    /** Its positional arguments, in order. */
    public val arguments: List<Argument> = emptyList(),
    /** The commands below it. */
    public val subcommands: List<CommandSpec> = emptyList(),
    /** `demo session login` logs an account in; the group `demo session` does not run at all. */
    public val runnable: Boolean = subcommands.isEmpty(),
    /** Shown in help right after the usage line: most readers want an example before a list of options. */
    public val examples: List<Example> = emptyList(),
    /** The close of the help page, such as where the documentation and issue tracker are; wrapped as [help] is. */
    public val epilogue: String? = null,
    /**
     * Rules between options and arguments — "not both", "exactly one of", "this needs that" — that the parser checks
     * for this command and every command below it. See [Constraint].
     */
    public val constraints: List<Constraint> = emptyList(),
    /**
     * The environment variables it reads that aren't an option's [Option.Single.env], for help's Environment section,
     * which lists the options' variables too, and those the kit reads for [StandardOptions.noColor] and
     * [StandardOptions.width]. Like options, they apply to the commands below it, and one of those may
     * redeclare a variable to describe it differently.
     */
    public val environment: List<EnvironmentVariable> = emptyList(),
    /**
     * Other words that select it for good, such as `ls` for `list`. The parser reads them as [name], with no warning,
     * and the command list of the command above shows them beside [name]; completions offer only [name].
     */
    public val aliases: List<String> = emptyList(),
    /**
     * The names it had before it was renamed. The parser still reads them, with a warning that names [name]; help and
     * completions show only [name], so a script that types an old name keeps working while people learn the new one.
     */
    public val formerNames: List<String> = emptyList(),
    /**
     * What to do instead, such as `use 'tool export' instead`, for a command that is going away; `null` for one that
     * isn't. The parser still runs it, with a warning that gives this; help tags it `(deprecated)` in the command list
     * and starts its own page with this, and completions leave it out.
     */
    public val deprecated: String? = null,
) {
    /** The constructor of 0.2.0, before [aliases], [formerNames] and [deprecated], kept so code compiled against it still links. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    public constructor(
        name: String,
        help: String = "",
        options: List<Option> = emptyList(),
        arguments: List<Argument> = emptyList(),
        subcommands: List<CommandSpec> = emptyList(),
        runnable: Boolean = subcommands.isEmpty(),
        examples: List<Example> = emptyList(),
        epilogue: String? = null,
        constraints: List<Constraint> = emptyList(),
        environment: List<EnvironmentVariable> = emptyList(),
    ) : this(
        name, help, options, arguments, subcommands, runnable, examples, epilogue, constraints, environment,
        emptyList(), emptyList(), null,
    )

    /** The constructor of 0.1.0, before [environment], kept so code compiled against it still links. */
    @Deprecated("Kept for binary compatibility", level = DeprecationLevel.HIDDEN)
    public constructor(
        name: String,
        help: String = "",
        options: List<Option> = emptyList(),
        arguments: List<Argument> = emptyList(),
        subcommands: List<CommandSpec> = emptyList(),
        runnable: Boolean = subcommands.isEmpty(),
        examples: List<Example> = emptyList(),
        epilogue: String? = null,
        constraints: List<Constraint> = emptyList(),
    ) : this(
        name, help, options, arguments, subcommands, runnable, examples, epilogue, constraints, emptyList(),
        emptyList(), emptyList(), null,
    )

    init {
        require(name.isNotBlank()) { "a command needs a name" }
        require((aliases + formerNames).none { it.isBlank() }) { "command '$name' has a blank alias or former name" }
        require(spellings.distinct().size == spellings.size) { "command '$name' names a spelling twice: $spellings" }
        require(deprecated == null || deprecated.isNotBlank()) { "deprecated command '$name' needs to say what to do instead" }
        // Each word must select one command: with two, the parser would silently take the first.
        val duplicateCommands = subcommands.flatMap { it.spellings }.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicateCommands.isEmpty()) { "command '$name' has more than one subcommand called $duplicateCommands" }
        val duplicateOptions = options.flatMap { it.spellings }.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicateOptions.isEmpty()) { "command '$name' declares $duplicateOptions twice" }
        val duplicateShorts = options.mapNotNull { it.short }.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicateShorts.isEmpty()) { "command '$name' declares $duplicateShorts twice" }
        val duplicateKeys = options.mapNotNull { (it as? Option.Single<*>)?.config }.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicateKeys.isEmpty()) { "command '$name' declares configuration keys $duplicateKeys twice" }
        // An option's variable is listed for it already: declaring it again would describe one variable twice.
        val optionVariables = options.flatMap { it.variables() }.map { it.name }.toSet()
        val duplicateVariables = environment.groupBy { it.name }.filter { (name, declared) ->
            declared.size > 1 || name in optionVariables
        }.keys
        require(duplicateVariables.isEmpty()) { "command '$name' declares environment variables $duplicateVariables twice" }
        val duplicateArguments = arguments.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicateArguments.isEmpty()) { "command '$name' declares arguments $duplicateArguments twice" }
        require(arguments.dropLast(1).none { it is Argument.Variadic<*> }) {
            "command '$name': only the last argument may be variadic"
        }
        require(arguments.zipWithNext().none { (first, second) -> !first.required && second.required }) {
            "command '$name': a required argument cannot follow an optional one"
        }
        require(subcommands.isEmpty() || arguments.isEmpty()) {
            "command '$name' has both subcommands and positional arguments, which cannot be told apart"
        }
        // Arguments aren't inherited, so this command can check its own; options may be, which only Program can see.
        val strangers = constraints.flatMap { it.operands }.filterIsInstance<Argument>().filter { it !in arguments }
        require(strangers.isEmpty()) { "command '$name' constrains $strangers, which are not its arguments" }
    }

    /** The subcommand called [name], or `null`. Only [CommandSpec.name] counts, not an alias or a former name. */
    public fun subcommand(name: String): CommandSpec? = subcommands.firstOrNull { it.name == name }

    /** Every word that selects it: [name], then the [aliases], then the [formerNames]. */
    internal val spellings: List<String> get() = listOf(name) + aliases + formerNames

    /** The subcommand [word] selects, by any of its [spellings], or `null`. */
    internal fun resolve(word: String): CommandSpec? = subcommands.firstOrNull { word in it.spellings }

    /**
     * The warning for selecting it by [spelling] below [parent], the commands from the root down to the one that lists
     * it: that it is [deprecated], or that [spelling] is a former name; `null` when it's neither.
     */
    internal fun warning(spelling: String, parent: List<CommandSpec>): String? {
        val above = commandLine(parent)
        return when {
            deprecated != null -> "'$above $spelling' is deprecated: $deprecated"
            spelling in formerNames -> "'$above $spelling' is deprecated; use '$above $name'"
            else -> null
        }
    }

    override fun toString(): String = "CommandSpec($name)"
}
