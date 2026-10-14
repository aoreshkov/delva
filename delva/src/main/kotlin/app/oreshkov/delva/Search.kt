package app.oreshkov.delva

import app.oreshkov.delva.internal.query.CursorCodec
import kotlinx.coroutines.flow.Flow
import java.io.OutputStream

/**
 * The hits of one [KeyValueQuery] or [JsonPathQuery], read lazily page by page from one snapshot of the store
 * (SPEC.md §4.6, §8, §10.4): the result sees every operation acknowledged before the search started and nothing later,
 * so its pages together return every hit exactly once.
 *
 * Hits can be read until the result is closed. A result is consumed once: [nextPage], [iterator] and [hits] share
 * one position. Not thread-safe.
 */
@SubclassOptInRequired(DelvaImplementationApi::class)
public interface SearchResult : AutoCloseable {
    /** The snapshot the result reads, to correlate pages of different searches. */
    public val snapshotLsn: Long

    /** The next page: at most the query's `limit` hits within its `maxBytes`; null when no hits are left. */
    public fun nextPage(): SearchPage?

    /** The remaining hits across pages, in the query's order of `(docId, nodePre)`. */
    public operator fun iterator(): Iterator<SearchHit>

    /**
     * The remaining hits as a flow. Collecting it reads the store on the collecting coroutine's thread, so collect
     * on a dispatcher that allows blocking work.
     */
    public val hits: Flow<SearchHit>
}

/** One page of hits. */
public class SearchPage internal constructor(
    /** The page's hits, in the search's order. */
    public val hits: List<SearchHit>,
    /** Where a new search resumes after this page; null when this is the last page of the result. */
    public val cursor: Cursor?,
)

/** One page of the objects of a type, from [JsonStore.objectsOfTypePage]. */
public class TypePage internal constructor(
    /** The objects, in `(docId, nodePre)` order. */
    public val nodes: List<NodeRef>,
    /** Where [JsonStore.objectsOfTypePage] resumes after this page; null when no objects of the type were left. */
    public val cursor: Cursor?,
) {
    override fun toString(): String = "TypePage(${nodes.size} nodes${if (cursor == null) "" else ", more"})"
}

/** One hit: an object (CONTAINING_ELEMENT) or a document root (WHOLE_DOCUMENT). Readable while its result is open. */
@SubclassOptInRequired(DelvaImplementationApi::class)
public interface SearchHit {
    /** What the hit stands for: the containing object, or the document's root (SPEC.md §4.3). */
    public val node: NodeRef

    /** The document the hit is in. */
    public val docId: DocId get() = node.docId

    /** The type of [node] as the result's snapshot sees it, or null. */
    public val type: String?

    /**
     * The key of the hit's document in a store with [CreationOptions.documentKey], null in a store without one. Known
     * already for a [DocumentKeyQuery] hit, and looked up in the key index when first read for others.
     */
    @ExperimentalDelvaApi
    public val documentKey: String?

    /**
     * Where [node] is in its document, as an RFC 9535 Normalized Path from the document root (SPEC.md §3.2):
     * `$['orders'][3]`, and `$` for the root. Computed when first read: a JSONPath hit's is known already, a key–value
     * hit's is found by reading the fragments from the root down to the node.
     */
    public val path: String

    /**
     * Payload bytes that store the node, including the fragments it refers to, before compression. It predicts the
     * size of the JSON output roughly, not exactly.
     */
    public val encodedSize: Long

    /** Resume point strictly past this hit, in the query's order. */
    public val cursor: Cursor

    /** Writes the node as JSON (SPEC.md §5) with memory independent of its size. Doesn't close [out]. */
    public fun writeJsonTo(out: OutputStream): Unit = writeJsonTo(out, JsonFormat.COMPACT)

    /** Writes the node as JSON laid out in [format] (SPEC.md §5.1) with memory independent of its size. Doesn't close [out]. */
    public fun writeJsonTo(out: OutputStream, format: JsonFormat)

    /** The node as a JSON string; throws [ResultTooLargeException] if it has more than [maxChars] characters. */
    public fun readJsonAsString(maxChars: Int = 1_000_000): String = readJsonAsString(JsonFormat.COMPACT, maxChars)

    /**
     * The node as a JSON string laid out in [format]; throws [ResultTooLargeException] if the text has more than
     * [maxChars] characters, its whitespace included.
     */
    public fun readJsonAsString(format: JsonFormat, maxChars: Int = 1_000_000): String
}

