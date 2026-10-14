package app.oreshkov.clikit

import kotlin.enums.EnumEntries
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** The converters most command lines need; a program writes its own [ValueConverter] for the rest. */
public object Converters {
    /** The text as it was given. */
    public val string: ValueConverter<String> = ValueConverter { ConversionResult.Ok(it) }

    /** A decimal integer that fits an `Int`. */
    public val int: ValueConverter<Int> = ValueConverter { raw ->
        raw.toIntOrNull()?.let { ConversionResult.Ok(it) }
            ?: ConversionResult.Invalid("'$raw' is not an integer")
    }

    /** A decimal integer that fits a `Long`. */
    public val long: ValueConverter<Long> = ValueConverter { raw ->
        raw.toLongOrNull()?.let { ConversionResult.Ok(it) }
            ?: ConversionResult.Invalid("'$raw' is not an integer")
    }

    /** `1` and up: a count, a limit, a width. */
    public val positiveInt: ValueConverter<Int> = intRange(1..Int.MAX_VALUE)

    /** `0` and up, for a setting where `0` means off. */
    public val nonNegativeInt: ValueConverter<Int> = intRange(0..Int.MAX_VALUE)

    /** `1` and up, for a count or an id too large for an `Int`. */
    public val positiveLong: ValueConverter<Long> = longRange(1..Long.MAX_VALUE)

    /** An integer in [range]; the message for one outside it names the range. */
    public fun intRange(range: IntRange): ValueConverter<Int> = ValueConverter { raw ->
        val value = raw.toIntOrNull()
        when {
            value == null -> ConversionResult.Invalid("'$raw' is not an integer")
            value !in range -> ConversionResult.Invalid("$value is not in ${describe(range)}")
            else -> ConversionResult.Ok(value)
        }
    }

    /** A `Long` in [range]; the message for one outside it names the range. */
    public fun longRange(range: LongRange): ValueConverter<Long> = ValueConverter { raw ->
        val value = raw.toLongOrNull()
        when {
            value == null -> ConversionResult.Invalid("'$raw' is not an integer")
            value !in range -> ConversionResult.Invalid("$value is not in ${describe(range)}")
            else -> ConversionResult.Ok(value)
        }
    }

    /** `k=v`, which is what `-p page=2` needs. The value keeps everything after the first `=`. */
    public val keyValue: ValueConverter<Pair<String, String>> = ValueConverter { raw ->
        val index = raw.indexOf('=')
        if (index <= 0) {
            ConversionResult.Invalid("'$raw' is not a key=value pair")
        } else {
            ConversionResult.Ok(raw.substring(0, index) to raw.substring(index + 1))
        }
    }

    /**
     * httpie's pairs: `k=v` for a text value, `k:=v` for a raw one — JSON, a number — that the caller parses as it
     * needs. Split at the first `=`, so the value keeps any `=` of its own; a key can't be empty.
     */
    public val typedKeyValue: ValueConverter<TypedKeyValue> = ValueConverter { raw ->
        val index = raw.indexOf('=')
        val typed = index > 0 && raw[index - 1] == ':'
        val key = if (typed) raw.substring(0, index - 1) else raw.substring(0, index.coerceAtLeast(0))
        if (index < 0 || key.isEmpty()) {
            ConversionResult.Invalid("'$raw' is not a key=value or key:=value pair")
        } else {
            ConversionResult.Ok(TypedKeyValue(key, raw.substring(index + 1), raw = typed))
        }
    }

    /** A fixed enumeration of strings — the kind completions can offer and help can list. */
    public fun choice(vararg allowed: String): ValueConverter<String> = Choice(allowed.toList()) { it }

    /**
     * An enum, spelled on the command line the way [spell] says: by default the constant's name in
     * lower case with `-` for `_`, so `SearchOrder.DESCENDING` is `descending`. The spellings are
     * the converter's [ValueConverter.choices], so help and completions list them without a second
     * declaration.
     */
    public fun <E : Enum<E>> enum(
        entries: EnumEntries<E>,
        spell: (E) -> String = { it.name.lowercase().replace('_', '-') },
    ): ValueConverter<E> {
        val bySpelling = entries.associateBy(spell)
        require(bySpelling.size == entries.size) { "two constants of ${entries.first()::class} spell the same" }
        return Choice(bySpelling.keys.toList()) { bySpelling.getValue(it) }
    }

