package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.DelvaException
import app.oreshkov.delva.internal.bytes.ByteReader
import app.oreshkov.delva.internal.io.uninterruptibly
import app.oreshkov.delva.internal.log.ControlRecords
import app.oreshkov.delva.internal.log.Extents
import app.oreshkov.delva.internal.log.FragmentRecords
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.log.LogLane
import app.oreshkov.delva.internal.log.RecordReader
import app.oreshkov.delva.internal.log.RecordType
import app.oreshkov.delva.internal.lsm.FragmentDrops
import app.oreshkov.delva.internal.lsm.FragmentEntry
import app.oreshkov.delva.internal.lsm.HiddenDocIds
import app.oreshkov.delva.internal.lsm.MergeControl
import app.oreshkov.delva.internal.lsm.Version
import app.oreshkov.delva.internal.store.LogFragmentStore
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Space reclamation settings; internal like the other engine options.
 *
 * - [enabled]: false never reclaims by itself. Reclamation has a thread of its own when flushes do
 *   ([EngineOptions.backgroundFlush]); otherwise it runs only when [Engine.reclaim] asks, because it waits for the writer.
 * - [maxLiveRatio]: a sealed lane file is reclaimed once the share of its record bytes not known to be discarded is
 *   below this.
 * - [bytesPerSecond]: copies are appended at most this fast, bursts of one second allowed; 0 = unlimited.
 * - [maxRelocateFragments], [maxRelocateBytes]: at most this many fragments and copied bytes per RELOCATE record, which
 *   bounds the entries the writer applies at once and the copies a crash can waste.
 */
internal class ReclaimOptions(
    val enabled: Boolean = true,
    val maxLiveRatio: Double = 0.5,
    val bytesPerSecond: Long = DEFAULT_BYTES_PER_SECOND,
    val maxRelocateFragments: Int = DEFAULT_MAX_RELOCATE_FRAGMENTS,
    val maxRelocateBytes: Long = DEFAULT_MAX_RELOCATE_BYTES,
) {
    init {
        require(maxLiveRatio > 0 && maxLiveRatio <= 1 && bytesPerSecond >= 0)
        require(maxRelocateFragments >= 1 && maxRelocateBytes >= 1)
    }

    companion object {
        const val DEFAULT_BYTES_PER_SECOND: Long = 64L shl 20
        const val DEFAULT_MAX_RELOCATE_FRAGMENTS: Int = 16_384
        const val DEFAULT_MAX_RELOCATE_BYTES: Long = 64L shl 20
    }
}

/**
 * Which lane files writes in progress still need as they are, shared by the writer, promoted inserts, recovery
 * and the [Reclaimer]:
 *
 * - the LSN of the last COMMIT or RELOCATE whose extents are in a file: until the MANIFEST's `indexedUpToLsn` covers it,
 *   recovery may replay that record and read the file;
 * - the lowest file each promoted insert in flight may have appended to, and the small-lane file a writer batch is
 *   appending to: their fragments aren't committed yet.
 *
 * Writers record a use before they drop the in-flight mark, so a reader that checks the marks first and the LSNs second
 * never misses both. Thread-safe.
 */
internal class LaneUsage {
    private val lastLsn = ConcurrentHashMap<Long, Long>()
    private val inFlight = ConcurrentHashMap<Long, Long>()

    @Volatile
    private var smallBatchFloor = Long.MAX_VALUE

    /** A COMMIT or RELOCATE at [lsn] names [extents]. */
    fun used(extents: Extents, lsn: Long) {
        for (i in 0 until extents.count) lastLsn.merge(extents.fileNo(i), lsn, ::maxOf)
    }

    /** LSN of the last COMMIT or RELOCATE naming lane file [fileNo] since the store opened; 0 if none. */
    fun lastLsn(fileNo: Long): Long = lastLsn[fileNo] ?: 0

    /** Promoted insert [docId] began; it appends to files numbered [fileNo] or higher. */
    fun begin(docId: Long, fileNo: Long) {
        inFlight[docId] = fileNo
    }

    /** Promoted insert [docId] committed or aborted. */
    fun end(docId: Long) {
        inFlight.remove(docId)
    }

    /** A writer batch appends small inserts to files numbered [fileNo] or higher, until [smallBatchDone]. */
    fun smallBatch(fileNo: Long) {
        smallBatchFloor = fileNo
    }

    fun smallBatchDone() {
        smallBatchFloor = Long.MAX_VALUE
    }

    /** Lowest lane file that uncommitted fragments may be in; files below it only hold ended documents. */
    fun floor(): Long {
        var floor = smallBatchFloor
        for (fileNo in inFlight.values) floor = minOf(floor, fileNo)
        return floor
    }

