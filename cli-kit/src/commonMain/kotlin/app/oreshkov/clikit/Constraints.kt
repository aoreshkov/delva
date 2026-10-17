package app.oreshkov.clikit

/** Something a [Constraint] can name: an [Option] or an [Argument], by its handle. */
public sealed interface Operand

/**
 * A rule between a command's options and arguments — "not both", "exactly one of", "this needs that" — declared with
 * the command rather than checked in its handler. It names two or more [operands], each once, none of them required
 * but the option whose value [RequiresValue] checks.
 *
 * The parser checks it once every value is converted, so a command line that breaks it is a usage error with the
 * usage line, reported before the handler has read a setting or opened anything. Help lists it under `Notes:`.
 *
 * An operand is *given* when:
 * - a flag is present;
 * - a single-valued option has a value, from the command line or from its [Option.Single.env] variable;
 * - a repeated option, or a variadic argument, has at least one value;
 * - an argument has a value.
 *
 * A value its converter calls [ConversionResult.Unset] isn't one, and a message about the constraint says what was
 * given for it.
 *
 * A value from the environment counts, and the message names the variable: a variable that silently lost to a
 * conflicting option would be worse than a refusal.
 *
 * A constraint applies to the command that declares it and to every command below it, as that command's options do,
 * and it may name options inherited from an ancestor. [Program] checks, when it is built, that every operand is an
 * option or argument of each command the constraint applies to; the handle it names must be the one that command
 * reads, not one a subcommand redeclared.
 *
 * There is no "implies": what a flag implies is meaning, which belongs to the handler, and its help text says it.
 */
public sealed class Constraint(public val operands: List<Operand>) {
    init {
        require(operands.size >= 2) { "a constraint names at least two operands, not $operands" }
        val twice = operands.groupBy { it }.filterValues { it.size > 1 }.keys
        require(twice.isEmpty()) { "a constraint names $twice twice" }
        // A required option is always given, so a rule about its presence is a mistake; one about its value isn't.
        val required = operands.filter { it.isRequired && !(this is RequiresValue && it === operands[1]) }
        require(required.isEmpty()) {
            "$required ${if (required.size == 1) "is" else "are"} required, so always given; a constraint on that is a mistake"
        }
    }

    /** At most one of [operands] may be given. */
    public class Exclusive(vararg operands: Operand) : Constraint(operands.toList())

    /** Exactly one of [operands] must be given. */
    public class ExactlyOne(vararg operands: Operand) : Constraint(operands.toList())

    /** When [subject] is given, [requirement] must be too. */
    public class Requires(public val subject: Operand, public val requirement: Operand) :
        Constraint(listOf(subject, requirement))

    /**
     * When [subject] is given, [option] must have one of [values]: `--port requires --transport http`.
     *
     * Each of [values] is the text a user would type, converted once, here, by the option's converter, so a value it
     * refuses, or calls [ConversionResult.Unset], is a mistake in the declaration and refused. The command line keeps
     * the constraint when the option's converted value, from the command line or its [Option.Single.env] variable,
     * equals one of them, so the converter's values need a meaningful `equals`: a string, a number, an enum constant.
     *
     * An absent [option] doesn't match, even when the handler's default would: the parser can't see that default.
     * Give the option a variable or make it required rather than count on a default the rule can't check. The option
     * may be required, since the rule is about its value; [subject] may not.
     */
    @ExperimentalCliKitApi
    public class RequiresValue(
        /** The operand that, given, needs [option] to have one of [values]. */
        public val subject: Operand,
        /** The option whose value is checked. */
        public val option: Option.Single<*>,
        vararg values: String,
    ) : Constraint(listOf(subject, option)) {
        /** The values [option] may have, as they are typed, in the order given. */
        public val values: List<String> = values.toList()

        /** [values] converted by [option]'s converter: what a parsed value is compared with. */
        internal val accepted: List<Any?>

        init {
            require(this.values.isNotEmpty()) { "a constraint on the value of $option names at least one value" }
            val twice = this.values.groupBy { it }.filterValues { it.size > 1 }.keys
            require(twice.isEmpty()) { "a constraint on the value of $option names $twice twice" }
            accepted = this.values.map { value ->
                when (val conversion = option.converter.convert(value)) {
                    is ConversionResult.Ok -> conversion.value
                    is ConversionResult.Invalid ->
                        throw IllegalArgumentException("'$value' is not a value of $option: ${conversion.reason}")
                    is ConversionResult.Unset ->
                        throw IllegalArgumentException("'$value' counts as not set for $option, so it can't be required")
                }
            }
        }

        /** `--transport http or sse`: what [subject] requires. */
        internal val needs: String
            get() = "$option " + values.map(::typed).let { typed ->
                if (typed.size == 1) typed[0] else typed.dropLast(1).joinToString(", ") + " or " + typed.last()
            }

        /** [value] as it would be typed, quoted when it couldn't stand alone on a command line. */
        private fun typed(value: String): String =
            if (value.isEmpty() || value.any(Char::isWhitespace)) "'$value'" else value
    }