/**
 * A node of a stored document (SPEC.md §3.2): its document, [docId], and its pre-order number, [nodePre], and nothing
 * more. The pair is the whole of a node reference, now and in later versions: it can be stored, compared and rebuilt
 * from its two numbers.
 */
public class NodeRef(public val docId: DocId, public val nodePre: Long) : Comparable<NodeRef> {
    init {
        require(nodePre >= 0) { "nodePre must not be negative, was $nodePre" }
    }

    override fun compareTo(other: NodeRef): Int {
        val c = docId.raw.compareTo(other.docId.raw)
        return if (c != 0) c else nodePre.compareTo(other.nodePre)
    }

    override fun equals(other: Any?): Boolean = other is NodeRef && other.docId == docId && other.nodePre == nodePre

    override fun hashCode(): Int = docId.hashCode() * 31 + nodePre.hashCode()

    override fun toString(): String = "NodeRef(${docId.raw}, $nodePre)"
}

/**
 * An opaque position in a result sequence: the hits strictly past `(docId, nodePre)` in the order of the search that
 * produced it — after it for [SearchOrder.ASCENDING], before it for [SearchOrder.DESCENDING]. [toString] gives a
 * compact URL-safe text, and [parse] reads it back. A cursor resumes only what produced it: a search of the same
 * [SearchOrder] and kind, or [JsonStore.objectsOfTypePage] for the cursor of a [TypePage]; anything else throws
 * [InvalidCursorException].
 *
 * A cursor used in a new search sees the store's newer state. A document whose insert started before the cursor's
 * page was produced but was acknowledged later can land in the stretch the earlier pages already covered and is then
 * missed; [mayMissLateCommits] tells whether that's possible. A descending search resumed from a cursor never returns
 * documents above the cursor's, so documents inserted since the first page aren't returned either.
 *
 * The cursor of a [DocumentKeyQuery] is a position in key order: it resumes strictly past the key of its last hit
 * ([docId] is that hit's document, [nodePre] 0), so a document inserted since with a key the earlier pages passed isn't
 * returned. Its text is at most about 1,400 characters, as it holds the key.
 */
public class Cursor internal constructor(
    public val docId: DocId,
    public val nodePre: Long,
    /** The snapshot of the page that produced the cursor. */
    public val snapshotLsn: Long,
    /**
     * Ascending: the smallest docId in flight at [snapshotLsn] (or one past the largest allocated when none was).
     * Descending: the largest docId in flight then, or -1. For a key cursor, 1 when an insert was in flight then, else 0.
     */
    internal val lateCommitBound: Long,
    internal val order: SearchOrder,
    /** True for the cursor of a [TypePage], which only [JsonStore.objectsOfTypePage] takes. */
    internal val forTypes: Boolean,
    /** The document key of the last hit of a [DocumentKeyQuery] page, which only such a query takes; else null. */
    internal val key: String?,
) {
    // Not default arguments: those would put a synthetic public constructor into the ABI.
    internal constructor(docId: DocId, nodePre: Long, snapshotLsn: Long, lateCommitBound: Long, order: SearchOrder) :
        this(docId, nodePre, snapshotLsn, lateCommitBound, order, forTypes = false, key = null)

    internal constructor(docId: DocId, nodePre: Long, snapshotLsn: Long, lateCommitBound: Long, order: SearchOrder, forTypes: Boolean) :
        this(docId, nodePre, snapshotLsn, lateCommitBound, order, forTypes, key = null)

    /**
     * True when an insert that was still in flight at [snapshotLsn] has a docId the earlier pages already passed: below
     * [docId] in an ascending search, above it in a descending one. For the cursor of a [DocumentKeyQuery], true when any
     * insert was in flight then, as its key can't be known.
     */
    public val mayMissLateCommits: Boolean
        get() = when {
            key != null -> lateCommitBound != 0L
            order == SearchOrder.ASCENDING -> lateCommitBound < docId.raw
            else -> lateCommitBound > docId.raw
        }

    override fun toString(): String = CursorCodec.encode(this)

    override fun equals(other: Any?): Boolean =
        other is Cursor && other.docId == docId && other.nodePre == nodePre && other.snapshotLsn == snapshotLsn &&
            other.lateCommitBound == lateCommitBound && other.order == order && other.forTypes == forTypes && other.key == key

    override fun hashCode(): Int =
        listOf(docId, nodePre, snapshotLsn, lateCommitBound, order, forTypes, key).hashCode()

    public companion object {
        /** Reads a cursor from [toString] text; throws [InvalidCursorException] if it isn't one. */
        public fun parse(text: String): Cursor = CursorCodec.decode(text)
    }
}
