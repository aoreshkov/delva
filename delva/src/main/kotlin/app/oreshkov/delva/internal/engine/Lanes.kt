package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.StoreClosedException
import app.oreshkov.delva.internal.log.LogLane
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bytes that inserts may hold together on the caller's threads: each insert's base reservation (its reusable
 * buffers) and what a small insert buffers before it's logged. [tryAcquire] never waits, so a buffering insert that
 * can't get more is promoted instead; [acquire] waits, which bounds how many inserts run at once. Thread-safe.
 */
internal class IngestBudget(val totalBytes: Long, private val health: EngineHealth? = null) {
    private val lock = ReentrantLock()
    private val released = lock.newCondition()
    private var available = totalBytes

    /** Inserts that waited in [acquire]; for tests. */
    @Volatile
    var waits: Long = 0
        private set

    fun tryAcquire(bytes: Long): Boolean = lock.withLock {
        if (available < bytes) return false
        available -= bytes
        true
    }

    /**
     * Takes [bytes], waiting while they aren't available; throws once the engine fails or closes. More than the total
     * is capped at the total, so one insert can always run.
     */
    fun acquire(bytes: Long): Long {
        val wanted = minOf(bytes, totalBytes)
        lock.withLock {
            var waited = false
            while (available < wanted) {
                health?.checkUsable()
                if (!waited) {
                    waited = true
                    waits++
                }
                released.await(WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS)
            }
            available -= wanted
        }
        return wanted
    }

    fun release(bytes: Long) {
        if (bytes == 0L) return
        lock.withLock {
            available += bytes
            released.signalAll()
        }
    }

    val availableBytes: Long get() = lock.withLock { available }

    private companion object {
        /** Waits re-check the engine's state this often, so a close or failure ends them. */
        const val WAIT_SLICE_MILLIS = 50L
    }
}

/** One large lane: its log files, used by one promoted insert at a time. */
internal class LargeLane(val id: Int, val log: LogLane) {
    /** Bytes appended since the lane was last synced. */
    var unsyncedBytes: Long = 0
}

/**
 * The large lanes `L1…Ln`: a promoted insert takes a free lane for its whole life, so at most n
 * large inserts stream at once and each appends to files of its own; the others wait for a lane while holding their
 * buffered bytes, which is the back-pressure. Small inserts never need a lane, so a waiting or slow large insert never
 * stalls them. Thread-safe.
 */
internal class LargeLanes(lanes: List<LargeLane>, private val health: EngineHealth) {
    private val lock = ReentrantLock()
    private val freed = lock.newCondition()
    private val free = ArrayDeque(lanes.sortedBy { it.id })
    private val all = lanes.toList()
    private var closed = false

    val count: Int get() = all.size

    /** Inserts that had to wait for a lane; for tests. */
    @Volatile
    var waits: Long = 0
        private set

    /** Takes a free lane, waiting for one; throws once the engine fails or closes. */
    fun acquire(): LargeLane {
        lock.withLock {
            var waited = false
            while (true) {
                health.checkUsable()
                if (closed) throw StoreClosedException()
                val lane = free.removeFirstOrNull()
                if (lane != null) return lane
                if (!waited) {
                    waited = true
                    waits++
                }
                freed.await(WAIT_SLICE_MILLIS, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun release(lane: LargeLane) = lock.withLock {
        // The lowest free id first, so a store with one large insert at a time keeps using one lane's files.
        val at = free.indexOfFirst { it.id > lane.id }
        if (at < 0) free.addLast(lane) else free.add(at, lane)
        freed.signalAll()
    }

    /** Lanes not held by an insert; for tests. */
    val freeCount: Int get() = lock.withLock { free.size }

    /**
     * Stops handing out lanes and runs [action] on each free one; lanes still held by an insert are skipped (their
     * insert fails, and its BEGIN is aborted at the next open).
     */
    fun close(action: (LargeLane) -> Unit) {
        val idle = lock.withLock {
            closed = true
            free.toList().also { free.clear() }
        }
        for (lane in idle) action(lane)
    }

    private companion object {
        const val WAIT_SLICE_MILLIS = 50L
    }
}
