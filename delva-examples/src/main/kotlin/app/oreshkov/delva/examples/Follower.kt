/*
 * A reader beside a long-running writer.
 *
 * A program that only reads a store, such as a report, a dashboard or a one-off query, can run while another process
 * has the store open: openSecondary takes no lock and never writes. It sees what the writer had written when it
 * opened, and refresh catches up with what the writer wrote since. Code written against ReadableJsonStore, or its
 * blocking face, serves the writer and its secondaries alike.
 */
package app.oreshkov.delva.examples

import app.oreshkov.delva.BlockingReadableJsonStore
import app.oreshkov.delva.BlockingSecondaryJsonStore
import app.oreshkov.delva.KeyValueQuery
import kotlin.time.Duration
import kotlin.time.TimeSource

/** How many hits [query] has in [store], whichever kind of store it is. */
fun hitCount(store: BlockingReadableJsonStore, query: KeyValueQuery): Long = store.count(query)

/**
 * Waits until [query] has at least [atLeast] hits in what the store's writer has written, refreshing [reader] every
 * [poll]; returns whether that happened within [timeout]. A refresh that finds nothing new costs a look at the store's
 * metadata, so a short [poll] is cheap.
 */
fun awaitHits(reader: BlockingSecondaryJsonStore, query: KeyValueQuery, atLeast: Long, poll: Duration, timeout: Duration): Boolean {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (true) {
        if (reader.count(query, atLeast) >= atLeast) return true
        if (deadline.hasPassedNow()) return false
        if (!reader.refresh()) Thread.sleep(poll.inWholeMilliseconds)
    }
}
