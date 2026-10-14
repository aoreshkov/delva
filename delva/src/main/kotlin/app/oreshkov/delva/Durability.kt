package app.oreshkov.delva

import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/** When acknowledged writes become durable. */
public sealed interface Durability {
    /**
     * Every group of writes is synced to disk before it's acknowledged, so acknowledged writes survive crashes. The
     * default.
     */
    public data object SyncEachBatch : Durability

    /**
     * Writes are acknowledged once written and synced every [interval]; a crash may lose writes acknowledged since the
     * last sync. A document is still either completely present or completely absent after a crash.
     */
    public class Periodic(public val interval: Duration) : Durability {
        init {
            require(interval.isPositive()) { "interval must be positive" }
        }

        override fun equals(other: Any?): Boolean = other is Periodic && other.interval == interval

        override fun hashCode(): Int = interval.hashCode()

        override fun toString(): String = "Periodic($interval)"
    }

    /**
     * Writes aren't synced, except where a document could otherwise be left incomplete; a crash may lose any of them. For
     * tests and bulk loads that can be repeated.
     */
    public data object None : Durability

    public companion object {
        /**
         * [Periodic] with [interval]. `Periodic`'s own constructor takes a `kotlin.time.Duration`, which is a value
         * class and so has a JVM name no Java caller can spell; this factory doesn't.
         */
        @JvmStatic
        public fun periodic(interval: java.time.Duration): Periodic = Periodic(interval.toKotlinDuration())
    }
}
