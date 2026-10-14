package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.uninterruptibly
import app.oreshkov.delva.internal.lsm.ByteCache
import app.oreshkov.delva.internal.lsm.FragmentSegments
import app.oreshkov.delva.internal.lsm.HiddenDocIds
import app.oreshkov.delva.internal.lsm.KeySegments
import app.oreshkov.delva.internal.lsm.MergeControl
import app.oreshkov.delva.internal.lsm.MergeLimitException
import app.oreshkov.delva.internal.lsm.PostingMerge
import app.oreshkov.delva.internal.lsm.SegmentFormat
import app.oreshkov.delva.internal.lsm.SegmentHandle
import app.oreshkov.delva.internal.lsm.SegmentReader
import app.oreshkov.delva.internal.lsm.SegmentStats
import app.oreshkov.delva.internal.lsm.TypeEntry
import app.oreshkov.delva.internal.lsm.TypeSegments
import app.oreshkov.delva.internal.lsm.Version
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Compaction settings; internal like the other engine options.
 *
 * - [enabled]: false never compacts (flushes still merge fragment segments). Compaction has a thread of its own when
 *   flushes do ([EngineOptions.backgroundFlush]); otherwise it runs right after each flush on the flushing thread, which
 *   keeps simulations deterministic.
 * - [minMergeSegments], [sizeRatio]: a size-tiered merge takes at least [minMergeSegments] adjacent segments, newest
 *   first, each older one no larger than [sizeRatio] × the bytes already taken.
 * - [maxInputBytes]: a size-tiered merge reads at most this many bytes, and segments above it are never merged with
 *   others again; they're only rewritten alone when enough of their documents were deleted.
 * - [deletedRatio]: a segment is rewritten alone once this share of its docId range was aborted or deleted after it
 *   was written.
 * - [tombstoneRatio]: a type segment whose inverted entries are this share tombstones is merged with every older type
 *   segment (within [maxInputBytes]), which drops all of them.
 * - [bytesPerSecond]: compaction writes at most this fast, bursts of one second allowed; 0 = unlimited.
 * - [maxRunBytes], [listMemoryBytes], [readCacheBytes]: memory of a merge — entry bytes per merged posting run, list
 *   bytes kept before spilling, and the block cache the inputs are read through (separate from searches' cache).
 */
internal class CompactionOptions(
    val enabled: Boolean = true,
    val minMergeSegments: Int = 4,
    val sizeRatio: Double = 1.0,
    val maxInputBytes: Long = DEFAULT_MAX_INPUT_BYTES,
    val deletedRatio: Double = 0.2,
    val tombstoneRatio: Double = 0.3,
    val bytesPerSecond: Long = DEFAULT_BYTES_PER_SECOND,
    val maxRunBytes: Int = PostingMerge.DEFAULT_MAX_RUN_BYTES,
    val listMemoryBytes: Int = PostingMerge.DEFAULT_LIST_MEMORY_BYTES,
    val readCacheBytes: Long = DEFAULT_READ_CACHE_BYTES,
) {
    init {
        require(minMergeSegments >= 2 && sizeRatio > 0 && maxInputBytes > 0)
        require(deletedRatio > 0 && tombstoneRatio > 0 && bytesPerSecond >= 0)
        require(maxRunBytes >= 64 && listMemoryBytes >= 1024 && readCacheBytes >= 0)
    }

    companion object {
        const val DEFAULT_MAX_INPUT_BYTES: Long = 1L shl 30
        const val DEFAULT_BYTES_PER_SECOND: Long = 128L shl 20
        const val DEFAULT_READ_CACHE_BYTES: Long = 8L shl 20
    }
}

/**
 * Which segments of one index to compact next; a pure function of the segments, oldest
 * first. In order of preference:
 *
 * 1. **Size-tiered**: from the newest available segment backwards, take older adjacent segments while each is at most
 *    `sizeRatio` × the bytes taken and the total stays within `maxInputBytes`; at least `minMergeSegments` of them.
 *    Flushes append small segments, so segments grow like a binary counter: O(log n) segments, each entry rewritten
 *    O(log n) times.
 * 2. **Deleted ratio**: the newest segment whose share of docIds hidden since it was written reached `deletedRatio`,
 *    alone. Its rewrite records the hidden docIds, so it isn't picked again for them.
 * 3. **Tombstones**: the oldest type segment whose tombstone share reached `tombstoneRatio`, with every older segment,
 *    when they fit in `maxInputBytes`. Nothing is older than the result, so its tombstones all go.
 *
 * Every pick shrinks the segment count or the hidden and tombstone entries, so repeated picks end.
 */
internal object CompactionPolicy {
    enum class Reason { SIZE_TIERED, DELETED, TOMBSTONES }

    /** What the policy knows of one segment. */
    class Candidate(val bytes: Long, val available: Boolean, val hiddenRatio: Double = 0.0, val tombstoneRatio: Double = 0.0)

    /** Segments `[from, to)` to compact. */
    class Pick(val from: Int, val to: Int, val reason: Reason) {
        override fun toString(): String = "Pick($from, $to, $reason)"
    }

    fun pick(segments: List<Candidate>, options: CompactionOptions): Pick? {
        sizeTiered(segments, options)?.let { return it }
        for (i in segments.indices.reversed()) {
            if (segments[i].available && segments[i].hiddenRatio >= options.deletedRatio) return Pick(i, i + 1, Reason.DELETED)
        }
        var bytes = 0L
        for ((i, s) in segments.withIndex()) {
            bytes += s.bytes
            if (!s.available || bytes > options.maxInputBytes) break
            if (s.tombstoneRatio >= options.tombstoneRatio) return Pick(0, i + 1, Reason.TOMBSTONES)
        }
        return null
    }

    private fun sizeTiered(segments: List<Candidate>, options: CompactionOptions): Pick? {
        for (end in segments.indices.reversed()) {
            val newest = segments[end]
            if (!newest.available || newest.bytes > options.maxInputBytes) continue
            var from = end
            var total = newest.bytes
            while (from > 0) {
                val older = segments[from - 1]
                if (!older.available || older.bytes > options.sizeRatio * total || total + older.bytes > options.maxInputBytes) break
                total += older.bytes
                from--
            }
            if (end + 1 - from >= options.minMergeSegments) return Pick(from, end + 1, Reason.SIZE_TIERED)
        }
        return null
    }
}

/**
 * Limits compaction writes to [bytesPerSecond], with bursts of one second: [acquire] waits until the bytes are
 * allowed. Time comes from [clock] and waiting from [sleep], so tests control both. Not thread-safe.
 */
internal class RateLimiter(private val bytesPerSecond: Long, private val clock: Clock, private val sleep: (Long) -> Unit = { LockSupport.parkNanos(it) }) {
    private var available = bytesPerSecond.toDouble()
    private var last = clock.nanoTime()

    /** Nanoseconds [acquire] waited in total. */
    var waitedNanos: Long = 0
        private set

    fun acquire(bytes: Int, control: MergeControl) {
        if (bytesPerSecond <= 0) return
        refill()
        available -= bytes
        while (available < 0) {
            control.checkContinue()
            val nanos = (-available * 1e9 / bytesPerSecond).toLong().coerceIn(1, MAX_SLEEP_NANOS)
            sleep(nanos)
            waitedNanos += nanos
            refill()
        }
    }

    private fun refill() {
        val now = clock.nanoTime()
        available = minOf(bytesPerSecond.toDouble(), available + (now - last) * bytesPerSecond / 1e9)
        last = now
    }

    private companion object {
        const val MAX_SLEEP_NANOS: Long = 50_000_000
    }
}

/** A file whose writes pass a [RateLimiter] first. */
private class ThrottledFileIo(private val file: FileIo, private val limiter: RateLimiter, private val control: MergeControl) : FileIo by file {
    override fun write(position: Long, source: ByteArray, offset: Int, length: Int) {
        limiter.acquire(length, control)
        file.write(position, source, offset, length)
    }
}

/** A compaction was stopped because the store is closing or failed. */
private class CompactionStopped : RuntimeException(null, null, false, false)

/**
 * Compacts the segments of the three indexes: one job at a time picks a run
 * of adjacent segments of one index with [CompactionPolicy], claims them, merges them into one segment written like a
 * flush's (`*.tmp` → sync → rename → directory sync), and [Indexes.commitCompaction] puts it in their place. Every merge
 * leaves out the entries of the docIds hidden in the current MANIFEST's bitmap files ([Indexes.persistedHidden]).
 *
 * - Inputs are read through their own file handles and a private block cache, closed before the commit (so Windows can
 *   delete the inputs once searches release them), and writes pass a [RateLimiter].
 * - A job holds the version it picked from, so the segments older than its run can't change: type merges drop
 *   tombstones by them.
 * - Damage found in an input ([CorruptionException]), or a posting list too large for a segment, abandons the job and
 *   excludes its inputs from later jobs; searches still report the damage. Any other error fails the engine.
 * - [request] runs jobs until none is left: on the compaction thread, or right away without one.
 *
 * Thread-safe.
 */
internal class Compactor(private val indexes: Indexes, private val options: CompactionOptions, private val clock: Clock) {
    private val wakeLock = ReentrantLock()
    private val wake = wakeLock.newCondition()
    private var requested = false
    private val runLock = ReentrantLock()
    private var thread: Thread? = null

    @Volatile
    private var stopping = false

    private val control = MergeControl { if (stopping || indexes.health.failed) throw CompactionStopped() }

    /** Compactions committed, by reason; for tests and diagnostics. */
    @Volatile
    var compactions: Long = 0
        private set
    val byReason: LongArray = LongArray(CompactionPolicy.Reason.entries.size)

    /** Bytes compactions read (input segment files) and wrote (output segment files). */
    @Volatile
    var inputBytes: Long = 0
        private set

    @Volatile
    var outputBytes: Long = 0
        private set

    /** Jobs given up because an input was damaged or too large. */
    @Volatile
    var failedJobs: Long = 0
        private set

    /** Nanoseconds writes waited for the rate limit. */
    @Volatile
    var throttledNanos: Long = 0
        private set

    /** Segment bytes written by flushes and compactions per byte written by flushes; 1 before any flush. */
    val writeAmplification: Double
        get() {
            val flushed = indexes.flushedBytes
            return if (flushed == 0L) 1.0 else (flushed + outputBytes).toDouble() / flushed
        }

    /** Starts the compaction thread (with a flush thread) and asks for a first round. */
    fun start() {
        if (!options.enabled) return
        if (indexes.options.backgroundFlush) {
            thread = Thread.ofPlatform().name("delva-compaction").daemon().start {
                while (!stopping) {
                    wakeLock.withLock {
                        while (!requested && !stopping) wake.await()
                        requested = false
                    }
                    if (!stopping) runUntilIdle()
                }
            }
        }
        request()
    }

    /** Asks for compaction: wakes the thread, or compacts now without one. Doesn't block with a thread. */
    fun request() {
        if (!options.enabled || stopping) return
        val t = thread
        if (t == null) {
            if (indexes.options.backgroundFlush) return
            runUntilIdle()
            return
        }
        wakeLock.withLock {
            requested = true
            wake.signalAll()
        }
    }

    /** Runs jobs until the policy picks none (or the store stops); returns how many were committed. */
    fun runUntilIdle(): Int {
        var n = 0
        while (!stopping && !indexes.health.failed && runOnce()) n++
        return n
    }

    /** Stops the thread; a running job is abandoned, its temporary files deleted. */
    fun stop() {
        stopping = true
        wakeLock.withLock { wake.signalAll() }
        thread?.let { uninterruptibly { it.join() } }
        // Waits for a job running on another thread (inline compaction after a flush).
        runLock.withLock {}
    }

    private class Job(val indexKind: Int, val inputs: List<SegmentHandle>, val older: List<SegmentHandle>, val hidden: HiddenDocIds, val version: Version, val reason: CompactionPolicy.Reason)

    /** Picks and runs one job; false when there was none. */
    private fun runOnce(): Boolean = runLock.withLock {
        val job = select() ?: return false
        var committed = false
        try {
            val drops = if (job.indexKind == SegmentFormat.INDEX_FRAGMENT) LaneDrops(indexes.files) else null
            val output = write(job, drops)
            indexes.commitCompaction(job.indexKind, job.inputs, output, drops)
            committed = true
            compactions++
            byReason[job.reason.ordinal]++
            inputBytes += job.inputs.sumOf { it.fileBytes }
            outputBytes += output?.fileBytes ?: 0L
        } catch (_: CompactionStopped) {
            return false
        } catch (e: CorruptionException) {
            giveUp(job, e)
        } catch (e: MergeLimitException) {
            giveUp(job, e)
        } catch (t: Throwable) {
            indexes.health.fail(t)
            return false
        } finally {
            job.version.release()
            if (!committed) indexes.withCommitLock { for (s in job.inputs) s.claimed = false }
        }
        true
    }

    private fun giveUp(job: Job, cause: Exception) {
        for (s in job.inputs) s.compactionFailed = true
        failedJobs++
        System.getLogger("app.oreshkov.delva").log(System.Logger.Level.WARNING, "compaction of ${job.inputs.map { it.path.fileName }} abandoned: $cause")
    }

    /** Claims the inputs of the first index with work, under the commit lock. */
    private fun select(): Job? = indexes.withCommitLock {
        val version = indexes.versions.acquire()
        val hidden = indexes.persistedHidden
        for (kind in INDEX_KINDS) {
            val segments = version.segments(kind)
            if (segments.isEmpty()) continue
            val pick = CompactionPolicy.pick(segments.map { candidate(it, hidden) }, options) ?: continue
            val inputs = segments.subList(pick.from, pick.to).toList()
            for (s in inputs) s.claimed = true
            return@withCommitLock Job(kind, inputs, segments.subList(0, pick.from).toList(), hidden, version, pick.reason)
        }
        version.release()
        null
    }

    private fun candidate(segment: SegmentHandle, hidden: HiddenDocIds): CompactionPolicy.Candidate {
        val stats = segment.reader.stats
        val range = stats.docIdRange
        val newlyHidden = if (range == 0L) 0L else (hidden.countInRange(stats.minDocId, stats.maxDocId) - stats.hiddenDocIds).coerceAtLeast(0)
        val types = segment.types
        var tombstones = 0L
        var inverted = 0L
        if (types != null) {
            for (i in 0 until types.typeCount) {
                val typeId = types.typeIdAt(i)
                tombstones += types.tombstones(typeId)
                inverted += types.puts(typeId) + types.tombstones(typeId)
            }
        }
        return CompactionPolicy.Candidate(
            segment.fileBytes,
            available = !segment.claimed && !segment.compactionFailed,
            hiddenRatio = if (range == 0L) 0.0 else newlyHidden.toDouble() / range,
            tombstoneRatio = if (inverted == 0L) 0.0 else tombstones.toDouble() / inverted,
        )
    }

    /** Merges the job's inputs into a durable segment file; null when nothing is left of them. [drops] counts fragment entries left out. */
    private fun write(job: Job, drops: LaneDrops?): SegmentHandle? {
        val files = indexes.files
        val io = indexes.io
        val fileNo = files.allocateFileNo()
        val path = files.segmentPath(fileNo, job.indexKind)
        val tmp = Indexes.tmpOf(path)
        val spillPath = path.resolveSibling("${path.fileName}.spill.tmp")
        val cache = ByteCache(options.readCacheBytes)
        val readers = ArrayList<SegmentReader>(job.inputs.size)
        var spill: FileIo? = null
        val limiter = RateLimiter(options.bytesPerSecond, clock)
        var renamed = false
        try {
            for (s in job.inputs) readers += openInput(s, cache)
            io.delete(tmp)
            val stats = io.open(tmp, OpenMode.CREATE_NEW).use { raw ->
                val file = ThrottledFileIo(raw, limiter, control)
                val blockBytes = indexes.options.segmentBlockBytes
                val stats: SegmentStats? = when (job.indexKind) {
                    SegmentFormat.INDEX_POSTING -> PostingMerge(readers, job.hidden, control, {
                        io.delete(spillPath)
                        io.open(spillPath, OpenMode.CREATE_NEW).also { spill = it }
                    }, options.maxRunBytes, options.listMemoryBytes).write(file, blockBytes)
                    SegmentFormat.INDEX_FRAGMENT -> FragmentSegments.merge(readers, file, blockBytes, job.hidden, control, drops)
                    SegmentFormat.INDEX_KEY -> KeySegments.merge(readers, job.hidden, control, file, blockBytes)
                    else -> TypeSegments.merge(readers, olderMayHold(job.older), job.hidden, control, file, blockBytes)
                }
                control.checkContinue()
                raw.force(true)
                stats
            }
            throttledNanos += limiter.waitedNanos
            if (stats == null || stats.entryCount == 0L) return null
            io.atomicRename(tmp, path)
            renamed = true
            io.syncDirectory(files.indexDir)
            return indexes.open(fileNo, job.indexKind, path)
        } finally {
            for (r in readers) runCatching { r.close() }
            spill?.let { file ->
                runCatching { file.close() }
                runCatching { io.delete(spillPath) }
            }
            if (!renamed) runCatching { io.delete(tmp) }
        }
    }

    private fun openInput(segment: SegmentHandle, cache: ByteCache): SegmentReader {
        val file = indexes.io.open(segment.path, OpenMode.READ)
        try {
            return SegmentReader.open(segment.fileNo, file, cache, segment.reader.indexKind)
        } catch (t: Throwable) {
            file.close()
            throw t
        }
    }

    /**
     * Whether a type segment in [older] (the segments before the job's run) could hold the key of [TypeEntry]: its docId
     * range, the type's docId range for an inverted key, and its bloom filter over docIds allow it.
     */
    private fun olderMayHold(older: List<SegmentHandle>): (TypeEntry) -> Boolean = { e ->
        var hash = 0L
        older.any { s ->
            val stats = s.reader.stats
            val types = s.types!!
            e.docId >= stats.minDocId && e.docId <= stats.maxDocId &&
                (e.space == TypeEntry.FORWARD || (types.has(e.typeId) && e.docId >= types.minDocId(e.typeId) && e.docId <= types.maxDocId(e.typeId))) &&
                s.reader.mayContain(if (hash != 0L) hash else TypeSegments.bloomHash(e.docId).also { hash = it })
        }
    }

    private companion object {
        val INDEX_KINDS = intArrayOf(SegmentFormat.INDEX_POSTING, SegmentFormat.INDEX_FRAGMENT, SegmentFormat.INDEX_TYPE, SegmentFormat.INDEX_KEY)
    }
}