    fun forget(fileNo: Long) {
        lastLsn.remove(fileNo)
    }
}

/**
 * The lane-file side of a fragment-index flush or merge: entries of reclaimed (no longer listed) lane files are
 * left out, and the record bytes of entries left out because their docId is hidden are counted per file as discarded.
 * Not thread-safe; one per flush or merge.
 */
internal class LaneDrops(private val files: LogFiles) : FragmentDrops {
    private val discarded = HashMap<Long, Long>()
    private var fileNo = -1L
    private var reclaimed = false

    override fun isReclaimed(entry: FragmentEntry): Boolean {
        val no = LogFragmentStore.fileNoOf(entry.location)
        if (no != fileNo) {
            // A file never becomes listed again, so a stale "listed" only keeps an entry that could have gone.
            fileNo = no
            reclaimed = !files.isListedLane(no)
        }
        return reclaimed
    }

    override fun dropped(entry: FragmentEntry) {
        discarded.merge(LogFragmentStore.fileNoOf(entry.location), LogFormat.padded(entry.length).toLong(), Long::plus)
    }

    /** [previous] discarded bytes plus the ones counted here, for the lane files still listed. */
    fun addTo(previous: Map<Long, Long>): Map<Long, Long> {
        if (discarded.isEmpty() && previous.keys.all { files.isListedLane(it) }) return previous
        val result = HashMap<Long, Long>()
        for ((no, bytes) in previous) if (files.isListedLane(no)) result[no] = bytes
        for ((no, bytes) in discarded) if (files.isListedLane(no)) result.merge(no, bytes, Long::plus)
        return result
    }
}

/** Reclamation was stopped because the store is closing or failed. */
private class ReclaimStopped : RuntimeException(null, null, false, false)

/**
 * Reclaims the space of lane files whose documents were deleted or aborted, one file
 * at a time:
 *
 * 1. **Pick**, under the commit lock: the sealed lane file with the smallest live share below
 *    [ReclaimOptions.maxLiveRatio], estimated from its discarded bytes (the records of fragment-index entries that
 *    flushes and compactions dropped because their docIds were hidden, [LaneDrops]). Only files that recovery can do
 *    without qualify: no uncommitted fragments in them ([LaneUsage.floor]) and every COMMIT or RELOCATE naming them at
 *    or below the MANIFEST's `indexedUpToLsn`. The job pins the current version and takes the hidden docIds of the
 *    current MANIFEST's bitmap files.
 * 2. **Copy** the file's records in file order (documents stay contiguous). A fragment is live when its docId isn't in
 *    those hidden docIds and the version's fragment index locates its key at this record; live records are appended
 *    unchanged to the GC lane.
 * 3. **Relocate** in chunks: sync the GC lane, then the writer logs a RELOCATE record, syncs the control log (in every
 *    durability mode) and puts the new locations into the active memtable, where they shadow the old ones.
 * 4. **Retire**: a MANIFEST without the file ([LogFiles.retireLaneFile]), then a new version. The file is deleted once
 *    every version installed before that one is released, since only those can still locate records in it (on
 *    Windows its handle is closed first).
 *
 * A crash between chunks leaves the file listed; relocated chunks are skipped when it's picked again, as the index no
 * longer locates their keys there. Damage in the file abandons the job and excludes the file until the store reopens;
 * reads of the damaged fragments still fail. Writes pass a [RateLimiter].
 *
 * Thread-safe.
 */
internal class Reclaimer(private val engine: Engine, private val options: ReclaimOptions, private val usage: LaneUsage, private val gcLane: LogLane) {
    private val indexes: Indexes get() = engine.indexes
    private val files: LogFiles get() = engine.files
    private val wakeLock = ReentrantLock()
    private val wake = wakeLock.newCondition()
    private var requested = false
    private val runLock = ReentrantLock()
    private var thread: Thread? = null

    @Volatile
    private var stopping = false

    private val control = MergeControl { if (stopping || engine.failed) throw ReclaimStopped() }

    /** Files excluded until reopen because reclaiming them failed; guarded by [runLock]. */
    private val failedFiles = HashSet<Long>()

    /** Sizes of sealed lane files, which never change. */
    private val sizes = ConcurrentHashMap<Long, Long>()

    private class Retired(val fileNo: Long, val fileBytes: Long, val seq: Long)

    /** Retired files waiting for the versions that may read them; guarded by itself. */
    private val retired = ArrayList<Retired>()

    @Volatile
    private var hasRetired = false

    /** Lane files retired, and those of them deleted; for tests and diagnostics. */
    @Volatile
    var retiredFiles: Long = 0
        private set

    @Volatile
    var deletedFiles: Long = 0
        private set

    /** Bytes of the lane files deleted. */
    @Volatile
    var deletedBytes: Long = 0
        private set

