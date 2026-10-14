package app.oreshkov.delva.internal

import java.lang.management.ManagementFactory

/**
 * The peak of the live heap while it is open, for tests that bound memory. Every [intervalMillis] it runs a full
 * collection and reads the heap in use, which is then what is reachable.
 *
 * What a pool reports after an ordinary collection isn't that: G1 reclaims old-generation garbage only in mixed
 * collections, so between them the figure counts garbage too, and a bound on it fails at random. The forced collections
 * slow the code under test somewhat; [close] takes a last reading.
 */
internal class LiveHeapSampler(private val intervalMillis: Long = 250) : AutoCloseable {
    /** The largest live heap read so far, in bytes. */
    @Volatile
    var peak: Long = 0
        private set

    @Volatile
    private var running = true
    private val memory = ManagementFactory.getMemoryMXBean()
    private val thread = Thread {
        while (running) {
            sample()
            try {
                Thread.sleep(intervalMillis)
            } catch (_: InterruptedException) {
                break
            }
        }
    }.apply {
        name = "live-heap-sampler"
        isDaemon = true
        start()
    }

    private fun sample() {
        System.gc()
        val used = memory.heapMemoryUsage.used
        if (used > peak) peak = used
    }

    override fun close() {
        running = false
        thread.interrupt()
        thread.join()
        sample()
    }
}
