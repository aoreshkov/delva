package app.oreshkov.clikit

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A moment as a command line gives it (see [Converters.timePoint]): an instant, or an offset from whenever the
 * command runs. Kept apart until [resolve], so the handler supplies the clock and a test controls it.
 */
public sealed interface TimePoint {

    /** The instant this names, taking [now] for a [Relative] one; an offset past the instants that exist stops at the edge. */
    public fun resolve(now: Instant): Instant

    /** The [instant] given as `2026-09-28T05:52:13Z`, or as epoch milliseconds. */
    public data class Absolute(val instant: Instant) : TimePoint {
        override fun resolve(now: Instant): Instant = instant
    }

    /** An [offset] from now, given as `-30d` or `+2h`: negative is in the past. */
    public data class Relative(val offset: Duration) : TimePoint {
        override fun resolve(now: Instant): Instant = now + offset
    }

    /** Which way a duration given without a sign points: `--before 30d` means thirty days ago. */
    public enum class Direction {
        /** Before now. */
        PAST,

        /** After now. */
        FUTURE,
    }
}