    /** Fragments and record bytes copied to the GC lane, and RELOCATE records logged for them. */
    @Volatile
    var relocatedFragments: Long = 0
        private set

    @Volatile
    var relocatedBytes: Long = 0
        private set

    @Volatile
    var relocations: Long = 0
        private set

    /** Jobs given up because the file was damaged. */
    @Volatile
    var failedJobs: Long = 0
        private set

    /** Nanoseconds copies waited for the rate limit. */
    @Volatile
    var throttledNanos: Long = 0
        private set

    /** Starts the reclamation thread when flushes have one, and asks for a first round. */
    fun start() {
        if (!options.enabled || !engine.options.backgroundFlush || !engine.options.startWriterThread) return
        thread = Thread.ofPlatform().name("delva-reclaim").daemon().start {
            while (!stopping) {
                wakeLock.withLock {
                    while (!requested && !stopping) wake.await()
                    requested = false
                }
                if (!stopping) runUntilIdle()
            }
        }
        request()
    }

    /** Wakes the thread; without one, nothing (reclamation then runs only when [runUntilIdle] is called). Doesn't block. */
    fun request() {
        if (thread == null || stopping) return
        wakeLock.withLock {
            requested = true
            wake.signalAll()
        }
    }

    /** Reclaims files until none qualifies (or the store stops); returns how many were retired. */
    fun runUntilIdle(): Int {
        var n = 0
        while (!stopping && !engine.failed) {
            val done = runOnce() ?: break
            if (done) n++
        }
        return n
    }

    /** Stops the thread; a running job is abandoned between records. Retired files not deleted yet are deleted at the next open. */
    fun stop() {
        stopping = true
        wakeLock.withLock { wake.signalAll() }
        thread?.let { uninterruptibly { it.join() } }
        runLock.withLock {}
    }

    private class Job(val fileNo: Long, val fileBytes: Long, val version: Version, val hidden: HiddenDocIds)

    /** Picks and runs one job: true when its file was retired, false when it was abandoned, null when there was none. */
    private fun runOnce(): Boolean? = runLock.withLock {
        val job = try {
            select()
        } catch (e: IOException) {
            engine.fail(e)
            null
        } ?: return null
        try {
            relocate(job)
            retire(job)
            true
        } catch (_: ReclaimStopped) {
            null
        } catch (e: DelvaException) {
            if (e is CorruptionException) {
                giveUp(job, e)
                false
            } else {
                // The store failed or is closing.
                null
            }
        } catch (t: Throwable) {
            engine.fail(t)
            null
        } finally {
            job.version.release()
            deleteReleased()
        }
    }

    private fun giveUp(job: Job, cause: Exception) {
        failedFiles += job.fileNo
        failedJobs++
        System.getLogger("app.oreshkov.delva").log(System.Logger.Level.WARNING, "reclaiming lane file ${job.fileNo} abandoned: $cause")
    }

    private fun select(): Job? = indexes.withCommitLock {
        // Listing, then in-flight marks, then LSNs: see LaneUsage.
        val listed = files.listed()
        val floor = usage.floor()
        val index = files.index
        var best = -1L
        var bestBytes = 0L
        var bestRatio = options.maxLiveRatio
        for (file in listed) {
            if (file.control || !file.sealed || file.fileNo >= floor || file.fileNo in failedFiles) continue
            if (usage.lastLsn(file.fileNo) > index.indexedUpToLsn) continue
            val bytes = sizes.getOrPut(file.fileNo) { files.laneReader(file.fileNo).size() }
            val data = bytes - LogFormat.HEADER_BYTES - ControlRecords.SEAL_RECORD_BYTES
            val live = if (data <= 0) 0.0 else (data - (index.discarded[file.fileNo] ?: 0L).coerceAtMost(data)).toDouble() / data
            if (live < bestRatio) {
                best = file.fileNo
                bestBytes = bytes
                bestRatio = live
            }
        }
        if (best < 0) null else Job(best, bestBytes, indexes.versions.acquire(), indexes.persistedHidden)
    }

