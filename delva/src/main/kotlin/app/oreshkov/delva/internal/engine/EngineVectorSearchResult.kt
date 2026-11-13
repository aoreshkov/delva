package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.DocId
import app.oreshkov.delva.JsonFormat
import app.oreshkov.delva.VectorHit
import app.oreshkov.delva.VectorSearchResult
import app.oreshkov.delva.internal.lsm.KeyIndexView
import app.oreshkov.delva.internal.lsm.Version
import app.oreshkov.delva.internal.query.HitAddress
import app.oreshkov.delva.internal.query.TopK
import app.oreshkov.delva.internal.query.VectorSearch
import app.oreshkov.delva.internal.store.FragmentStore
import app.oreshkov.delva.internal.types.TypeDictionary
import app.oreshkov.delva.internal.types.TypeIndexView
import java.io.OutputStream

/**
 * The hits of a vector search, found when it started, over one [Snapshot]. It pins the index [Version] the search read
 * until it's closed, so the hits' documents stay readable as the snapshot saw them; one that is never closed is
 * released when it's garbage collected, with a warning, as an [EngineSearchResult] is.
 */
internal class EngineVectorSearchResult(
    private val engine: ReadEngine,
    snapshot: Snapshot,
    version: Version,
    internal val fragments: FragmentStore,
    internal val types: TypeIndexView,
    internal val keys: KeyIndexView?,
    internal val names: TypeDictionary,
    search: VectorSearch,
    top: TopK,
) : VectorSearchResult {
    // The search's counters, not the search: it holds the scan's cursors and buffers, which the result doesn't need.

    /** Vectors the search compared with the query, for tests. */
    internal val compared: Long = search.compared

    /** Documents whose filter the search tested, for tests. */
    internal val filterTests: Long = search.filterTests

    override val snapshotLsn: Long = snapshot.lsn

    override val hits: List<VectorHit> = List(top.size) { EngineVectorHit(engine, this, top.docIds[it], top.distances[it]) }

    @Volatile
    private var closed = false

    // Last, as in EngineSearchResult: once registered, the Cleaner owns the version's release.
    private val pin = VersionPin(version)
    private val cleanable = RESULT_CLEANER.register(this, pin)

    override fun close() {
        closed = true
        pin.closed = true
        cleanable.clean()
    }

    internal fun checkReadable() {
        engine.checkUsable()
        check(!closed) { "the search result is closed" }
    }

    override fun toString(): String = "VectorSearchResult(${hits.size} hits)"
}

internal class EngineVectorHit(
    private val engine: ReadEngine,
    private val result: EngineVectorSearchResult,
    docId: Long,
    override val distance: Float,
) : VectorHit {
    private val address = HitAddress(docId, 0, true, null, 0, 0, 0)

    override val docId: DocId = DocId(docId)

    override val type: String?
        get() {
            result.checkReadable()
            return engine.nameOf(result.names, result.types.typeIdOf(address.docId, 0))
        }

    private var key: String? = null
    private var keyRead = false

    override val documentKey: String?
        get() {
            val keys = result.keys ?: return null
            if (!keyRead) {
                result.checkReadable()
                key = keys.keyOf(address.docId)?.let(DocumentKeys::decode)
                keyRead = true
            }
            return key
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

    override fun toString(): String = "VectorHit(${docId.raw}, $distance)"
}
