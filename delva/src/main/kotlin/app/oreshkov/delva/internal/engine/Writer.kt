package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.DelvaException
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.Durability
import app.oreshkov.delva.StoreClosedException
import app.oreshkov.delva.WriteConflictException
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.compress.FragmentCodecs
import app.oreshkov.delva.internal.io.uninterruptibly
import app.oreshkov.delva.internal.log.CommitInfo
import app.oreshkov.delva.internal.log.ControlRecords
import app.oreshkov.delva.internal.log.Extents
import app.oreshkov.delva.internal.log.FragmentRecords
import app.oreshkov.delva.internal.log.LogFormat
import app.oreshkov.delva.internal.log.LogLane
import app.oreshkov.delva.internal.log.ManifestWatermark
import app.oreshkov.delva.internal.log.RelocateInfo
import app.oreshkov.delva.internal.lsm.FragmentEntry
import app.oreshkov.delva.internal.lsm.KeyIndexView
import app.oreshkov.delva.internal.lsm.PostingRun
import app.oreshkov.delva.internal.lsm.Version
import app.oreshkov.delva.internal.payload.FragmentContext
import app.oreshkov.delva.internal.store.LogFragmentStore
import app.oreshkov.delva.internal.types.TypeDictionary
import app.oreshkov.delva.internal.types.TypeIndexView
import app.oreshkov.delva.internal.visibility.Visibility
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A small insert prepared on the caller's thread: its fragments as framed FRAGMENT records, its sorted posting
 * runs, and its COMMIT. The writer appends the records to the small lane, fills in [location]s and the COMMIT's
 * extents, and applies the entries once the COMMIT is durable. Reusable after [clear].
 */
internal class PreparedInsert {
    val records = GrowableBytes(16 * 1024)
    var count: Int = 0
        private set
    var pre = LongArray(16)
        private set
    var kind = IntArray(16)
        private set
    var seq = IntArray(16)
        private set
    var recordStart = IntArray(16)
        private set

    /** Framed record length without padding. */
    var recordLength = IntArray(16)
        private set

    /** Payload offset from the record start. */
    var payloadOffset = IntArray(16)
        private set

    /** Payload length before compression. */
    var rawLength = IntArray(16)
        private set
    var location = LongArray(16)
        private set
    val runs = ArrayList<PostingRun>()
    val commit = CommitInfo()

    /** Type assignments given with the insert, as the splitter matched them; kept at promotion. */
    val types = InsertTypes()

    /** Committed documents deleted with this insert's COMMIT, ascending; empty for a plain insert. */
    var replaces: LongArray = NO_DOC_IDS

    /** Whether the visible document with this insert's key is replaced ([app.oreshkov.delva.KeyConflict.REPLACE]) rather than a conflict. */
    var replaceKey: Boolean = false

    /** Heap kept for reuse by the record buffer and the per-fragment arrays. */
    val retainedBytes: Long get() = records.capacity + 48L * pre.size

    /** Bytes accounted against the small-insert threshold. */
    var bytes: Long = 0

    val docId: Long get() = commit.docId

    /** Adds a fragment; returns the bytes it takes. */
    fun addFragment(docId: Long, pre: Long, kind: Int, seq: Int, context: FragmentContext, payload: ByteArray, offset: Int, length: Int, codecs: FragmentCodecs): Int {
        if (count == this.pre.size) grow()
        val i = count++
        val start = records.size
        this.pre[i] = pre
        this.kind[i] = kind
        this.seq[i] = seq
        recordStart[i] = start
        payloadOffset[i] = FragmentRecords.write(records, docId, pre, kind, seq, context, payload, offset, length, codecs)
        rawLength[i] = length
        recordLength[i] = LogFormat.RECORD_HEADER_BYTES + LittleEndian.getInt(records.array, start + 4)
        return records.size - start
    }

    fun paddedLength(i: Int): Int = LogFormat.padded(recordLength[i])

    fun setLocation(i: Int, location: Long) {
        this.location[i] = location
    }

    /** Drops fragments and runs, keeping the COMMIT. */
    fun clearBuffers() {
        records.clear()
        count = 0
        runs.clear()
        bytes = 0
    }

    fun clear() {
        clearBuffers()
        commit.extents.clear()
        commit.flags = 0
        commit.key = null
        types.clear()
        replaces = NO_DOC_IDS
        replaceKey = false
    }

    private fun grow() {
        val n = pre.size * 2
        pre = pre.copyOf(n)
        kind = kind.copyOf(n)
        seq = seq.copyOf(n)
        recordStart = recordStart.copyOf(n)
        recordLength = recordLength.copyOf(n)
        payloadOffset = payloadOffset.copyOf(n)
        rawLength = rawLength.copyOf(n)
        location = location.copyOf(n)
    }
}