    /** Copies the live fragments of the job's file to the GC lane and relocates them, chunk by chunk. */
    private fun relocate(job: Job) {
        val source = "lane file ${job.fileNo}"
        val index = job.version.fragmentIndex()
        val reader = RecordReader(engine.options.split.maxRecordBytes)
        reader.open(files.laneReader(job.fileNo), LogFormat.HEADER_BYTES.toLong(), job.fileBytes)
        val view = FragmentView()
        val byteReader = ByteReader(ByteArray(0))
        val limiter = RateLimiter(options.bytesPerSecond, engine.options.clock)
        val chunk = Chunk(job.fileNo)
        // At most the bytes not known to be discarded are copied: keep them in one GC-lane file when they fit.
        gcLane.makeRoom(job.fileBytes - (files.index.discarded[job.fileNo] ?: 0L).coerceAtMost(job.fileBytes))
        try {
            scan@ while (true) {
                control.checkContinue()
                val next = try {
                    reader.next()
                } catch (e: IOException) {
                    // Reported like damage, as readers do.
                    throw CorruptionException("$source@${reader.position}", "unreadable", e)
                }
                when (next) {
                    RecordReader.RECORD -> when (reader.type) {
                        RecordType.SEAL -> break@scan
                        RecordType.FRAGMENT -> {
                            FragmentRecords.decode(reader.bytes, reader.bodyOffset, reader.bodyLength, byteReader, view, "$source@${reader.recordStart}", engine.options.split.maxRecordBytes)
                            if (job.hidden.contains(view.docId)) continue@scan
                            val location = LogFragmentStore.location(job.fileNo, reader.recordStart)
                            val entry = index.find(view.docId, view.pre, view.kind, view.seq)
                            if (entry == null || entry.location != location) continue@scan
                            if (entry.length != reader.recordLength) throw CorruptionException("$source@${reader.recordStart}", "record of ${reader.recordLength} bytes, the index says ${entry.length}")
                            val padded = LogFormat.padded(reader.recordLength)
                            limiter.acquire(padded, control)
                            val at = gcLane.append(reader.bytes, reader.bodyOffset - LogFormat.RECORD_HEADER_BYTES, padded)
                            chunk.add(FragmentEntry(entry.docId, entry.pre, entry.kind, entry.seq, LogFragmentStore.location(gcLane.lastFileNo, at), entry.length, entry.payloadOffset, entry.rawLength), gcLane.lastFileNo, at, padded)
                            if (chunk.count >= options.maxRelocateFragments || chunk.bytes >= options.maxRelocateBytes) chunk.commit()
                        }
                        else -> throw CorruptionException("$source@${reader.recordStart}", "record type 0x${reader.type.toString(16)} in a lane file")
                    }
                    // Only sealed files are reclaimed, so an end before the SEAL is damage (zeroed bytes, say): retiring the
                    // file would delete the live fragments after it.
                    RecordReader.END -> throw CorruptionException("$source@${reader.position}", "ends before its SEAL")
                    else -> throw CorruptionException("$source@${reader.position}", "damaged record")
                }
            }
            chunk.commit()
        } finally {
            throttledNanos += limiter.waitedNanos
        }
    }

    /** Copied fragments not relocated yet. */
    private inner class Chunk(private val oldFileNo: Long) {
        private val entries = ArrayList<FragmentEntry>()
        private val extents = Extents()
        var bytes = 0L
            private set

        val count: Int get() = entries.size

        fun add(entry: FragmentEntry, fileNo: Long, at: Long, padded: Int) {
            entries += entry
            extents.add(fileNo, at, at + padded)
            bytes += padded
        }

        /** Makes the copies durable, then has the writer log and apply their RELOCATE. */
        fun commit() {
            if (entries.isEmpty()) return
            try {
                gcLane.force()
            } catch (e: IOException) {
                throw engine.fail(e)
            }
            val request = WriteRequest.Relocate(oldFileNo, entries.toList(), Extents().also { it.copyFrom(extents) })
            // The new entries go to the memtable like an insert's.
            engine.awaitMemtableRoom()
            engine.await(engine.writer.submit(request))
            relocations++
            relocatedFragments += entries.size
            relocatedBytes += bytes
            entries.clear()
            extents.clear()
            bytes = 0
        }
    }

    /** Drops the job's file from the MANIFEST; it's deleted once the versions that may read it are released. */
    private fun retire(job: Job) {
        indexes.withCommitLock { files.retireLaneFile(job.fileNo) }
        usage.forget(job.fileNo)
        sizes.remove(job.fileNo)
        val seq = indexes.newVersion()
        synchronized(retired) {
            retired += Retired(job.fileNo, job.fileBytes, seq)
            hasRetired = true
        }
        retiredFiles++
    }

    /** Deletes the retired files no live version can read; called whenever a version is released. */
    fun deleteReleased() {
        if (!hasRetired) return
        val ready = synchronized(retired) {
            val oldest = indexes.versions.oldestLiveSeq()
            val ready = retired.filter { it.seq <= oldest }
            retired.removeAll(ready)
            hasRetired = retired.isNotEmpty()
            ready
        }
        for (file in ready) {
            // A failed delete is logged; the file isn't listed anymore, so the next open deletes it.
            if (files.deleteLaneFile(file.fileNo)) {
                deletedFiles++
                deletedBytes += file.fileBytes
            }
        }
    }
}
