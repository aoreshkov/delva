package app.oreshkov.delva.internal.facade

import app.oreshkov.delva.BlockingJsonStore
import app.oreshkov.delva.BlockingSecondaryJsonStore
import app.oreshkov.delva.CompareOperand
import app.oreshkov.delva.CompareOptions
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DiffOptions
import app.oreshkov.delva.Difference
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.InsertOutcome
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.LockHolder
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.QueryPlan
import app.oreshkov.delva.ReadableJsonStore
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.SecondaryJsonStore
import app.oreshkov.delva.SelectResult
import app.oreshkov.delva.StoreInfo
import app.oreshkov.delva.TypeInfo
import app.oreshkov.delva.TypePage
import app.oreshkov.delva.VerifyProgress
import app.oreshkov.delva.VerifyReport
import app.oreshkov.delva.internal.engine.Engine
import app.oreshkov.delva.internal.engine.ReadEngine
import app.oreshkov.delva.internal.engine.SecondaryEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The suspending facade of the reads: runs the blocking [reader] on a dispatcher limited to `ioParallelism` threads.
 * The only place, with its subclasses and the results' flows ([app.oreshkov.delva.internal.engine.iteratorFlow]), where
 * the engine meets coroutines.
 *
 * Cancellation doesn't interrupt the thread, though an interrupt would do no harm: the engine's I/O isn't
 * interruptible. An input is read through a stream that throws [kotlinx.coroutines.CancellationException] once the
 * coroutine is cancelled, which ends the call like any failing client stream.
 */
internal abstract class CoroutineReadableJsonStore(private val reader: ReadEngine, ioParallelism: Int) : ReadableJsonStore {
    protected val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(ioParallelism, "delva-io")

    override suspend fun get(key: String): DocId? = withContext(dispatcher) { reader.get(key) }

    override suspend fun typeOf(node: NodeRef): String? = withContext(dispatcher) { reader.typeOf(node) }

    override suspend fun pathOf(node: NodeRef): String = withContext(dispatcher) { reader.pathOf(node) }

    override suspend fun objectsOfType(type: String, after: NodeRef?, limit: Int): List<NodeRef> = withContext(dispatcher) { reader.objectsOfType(type, after, limit) }

    override suspend fun objectsOfTypePage(type: String, after: Cursor?, limit: Int): TypePage =
        withContext(dispatcher) { reader.objectsOfTypePage(type, after, limit) }

    override fun objectsOfTypeFlow(type: String, after: NodeRef?): Flow<NodeRef> = flow {
        reader.openTypeScan(type, after).use { scan ->
            while (true) emit(scan.next() ?: break)
        }
    }.flowOn(dispatcher)

    override suspend fun countOfType(type: String): Long = withContext(dispatcher) { reader.countOfType(type) }

    override val creationOptions: CreationOptions get() = reader.creationOptions

    override fun info(): StoreInfo = reader.info()

    override fun listTypes(): List<TypeInfo> = reader.listTypes()

    override suspend fun isEqual(a: CompareOperand, b: CompareOperand, options: CompareOptions): Boolean = withContext(dispatcher) {
        val job = coroutineContext.job
        reader.isEqual(cancellable(a, job), cancellable(b, job), options)
    }

    override fun diff(a: CompareOperand, b: CompareOperand, options: DiffOptions): Flow<Difference> = flow {
        val job = currentCoroutineContext().job
        reader.openDiff(cancellable(a, job), cancellable(b, job), options).use { cursor ->
            while (true) emit(cursor.next() ?: break)
        }
    }.flowOn(dispatcher)

    /** A JSON stream operand whose reads stop once [job] is cancelled. */
    private fun cancellable(operand: CompareOperand, job: Job): CompareOperand =
        if (operand is CompareOperand.JsonStream) CompareOperand.JsonStream(CancellableInputStream(operand.source, job)) else operand

    /** Cancellation stops the scrub at its next progress report, like a cancelled insert stops at its next read. */
    override suspend fun verify(progress: VerifyProgress): VerifyReport = withContext(dispatcher) {
        val job = coroutineContext.job
        reader.verify { phase, bytesChecked, bytesTotal ->
            job.ensureActive()
            progress.onProgress(phase, bytesChecked, bytesTotal)
        }
    }

    override fun search(query: KeyValueQuery): SearchResult = reader.search(query)

    override fun select(node: NodeRef, path: JsonPath): SelectResult = reader.select(node, path)

    override fun search(query: JsonPathQuery): SearchResult = reader.search(query)

    override fun explain(query: JsonPathQuery): QueryPlan = reader.explain(query)

    override suspend fun count(query: KeyValueQuery, atMost: Long): Long = withContext(dispatcher) { reader.count(query, atMost) }

    override suspend fun count(query: JsonPathQuery, atMost: Long): Long = withContext(dispatcher) { reader.count(query, atMost) }

    override fun search(query: DocumentKeyQuery): SearchResult = reader.search(query)