internal val NO_DOC_IDS = LongArray(0)

/** `(nodePre, typeName)` pairs an insert assigns; their count is bounded by the paths the caller gave. Reusable. */
internal class InsertTypes {
    var count: Int = 0
        private set
    private var pres = LongArray(4)
    private var names = arrayOfNulls<String>(4)

    fun pre(i: Int): Long = pres[i]

    fun name(i: Int): String = names[i]!!

    fun add(pre: Long, name: String) {
        if (count == pres.size) {
            pres = pres.copyOf(count * 2)
            names = names.copyOf(count * 2)
        }
        pres[count] = pre
        names[count++] = name
    }

    fun clear() {
        names.fill(null, 0, count)
        count = 0
    }
}

/** A request to the writer; [done] completes with the LSN of the batch that made it durable. */
internal sealed class WriteRequest {
    val done = CompletableFuture<Long>()

    /**
     * A small insert. When a document of its [PreparedInsert.replaces] isn't visible, or its key is taken and not
     * replaced, the writer fails it with [WriteConflictException] before writing anything.
     */
    class Insert(val prepared: PreparedInsert) : WriteRequest()

    /**
     * BEGIN of a promoted insert; the writer tracks its [watermark] until the insert ends. It appends to lane files
     * numbered [firstFileNo] or higher.
     */
    class Begin(val docId: Long, val laneId: Int, val watermark: LaneWatermark, val firstFileNo: Long) : WriteRequest()

    /**
     * COMMIT of a promoted insert whose fragments and postings are already in place, deleting the committed documents
     * [replaces] (ascending) with it, and with [replaceKey] the visible document that has its key. When one of them isn't
     * visible, or its key is taken and not replaced, the writer logs an ABORT instead and fails the request with
     * [WriteConflictException].
     */
    class Commit(val commit: CommitInfo, val types: InsertTypes, var replaces: LongArray, val replaceKey: Boolean) : WriteRequest() {
        /** Set by the writer's check: the insert is aborted. */
        var conflict: WriteConflictException? = null
    }

    class Abort(val docId: Long, val reason: Int) : WriteRequest()

    /**
     * Type assignments to objects of committed documents, applied in order: entry `i` sets the type of
     * `(docIds[i], pres[i])` to `names[i]` (null removes it). The caller checked that the nodes are objects; the writer
     * fails the request with [app.oreshkov.delva.DocumentNotFoundException] when a document isn't visible.
     */
    class AssignTypes(val docIds: LongArray, val pres: LongArray, val names: Array<String?>) : WriteRequest()

    /**
     * Removes the type [typeId] from every object of a visible document that has it when the writer gets to the request,
     * as one atomic change; [cleared] is how many, read once [done] completed.
     */
    class ClearType(val typeId: Int) : WriteRequest() {
        @Volatile
        var cleared: Long = 0
    }

    /**
     * Deletes of committed documents, as one atomic call: [docIds] ascend strictly. The caller checked that each
     * was committed; the writer skips those deleted meanwhile and fails the request with
     * [app.oreshkov.delva.DocumentNotFoundException] when one isn't committed.
     */
    class Delete(val docIds: LongArray) : WriteRequest() {
        /** What the writer deletes: [docIds] not deleted before; read once [done] completed. */
        @Volatile
        var deleting: LongArray = LongArray(0)
    }

    /**
     * Fragments of lane file [oldFileNo] copied to the GC lane at [extents], which are durable: the writer
     * logs RELOCATE, syncs it in every durability mode and puts the new [entries] into the active memtable.
     */
    class Relocate(val oldFileNo: Long, val entries: List<FragmentEntry>, val extents: Extents) : WriteRequest()

    /** Requests that go into one batch together; each completes on its own. */
    class Group(val requests: List<WriteRequest>) : WriteRequest()

    /** Freezes and flushes the memtables after the batch: when [force], even below the memtable limit. */
    class Flush(val force: Boolean) : WriteRequest()

    class Stop : WriteRequest()
}

/** How the writer reports failures to the engine. */
internal interface EngineHealth {
    /** Throws [app.oreshkov.delva.EngineFailedException] or [StoreClosedException] unless the engine is running. */
    fun checkUsable()

    val failed: Boolean

    /**
     * Moves the engine to FAILED (while closing, records the failure for close to throw) and returns the exception to
     * hand to callers.
     */
    fun fail(cause: Throwable): DelvaException
}