    /**
     * A size in bytes: `4096`, `64K`, `16MiB`, `2G`. Every unit is a power of 1024, spelled `K`,
     * `M`, `G` or `T`, optionally followed by `iB`; `KB` and `MB` are refused rather than guessed,
     * because half the world reads them as powers of 1000.
     */
    public val byteSize: ValueConverter<Long> = ValueConverter { raw ->
        val match = BYTE_SIZE.matchEntire(raw.trim())
            ?: return@ValueConverter ConversionResult.Invalid("'$raw' is not a size such as 4096, 64K or 16MiB")
        val number = match.groupValues[1].toLongOrNull()
            ?: return@ValueConverter ConversionResult.Invalid("'$raw' is too large")
        val shift = when (match.groupValues[2].uppercase()) {
            "" -> 0
            "K" -> 10
            "M" -> 20
            "G" -> 30
            else -> 40
        }
        if (number > (Long.MAX_VALUE shr shift)) {
            ConversionResult.Invalid("'$raw' is too large")
        } else {
            ConversionResult.Ok(number shl shift)
        }
    }

    private val BYTE_SIZE = Regex("(\\d+)\\s*(?:([KkMmGgTt])(?:iB)?|B?)")

    /**
     * A length of time: `90s`, `30m`, `2h`, `7d`, `1w`, or a compound such as `1h30m`, each unit once and the largest
     * first. Units are `ms`, `s`, `m`, `h`, `d` and `w`; there is no sign and no fraction. Months and years are
     * refused rather than guessed, because they have no fixed length.
     */
    public val duration: ValueConverter<Duration> = ValueConverter(::parseDuration)

    /**
     * A moment, in any of the forms a person reaches for:
     * - an ISO-8601 instant, `2026-09-28T05:52:13Z` or with an offset such as `+02:00`;
     * - milliseconds since the epoch, `1727502733000`, which [Format] also writes. A number of 11 digits or fewer is
     *   refused: as milliseconds it would fall in 1970–1973, so it's almost certainly seconds, and reading it
     *   silently would answer a different question;
     * - an offset from now, `-30d` or `+2h` (a [duration] with a sign); one without a sign, `30d`, points [bare].
     *
     * An offset that starts with `-` is read as a value after an option (`--before -30d`), but as a positional it
     * needs `--` before it, as any value that looks like an option does.
     */
    public fun timePoint(bare: TimePoint.Direction = TimePoint.Direction.PAST): ValueConverter<TimePoint> =
        ValueConverter { raw ->
            when {
                raw.isNotEmpty() && raw.all { it in '0'..'9' } -> epochMillis(raw)
                raw.startsWith('-') -> parseDuration(raw.substring(1)).map { TimePoint.Relative(-it) }
                raw.startsWith('+') -> parseDuration(raw.substring(1)).map { TimePoint.Relative(it) }
                DURATION.matches(raw) -> parseDuration(raw).map {
                    TimePoint.Relative(if (bare == TimePoint.Direction.PAST) -it else it)
                }
                else -> try {
                    ConversionResult.Ok(TimePoint.Absolute(Instant.parse(raw)))
                } catch (e: IllegalArgumentException) {
                    ConversionResult.Invalid(
                        "'$raw' is not a time such as 2026-09-28T05:52:13Z, epoch milliseconds, -30d or +2h",
                    )
                }
            }
        }

    private val DURATION = Regex("(?:\\d+(?:ms|s|m|h|d|w))+")
    private val DURATION_PART = Regex("(\\d+)(ms|s|m|h|d|w)")
    private val CALENDAR_UNIT = Regex("\\d+(?:M|mo|y|Y)")

    /** Largest first: a compound must give them in this order, each once. */
    private val DURATION_UNITS = listOf("w", "d", "h", "m", "s", "ms")

