package app.oreshkov.delva

import java.io.OutputStream

/**
 * A vector search (SPEC.md §11.3): the [k] visible documents whose vectors are nearest to [vector] under the store's
 * [VectorField.metric], nearest first, in a store with a [CreationOptions.vectorField].
 *
 * - [vector]: the query vector, of the field's dimensions; its numbers must be finite and, under
 *   [VectorMetric.COSINE], not all zero. The query keeps a copy, and [vector] returns another.
 * - [k]: how many hits at most, from 1 to [MAX_K].
 * - [maxDistance]: when given, documents farther than it are left out, so a result can have fewer than [k] hits.
 * - [scan]: what to do when the [documentFilter]'s index terms narrow nothing (see [JsonStore.explain]).
 * - [documentFilter]: only the documents it accepts are candidates; set with [withDocumentFilter].
 *
 * Queries are values: two instances with the same settings, the vector's numbers compared bit by bit, are equal, and
 * [copy] derives a variant. Throws [IllegalArgumentException] for an empty vector, a number that isn't finite, a [k]
 * outside `1..`[MAX_K] and a [maxDistance] that is NaN.
 */
@ExperimentalDelvaApi
public class VectorQuery private constructor(
    private val values: FloatArray,
    public val k: Int,
    public val maxDistance: Float?,
    public val scan: ScanPolicy,
    /**
     * The condition a document has to meet to be a candidate (SPEC.md §10.5), or null for every document with a vector.
     * A comparison of one of its root's members with a literal is an index term that narrows the candidates; a filter
     * without one needs [scan] to be [ScanPolicy.ALLOW_SCAN].
     */
    public val documentFilter: DocumentFilter?,
) {
    /** The [k] documents nearest to [vector], within [maxDistance] when it's given. */
    public constructor(
        vector: FloatArray,
        k: Int = 10,
        maxDistance: Float? = null,
        scan: ScanPolicy = ScanPolicy.REJECT,
    ) : this(vector.copyOf(), k, maxDistance, scan, null)

    init {
        require(values.isNotEmpty()) { "the query vector is empty" }
        for (i in values.indices) require(values[i].isFinite()) { "element $i of the query vector is ${values[i]}, not a finite number" }
        require(k in 1..MAX_K) { "k must be in 1..$MAX_K, was $k" }
        require(maxDistance == null || !maxDistance.isNaN()) { "maxDistance must not be NaN" }
    }

    /** A copy of the query vector. */
    public val vector: FloatArray get() = values.copyOf()

    /** How many numbers the query vector has, which must be the vector field's dimensions. */
    public val dimensions: Int get() = values.size

    /** The query vector itself, never handed out. */
    internal val queryValues: FloatArray get() = values

    /** This query with [documentFilter] set to [filter], or removed with null. */
    public fun withDocumentFilter(filter: DocumentFilter?): VectorQuery = VectorQuery(values, k, maxDistance, scan, filter)

    /** This query with the given settings changed, the [documentFilter] kept; the result is validated like a new instance. */
    public fun copy(
        vector: FloatArray = this.values,
        k: Int = this.k,
        maxDistance: Float? = this.maxDistance,
        scan: ScanPolicy = this.scan,
    ): VectorQuery = VectorQuery(if (vector === values) values else vector.copyOf(), k, maxDistance, scan, documentFilter)

    override fun equals(other: Any?): Boolean =
        other is VectorQuery && other.values.contentEquals(values) && other.k == k && other.maxDistance == maxDistance &&
            other.scan == scan && other.documentFilter == documentFilter

    override fun hashCode(): Int = listOf(values.contentHashCode(), k, maxDistance, scan, documentFilter).hashCode()

    /** Names the query vector by its dimensions only: it has up to 4,096 numbers. */
    override fun toString(): String = buildString {
        append("VectorQuery(dimensions=").append(values.size).append(", k=").append(k)
        if (maxDistance != null) append(", maxDistance=").append(maxDistance)
        if (scan != ScanPolicy.REJECT) append(", scan=").append(scan)
        if (documentFilter != null) append(", documentFilter=").append(documentFilter)
        append(')')
    }

    public companion object {
        /**
         * The most hits a vector search returns. A search keeps its hits in memory until it ends, as there is no
         * position in a nearest-first order to resume from.
         */
        public const val MAX_K: Int = 10_000
    }
}

