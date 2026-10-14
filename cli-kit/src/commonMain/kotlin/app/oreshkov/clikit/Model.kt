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
    /** [raw], the text as given, converted; or [ConversionResult.Invalid] with what is wrong with it. */
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
}

/** The converted value passed through [transform], or the same [ConversionResult.Invalid]: a converter built on another. */
public inline fun <T, R> ConversionResult<T>.map(transform: (T) -> R): ConversionResult<R> = when (this) {
    is ConversionResult.Ok -> ConversionResult.Ok(transform(value))
    is ConversionResult.Invalid -> this
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
    /** One line for help; empty for none. */
    public val help: String,
    /** Kept out of `--help` and completions, for a debug flag. */
    public val hidden: Boolean,
) : Operand {
    init {
        require(long.startsWith("--") && long.length > 2) { "long option '$long' must start with '--'" }
        require(short == null || (short.length == 2 && short[0] == '-' && short[1] != '-')) {
            "short option '$short' must be a single dash and one character"
        }
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
    ) : Option(long, short, help, hidden) {
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
         * the variable, then the handler's default. Read through [Program]'s environment, so a test controls it.
         * A variable set to an empty string counts as not set.
         */
        public val env: String? = null,
    ) : Option(long, short, help, hidden) {
        override val takesValue: Boolean get() = true
        override val choices: List<String> get() = converter.choices

        init {
            require(env == null || ENV_NAME.matches(env)) { "environment variable '$env' must be upper case, digits and '_'" }
        }

        private companion object {
            val ENV_NAME = Regex("[A-Z_][A-Z0-9_]*")
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
    ) : Option(long, short, help, hidden) {
        override val takesValue: Boolean get() = true
        override val choices: List<String> get() = converter.choices
    }
}

/** A declared positional argument; like [Option], its identity is the object. */
public sealed class Argument(
    /** The name help shows, as `<name>`. */
    public val name: String,
    /** One line for help; empty for none. */
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

/** A command line as help shows it, [commandLine], with a line saying what it does, [description]. */
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
    /** What the command does, for help. */
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
    /** A closing paragraph of the help page, such as where the documentation and issue tracker are. */
    public val epilogue: String? = null,
    /**
     * Rules between options and arguments — "not both", "exactly one of", "this needs that" — that the parser checks
     * for this command and every command below it. See [Constraint].
     */
    public val constraints: List<Constraint> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "a command needs a name" }
        val duplicateOptions = options.groupBy { it.long }.filterValues { it.size > 1 }.keys
        require(duplicateOptions.isEmpty()) { "command '$name' declares $duplicateOptions twice" }
        val duplicateShorts = options.mapNotNull { it.short }.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicateShorts.isEmpty()) { "command '$name' declares $duplicateShorts twice" }
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

    /** The subcommand called [name], or `null`. */
    public fun subcommand(name: String): CommandSpec? = subcommands.firstOrNull { it.name == name }

    override fun toString(): String = "CommandSpec($name)"
}