    override suspend fun count(query: DocumentKeyQuery, atMost: Long): Long = withContext(dispatcher) { reader.count(query, atMost) }

    override fun close() = reader.close()
}

/**
 * The suspending facade of a store's writer. A write reports what happened: cancelled before it starts, it throws
 * `CancellationException` and writes nothing; once started, it runs to the end and returns its result even if the
 * caller was cancelled meanwhile, whose next suspension point then throws. An input stream is the exception: its reads
 * stop once the caller is cancelled, which ends the insert like a failing stream, dropped when it was buffered and
 * aborted when it was promoted.
 */
internal class CoroutineJsonStore(internal val core: Engine, ioParallelism: Int) : CoroutineReadableJsonStore(core, ioParallelism), JsonStore {
    override suspend fun insert(source: InputStream, types: Map<String, String>): DocId = write { caller ->
        core.insert(CancellableInputStream(source, caller), types)
    }

    override suspend fun insert(path: Path, types: Map<String, String>): DocId = write { caller ->
        Files.newInputStream(path).use { core.insert(CancellableInputStream(it, caller), types) }
    }

    override suspend fun insert(json: String, types: Map<String, String>): DocId = write { core.insert(json, types) }

    override suspend fun replace(docIds: Collection<DocId>, source: InputStream, types: Map<String, String>): DocId = write { caller ->
        core.replace(docIds, CancellableInputStream(source, caller), types)
    }

    override suspend fun replace(docIds: Collection<DocId>, path: Path, types: Map<String, String>): DocId = write { caller ->
        Files.newInputStream(path).use { core.replace(docIds, CancellableInputStream(it, caller), types) }
    }

    override suspend fun replace(docIds: Collection<DocId>, json: String, types: Map<String, String>): DocId = write { core.replace(docIds, json, types) }

    override suspend fun upsert(source: InputStream, types: Map<String, String>): DocId = write { caller ->
        core.upsert(CancellableInputStream(source, caller), types)
    }

    override suspend fun upsert(path: Path, types: Map<String, String>): DocId = write { caller ->
        Files.newInputStream(path).use { core.upsert(CancellableInputStream(it, caller), types) }
    }

    override suspend fun upsert(json: String, types: Map<String, String>): DocId = write { core.upsert(json, types) }

    override suspend fun assignType(node: NodeRef, type: String?) = write { core.assignType(node, type) }

    override suspend fun assignTypes(assignments: List<Pair<NodeRef, String?>>) = write { core.assignTypes(assignments) }

    override suspend fun clearType(type: String): Long = write { core.clearType(type) }

    override suspend fun delete(docId: DocId): Boolean = write { core.delete(docId) }

    override suspend fun deleteAll(docIds: Collection<DocId>): Int = write { core.deleteAll(docIds) }

    override suspend fun insertAll(sources: Iterable<InsertSource>): List<InsertOutcome> = write { caller ->
        core.insertAll(sources.map { InsertSource(CancellableInputStream(it.source, caller), it.types, it.replaces, it.onExistingKey) })
    }

    /** Runs [block] on the dispatcher, as the class comment describes; it gets the caller's job for its input streams. */
    private suspend inline fun <T> write(crossinline block: (caller: Job) -> T): T {
        val caller = currentCoroutineContext().job
        caller.ensureActive()
        // Not cancellable once started: withContext discards a committed result when it dispatches back to a
        // cancelled caller, so the outer call must not change the dispatcher, and the inner one can't be cancelled.
        return withContext(NonCancellable) {
            withContext(dispatcher) {
                caller.ensureActive() // still before anything is written
                block(caller)
            }
        }
    }

    // A view rather than the engine itself: the engine is AutoCloseable, and closing the view must not be possible.
    private val blockingView = object : BlockingJsonStore by core {}

    override fun blocking(): BlockingJsonStore = blockingView
}

/** The suspending facade of a secondary. */
internal class CoroutineSecondaryJsonStore(internal val core: SecondaryEngine, ioParallelism: Int) : CoroutineReadableJsonStore(core, ioParallelism), SecondaryJsonStore {
    override suspend fun refresh(): Boolean = withContext(dispatcher) { core.refresh() }

    override fun writer(): LockHolder? = core.writer()

    // A view rather than the engine itself: the engine is AutoCloseable, and closing the view must not be possible.
    private val blockingView = object : BlockingSecondaryJsonStore by core {}

    override fun blocking(): BlockingSecondaryJsonStore = blockingView
}

/** [source] (not closed with this stream) that fails every read once [job] is cancelled. */
internal class CancellableInputStream(source: InputStream, private val job: Job) : FilterInputStream(source) {
    override fun read(): Int {
        job.ensureActive()
        return super.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        job.ensureActive()
        return super.read(b, off, len)
    }

    override fun skip(n: Long): Long {
        job.ensureActive()
        return super.skip(n)
    }

    override fun close() {
        // The caller owns the source.
    }
}