/**
 * The single writer: drains a bounded queue and handles its requests as one group commit —
 * small-insert fragments to the small lane → sync the lane → the control records of each request (COMMIT with
 * IMPLICIT_BEGIN, BEGIN, COMMIT, ABORT, preceded by the `AT_INSERT` DELETE records of a replace and the TYPEDEF and TYPE
 * records of an insert's types; TYPEDEF and TYPE for assignments; DELETE; RELOCATE) → sync the control log → apply small
 * inserts, type changes and relocations to the memtables → publish one snapshot (with the batch's commits, aborts and
 * deletes) → acknowledge. It never reads client input. It marks in [usage] which lane files the batch and promoted
 * inserts may still need.
 *
 * Type assignments: the old type comes from the current version's newest entries, overlaid with the
 * assignments earlier in the batch; an assignment that changes nothing writes nothing.
 *
 * Before the batch writes anything, the documents of assignments, deletes and replaces are checked in request order
 * against the latest snapshot and the deletes earlier in the batch: an assignment to a document that isn't visible
 * fails alone, so assign-then-delete succeeds and delete-then-assign fails; a delete skips documents already deleted;
 * a replace of a document that isn't visible conflicts, so of two replaces of one document the second fails. An insert
 * whose document key a visible document has conflicts, unless it replaces that document; an upsert adds it to its
 * replaces. Its key entries go into the memtable with its COMMIT.
 *
 * Between batches it freezes the memtables once they pass [memtableBytes] or a flush was requested:
 * the log is synced up to the last LSN first (in every durability mode), then [Indexes.freeze] captures the
 * watermarks of the promoted inserts it has begun and not ended, the committed end of each lane file, the aborted
 * and deleted docIds and the number of type names, all as of that LSN.
 *
 * Syncs follow [durability]: per batch, on a timer (acknowledging before the sync), or never; a batch with a RELOCATE
 * syncs in every mode, since the relocated file is deleted once it's acknowledged. Any exception fails the
 * engine and every request of the batch; the writer never retries a sync. [runBatch] can be called directly instead
 * of [start]ing the thread.
 */