    private fun parseDuration(raw: String): ConversionResult<Duration> {
        if (CALENDAR_UNIT.containsMatchIn(raw)) {
            return ConversionResult.Invalid("'$raw': months and years have no fixed length; give days or weeks")
        }
        if (!DURATION.matches(raw)) {
            return ConversionResult.Invalid("'$raw' is not a duration such as 90s, 30m, 2h, 7d or 1h30m")
        }
        var total = Duration.ZERO
        var previous = -1
        for (part in DURATION_PART.findAll(raw)) {
            val (number, unit) = part.destructured
            val rank = DURATION_UNITS.indexOf(unit)
            if (rank <= previous) return ConversionResult.Invalid("'$raw': give each unit once, the largest first, as in 1h30m")
            previous = rank
            val amount = number.toLongOrNull()?.let { durationOf(it, unit) }
            if (amount == null || amount.isInfinite()) return ConversionResult.Invalid("'$raw' is too long")
            total += amount
        }
        return if (total.isInfinite()) ConversionResult.Invalid("'$raw' is too long") else ConversionResult.Ok(total)
    }

    /** [amount] of [unit], or `null` past what a [Duration] holds; `Long` arithmetic here would overflow silently. */
    private fun durationOf(amount: Long, unit: String): Duration? {
        val millisPerUnit = when (unit) {
            "w" -> 7 * MILLIS_PER_DAY
            "d" -> MILLIS_PER_DAY
            "h" -> 3_600_000L
            "m" -> 60_000L
            "s" -> 1_000L
            else -> 1L
        }
        return if (amount > Long.MAX_VALUE / millisPerUnit) null else (amount * millisPerUnit).milliseconds
    }

    private const val MILLIS_PER_DAY = 86_400_000L

    /** Below this many digits, epoch milliseconds would name a moment before March 1973: surely seconds instead. */
    private const val MIN_EPOCH_MILLIS_DIGITS = 12

    private fun epochMillis(raw: String): ConversionResult<TimePoint> {
        if (raw.length < MIN_EPOCH_MILLIS_DIGITS) {
            return ConversionResult.Invalid(
                "'$raw' looks like seconds since the epoch; give milliseconds (13 digits) or an ISO-8601 time",
            )
        }
        val millis = raw.toLongOrNull() ?: return ConversionResult.Invalid("'$raw' is too large")
        return ConversionResult.Ok(TimePoint.Absolute(Instant.fromEpochMilliseconds(millis)))
    }

    /** `1 or more` for a range open at the top, where the type's maximum stands for "no limit"; `1..10` otherwise. */
    private fun describe(range: IntRange): String =
        if (range.last == Int.MAX_VALUE) "${range.first} or more" else "${range.first}..${range.last}"

    private fun describe(range: LongRange): String =
        if (range.last == Long.MAX_VALUE) "${range.first} or more" else "${range.first}..${range.last}"

    private class Choice<T>(override val choices: List<String>, private val value: (String) -> T) :
        ValueConverter<T> {
        override fun convert(raw: String): ConversionResult<T> =
            if (raw in choices) {
                ConversionResult.Ok(value(raw))
            } else {
                ConversionResult.Invalid("'$raw' is not one of ${choices.joinToString("|")}")
            }
    }
}

/** A pair from [Converters.typedKeyValue]: [value] is text for `k=v`, and for `k:=v` [raw] text the caller parses. */
public class TypedKeyValue internal constructor(
    /** The key, the text before `=` or `:=`. */
    public val key: String,
    /** The text after `=` or `:=`. */
    public val value: String,
    /** Whether the pair was given as `k:=v`, so that [value] is raw text, such as JSON, for the caller to parse. */
    public val raw: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is TypedKeyValue && other.key == key && other.value == value && other.raw == raw

    override fun hashCode(): Int = (31 * key.hashCode() + value.hashCode()) * 31 + raw.hashCode()

    override fun toString(): String = "TypedKeyValue(key=$key, value=$value, raw=$raw)"
}

/**
 * The values of a repeated key–value [option] by key, in the order given, or a usage failure naming a key given
 * twice: `-p page=1 -p page=2` asks two things at once, and keeping either would answer the wrong one.
 */
public fun <T> OptionValues.uniqueKeys(option: Option.Repeated<T>, key: (T) -> String): Map<String, T> {
    val byKey = LinkedHashMap<String, T>()
    for (value in this[option]) {
        val name = key(value)
        if (name in byKey) throw CommandFailure(ExitStatus.USAGE, "$option gives '$name' twice")
        byKey[name] = value
    }
    return byKey
}