/** How a [VectorQuery] finds its candidates, as [JsonStore.explain] reports it without running the search. */
@ExperimentalDelvaApi
public class VectorQueryPlan internal constructor(
    /**
     * How many vectors the index holds, which is the most a search compares: an unfiltered search compares every one of
     * them whose document is visible. The count includes the vectors of deleted documents until compaction drops them.
     */
    public val vectors: Long,
    /** True when no index term narrows the candidates, so every vector is compared (or the search is rejected). */
    public val fullScan: Boolean,
    /** The index terms every candidate document has, as [QueryPlan.terms] writes them, or null when none narrow it. */
    public val terms: String?,
    /**
     * Whether the search reads documents: with a document filter, the root of each candidate near enough to be a hit
     * is read to test the filter. Without one, only the vector index is read.
     */
    public val readsDocuments: Boolean,
) {
    override fun toString(): String = buildString {
        append("VectorQueryPlan(").append(vectors).append(" vectors, ")
        append(if (fullScan) "full scan" else terms)
        if (readsDocuments) append(", reads documents")
        append(')')
    }
}

/**
 * The hits of one [VectorQuery], nearest first, found on one snapshot of the store (SPEC.md §8, §11.3). The search
 * compares the vectors when it starts; the hits' documents are read only when a hit is read, from the same snapshot,
 * until the result is closed. Not thread-safe; hits may be read from other threads.
 */
@ExperimentalDelvaApi
@SubclassOptInRequired(DelvaImplementationApi::class)
public interface VectorSearchResult : AutoCloseable {
    /** The snapshot the result reads, to correlate it with other searches. */
    public val snapshotLsn: Long

    /** Every hit, nearest first, and documents of equal distance by docId ascending; at most the query's `k`. */
    public val hits: List<VectorHit>

    /** The [hits] in their order. */
    public operator fun iterator(): Iterator<VectorHit> = hits.iterator()
}

/** One hit of a vector search: a document's root and its distance to the query vector. Readable while its result is open. */
@ExperimentalDelvaApi
@SubclassOptInRequired(DelvaImplementationApi::class)
public interface VectorHit {
    /** The document. */
    public val docId: DocId

    /** The document's root, the node the hit stands for. */
    public val node: NodeRef get() = NodeRef(docId, 0)

    /**
     * The distance from the query vector to the document's, under the store's metric (SPEC.md §11.3): smaller is closer.
     * Never NaN; infinite when it is too large for a float, as a dot product of huge vectors can be.
     */
    public val distance: Float

    /** The type of the document's root as the result's snapshot sees it, or null. */
    public val type: String?

    /** The key of the document in a store with [CreationOptions.documentKey], looked up when first read; else null. */
    public val documentKey: String?

    /** Payload bytes that store the document, before compression, as [SearchHit.encodedSize] counts them. */
    public val encodedSize: Long

    /** Writes the document as JSON (SPEC.md §5) with memory independent of its size. Doesn't close [out]. */
    public fun writeJsonTo(out: OutputStream): Unit = writeJsonTo(out, JsonFormat.COMPACT)

    /** Writes the document as JSON laid out in [format] (SPEC.md §5.1) with memory independent of its size. Doesn't close [out]. */
    public fun writeJsonTo(out: OutputStream, format: JsonFormat)

    /** The document as a JSON string; throws [ResultTooLargeException] if it has more than [maxChars] characters. */
    public fun readJsonAsString(maxChars: Int = 1_000_000): String = readJsonAsString(JsonFormat.COMPACT, maxChars)

    /**
     * The document as a JSON string laid out in [format]; throws [ResultTooLargeException] if the text has more than
     * [maxChars] characters, its whitespace included.
     */
    public fun readJsonAsString(format: JsonFormat, maxChars: Int = 1_000_000): String
}
