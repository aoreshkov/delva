package app.oreshkov.clikit

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ConvertersTest {

    private fun <T> ok(converter: ValueConverter<T>, raw: String): T {
        val result = converter.convert(raw)
        assertIs<ConversionResult.Ok<T>>(result, "'$raw' -> $result")
        return result.value
    }

    private fun invalid(converter: ValueConverter<*>, raw: String): String {
        val result = converter.convert(raw)
        assertIs<ConversionResult.Invalid>(result, "'$raw' -> $result")
        return result.reason
    }

    private enum class Order { ASCENDING, DESCENDING, NEWEST_FIRST }

    @Test
    fun `integers, longs and ranges`() {
        assertEquals(-3, ok(Converters.int, "-3"))
        assertEquals(5_000_000_000L, ok(Converters.long, "5000000000"))
        assertContains(invalid(Converters.int, "5000000000"), "not an integer")
        assertEquals(1, ok(Converters.positiveInt, "1"))
        assertContains(invalid(Converters.positiveInt, "0"), "1 or more")
        assertContains(invalid(Converters.intRange(1..9), "10"), "1..9")
    }

    @Test
    fun `non-negative ints, positive longs and long ranges`() {
        assertEquals(0, ok(Converters.nonNegativeInt, "0"))
        assertEquals(Int.MAX_VALUE, ok(Converters.nonNegativeInt, "2147483647"))
        assertEquals("-1 is not in 0 or more", invalid(Converters.nonNegativeInt, "-1"))
        assertContains(invalid(Converters.nonNegativeInt, "2147483648"), "not an integer")

        assertEquals(Long.MAX_VALUE, ok(Converters.positiveLong, "9223372036854775807"))
        assertEquals("0 is not in 1 or more", invalid(Converters.positiveLong, "0"))
        assertEquals("'9223372036854775808' is not an integer", invalid(Converters.positiveLong, "9223372036854775808"))
        assertEquals("'1e3' is not an integer", invalid(Converters.positiveLong, "1e3"))

        assertEquals(-5L, ok(Converters.longRange(-5L..5_000_000_000L), "-5"))
        assertEquals("5000000001 is not in -5..5000000000", invalid(Converters.longRange(-5L..5_000_000_000L), "5000000001"))
    }

    @Test
    fun `an enum is spelled in lower kebab case, and lists its spellings as choices`() {
        val order = Converters.enum(Order.entries)
        assertEquals(listOf("ascending", "descending", "newest-first"), order.choices)
        assertEquals(Order.NEWEST_FIRST, ok(order, "newest-first"))
        assertContains(invalid(order, "DESCENDING"), "ascending|descending|newest-first")
        assertEquals(Order.ASCENDING, ok(Converters.enum(Order.entries) { it.name.take(3) }, "ASC"))
    }

    @Test
    fun `a choice lists its values, and an open converter lists none`() {
        assertEquals(listOf("a", "b"), Converters.choice("a", "b").choices)
        assertEquals(emptyList(), Converters.string.choices)
    }

    @Test
    fun `byte sizes are powers of 1024, and KB is refused rather than guessed`() {
        assertEquals(4096L, ok(Converters.byteSize, "4096"))
        assertEquals(4096L, ok(Converters.byteSize, "4096B"))
        assertEquals(64L * 1024, ok(Converters.byteSize, "64K"))
        assertEquals(16L shl 20, ok(Converters.byteSize, "16MiB"))
        assertEquals(2L shl 30, ok(Converters.byteSize, "2g"))
        assertEquals(1L shl 40, ok(Converters.byteSize, "1T"))
        invalid(Converters.byteSize, "16KB")
        invalid(Converters.byteSize, "1.5M")
        invalid(Converters.byteSize, "-1")
        assertContains(invalid(Converters.byteSize, "9000000000T"), "too large")
    }

    @Test
    fun `durations are whole units, compounds largest first, and months and years are refused`() {
        assertEquals(90.seconds, ok(Converters.duration, "90s"))
        assertEquals(30.minutes, ok(Converters.duration, "30m"))
        assertEquals(250.milliseconds, ok(Converters.duration, "250ms"))
        assertEquals(14.days, ok(Converters.duration, "2w"))
        assertEquals(1.hours + 30.minutes, ok(Converters.duration, "1h30m"))
        assertEquals(7.days + 2.hours + 3.minutes + 4.seconds + 5.milliseconds, ok(Converters.duration, "1w2h3m4s5ms"))
        assertEquals(Duration.ZERO, ok(Converters.duration, "0s"))

        for (raw in listOf("", "90", "-5m", "+5m", "1.5h", "5 m", "5min", "h", "5S", "1h 30m")) {
            assertContains(invalid(Converters.duration, raw), "is not a duration", message = raw)
        }
        assertContains(invalid(Converters.duration, "30m1h"), "largest first")
        assertContains(invalid(Converters.duration, "1h1h"), "each unit once")
        assertContains(invalid(Converters.duration, "2M"), "no fixed length")
        assertContains(invalid(Converters.duration, "1y"), "no fixed length")
        assertContains(invalid(Converters.duration, "99999999999w"), "too long")
        assertContains(invalid(Converters.duration, "99999999999999999999s"), "too long")
    }

    @Test
    fun `a time point is an instant, epoch milliseconds, or an offset from now`() {
        val now = Instant.parse("2026-09-28T12:00:00Z")
        fun at(raw: String, bare: TimePoint.Direction = TimePoint.Direction.PAST): Instant =
            ok(Converters.timePoint(bare), raw).resolve(now)

        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), at("2026-09-01T00:00:00Z"))
        assertEquals(Instant.parse("2026-08-31T22:00:00Z"), at("2026-09-01T00:00:00+02:00"))
        assertEquals(Instant.fromEpochMilliseconds(1_727_502_733_000), at("1727502733000"))
        assertEquals(Instant.fromEpochMilliseconds(100_000_000_000), at("100000000000"))
        assertEquals(now - 30.days, at("-30d"))
        assertEquals(now + 2.hours, at("+2h"))
        assertEquals(now - 30.days, at("30d"))
        assertEquals(now + 30.days, at("30d", TimePoint.Direction.FUTURE))
        assertEquals(TimePoint.Relative(-(1.hours + 30.minutes)), ok(Converters.timePoint(), "-1h30m"))

        assertContains(invalid(Converters.timePoint(), "1727502733"), "looks like seconds")
        assertContains(invalid(Converters.timePoint(), "99999999999"), "looks like seconds")
        assertContains(invalid(Converters.timePoint(), "99999999999999999999"), "too large")
        assertContains(invalid(Converters.timePoint(), "-30"), "is not a duration")
        assertContains(invalid(Converters.timePoint(), "-1M"), "no fixed length")
        for (raw in listOf("", "yesterday", "2026-09-28", "2026-13-01T00:00:00Z", "30 d")) {
            assertContains(invalid(Converters.timePoint(), raw), "is not a time", message = raw)
        }
    }

    @Test
    fun `a relative time far past the instants that exist resolves to the edge`() {
        val far = ok(Converters.timePoint(), "+99999999w").resolve(Instant.parse("2026-09-28T12:00:00Z"))
        assertTrue(far > Instant.DISTANT_FUTURE, "$far")
    }

    @Test
    fun `typed key=value tells text from raw values, httpie style`() {
        assertEquals(TypedKeyValue("name", "ada", raw = false), ok(Converters.typedKeyValue, "name=ada"))
        assertEquals(TypedKeyValue("age", "36", raw = true), ok(Converters.typedKeyValue, "age:=36"))
        assertEquals(TypedKeyValue("q", "a=b:=c", raw = false), ok(Converters.typedKeyValue, "q=a=b:=c"))
        assertEquals(TypedKeyValue("tags", "[\"x\"]", raw = true), ok(Converters.typedKeyValue, "tags:=[\"x\"]"))
        assertEquals(TypedKeyValue("k", "", raw = false), ok(Converters.typedKeyValue, "k="))
        assertEquals(TypedKeyValue("a:b", "c", raw = false), ok(Converters.typedKeyValue, "a:b=c"))
        for (raw in listOf("=v", ":=v", "novalue", "")) invalid(Converters.typedKeyValue, raw)
    }

    @Test
    fun `unique keys keep the order given, and refuse a key given twice`() {
        val param = Option.Repeated("--param", Converters.keyValue)
        val command = CommandSpec("get", options = listOf(param))
        fun values(vararg args: String) = (Parser(command).parse(args.toList()) as ParseResult.Invocation).values

        assertEquals(
            listOf("b" to ("b" to "1"), "a" to ("a" to "2")),
            values("--param", "b=1", "--param", "a=2").uniqueKeys(param) { it.first }.toList(),
        )
        val failure = assertFailsWith<CommandFailure> { values("--param", "a=1", "--param", "a=2").uniqueKeys(param) { it.first } }
        assertEquals(ExitStatus.USAGE, failure.status)
        assertEquals("--param gives 'a' twice", failure.message)
    }

    @Test
    fun `key=value keeps everything after the first equals sign`() {
        assertEquals("filter" to "a=b", ok(Converters.keyValue, "filter=a=b"))
        assertEquals("k" to "", ok(Converters.keyValue, "k="))
        invalid(Converters.keyValue, "=v")
    }
}
