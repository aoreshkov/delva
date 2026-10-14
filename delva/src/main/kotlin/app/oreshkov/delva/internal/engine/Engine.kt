package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.BlockingJsonStore
import app.oreshkov.delva.CompareOperand
import app.oreshkov.delva.CompareOptions
import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DelvaException
import app.oreshkov.delva.DiffOptions
import app.oreshkov.delva.Difference
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.Durability
import app.oreshkov.delva.EngineFailedException
import app.oreshkov.delva.InsertOutcome
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.InvalidCursorException
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.KeyConflict
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.Normalization
import app.oreshkov.delva.QueryPlan
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.SelectResult
import app.oreshkov.delva.StoreClosedException
import app.oreshkov.delva.StoreExistsException
import app.oreshkov.delva.StoreInfo
import app.oreshkov.delva.StoreNotFoundException
import app.oreshkov.delva.TypeInfo
import app.oreshkov.delva.TypePage
import app.oreshkov.delva.VerifyProgress
import app.oreshkov.delva.VerifyReport
import app.oreshkov.delva.WriteConflictException
import app.oreshkov.delva.internal.canon.Nfc
import app.oreshkov.delva.internal.compare.CompareSettings
import app.oreshkov.delva.internal.compare.CompareSide
import app.oreshkov.delva.internal.compare.CompareStats
import app.oreshkov.delva.internal.compare.Comparison
import app.oreshkov.delva.internal.compare.LiteralFragments
import app.oreshkov.delva.internal.compare.LiteralParser
import app.oreshkov.delva.internal.compress.CompressionDictionary
import app.oreshkov.delva.internal.compress.CompressionOptions
import app.oreshkov.delva.internal.compress.DictionaryFiles
import app.oreshkov.delva.internal.compress.DictionaryTrainer
import app.oreshkov.delva.internal.compress.FragmentCodecs
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.uninterruptibly
import app.oreshkov.delva.internal.log.ControlRecords
import app.oreshkov.delva.internal.log.Extents
import app.oreshkov.delva.internal.log.FragmentView
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogLane
import app.oreshkov.delva.internal.log.ManifestStore
import app.oreshkov.delva.internal.lsm.ByteCache
import app.oreshkov.delva.internal.lsm.FragmentIndexMemtable
import app.oreshkov.delva.internal.lsm.KeyIndexView
import app.oreshkov.delva.internal.lsm.PostingMemtable
import app.oreshkov.delva.internal.lsm.Version
import app.oreshkov.delva.internal.path.LogicalExpr
import app.oreshkov.delva.internal.path.PathQuery
import app.oreshkov.delva.internal.payload.FragmentKind
import app.oreshkov.delva.internal.payload.PayloadTag
import app.oreshkov.delva.internal.query.Candidates
import app.oreshkov.delva.internal.query.HitReader
import app.oreshkov.delva.internal.query.LocatedNode
import app.oreshkov.delva.internal.query.NodeLocator
import app.oreshkov.delva.internal.query.PathEvaluator
import app.oreshkov.delva.internal.query.PathPlanner
import app.oreshkov.delva.internal.query.PathSearch
import app.oreshkov.delva.internal.query.QueryExecutor
import app.oreshkov.delva.internal.query.QueryTerm
import app.oreshkov.delva.internal.query.StoredDocument
import app.oreshkov.delva.internal.split.FileSystemSpillFiles
import app.oreshkov.delva.internal.split.SpillFiles
import app.oreshkov.delva.internal.split.TypePaths
import app.oreshkov.delva.internal.store.FragmentStore
import app.oreshkov.delva.internal.store.LogFragmentStore
import app.oreshkov.delva.internal.types.TypeDictionary
import app.oreshkov.delva.internal.types.TypeIndexView
import app.oreshkov.delva.internal.types.TypeNames
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Arrays
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The durable engine: inserts split on the caller's thread and are logged by the [Writer] with group commit;
 * promoted inserts stream their fragments into a large lane of their own on the caller's thread. Postings and
 * fragment-index entries go to memtables that are flushed to segments ([Indexes]); inserts wait while the memtables are
 * over their budget. Fragments are read back from the lane files, whose space the [Reclaimer] gives back once
 * enough of their documents were deleted. Opening replays the log after the last flush ([Recovery]). Reads are
 * [ReadEngine]'s: a search pins one snapshot and one index version and never locks.
 *
 * States: RUNNING → CLOSING → CLOSED, or RUNNING → FAILED after any write or sync error; a failed engine
 * throws [EngineFailedException] from every operation until it's closed and reopened. Thread-safe.
 */
