package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DocId
import app.oreshkov.delva.JsonFormat
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultTooLargeException
import app.oreshkov.delva.SearchHit
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.SearchPage
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.internal.lsm.KeyIndexView
import app.oreshkov.delva.internal.lsm.Version
import app.oreshkov.delva.internal.query.HitAddress
import app.oreshkov.delva.internal.query.HitSource
import app.oreshkov.delva.internal.query.PathSearch
import app.oreshkov.delva.internal.query.QueryExecutor
import app.oreshkov.delva.internal.query.StoredDocument
import app.oreshkov.delva.internal.store.FragmentStore
import app.oreshkov.delva.internal.types.TypeDictionary
import app.oreshkov.delva.internal.types.TypeIndexView
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow

/**
 * A search over one [Snapshot]: pages are cut by `limit` and the byte budget, with one hit of
 * lookahead so a page knows whether more follow. It pins an index [Version] until it's closed, so flushes and
 * compactions never remove what it reads; a result that is never closed is released when it's garbage collected, with
 * a warning. Not thread-safe; hits may be read from other threads.
 */
internal class EngineSearchResult(
    private val engine: ReadEngine,
    private val limit: Int,
    private val maxBytes: Long,
    private val order: SearchOrder,
    snapshot: Snapshot,
    version: Version,
    internal val fragments: FragmentStore,
    /** Types as the snapshot sees them; [ReadEngine.openView] made sure the version's segments aren't newer. */
    internal val types: TypeIndexView,
    /** A [QueryExecutor] for a key–value search, a [PathSearch] for a JSONPath search, a [KeyHitSource] in key order. */
    internal val source: HitSource,
    /** The document keys of a store with document keys, else null. */
    internal val keys: KeyIndexView?,
    /** Whether the hits come in document-key order, so cursors are key cursors. */
    private val keyOrder: Boolean,
    /** The type names of the version's type index. */
    internal val names: TypeDictionary,
) : SearchResult {
    override val snapshotLsn: Long = snapshot.lsn
    private val lateCommitBound: Long = when {
        keyOrder -> if (snapshot.visibility.inFlightCount > 0) 1 else 0
        order == SearchOrder.ASCENDING -> snapshot.visibility.inFlightFloor
        else -> snapshot.visibility.inFlightCeiling
    }

    internal val executor: QueryExecutor get() = source as QueryExecutor

    private var pending: EngineSearchHit? = null
    private var exhausted = false

    @Volatile
    private var closed = false

    // Keep these two last: Kotlin initializes properties in declaration order, and once this result is registered with
    // the Cleaner it owns the version's release, so nothing that runs after the registration may throw.
    private val pin = VersionPin(version)
    private val cleanable = RESULT_CLEANER.register(this, pin)

    override fun nextPage(): SearchPage? {
        val first = take() ?: return null
        val hits = ArrayList<SearchHit>(minOf(limit, 64))
        hits += first
        val budget = maxBytes
        var bytes = if (budget == Long.MAX_VALUE) 0L else first.encodedSize
        while (hits.size < limit) {
            val hit = take() ?: break
            if (budget != Long.MAX_VALUE) {
                val size = hit.encodedSize
                if (size > budget - bytes) {
                    pending = hit
                    return SearchPage(hits, cursorOf(hits.last()))
                }
                bytes += size
            }
            hits += hit
        }
        pending = pending ?: fetch()
        return SearchPage(hits, if (pending != null) cursorOf(hits.last()) else null)
    }

    override fun iterator(): Iterator<SearchHit> = object : Iterator<SearchHit> {
        override fun hasNext(): Boolean {
            if (pending == null) pending = fetch()
            return pending != null
        }

        override fun next(): SearchHit = take() ?: throw NoSuchElementException()
    }

    override val hits: Flow<SearchHit> get() = iteratorFlow(::iterator)

    override fun close() {
        closed = true
        pin.closed = true
        cleanable.clean()
    }

    internal fun checkReadable() {
        engine.checkUsable()
        check(!closed) { "the search result is closed" }
    }

    /**
     * Counts the hits not taken yet, stopping at [atMost]: the source finds them as for pages, but no hit is made and no
     * node is read.
     */
    internal fun countHits(atMost: Long): Long {
        checkReadable()
        var count = 0L
        if (pending != null && atMost > 0) {
            pending = null
            count++
        }
        while (count < atMost && !exhausted) {
            if (source.next()) count++ else exhausted = true
        }
        return count
    }

    internal fun cursorOf(hit: SearchHit): Cursor = if (keyOrder) {
        Cursor(hit.node.docId, 0, snapshotLsn, lateCommitBound, order, forTypes = false, key = (hit as EngineSearchHit).address.documentKey)
    } else {
        Cursor(hit.node.docId, hit.node.nodePre, snapshotLsn, lateCommitBound, order)
    }

    private fun take(): EngineSearchHit? {
        checkReadable()
        val hit = pending ?: fetch()
        pending = null
        return hit
    }

    private fun fetch(): EngineSearchHit? {
        checkReadable()
        if (exhausted) return null
        if (!source.next()) {
            exhausted = true
            return null
        }
        return EngineSearchHit(engine, this, source.hit!!)
    }
}