internal class Writer(
    private val smallLane: LogLane,
    private val control: LogLane,
    private val indexes: Indexes,
    private val registry: DocIdRegistry,
    private val durability: Durability,
    private val clock: Clock,
    private val health: EngineHealth,
    capacity: Int,
    private val maxBatch: Int,
    lastLsn: Long,
    private val memtableBytes: Long,
    committedEnd: Map<Long, Long>,
    private val dictionary: TypeDictionary,
    /** Most docIds one DELETE record holds; a larger delete call spans several records. */
    private val maxDeleteRecordDocIds: Int,
    private val usage: LaneUsage = LaneUsage(),
) {
    // Type changes of the batch, applied to the memtable with its inserts.
    private var effectCount = 0
    private var effectDocIds = LongArray(16)
    private var effectPres = LongArray(16)
    private var effectLsns = LongArray(16)
    private var effectNew = IntArray(16)
    private var effectOld = IntArray(16)

    /** Types assigned earlier in the batch, which later assignments see as the current type. */
    private val assigned = HashMap<NodeKey, Int>()

    /** Types cleared earlier in the batch: objects that had one before the batch and aren't in [assigned] have none. */
    private val clearedTypes = HashSet<Int>()
    private var typeVersion: Version? = null
    private var typeView: TypeIndexView? = null

    /** Keys committed earlier in the batch, by the docId that has them. */
    private val batchKeys = HashMap<ByteBuffer, Long>()
    private var keyView: KeyIndexView? = null

    private data class NodeKey(val docId: Long, val pre: Long)

    private val largeInFlight = LinkedHashMap<Long, LaneWatermark>()
    private val committedEnd = HashMap(committedEnd)
    private val flushWanted = AtomicBoolean()
    private val flushRequests = ArrayList<WriteRequest.Flush>()

    private val queue = ArrayBlockingQueue<WriteRequest>(capacity)
    private val submitLock = Any()
    private var accepting = true
    private val batch = ArrayList<WriteRequest>()
    private val record = GrowableBytes(256)
    private val relocate = RelocateInfo()
    private var commits = LongArray(16)
    private var aborts = LongArray(16)

    /** DocIds the batch deletes, in record order. */
    private var deletes = LongArray(16)
    private var deleteCount = 0

    // DocIds deleted or aborted since the last freeze: replay collects each of them until a flush persists them.
    private var endedSinceFreeze = 0L
    private val batchDeleted = HashSet<Long>()
    private var thread: Thread? = null

    /** LSN of the last control record appended. */
    var lsn: Long = lastLsn
        private set

    /** LSN up to which the control log is known to be synced. */
    @Volatile
    var durableLsn: Long = lastLsn
        private set

    private var unsynced = false
    private var lastSync = clock.nanoTime()

    /** Requests waiting in the queue; for tests. */
    val queued: Int get() = queue.size

    /** Batches processed; for tests. */
    @Volatile
    var batches: Long = 0
        private set

    init {
        control.beforeRoll = { smallLane.force() }
    }

    fun start() {
        thread = Thread.ofPlatform().name("delva-writer").daemon().start {
            while (runBatch(wait = true)) {
                // next batch
            }
        }
    }

    /**
     * Queues [request]; blocks while the queue is full. An interrupt doesn't end the wait but stays set: a promoted insert
     * submits its COMMIT after its BEGIN, and must not be left half done.
     */
    fun <R : WriteRequest> submit(request: R): R {
        synchronized(submitLock) {
            health.checkUsable()
            if (!accepting) throw StoreClosedException()
            uninterruptibly { queue.put(request) }
        }
        return request
    }

    /**
     * Asks for a freeze and flush after the next batch, without blocking: large inserts call it when their runs push
     * the memtables past the limit.
     */
    fun requestFlush() {
        if (flushWanted.compareAndSet(false, true)) queue.offer(WriteRequest.Flush(force = false))
    }

    /** Stops accepting requests, lets the queued ones finish, syncs unless the durability is NONE, and ends the thread. */
    fun stop() {
        val stop = WriteRequest.Stop()
        synchronized(submitLock) {
            if (!accepting) return
            accepting = false
            uninterruptibly { queue.put(stop) }
        }
        val t = thread
        if (t != null) {
            uninterruptibly { t.join() }
        } else {
            while (runBatch(wait = false)) {
                // drain
            }
        }
    }

    /**
     * Handles the queued requests as one batch, waiting for the first one when [wait]. Returns false once the stop
     * request was handled.
     */
    fun runBatch(wait: Boolean): Boolean {
        batch.clear()
        val first = if (wait) waitForRequest() else queue.poll()
        if (first == null) {
            syncIfDue()
            return true
        }
        batch += first
        queue.drainTo(batch, maxBatch - 1)
        if (batch.any { it is WriteRequest.Group }) expandGroups()
        // Flush requests are offered without the submit lock, so one may follow the stop request.
        flushRequests.clear()
        if (batch.any { it is WriteRequest.Flush }) {
            batch.removeAll { request -> (request is WriteRequest.Flush).also { if (it) flushRequests += request as WriteRequest.Flush } }
        }
        val stop = batch.lastOrNull() as? WriteRequest.Stop
        if (stop != null) batch.removeAt(batch.size - 1)
        process()
        syncIfDue()
        afterBatch()
        if (stop != null) {
            if (durability !is Durability.None && !health.failed && unsynced) sync()
            stop.done.complete(lsn)
            return false
        }
        return true
    }

    private fun afterBatch() {
        val forced = flushRequests.any { it.force }
        val wanted = flushWanted.getAndSet(false)
        if (forced || wanted || indexes.active.approximateBytes + ENDED_DOC_BYTES * endedSinceFreeze >= memtableBytes) maybeFreeze(forced)
        for (request in flushRequests) {
            if (health.failed) request.done.completeExceptionally(health.fail(IllegalStateException("the engine failed"))) else request.done.complete(lsn)
        }
        flushRequests.clear()
    }

    private fun maybeFreeze(forced: Boolean) {
        // A memtable without postings is frozen too when deletes or aborts filled it, so the replay after them stays short.
        val ended = ENDED_DOC_BYTES * endedSinceFreeze >= memtableBytes
        if (health.failed || (indexes.active.isEmpty && !forced && !ended)) return
        if (!forced && indexes.frozenCount >= MAX_FROZEN) return
        // indexedUpToLsn must never pass what the log holds durably, whatever the durability mode.
        if (durableLsn < lsn || unsynced) sync()
        if (health.failed) return
        val frozen = try {
            indexes.freeze(lsn, HashMap(committedEnd), registry.current().visibility, dictionary.size, allowEmpty = forced || ended) {
                largeInFlight.values.map { ManifestWatermark(it.docId, it.fileNo, it.offset) }
            }
        } catch (t: Throwable) {
            health.fail(t)
            return
        } ?: return
        indexes.submit(frozen)
        endedSinceFreeze = 0
    }

    private fun noteCommitted(extents: Extents) {
        for (i in 0 until extents.count) {
            val fileNo = extents.fileNo(i)
            if (extents.end(i) > (committedEnd[fileNo] ?: 0L)) committedEnd[fileNo] = extents.end(i)
        }
    }

    private fun expandGroups() {
        val taken = ArrayList(batch)
        batch.clear()
        for (request in taken) {
            if (request is WriteRequest.Group) {
                batch.addAll(request.requests)
                request.done.complete(0)
            } else {
                batch += request
            }
        }
    }

    private fun waitForRequest(): WriteRequest? {
        val periodic = durability as? Durability.Periodic
        if (periodic == null || !unsynced) return queue.take()
        val due = lastSync + periodic.interval.inWholeNanoseconds - clock.nanoTime()
        return if (due <= 0) queue.poll() else queue.poll(due, TimeUnit.NANOSECONDS)
    }

    private fun process() {
        if (batch.isEmpty()) return
        batches++
        if (health.failed) {
            failBatch(health.fail(IllegalStateException("the engine failed before this batch")))
            return
        }
        effectCount = 0
        deleteCount = 0
        assigned.clear()
        clearedTypes.clear()
        try {
            checkDocuments()
        } catch (t: Throwable) {
            releaseViews()
            failBatch(health.fail(t))
            return
        }
        if (batch.isEmpty()) {
            releaseViews()
            return
        }
        try {
            var appended = false
            for (request in batch) {
                if (request is WriteRequest.Insert) {
                    // Until its COMMITs are logged, the batch's fragments must stay where they are.
                    if (!appended) usage.smallBatch(smallLane.appendFloor)
                    appendFragments(request.prepared)
                    appended = true
                }
            }
            if (appended) {
                if (durability is Durability.SyncEachBatch) smallLane.force() else smallLane.flush()
            }
            var relocating = false
            for (request in batch) {
                when (request) {
                    is WriteRequest.Insert -> {
                        val commit = request.prepared.commit
                        appendReplaces(commit, request.prepared.replaces)
                        commit.typeCount = appendInsertTypes(commit.docId, request.prepared.types).toLong()
                        appendRecord { l -> ControlRecords.writeCommit(record, l, commit) }
                        noteCommitted(commit.extents)
                        usage.used(commit.extents, lsn)
                    }
                    is WriteRequest.Begin -> {
                        appendRecord { l -> ControlRecords.writeBegin(record, l, request.docId, request.laneId) }
                        largeInFlight[request.docId] = request.watermark
                        usage.begin(request.docId, request.firstFileNo)
                    }
                    is WriteRequest.Commit -> {
                        val commit = request.commit
                        if (request.conflict != null) {
                            appendRecord { l -> ControlRecords.writeAbort(record, l, commit.docId, ControlRecords.ABORT_OTHER) }
                            endedSinceFreeze++
                        } else {
                            appendReplaces(commit, request.replaces)
                            commit.typeCount = appendInsertTypes(commit.docId, request.types).toLong()
                            appendRecord { l -> ControlRecords.writeCommit(record, l, commit) }
                            noteCommitted(commit.extents)
                            usage.used(commit.extents, lsn)
                        }
                        largeInFlight.remove(commit.docId)
                        usage.end(commit.docId)
                    }
                    is WriteRequest.Abort -> {
                        appendRecord { l -> ControlRecords.writeAbort(record, l, request.docId, request.reason) }
                        endedSinceFreeze++
                        largeInFlight.remove(request.docId)
                        usage.end(request.docId)
                    }
                    is WriteRequest.AssignTypes -> appendAssignments(request)
                    is WriteRequest.ClearType -> appendClear(request)
                    is WriteRequest.Delete -> appendDeletes(request.deleting)
                    is WriteRequest.Relocate -> {
                        relocate.oldFileNo = request.oldFileNo
                        relocate.fragmentCount = request.entries.size.toLong()
                        relocate.extents.copyFrom(request.extents)
                        appendRecord { l -> ControlRecords.writeRelocate(record, l, relocate) }
                        noteCommitted(request.extents)
                        usage.used(request.extents, lsn)
                        relocating = true
                    }
                    is WriteRequest.Stop, is WriteRequest.Group, is WriteRequest.Flush -> error("${request::class.simpleName} inside a batch")
                }
            }
            usage.smallBatchDone()
            when (durability) {
                is Durability.SyncEachBatch -> {
                    control.force()
                    durableLsn = lsn
                }
                is Durability.Periodic -> {
                    control.flush()
                    unsynced = true
                }
                is Durability.None -> control.flush()
            }
            // The reclaimer deletes a relocated file once the relocation is acknowledged: it must survive a crash.
            if (relocating && durability !is Durability.SyncEachBatch) {
                smallLane.force()
                control.force()
                durableLsn = lsn
                unsynced = false
                lastSync = clock.nanoTime()
            }
            apply()
        } catch (t: Throwable) {
            failBatch(health.fail(t))
            return
        } finally {
            usage.smallBatchDone()
            releaseViews()
        }
        for (request in batch) {
            val conflict = (request as? WriteRequest.Commit)?.conflict
            if (conflict != null) request.done.completeExceptionally(conflict) else request.done.complete(lsn)
        }
    }

    private fun releaseViews() {
        typeVersion?.release()
        typeVersion = null
        typeView = null
        keyView = null
    }

    /** Appends one control record written by [write] with the next LSN. */
    private inline fun appendRecord(write: (Long) -> Unit) {
        val recordLsn = lsn + 1
        record.clear()
        write(recordLsn)
        control.append(record.array, 0, record.size, recordLsn)
        lsn = recordLsn
    }

    /**
     * Checks the documents of assignments, deletes and replaces in request order, before anything is written,
     * against the latest snapshot and the deletes earlier in the batch. An assignment to a document that isn't
     * visible, or a delete of one that was never committed, fails and leaves the batch; a delete keeps the documents
     * that aren't deleted yet in [WriteRequest.Delete.deleting]. A replace of a document that isn't visible conflicts, and
     * so does an insert whose document key a visible document it doesn't replace has, counting the keys of the inserts
     * earlier in the batch: a small insert leaves the batch, a promoted one is aborted in it.
     */
    private fun checkDocuments() {
        if (batch.none { it is WriteRequest.AssignTypes || it is WriteRequest.Delete || it is WriteRequest.ClearType || replacesOf(it).isNotEmpty() || commitOf(it)?.key != null }) return
        val visibility = registry.current().visibility
        batchDeleted.clear()
        batchKeys.clear()
        batch.removeAll { request ->
            val missing = when (request) {
                is WriteRequest.AssignTypes -> request.docIds.firstOrNull { !visibility.isVisible(it) || it in batchDeleted }
                is WriteRequest.Delete -> request.docIds.firstOrNull { !visibility.isVisible(it) && !visibility.isDeleted(it) }
                is WriteRequest.Insert, is WriteRequest.Commit -> return@removeAll checkInsert(request, visibility)
                else -> return@removeAll false
            }
            if (missing != null) {
                request.done.completeExceptionally(DocumentNotFoundException(DocId(missing)))
                return@removeAll true
            }
            if (request is WriteRequest.Delete) {
                request.deleting = request.docIds.filter { visibility.isVisible(it) && batchDeleted.add(it) }.toLongArray()
            }
            false
        }
    }

    private fun replacesOf(request: WriteRequest): LongArray = when (request) {
        is WriteRequest.Insert -> request.prepared.replaces
        is WriteRequest.Commit -> request.replaces
        else -> NO_DOC_IDS
    }

    private fun commitOf(request: WriteRequest): CommitInfo? = when (request) {
        is WriteRequest.Insert -> request.prepared.commit
        is WriteRequest.Commit -> request.commit
        else -> null
    }

    /**
     * Whether the insert [request] leaves the batch because it conflicts: a document it replaces isn't visible, or a
     * visible document it doesn't replace has its key. A small insert fails with [WriteConflictException] and leaves, a
     * promoted one stays to be aborted. When it replaces the key's document ([PreparedInsert.replaceKey]), that document
     * joins its replaces. Otherwise its replaces count as deleted, and its key as taken, for the requests after it.
     */
    private fun checkInsert(request: WriteRequest, visibility: Visibility): Boolean {
        var replaces = replacesOf(request)
        val key = commitOf(request)!!.key
        val missing = if (replaces.isEmpty()) emptyList() else replaces.filter { !visibility.isVisible(it) || it in batchDeleted }
        val conflict = when {
            missing.isNotEmpty() -> WriteConflictException(missing.map { DocId(it) })
            key == null -> null
            else -> {
                val holder = keyHolder(key)
                when {
                    holder < 0 || Arrays.binarySearch(replaces, holder) >= 0 -> null
                    request is WriteRequest.Commit && request.replaceKey -> {
                        replaces = (replaces + holder).also { it.sort() }
                        request.replaces = replaces
                        null
                    }
                    request is WriteRequest.Insert && request.prepared.replaceKey -> {
                        replaces = (replaces + holder).also { it.sort() }
                        request.prepared.replaces = replaces
                        null
                    }
                    else -> WriteConflictException(listOf(DocId(holder)), String(key, Charsets.UTF_8))
                }
            }
        }
        if (conflict == null) {
            for (docId in replaces) batchDeleted += docId
            if (key != null) batchKeys[ByteBuffer.wrap(key)] = commitOf(request)!!.docId
            return false
        }
        if (request is WriteRequest.Commit) {
            request.conflict = conflict
            return false
        }
        request.done.completeExceptionally(conflict)
        return true
    }

    /**
     * The visible document with [key] as the batch leaves it so far: committed by an earlier request of the batch, or
     * in the latest snapshot, and not deleted by an earlier request; -1 for none.
     */
    private fun keyHolder(key: ByteArray): Long {
        batchKeys[ByteBuffer.wrap(key)]?.let { if (it !in batchDeleted) return it }
        val view = keyView ?: run {
            if (typeVersion == null) currentTypes()
            KeyIndexView(typeVersion!!, registry.current().visibility).also { keyView = it }
        }
        return view.docIdOf(key) { it in batchDeleted }
    }

    /**
     * Logs the deletion of [docIds] (ascending) with the COMMIT of [commit] that follows: `AT_INSERT` DELETE records of at
     * most [maxDeleteRecordDocIds], chained as [appendDeletes] chains a call's, and the COMMIT's `REPLACES` flag.
     */
    private fun appendReplaces(commit: CommitInfo, docIds: LongArray) {
        if (docIds.isEmpty()) {
            commit.flags = commit.flags and ControlRecords.COMMIT_REPLACES.inv()
            return
        }
        appendDeletes(docIds, ControlRecords.DELETE_AT_INSERT, commit.docId)
        commit.flags = commit.flags or ControlRecords.COMMIT_REPLACES
    }

    /**
     * Logs the deletion of [docIds] (ascending) in records of at most [maxDeleteRecordDocIds], flagged so a torn log tail
     * can't keep part of the call, and with [atInsert] on each (the `AT_INSERT` flag and [insertDocId], or none); deleting
     * nothing writes nothing.
     */
    private fun appendDeletes(docIds: LongArray, atInsert: Int = 0, insertDocId: Long = -1) {
        var from = 0
        while (from < docIds.size) {
            val to = minOf(docIds.size, from + maxDeleteRecordDocIds)
            val flags = atInsert or (if (from == 0) ControlRecords.DELETE_FIRST else 0) or (if (to < docIds.size) ControlRecords.DELETE_CONTINUED else 0)
            appendRecord { l -> ControlRecords.writeDelete(record, l, docIds, from, to, flags, insertDocId) }
            from = to
        }
        if (deleteCount + docIds.size > deletes.size) deletes = deletes.copyOf(maxOf(deletes.size * 2, deleteCount + docIds.size))
        docIds.copyInto(deletes, deleteCount)
        deleteCount += docIds.size
        endedSinceFreeze += docIds.size
    }

    /** The id of [name], logging a TYPEDEF for a new one. */
    private fun resolveType(name: String): Int {
        val known = dictionary.idOf(name)
        if (known != 0) return known
        val id = dictionary.add(name)
        appendRecord { l -> ControlRecords.writeTypeDef(record, l, id, name) }
        return id
    }

    /** Logs the TYPE records of an insert right before its COMMIT; returns how many. */
    private fun appendInsertTypes(docId: Long, types: InsertTypes): Int {
        for (i in 0 until types.count) {
            val typeId = resolveType(types.name(i))
            val pre = types.pre(i)
            appendRecord { l -> ControlRecords.writeType(record, l, docId, pre, typeId, ControlRecords.TYPE_AT_INSERT) }
            addEffect(docId, pre, typeId, 0)
        }
        return types.count
    }

    /**
     * Logs the assignments that change a type; assigning the current type writes nothing. The call's TYPEDEFs come
     * first, then its TYPE records, the first flagged FIRST and all but the last CONTINUED, so a torn log tail can't keep
     * part of the call.
     */
    private fun appendAssignments(request: WriteRequest.AssignTypes) {
        val first = effectCount
        for (i in request.docIds.indices) {
            val docId = request.docIds[i]
            val pre = request.pres[i]
            val key = NodeKey(docId, pre)
            val old = assigned[key] ?: currentTypes().typeIdOf(docId, pre).let { if (it in clearedTypes) 0 else it }
            val name = request.names[i]
            if (name == null && old == 0) continue
            if (name != null && old != 0 && dictionary.idOf(name) == old) continue
            val typeId = if (name == null) 0 else resolveType(name)
            addEffect(docId, pre, typeId, old)
            assigned[key] = typeId
        }
        appendTypeRecords(first)
    }

    /**
     * Logs the removal of [WriteRequest.ClearType.typeId] from every object of a visible document that has it, as
     * [appendAssignments] logs assignments. The objects that had it before the batch are scanned, once per batch; those
     * given it earlier in the batch are in [assigned]. Documents deleted in the batch are left out: they lose their
     * types anyway.
     */
    private fun appendClear(request: WriteRequest.ClearType) {
        val typeId = request.typeId
        val first = effectCount
        if (clearedTypes.add(typeId)) {
            currentTypes()
            val scan = TypeIndexView(typeVersion!!, registry.current().visibility, Long.MAX_VALUE).scan(typeId)
            while (scan.valid) {
                val docId = scan.docId
                if (docId !in batchDeleted && !assigned.containsKey(NodeKey(docId, scan.pre))) addEffect(docId, scan.pre, 0, typeId)
                scan.next()
            }
        }
        val visibility = registry.current().visibility
        val reassigned = assigned.entries.filter { (key, t) -> t == typeId && visibility.isVisible(key.docId) && key.docId !in batchDeleted }
        for ((key, _) in reassigned) {
            addEffect(key.docId, key.pre, 0, typeId)
            assigned[key] = 0
        }
        request.cleared = (effectCount - first).toLong()
        appendTypeRecords(first)
    }

    /** Logs the TYPE records of the effects from [first] on, the first flagged FIRST and all but the last CONTINUED. */
    private fun appendTypeRecords(first: Int) {
        for (e in first until effectCount) {
            val flags = (if (e == first) ControlRecords.TYPE_FIRST else 0) or (if (e < effectCount - 1) ControlRecords.TYPE_CONTINUED else 0)
            appendRecord { l -> ControlRecords.writeType(record, l, effectDocIds[e], effectPres[e], effectNew[e], flags) }
            effectLsns[e] = lsn
        }
    }

    /** The newest types of the current version, for the old type of an assignment. */
    private fun currentTypes(): TypeIndexView {
        typeView?.let { return it }
        val version = indexes.versions.acquire()
        typeVersion = version
        return TypeIndexView(version, null, Long.MAX_VALUE).also { typeView = it }
    }

    private fun addEffect(docId: Long, pre: Long, newTypeId: Int, oldTypeId: Int) {
        if (effectCount == effectDocIds.size) {
            val n = effectCount * 2
            effectDocIds = effectDocIds.copyOf(n)
            effectPres = effectPres.copyOf(n)
            effectLsns = effectLsns.copyOf(n)
            effectNew = effectNew.copyOf(n)
            effectOld = effectOld.copyOf(n)
        }
        effectDocIds[effectCount] = docId
        effectPres[effectCount] = pre
        effectLsns[effectCount] = lsn
        effectNew[effectCount] = newTypeId
        effectOld[effectCount] = oldTypeId
        effectCount++
    }

    private fun appendFragments(prepared: PreparedInsert) {
        val extents = prepared.commit.extents
        extents.clear()
        for (i in 0 until prepared.count) {
            val length = prepared.paddedLength(i)
            val at = smallLane.append(prepared.records.array, prepared.recordStart[i], length)
            prepared.setLocation(i, LogFragmentStore.location(smallLane.lastFileNo, at))
            extents.add(smallLane.lastFileNo, at, at + length)
        }
    }

    private fun apply() {
        var c = 0
        var a = 0
        if (commits.size < batch.size) commits = LongArray(batch.size)
        if (aborts.size < batch.size) aborts = LongArray(batch.size)
        if (effectCount > 0) {
            indexes.withActive { m ->
                for (i in 0 until effectCount) m.types.assign(effectDocIds[i], effectPres[i], effectLsns[i], effectNew[i], effectOld[i])
            }
        }
        for (request in batch) {
            when (request) {
                is WriteRequest.Insert -> {
                    val p = request.prepared
                    indexes.withActive { m ->
                        for (i in 0 until p.count) {
                            m.fragments.add(FragmentEntry(p.docId, p.pre[i], p.kind[i], p.seq[i], p.location[i], p.recordLength[i], p.payloadOffset[i], p.rawLength[i]))
                        }
                        for (run in p.runs) m.postings.add(run)
                        p.commit.key?.let { m.keys.add(it, p.docId) }
                    }
                    commits[c++] = p.docId
                }
                is WriteRequest.Commit -> if (request.conflict == null) {
                    request.commit.key?.let { key -> indexes.withActive { m -> m.keys.add(key, request.commit.docId) } }
                    commits[c++] = request.commit.docId
                } else {
                    aborts[a++] = request.commit.docId
                }
                is WriteRequest.Abort -> aborts[a++] = request.docId
                // Replaces the entries of the old locations for every reader of this memtable.
                is WriteRequest.Relocate -> indexes.withActive { m -> for (e in request.entries) m.fragments.put(e) }
                else -> {}
            }
        }
        registry.publish(lsn, commits, c, aborts, a, deletes, deleteCount)
    }

    private fun failBatch(e: DelvaException) {
        for (request in batch) request.done.completeExceptionally(e)
    }

    private fun syncIfDue() {
        val periodic = durability as? Durability.Periodic ?: return
        if (!unsynced || health.failed) return
        if (clock.nanoTime() - lastSync >= periodic.interval.inWholeNanoseconds) sync()
    }

    /** Syncs the small lane, then the control log (so COMMITs never become durable before their fragments). */
    private fun sync() {
        try {
            smallLane.force()
            control.force()
            durableLsn = lsn
            unsynced = false
            lastSync = clock.nanoTime()
        } catch (t: Throwable) {
            health.fail(t)
        }
    }

    private companion object {
        /** Frozen memtables waiting for their flush before the writer stops freezing more. */
        const val MAX_FROZEN = 4

        /** What a deleted or aborted docId costs replay until it is flushed, charged against the memtable. */
        const val ENDED_DOC_BYTES = 16L
    }
}