    /** One line for help: `--pretty and --compact cannot be combined`. */
    internal fun note(): String = when (this) {
        is Exclusive ->
            if (operands.size == 2) "${operands[0]} and ${operands[1]} cannot be combined" else "at most one of ${list(operands)}"
        is ExactlyOne -> "exactly one of ${list(operands)}"
        is Requires -> "$subject requires $requirement"
        is RequiresValue -> "$subject requires $needs"
    }

    /** Why the command line, as [parsed], breaks this constraint, or `null` when it keeps it. */
    internal fun violation(parsed: ParsedOperands): String? {
        val present = operands.filter(parsed::given)
        return when (this) {
            is Exclusive -> if (present.size > 1) combined(present, parsed) else null
            is ExactlyOne -> when (present.size) {
                0 -> "exactly one of ${list(operands)} is required" + absent(operands, parsed)
                1 -> null
                else -> combined(present, parsed)
            }
            is Requires -> if (parsed.given(subject) && !parsed.given(requirement)) {
                "${parsed.spell(subject)} requires $requirement" + absent(listOf(requirement), parsed)
            } else {
                null
            }
            is RequiresValue -> when {
                !parsed.given(subject) -> null
                !parsed.given(option) -> "${parsed.spell(subject)} requires $needs" + absent(listOf(option), parsed)
                parsed.value(option) in accepted -> null
                else -> "${parsed.spell(subject)} requires $needs, but ${parsed.spell(option)} is '${parsed.text(option)}'"
            }
        }
    }

    /** ` (<x> given '…', which counts as not set)` for those of [missing] that were given only unset values. */
    private fun absent(missing: List<Operand>, parsed: ParsedOperands): String =
        missing.mapNotNull { operand -> parsed.whyAbsent(operand)?.let { "$operand $it" } }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("; ", " (", ")")
            .orEmpty()

    override fun toString(): String = "${this::class.simpleName}(${note()})"

    private fun combined(present: List<Operand>, parsed: ParsedOperands): String =
        "${parsed.spell(present[0])} cannot be combined with ${parsed.spell(present[1])}"

    private fun list(operands: List<Operand>): String =
        operands.dropLast(1).joinToString(", ") + " and " + operands.last()
}

/** What the parser made of a command line, as a [Constraint] asks about its operands. */
internal interface ParsedOperands {
    /** Whether [operand] was given, as [Constraint] defines it. */
    fun given(operand: Operand): Boolean

    /** [operand] named in a message, with the variable its value came from. */
    fun spell(operand: Operand): String

    /** Why [operand], which the command line held, counts as absent: `given 'x', which counts as not set`; or `null`. */
    fun whyAbsent(operand: Operand): String?

    /** The converted value of [option], which was given. */
    fun value(option: Option.Single<*>): Any?

    /** The text that [option]'s value, which was given, was converted from. */
    fun text(option: Option.Single<*>): String
}

internal val Operand.isRequired: Boolean
    get() = when (this) {
        is Option -> required
        is Argument -> required
    }
