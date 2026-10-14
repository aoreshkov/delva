package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.DocId
import app.oreshkov.delva.JsonFormat
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.SelectResult
import app.oreshkov.delva.SelectedNode
import app.oreshkov.delva.internal.lsm.Version
import app.oreshkov.delva.internal.query.HitAddress
import app.oreshkov.delva.internal.query.NodeStream
import app.oreshkov.delva.internal.query.PathNode
import app.oreshkov.delva.internal.store.FragmentStore
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow

/**
 * The nodelist of one JSONPath query over one stored value ([stream]), read from one [Snapshot]. It pins an index
 * [Version] until it's closed, like a search result, and is released with a warning when it's garbage collected
 * without being closed. Not thread-safe.
 */
internal class EngineSelectResult(
    private val engine: ReadEngine,
    internal val docId: Long,
    snapshot: Snapshot,
    version: Version,
    internal val fragments: FragmentStore,
    private val stream: NodeStream,
) : SelectResult {
    override val snapshotLsn: Long = snapshot.lsn

    private var pending: PathNode? = null
    private var exhausted = false

    @Volatile
    private var closed = false

    // Keep these two last: Kotlin initializes properties in declaration order, and once this result is registered with
    // the Cleaner it owns the version's release, so nothing that runs after the registration may throw.
    private val pin = VersionPin(version)
    private val cleanable = RESULT_CLEANER.register(this, pin)

    override fun iterator(): Iterator<SelectedNode> = object : Iterator<SelectedNode> {
        override fun hasNext(): Boolean {
            checkReadable()
            if (pending == null && !exhausted) pending = stream.next() ?: null.also { exhausted = true }
            return pending != null
        }

        override fun next(): SelectedNode {
            if (!hasNext()) throw NoSuchElementException()
            val node = pending!!
            pending = null
            return EngineSelectedNode(engine, this@EngineSelectResult, node)
        }
    }

    override val nodes: Flow<SelectedNode> get() = iteratorFlow(::iterator)

    override fun close() {
        if (closed) return
        closed = true
        try {
            stream.close()
        } finally {
            pin.closed = true
            cleanable.clean()
        }
    }

    internal fun checkReadable() {
        engine.checkUsable()
        check(!closed) { "the select result is closed" }
    }
}

internal class EngineSelectedNode(private val engine: ReadEngine, private val result: EngineSelectResult, private val found: PathNode) : SelectedNode {
    override val node: NodeRef = NodeRef(DocId(result.docId), found.pre)

    override val path: String by lazy(LazyThreadSafetyMode.NONE) { found.normalizedPath() }

    override fun writeJsonTo(out: OutputStream, format: JsonFormat) {
        result.checkReadable()
        val address = HitAddress(result.docId, found.pre, false, found.located(), 0, 0, 0)
        engine.withHitReader(result.fragments) { it.writeJson(address, out, format) }
    }

    override fun readJsonAsString(format: JsonFormat, maxChars: Int): String {
        require(maxChars >= 0) { "maxChars must not be negative" }
        val out = CharLimitedOutput(maxChars)
        writeJsonTo(out, format)
        return out.text()
    }

    override fun toString(): String = "SelectedNode(${node.docId.raw}, ${node.nodePre}, $path)"
}
