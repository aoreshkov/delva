package app.oreshkov.clikit

/** Something a [Constraint] can name: an [Option] or an [Argument], by its handle. */
public sealed interface Operand

/**
 * A rule between a command's options and arguments — "not both", "exactly one of", "this needs that" — declared with
 * the command rather than checked in its handler. It names two or more [operands], each once, none of them required.
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
        val required = operands.filter { it.isRequired }
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

    /** One line for help: `--pretty and --compact cannot be combined`. */
    internal fun note(): String = when (this) {
        is Exclusive ->
            if (operands.size == 2) "${operands[0]} and ${operands[1]} cannot be combined" else "at most one of ${list(operands)}"
        is ExactlyOne -> "exactly one of ${list(operands)}"
        is Requires -> "$subject requires $requirement"
    }

    /**
     * Why the command line breaks this constraint, or `null` when it keeps it. [given] answers whether an operand was
     * given; [spell] names one in a message, with the variable its value came from.
     */
    internal fun violation(given: (Operand) -> Boolean, spell: (Operand) -> String): String? {
        val present = operands.filter(given)
        return when (this) {
            is Exclusive -> if (present.size > 1) combined(present, spell) else null
            is ExactlyOne -> when (present.size) {
                0 -> "exactly one of ${list(operands)} is required"
                1 -> null
                else -> combined(present, spell)
            }
            is Requires -> if (given(subject) && !given(requirement)) "${spell(subject)} requires $requirement" else null
        }
    }

    override fun toString(): String = "${this::class.simpleName}(${note()})"

    private fun combined(present: List<Operand>, spell: (Operand) -> String): String =
        "${spell(present[0])} cannot be combined with ${spell(present[1])}"

    private fun list(operands: List<Operand>): String =
        operands.dropLast(1).joinToString(", ") + " and " + operands.last()
}

internal val Operand.isRequired: Boolean
    get() = when (this) {
        is Option -> required
        is Argument -> required
    }
