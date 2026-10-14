package app.oreshkov.clikit

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {

    @Test
    fun `an instant is UTC, to the second, with no fraction`() {
        assertEquals("1970-01-01T00:00:00Z", Format.instant(0))
        assertEquals("2026-09-27T12:34:56Z", Format.instant(1_790_512_496_789))
        // Truncated towards the past, not towards zero.
        assertEquals("1969-12-31T23:59:59Z", Format.instant(-1))
    }

    @Test
    fun `an instant to the millisecond always has three fraction digits`() {
        assertEquals("2026-09-28T05:52:13.897Z", Format.instantMillis(1_790_574_733_897))
        assertEquals("2026-09-28T05:52:13.000Z", Format.instantMillis(1_790_574_733_000))
        assertEquals("2026-09-28T05:52:13.050Z", Format.instantMillis(1_790_574_733_050))
        assertEquals("1970-01-01T00:00:00.000Z", Format.instantMillis(0))
        assertEquals("1969-12-31T23:59:59.999Z", Format.instantMillis(-1))
    }

    @Test
    fun `durations, ratios and missing values`() {
        assertEquals("999ms", Format.duration(999))
        assertEquals("1.2s", Format.duration(1_250))
        assertEquals("42%", Format.ratio(0.425))
        assertEquals("—", Format.ratio(null))
        assertEquals("—", Format.optional(null))
    }

    @Test
    fun `byte sizes are powers of 1024, truncated to one decimal`() {
        assertEquals("0 B", Format.bytes(0))
        assertEquals("1023 B", Format.bytes(1023))
        assertEquals("1.0 KiB", Format.bytes(1024))
        assertEquals("64.0 KiB", Format.bytes(65_536))
        // Just under 1 MiB stays in KiB rather than rounding up to a unit it hasn't reached.
        assertEquals("1023.9 KiB", Format.bytes(1024L * 1024 - 1))
        assertEquals("1.5 GiB", Format.bytes(1_610_612_736))
        assertEquals("7.9 EiB", Format.bytes(Long.MAX_VALUE))
    }
}
