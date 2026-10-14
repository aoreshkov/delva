package app.oreshkov.clikit

import kotlin.time.Instant

/**
 * How a timestamp, a duration or a missing value is written for a human.
 *
 * Timestamps are UTC and second precision. Local time would be friendlier for exactly one operator
 * and ambiguous for every log they ever paste somewhere, and a table whose contents depend on the
 * machine's zone is a table a golden test cannot hold. Machine output keeps epoch milliseconds, so
 * nothing has to be parsed back.
 */
public object Format {

    /** `2026-09-27T12:00:00Z`: the instant truncated to the second, which drops the fraction. */
    public fun instant(epochMillis: Long): String =
        Instant.fromEpochSeconds(epochMillis.floorDiv(1_000L)).toString()

    /**
     * `2026-09-28T05:52:13.897Z`: UTC to the millisecond, always three fraction digits, so the lines of a log that
     * starts each with one stay aligned.
     */
    public fun instantMillis(epochMillis: Long): String {
        val seconds = instant(epochMillis)
        val millis = epochMillis.mod(1_000L).toString().padStart(3, '0')
        return seconds.dropLast(1) + "." + millis + "Z"
    }

    /** `—` rather than `null` or an empty cell: a missing value should look deliberate. */
    public fun optional(value: Any?): String = value?.toString() ?: "—"

    /** [millis] for a person: `850ms` under a second, `2.3s` from one second up, cut to tenths. */
    public fun duration(millis: Long): String =
        if (millis < 1_000) "${millis}ms" else "${millis / 1_000}.${(millis % 1_000) / 100}s"

    /**
     * A size for a reader: `512 B`, `64.0 KiB`, `1.2 GiB` — powers of 1024, one decimal, truncated
     * rather than rounded so a size just under a unit never reads as the unit itself.
     */
    public fun bytes(count: Long): String {
        if (count < 1024) return "$count B"
        var unit = 0
        var scaled = count
        while (scaled >= 1024 * 1024 && unit < BYTE_UNITS.size - 1) {
            scaled /= 1024
            unit++
        }
        val tenths = scaled * 10 / 1024
        return "${tenths / 10}.${tenths % 10} ${BYTE_UNITS[unit]}"
    }

    private val BYTE_UNITS = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")

    /** `0.0`–`1.0` as a percentage, or `—` where there is none to report. */
    public fun ratio(value: Double?): String =
        value?.let { "${(it * 100).toInt()}%" } ?: "—"
}