internal class EngineSearchHit(private val engine: ReadEngine, private val result: EngineSearchResult, internal val address: HitAddress) : SearchHit {
    override val node: NodeRef = NodeRef(DocId(address.docId), address.pre)

    override val cursor: Cursor get() = result.cursorOf(this)

    override val type: String?
        get() {
            if (address.typeId != HitAddress.TYPE_UNKNOWN) return engine.nameOf(result.names, address.typeId)
            result.checkReadable()
            return engine.nameOf(result.names, result.types.typeIdOf(address.docId, address.pre))
        }

    private var key: String? = null
    private var keyRead = false

    override val documentKey: String?
        get() {
            address.documentKey?.let { return it }
            val keys = result.keys ?: return null
            if (!keyRead) {
                result.checkReadable()
                key = keys.keyOf(address.docId)?.let(DocumentKeys::decode)
                keyRead = true
            }
            return key
        }

    private var normalizedPath: String? = null

    override val path: String
        get() {
            normalizedPath?.let { return it }
            result.checkReadable()
            val path = when {
                address.wholeDocument || address.pre == 0L -> "$"
                address.pathNode != null -> address.pathNode.normalizedPath()
                else -> StoredDocument(result.fragments, address.docId).nodeWithPath(address.pre).normalizedPath()
            }
            normalizedPath = path
            return path
        }

    private var size = -1L

    override val encodedSize: Long
        get() {
            if (size < 0) {
                result.checkReadable()
                size = engine.withHitReader(result.fragments) { it.encodedSize(address) }
            }
            return size
        }

    override fun writeJsonTo(out: OutputStream, format: JsonFormat) {
        result.checkReadable()
        engine.withHitReader(result.fragments) { it.writeJson(address, out, format) }
    }

    override fun readJsonAsString(format: JsonFormat, maxChars: Int): String {
        require(maxChars >= 0) { "maxChars must not be negative" }
        val out = CharLimitedOutput(maxChars)
        writeJsonTo(out, format)
        return out.text()
    }

    override fun toString(): String = "SearchHit(${node.docId.raw}, ${node.nodePre})"
}

/** Releases the versions of search and select results that were never closed. */
internal val RESULT_CLEANER: Cleaner = Cleaner.create()

/** Releases a result's version once: on close, or when the result was garbage collected without it. */
internal class VersionPin(private val version: Version) : Runnable {
    @Volatile
    var closed = false
    private val released = AtomicBoolean()

    override fun run() {
        if (!released.compareAndSet(false, true)) return
        if (!closed) {
            System.getLogger("app.oreshkov.delva").log(System.Logger.Level.WARNING, "a search or select result was garbage collected without being closed")
        }
        version.release()
    }
}

/** Collects UTF-8 bytes and fails once they decode to more than [maxChars] UTF-16 characters. */
internal class CharLimitedOutput(private val maxChars: Int) : OutputStream() {
    private val bytes = ByteArrayOutputStream()
    private var chars = 0L

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            val v = b[i].toInt() and 0xFF
            if (v and 0xC0 != 0x80) chars += if (v >= 0xF0) 2 else 1
        }
        if (chars > maxChars) throw ResultTooLargeException(maxChars)
        bytes.write(b, off, len)
    }

    fun text(): String = bytes.toString(Charsets.UTF_8)
}