internal class Engine private constructor(
    options: EngineOptions,
    val io: FileSystemIo,
    val dir: Path,
    private val lock: AutoCloseable,
) : ReadEngine(options), BlockingJsonStore {
    override val spillFiles: SpillFiles = FileSystemSpillFiles(io, dir.resolve(LogFiles.TMP_DIR))
    override val literalIo: FileSystemIo get() = io
    override val literalDir: Path = dir.resolve(LogFiles.TMP_DIR)
    override val secondary: Boolean get() = false

    /** Builds compression dictionaries from inserted payloads. */
    val dictionaryTrainer = DictionaryTrainer(codecs, ::installDictionary)

    lateinit var files: LogFiles
        private set
    lateinit var indexes: Indexes
        private set
    lateinit var registry: DocIdRegistry
        private set
    lateinit var writer: Writer
        private set

    /** Type names and their ids. */
    lateinit var dictionary: TypeDictionary
        private set
    private lateinit var largeLanes: LargeLanes

    /** Space reclamation of lane files. */
    lateinit var reclaimer: Reclaimer
        private set

    /** Documents whose fragments recovery found damaged. */
    var damagedDocIds: LongArray = LongArray(0)
        private set

    /** What the last open read and replayed. */
    lateinit var recoveryStats: RecoveryStats
        private set

    val replayedCommits: Long get() = recoveryStats.replayedCommits
    val replayedFragments: Long get() = recoveryStats.replayedFragments

    /** The active posting memtable; for tests. */
    val postings: PostingMemtable get() = indexes.active.postings

    /** The active fragment-index memtable; for tests. */
    val fragmentIndex: FragmentIndexMemtable get() = indexes.active.fragments

    private val pool = IngestPool()

    /** Ingests kept for reuse; for tests. */
    val pooledIngests: Int get() = pool.size.get()

    private val seedBase = options.keySeed ?: SecureRandom().nextLong()

    private val state = AtomicReference(State.RUNNING)

    /** Counted down once [close] has released everything. */
    private val closed = CountDownLatch(1)

    @Volatile
    private var failure: Throwable? = null

    private enum class State { RUNNING, CLOSING, CLOSED, FAILED }

    private fun recover() {
        val recovered = Recovery(this, dir).run()
        files = recovered.files
        indexes = recovered.indexes
        dictionary = recovered.dictionary
        registry = DocIdRegistry(recovered.nextDocId, recovered.lastLsn, recovered.aborted, recovered.deleted)
        largeLanes = LargeLanes((1..options.largeLanes).map { id -> LargeLane(id, recovered.largeLanes[id] ?: LogLane(files, control = false, laneId = id, active = null)) }, this)
        damagedDocIds = recovered.damagedDocIds
        recoveryStats = recovered.stats
        writer = Writer(
            recovered.smallLane, recovered.control, indexes, registry, options.store.durability, options.clock, this,
            options.writerQueueCapacity, options.maxBatchRequests, recovered.lastLsn, options.memtableBytes, recovered.committedEnd, dictionary,
            options.maxDeleteRecordDocIds, recovered.usage,
        )
        files.info.nextDocId = { registry.peekNextDocId() }
        files.info.durableLsn = { writer.durableLsn }
        reclaimer = Reclaimer(this, options.reclaim, recovered.usage, recovered.gcLane)
        indexes.versions.onReleased = { reclaimer.deleteReleased() }
        indexes.onIndexChanged = reclaimer::request
        if (options.backgroundFlush) indexes.startFlushThread()
        indexes.compactor.start()
        if (options.startWriterThread) writer.start()
        reclaimer.start()
    }

    override fun insert(source: InputStream, types: Map<String, String>): DocId = replace(emptyList(), source, types)

    override fun replace(docIds: Collection<DocId>, source: InputStream, types: Map<String, String>): DocId {
        val paths = typePaths(types)
        checkUsable()
        return insertStream(source, paths, replaceable(docIds), replaceKey = false)
    }

    override fun upsert(source: InputStream, types: Map<String, String>): DocId {
        val paths = typePaths(types)
        checkKeyed()
        checkUsable()
        return insertStream(source, paths, NO_DOC_IDS, replaceKey = true)
    }

    override fun upsert(path: Path, types: Map<String, String>): DocId {
        val paths = typePaths(types)
        checkKeyed()
        return Files.newInputStream(path).use { stream ->
            checkUsable()
            insertStream(stream, paths, NO_DOC_IDS, replaceKey = true)
        }
    }

    override fun upsert(json: String, types: Map<String, String>): DocId {
        val paths = typePaths(types)
        checkKeyed()
        checkUsable()
        return insertStream(ByteArrayInputStream(utf8Document(json)), paths, NO_DOC_IDS, replaceKey = true)
    }

    /** The type paths of an insert, with the store's document key path. */
    private fun typePaths(types: Map<String, String>): TypePaths =
        if (types.isEmpty()) keyPaths else TypePaths.compile(types, options.creation.normalization, options.creation.documentKey)

    /** The store's document key path alone, for inserts without types; [TypePaths.EMPTY] without one. */
    private val keyPaths: TypePaths = options.creation.documentKey?.let { TypePaths.compile(emptyMap(), options.creation.normalization, it) } ?: TypePaths.EMPTY

    /**
     * Inserts the document of [source], deleting [replaces] with it, and with [replaceKey] the document that has its key;
     * holds the ingest base while it runs.
     */
    private fun insertStream(source: InputStream, paths: TypePaths, replaces: LongArray, replaceKey: Boolean): DocId {
        val reserved = budget.acquire(options.ingestBaseBytes)
        try {
            return insertReserved(source, paths, replaces, replaceKey)
        } finally {
            budget.release(reserved)
        }
    }

    /**
     * The docIds a replace deletes, ascending and each once: [docIds] must all be visible and stored now, and none of
     * them [claimed] by an insertAll, else [WriteConflictException] names those that aren't. The writer checks them again.
     */
    private fun replaceable(docIds: Collection<DocId>, claimed: Claims? = null): LongArray {
        if (docIds.isEmpty()) return NO_DOC_IDS
        val sorted = LongArray(docIds.size)
        for ((i, docId) in docIds.withIndex()) sorted[i] = docId.raw
        sorted.sort()
        var count = 0
        for (docId in sorted) if (count == 0 || docId != sorted[count - 1]) sorted[count++] = docId
        val distinct = sorted.copyOf(count)
        val conflicting = withView { snapshot, version ->
            val nodes = NodeCheck(snapshot, version)
            distinct.filter { claimed?.contains(it) == true || !nodes.isStored(it) }
        }
        if (conflicting.isNotEmpty()) throw WriteConflictException(conflicting.map { DocId(it) })
        return distinct
    }

    private fun insertReserved(source: InputStream, paths: TypePaths, replaces: LongArray, replaceKey: Boolean): DocId {
        val docId = registry.allocate()
        val ingest = pool.take()
        var reusable = false
        try {
            try {
                ingest.split(docId, source, keySeed(docId), paths)
            } catch (e: Throwable) {
                if (ingest.promoted) abortPromoted(docId, e) else registry.forget(docId)
                reusable = e is DocumentRejectedException
                throw e
            }
            ingest.prepared.replaces = replaces
            ingest.prepared.replaceKey = replaceKey
            try {
                if (ingest.promoted) {
                    commitPromoted(ingest)
                } else {
                    val request = try {
                        awaitMemtableRoom()
                        writer.submit(WriteRequest.Insert(ingest.prepared))
                    } catch (e: Throwable) {
                        registry.forget(docId)
                        reusable = e is DelvaException
                        throw e
                    }
                    await(request)
                }
            } catch (e: WriteConflictException) {
                // The writer aborted a promoted insert, and wrote nothing of a small one.
                if (!ingest.promoted) registry.forget(docId)
                reusable = true
                throw e
            }
            reusable = true
            return DocId(docId)
        } finally {
            ingest.release()
            if (reusable) pool.give(ingest)
        }
    }

    /**
     * Inserts [sources] in order. Small inserts are held and logged together, one group commit per
     * [EngineOptions.maxBatchRequests] documents or whenever the ingest budget can't cover the next document; promoted
     * ones commit on their own. Returns each document's outcome. Other errors are thrown; documents not yet
     * acknowledged then stay invisible.
     *
     * Every source's type paths are compiled before the first is read, once per distinct map: a batch usually shares
     * one.
     *
     * A source's replaces are checked before it's read, against the current state, the documents earlier sources of the
     * call replace and the documents they insert: a conflict leaves the source unread. The writer checks them again.
     *
     * In a store with document keys, the held inserts are logged before a promoted one commits, so that every source
     * takes effect in call order and sees the keys of the sources before it.
     */
    override fun insertAll(sources: Iterable<InsertSource>): List<InsertOutcome> {
        val compiled = HashMap<Map<String, String>, TypePaths>()
        val batch = sources.map { source ->
            val paths = if (source.types.isEmpty()) keyPaths else compiled.getOrPut(source.types) { typePaths(source.types) }
            Triple(source, paths, source.replaces)
        }
        val keyed = options.creation.documentKey != null
        val results = ArrayList<InsertOutcome?>()
        val claimed = Claims()
        val held = HeldInserts(claimed)
        try {
            for ((source, paths, replacing) in batch) {
                checkUsable()
                val index = results.size
                results += null
                // With keys, a held insert may delete a document by its key, or conflict over one, only once it commits: log
                // them first, so the check sees what they did.
                if (keyed && replacing.isNotEmpty()) held.commit(results)
                val replaces = try {
                    replaceable(replacing, claimed)
                } catch (e: WriteConflictException) {
                    results[index] = InsertOutcome.Conflicted(e)
                    continue
                }
                var reserved = if (budget.tryAcquire(options.ingestBaseBytes)) options.ingestBaseBytes else 0L
                if (reserved == 0L) {
                    // The held inserts may be what holds the budget: log them first.
                    held.commit(results)
                    reserved = budget.acquire(options.ingestBaseBytes)
                }
                val docId = registry.allocate()
                // Whether or not it commits, a later source of the call can't replace it: held inserts aren't visible yet.
                claimed.allocated(docId)
                val ingest = pool.take()
                try {
                    ingest.split(docId, source.source, keySeed(docId), paths)
                    ingest.prepared.replaces = replaces
                    ingest.prepared.replaceKey = source.onExistingKey == KeyConflict.REPLACE
                    if (ingest.promoted) {
                        // With keys, sources take effect in order: an earlier held source must see no key of a later one.
                        if (keyed) held.commit(results)
                        commitPromoted(ingest)
                    }
                } catch (e: Throwable) {
                    // The writer aborted a promoted insert that conflicts.
                    if (e !is WriteConflictException) {
                        if (ingest.promoted) abortPromoted(docId, e) else registry.forget(docId)
                    }
                    ingest.release()
                    budget.release(reserved)
                    results[index] = when (e) {
                        is DocumentRejectedException -> InsertOutcome.Rejected(e)
                        is WriteConflictException -> InsertOutcome.Conflicted(e)
                        else -> throw e
                    }
                    pool.give(ingest)
                    continue
                }
                claimed.replaced(replaces)
                if (ingest.promoted) {
                    ingest.release()
                    budget.release(reserved)
                    pool.give(ingest)
                    results[index] = InsertOutcome.Inserted(DocId(docId))
                } else {
                    held.add(index, ingest, reserved)
                    if (held.size >= options.maxBatchRequests) held.commit(results)
                }
            }
            held.commit(results)
        } finally {
            held.abandon()
        }
        return results.map { it!! }
    }

    /**
     * The documents an [insertAll] call's sources may no longer replace: the docIds it allocated, ascending in a
     * primitive array (8 bytes each), and the documents earlier sources replace.
     */
    private class Claims {
        private var allocated = LongArray(16)
        private var count = 0
        private val replaced = HashSet<Long>()

        fun allocated(docId: Long) {
            if (count == allocated.size) allocated = allocated.copyOf(count * 2)
            allocated[count++] = docId
        }

        fun replaced(docIds: LongArray) {
            for (docId in docIds) replaced += docId
        }

        /** An earlier source didn't replace [docIds] after all: it conflicted when it was logged. */
        fun unreplaced(docIds: LongArray) {
            for (docId in docIds) replaced -= docId
        }

        operator fun contains(docId: Long): Boolean = docId in replaced || Arrays.binarySearch(allocated, 0, count, docId) >= 0
    }

    /** Small inserts of [insertAll] waiting for their group commit. */
    private inner class HeldInserts(private val claimed: Claims) {
        private val indexes = ArrayList<Int>()
        private val ingests = ArrayList<Ingest>()
        private var reserved = 0L

        val size: Int get() = ingests.size

        fun add(index: Int, ingest: Ingest, reservation: Long) {
            indexes += index
            ingests += ingest
            reserved += reservation
        }

        /** Logs the held inserts as one group and records their outcomes. */
        fun commit(results: MutableList<InsertOutcome?>) {
            if (ingests.isEmpty()) return
            awaitMemtableRoom()
            val requests = ingests.map { WriteRequest.Insert(it.prepared) }
            writer.submit(WriteRequest.Group(requests))
            // Submitted: the writer owns the buffers until each request completes.
            val taken = ingests.toList()
            ingests.clear()
            var completed = false
            try {
                for ((k, request) in requests.withIndex()) {
                    val docId = request.prepared.docId
                    results[indexes[k]] = try {
                        await(request)
                        InsertOutcome.Inserted(DocId(docId))
                    } catch (e: WriteConflictException) {
                        // The writer wrote nothing of it.
                        registry.forget(docId)
                        claimed.unreplaced(request.prepared.replaces)
                        InsertOutcome.Conflicted(e)
                    }
                }
                completed = true
            } finally {
                for (ingest in taken) {
                    ingest.release()
                    // After a failure the writer may still hold the buffers.
                    if (completed) pool.give(ingest)
                }
                indexes.clear()
                budget.release(reserved)
                reserved = 0
            }
        }

        /** Drops inserts that were never submitted: their docIds stay unused. */
        fun abandon() {
            for (ingest in ingests) {
                registry.forget(ingest.prepared.docId)
                ingest.release()
                pool.give(ingest)
            }
            ingests.clear()
            indexes.clear()
            budget.release(reserved)
            reserved = 0
        }
    }

    override fun insert(path: Path, types: Map<String, String>): DocId = replace(emptyList(), path, types)

    override fun replace(docIds: Collection<DocId>, path: Path, types: Map<String, String>): DocId {
        val paths = typePaths(types)
        return Files.newInputStream(path).use { stream ->
            checkUsable()
            insertStream(stream, paths, replaceable(docIds), replaceKey = false)
        }
    }

    override fun insert(json: String, types: Map<String, String>): DocId = replace(emptyList(), json, types)

    override fun replace(docIds: Collection<DocId>, json: String, types: Map<String, String>): DocId {
        val paths = typePaths(types)
        checkUsable()
        val replaces = replaceable(docIds)
        return insertStream(ByteArrayInputStream(utf8Document(json)), paths, replaces, replaceKey = false)
    }

    /**
     * A snapshot and a version holding at least everything it sees (the version is taken after the snapshot), whose
     * segments hold nothing newer than it (a type segment keeps only the newest version of a key). Release the
     * version when done.
     */
    override fun openView(): Pair<Snapshot, Version> {
        while (true) {
            val snapshot = registry.current()
            val version = indexes.versions.acquire()
            if (version.flushedLsn <= snapshot.lsn) return snapshot to version
            // A flush installed segments after the snapshot was taken; a new snapshot covers them.
            version.release()
        }
    }

    override fun currentSnapshot(): Snapshot = registry.current()

    /** Type names are only ever added, so every version reads the one dictionary. */
    override fun dictionaryOf(version: Version): TypeDictionary = dictionary

    /** Every listed log file, read to its end. */
    override fun openScrubView(): ScrubView {
        val (snapshot, version) = openView()
        return ScrubView(snapshot, version, files.listed(), files, files, files::isListedLane) { _, handle -> handle.size() }
    }

    // ---- types ---------------------------------------------------------------------------------------------------

    override fun assignType(node: NodeRef, type: String?) = assignTypes(listOf(node to type))

    override fun assignTypes(assignments: List<Pair<NodeRef, String?>>) {
        checkUsable()
        val names = Array(assignments.size) { assignments[it].second?.let { name -> typeName(name) } }
        for (type in names) type?.let { TypeNames.validate(it) }
        if (assignments.isEmpty()) return
        // Content is immutable, so the node checks hold later too; the writer re-checks that the documents are visible.
        withView { snapshot, version ->
            val nodes = NodeCheck(snapshot, version)
            for ((node, _) in assignments) nodes.check(node, requireObject = true)
        }
        val request = WriteRequest.AssignTypes(
            LongArray(assignments.size) { assignments[it].first.docId.raw },
            LongArray(assignments.size) { assignments[it].first.nodePre },
            names,
        )
        await(writer.submit(request))
    }

    override fun clearType(type: String): Long {
        checkUsable()
        val name = typeName(type)
        TypeNames.validate(name)
        val typeId = dictionary.idOf(name)
        if (typeId == 0) return 0
        val request = WriteRequest.ClearType(typeId)
        await(writer.submit(request))
        return request.cleared
    }

    // ---- delete --------------------------------------------------------------------------------------------------

    override fun delete(docId: DocId): Boolean = deleteAll(listOf(docId)) == 1

    /**
     * Deletes the documents of [docIds] in one atomic call; returns how many it deleted. Documents
     * already deleted are skipped. A docId that never named a committed document — not allocated, in flight, aborted, or
     * rejected before anything was written — fails the whole call with [DocumentNotFoundException] (SPEC.md §7).
     */
    override fun deleteAll(docIds: Collection<DocId>): Int {
        checkUsable()
        if (docIds.isEmpty()) return 0
        val sorted = LongArray(docIds.size)
        for ((i, docId) in docIds.withIndex()) sorted[i] = docId.raw
        sorted.sort()
        val live = withView { snapshot, version ->
            val visibility = snapshot.visibility
            val nodes = NodeCheck(snapshot, version)
            val kept = LongArray(sorted.size)
            var count = 0
            for ((i, docId) in sorted.withIndex()) {
                if ((i > 0 && docId == sorted[i - 1]) || visibility.isDeleted(docId)) continue
                if (!nodes.isStored(docId)) throw DocumentNotFoundException(DocId(docId))
                kept[count++] = docId
            }
            kept.copyOf(count)
        }
        if (live.isEmpty()) return 0
        // Visibility only changes by deletes once a document is committed; the writer skips those that happened meanwhile.
        val request = writer.submit(WriteRequest.Delete(live))
        await(request)
        return request.deleting.size
    }

    override fun fragmentsOf(version: Version): FragmentStore = LogFragmentStore(files, version.fragmentIndex(), fragmentCache, codecs, options.split.maxRecordBytes)

    /** Asks for a flush when the active memtables have passed the limit; doesn't block. */
    fun maybeRequestFlush() {
        if (indexes.active.approximateBytes >= options.memtableBytes) writer.requestFlush()
    }

    /** Freezes and flushes the memtables now and waits for the flush; for tests. */
    fun flush() {
        checkUsable()
        await(writer.submit(WriteRequest.Flush(force = true)))
        indexes.awaitFlushes()
        checkUsable()
    }

    /**
     * Waits for the pending flushes, then compacts until the policy picks nothing more, even when compaction is
     * disabled; returns the compactions committed. For tests.
     */
    fun compact(): Int {
        checkUsable()
        indexes.awaitFlushes()
        val n = indexes.compactor.runUntilIdle()
        checkUsable()
        return n
    }

    /**
     * Waits for the pending flushes, then reclaims lane files until none qualifies, even when reclamation is disabled;
     * returns how many files were retired. Needs the writer thread. For tests.
     */
    fun reclaim(): Int {
        check(options.startWriterThread) { "reclamation waits for the writer thread" }
        checkUsable()
        indexes.awaitFlushes()
        val n = reclaimer.runUntilIdle()
        checkUsable()
        return n
    }

    /**
     * Closes the store: stops the writer after the queued requests, syncs (unless the durability is NONE), writes a
     * MANIFEST and releases the directory lock. A large insert still streaming fails with [StoreClosedException].
     *
     * Every step runs even when an earlier one fails, so no thread or file handle outlives the call and the lock is
     * always released; the first failure is thrown with the later ones suppressed. A write, sync or flush that fails
     * while the store closes is thrown as [EngineFailedException], as it would be while the store runs: operations it
     * acknowledged may not be durable. An interrupt doesn't cut the close short, and stays set. A concurrent call
     * returns once the store is closed.
     */
    override fun close() {
        val from: State
        while (true) {
            val now = state.get()
            if (now == State.CLOSING || now == State.CLOSED) {
                uninterruptibly { closed.await() }
                return
            }
            if (state.compareAndSet(now, State.CLOSING)) {
                from = now
                break
            }
        }
        var error: Throwable? = null
        fun step(block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                error?.addSuppressed(t) ?: run { error = t }
            }
        }
        try {
            // Before the writer: a reclamation job may be waiting for it.
            step { reclaimer.stop() }
            step { writer.stop() }
            step { indexes.close() }
            if (failure == null && error == null) {
                // Lanes still held belong to inserts that fail now; their BEGINs are aborted at the next open.
                step { largeLanes.close { lane -> if (options.store.durability !is Durability.None) lane.log.force() } }
                step { files.writeManifest() }
            }
        } finally {
            state.set(State.CLOSED)
            step { files.close() }
            step { codecs.close() }
            step { lock.close() }
            closed.countDown()
        }
        // A failure from before the close was already reported to the operations it stopped.
        if (from == State.RUNNING && failure != null) {
            val failed = failedException()
            error?.let(failed::addSuppressed)
            throw failed
        }
        error?.let { throw it }
    }

    // ---- health --------------------------------------------------------------------------------------------------

    override fun checkUsable() {
        when (state.get()) {
            State.RUNNING -> {}
            State.FAILED -> throw failedException()
            else -> throw StoreClosedException()
        }
    }

    // A failure while closing leaves the state CLOSING; the writer and the flushes must still stop writing after it.
    override val failed: Boolean get() = state.get() == State.FAILED || failure != null

    override fun fail(cause: Throwable): DelvaException {
        if (state.compareAndSet(State.RUNNING, State.FAILED)) {
            failure = cause
            return failedException()
        }
        return when (state.get()) {
            State.FAILED -> failedException()
            // Files stay open until the state is CLOSED, so a failure while closing is a real one, which close reports.
            State.CLOSING -> {
                if (failure == null) failure = cause
                failedException()
            }
            else -> StoreClosedException()
        }
    }

    private fun failedException(): EngineFailedException {
        val cause = failure
        return EngineFailedException("The engine failed and must be reopened: $cause", cause)
    }

    // ---- insert helpers ----------------------------------------------------------------------------------------

    /**
     * Makes a dictionary the trainer adopted durable, then current: its file is synced, a MANIFEST lists it, and
     * only then do new fragments use it. A write error fails the engine.
     */
    private fun installDictionary(bytes: ByteArray) {
        val dictionary = CompressionDictionary(codecs.maxId + 1, bytes)
        if (dictionary.id > CompressionOptions.MAX_DICTIONARY_ID || state.get() != State.RUNNING) return
        try {
            DictionaryFiles.write(io, dir.resolve(LogFiles.DICTIONARY_DIR), dictionary)
            codecs.register(dictionary, use = false)
            files.addDictionaries(listOf(dictionary.id))
        } catch (e: IOException) {
            throw fail(e)
        } catch (e: IllegalStateException) {
            // The log files closed meanwhile: the store is closing, and the unlisted file is kept or removed at open.
            if (state.get() == State.RUNNING) throw e
            return
        }
        codecs.register(dictionary, use = true)
    }

    /**
     * Waits for [request]; rethrows the engine's failure. An interrupt doesn't end the wait, since the writer carries
     * out a submitted request regardless: it stays set on the thread, and the call reports what the writer did.
     */
    fun await(request: WriteRequest): Long {
        try {
            return uninterruptibly { request.done.get() }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /** The large lanes that promoted inserts take. */
    val lanes: LargeLanes get() = largeLanes

    /** Large-lane syncs forced by [app.oreshkov.delva.internal.log.LogOptions.largeLaneSyncBytes]; for tests. */
    @Volatile
    var largeLaneSyncs: Long = 0
        private set

    /** Waits for memtable room before an insert adds postings; without a writer thread nothing could make any. */
    fun awaitMemtableRoom() {
        if (options.startWriterThread) indexes.awaitMemtableRoom { writer.requestFlush() }
    }

    /** Appends a framed fragment record to [lane], which the caller holds; returns its location. */
    fun appendLarge(lane: LargeLane, bytes: ByteArray, offset: Int, length: Int, extents: Extents): Long {
        checkUsable()
        try {
            val log = lane.log
            val at = log.append(bytes, offset, length)
            extents.add(log.lastFileNo, at, at + length)
            lane.unsyncedBytes += length
            val syncBytes = options.log.largeLaneSyncBytes
            // Bounds the unsynced bytes of a long insert and the sync before its COMMIT.
            if (syncBytes > 0 && lane.unsyncedBytes >= syncBytes && options.store.durability !is Durability.None) {
                log.force()
                lane.unsyncedBytes = 0
                largeLaneSyncs++
            }
            return LogFragmentStore.location(log.lastFileNo, at)
        } catch (e: DelvaException) {
            throw e
        } catch (e: Exception) {
            throw fail(e)
        }
    }

    private fun commitPromoted(ingest: Ingest) {
        val lane = ingest.lane!!
        try {
            // In every mode, even None: a later flush syncs the control log with this COMMIT in it, and a durable
            // COMMIT must never name lane bytes that a crash can lose (one sync per large document).
            lane.log.force()
            lane.unsyncedBytes = 0
        } catch (e: Exception) {
            throw fail(e)
        }
        await(writer.submit(WriteRequest.Commit(ingest.prepared.commit, ingest.prepared.types, ingest.prepared.replaces, ingest.prepared.replaceKey)))
    }

    private fun abortPromoted(docId: Long, cause: Throwable) {
        val reason = (cause as? DocumentRejectedException)?.reason?.let { it.ordinal + 1 } ?: ControlRecords.ABORT_OTHER
        try {
            await(writer.submit(WriteRequest.Abort(docId, reason)))
        } catch (_: DelvaException) {
            // The engine failed or is closing: the dangling BEGIN is aborted when the store opens next.
        }
    }

    /**
     * Reusable [Ingest]s. Only instances whose buffers stayed small are kept, and only up to a count, so the pool's heap
     * is bounded by the options rather than by the largest or most concurrent inserts seen.
     */
    private inner class IngestPool {
        private val idle = ConcurrentLinkedQueue<Ingest>()
        val size = AtomicInteger()

        fun take(): Ingest = idle.poll()?.also { size.decrementAndGet() } ?: Ingest(this@Engine)

        fun give(ingest: Ingest) {
            if (ingest.promoted || ingest.retainedBytes > MAX_POOLED_INGEST_BYTES) return
            if (size.incrementAndGet() > MAX_POOLED_INGESTS) {
                size.decrementAndGet()
                return
            }
            idle.offer(ingest)
        }
    }

    /** Per-insert seed of the duplicate-key hashes, derived from the store's seed. */
    private fun keySeed(docId: Long): Long {
        var z = seedBase + docId * -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    companion object {
        /** Pooled ingests at most, and the most heap one may keep to be pooled. */
        private const val MAX_POOLED_INGESTS = 64
        private const val MAX_POOLED_INGEST_BYTES = 1L shl 20

        /**
         * Opens the store in [dir] as [opening] says and replays its log. A store that exists must have been created
         * with [EngineOptions.creation] ([app.oreshkov.delva.CreationOptionsMismatchException] otherwise);
         * [openStored] takes them from the store instead.
         *
         * [Opening.OPEN] changes nothing in a directory without a store — not even a lock file — and throws
         * [StoreNotFoundException]; [Opening.CREATE] throws [StoreExistsException] for a directory that has one.
         */
        fun open(dir: Path, options: EngineOptions, io: FileSystemIo, opening: Opening = Opening.OPEN_OR_CREATE): Engine =
            open(dir, io, opening) { options }

        /**
         * Opens the existing store in [dir] like [open] with [Opening.OPEN], with the options [options] makes of the
         * [CreationOptions] the store was created with. They're read under the lock: a process that doesn't hold it
         * mustn't open `CURRENT`, because on Windows an open `CURRENT` can't be replaced, which would fail the
         * MANIFEST write of the process that holds it.
         */
        fun openStored(dir: Path, io: FileSystemIo, options: (CreationOptions) -> EngineOptions): Engine =
            open(dir, io, Opening.OPEN) { options(ManifestStore(io, dir).peek()?.creationOptions() ?: throw StoreNotFoundException(dir)) }

        /** [options] runs under the lock. */
        private inline fun open(dir: Path, io: FileSystemIo, opening: Opening, options: () -> EngineOptions): Engine {
            val current = dir.resolve(ManifestStore.CURRENT)
            // An existence check reads directory metadata only; it doesn't open the file.
            if (opening == Opening.OPEN && !io.exists(current)) throw StoreNotFoundException(dir)
            if (opening != Opening.OPEN) io.createDirectories(dir)
            val lock = StoreLock.acquire(dir, io)
            try {
                // Checked again under the lock: another process may have created the store since.
                if (opening == Opening.CREATE && io.exists(current)) throw StoreExistsException(dir)
                val engine = Engine(options(), io, dir, lock)
                engine.recover()
                return engine
            } catch (t: Throwable) {
                lock.close()
                throw t
            }
        }

        /**
         * What the store in [dir] was created with, read without changing anything; null when there's no store. Only
         * for a store no other process has open (see [openStored]).
         */
        fun storedCreation(dir: Path, io: FileSystemIo): CreationOptions? =
            if (io.exists(dir)) ManifestStore(io, dir).peek()?.creationOptions() else null
    }
}
