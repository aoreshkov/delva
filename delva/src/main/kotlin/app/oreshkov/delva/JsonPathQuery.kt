package app.oreshkov.delva

/**
 * A JSONPath search (SPEC.md §10.4): the nodes [path] selects from the documents of the store. Hits are the distinct
 * selected nodes in `(docId, nodePre)` order, ascending or descending by [order] — each node once, unlike an RFC 9535
 * nodelist, which [JsonStore.select] gives for one value.
 *
 * - [mode]: [ResultMode.CONTAINING_ELEMENT] (the default) makes each selected node a hit; [ResultMode.WHOLE_DOCUMENT]
 *   each document where the query selects at least one node, with the root as the hit's node.
 * - [nested]: with [NestedHits.OUTERMOST_ONLY], a selected node inside another selected node of the same document is
 *   dropped.
 * - [limit], [maxBytes], [after]: pagination, as in [KeyValueQuery].
 * - [type]: only selected objects of this type are hits. A search with an invalid name throws
 *   [InvalidTypeNameException].
 * - [scan]: what to do when the index can't narrow the search down (see [JsonStore.explain]).
 * - [order]: the order of the hits, as in [KeyValueQuery].
 * - [documentFilter]: only the documents it accepts are searched; set with [withDocumentFilter].
 *
 * Queries are values: two instances with the same settings are equal, and [copy] derives a variant.
 */
public class JsonPathQuery @ExperimentalDelvaApi constructor(
    public val path: JsonPath,
    public val mode: ResultMode = ResultMode.CONTAINING_ELEMENT,
    public val nested: NestedHits = NestedHits.ALL,
    public val limit: Int = 100,
    public val maxBytes: Long = Long.MAX_VALUE,
    public val after: Cursor? = null,
    public val type: String? = null,
    public val scan: ScanPolicy = ScanPolicy.REJECT,
    public val order: SearchOrder = SearchOrder.ASCENDING,
    documentFilter: DocumentFilter?,
) {
    /** A search of every document. */
    public constructor(
        path: JsonPath,
        mode: ResultMode = ResultMode.CONTAINING_ELEMENT,
        nested: NestedHits = NestedHits.ALL,
        limit: Int = 100,
        maxBytes: Long = Long.MAX_VALUE,
        after: Cursor? = null,
        type: String? = null,
        scan: ScanPolicy = ScanPolicy.REJECT,
        order: SearchOrder = SearchOrder.ASCENDING,
    ) : this(path, mode, nested, limit, maxBytes, after, type, scan, order, null)

    /**
     * The condition a document has to meet to be searched (SPEC.md §10.5), or null to search every document. A
     * comparison of one of its root's members with a literal is an index term the search requires, as one in [path]
     * is: a query whose path alone would need a scan doesn't when the filter names such a term.
     */
    @ExperimentalDelvaApi
    public val documentFilter: DocumentFilter? = documentFilter

    init {
        require(limit > 0) { "limit must be positive, was $limit" }
        require(maxBytes > 0) { "maxBytes must be positive, was $maxBytes" }
        requireSearchCursor(after, order)
    }

    /** This query with [documentFilter] set to [filter], or removed with null. */
    @ExperimentalDelvaApi
    public fun withDocumentFilter(filter: DocumentFilter?): JsonPathQuery =
        JsonPathQuery(path, mode, nested, limit, maxBytes, after, type, scan, order, filter)

    /** This query with the given settings changed, the [documentFilter] kept; the result is validated like a new instance. */
    public fun copy(
        path: JsonPath = this.path,
        mode: ResultMode = this.mode,
        nested: NestedHits = this.nested,
        limit: Int = this.limit,
        maxBytes: Long = this.maxBytes,
        after: Cursor? = this.after,
        type: String? = this.type,
        scan: ScanPolicy = this.scan,
        order: SearchOrder = this.order,
    ): JsonPathQuery = JsonPathQuery(path, mode, nested, limit, maxBytes, after, type, scan, order, documentFilter)

    override fun equals(other: Any?): Boolean =
        other is JsonPathQuery && other.path == path && other.mode == mode && other.nested == nested && other.limit == limit &&
            other.maxBytes == maxBytes && other.after == after && other.type == type && other.scan == scan && other.order == order &&
            other.documentFilter == documentFilter

    override fun hashCode(): Int = listOf(path, mode, nested, limit, maxBytes, after, type, scan, order, documentFilter).hashCode()

    override fun toString(): String = buildString {
        append("JsonPathQuery(path=").append(path)
        append(", mode=").append(mode).append(", nested=").append(nested).append(", limit=").append(limit)
        if (maxBytes != Long.MAX_VALUE) append(", maxBytes=").append(maxBytes)
        if (after != null) append(", after=").append(after)
        if (type != null) append(", type=").append(type)
        if (scan != ScanPolicy.REJECT) append(", scan=").append(scan)
        if (order != SearchOrder.ASCENDING) append(", order=").append(order)
        if (documentFilter != null) append(", documentFilter=").append(documentFilter)
        append(')')
    }
}

/** What a JSONPath search does when no index term narrows it down (SPEC.md §10.4). */
public enum class ScanPolicy {
    /** Throw [QueryRejectedException], as a key–value search the index can't answer does. */
    REJECT,

    /** Evaluate the query on every visible document. */
    ALLOW_SCAN,
}

/** How a JSONPath search finds its candidate documents, as [JsonStore.explain] reports it. */
public class QueryPlan internal constructor(
    /** True when no index term narrows the search, so it reads every visible document (or is rejected). */
    public val fullScan: Boolean,
    /**
     * The index terms every candidate document has, as a formula such as `'price' == 10 AND ('tag' == 'a' OR 'tag' ==
     * 'b')`, or null for a full scan. Each term is a member key and a value it equals, directly or as an element of an
     * array value.
     */
    public val terms: String?,
    /**
     * When the query fixes where the objects its filter tests are — each segment before the filter one name or index —
     * the path of their parent, with indexes written `[*]`, such as `$['orders']`. Postings carry a hash of that path
     * (SPEC.md §15), so documents whose matching objects are elsewhere are skipped without being read. Null otherwise.
     */
    public val within: String? = null,
) {
    override fun toString(): String = when {
        fullScan -> "QueryPlan(full scan)"
        within != null -> "QueryPlan($terms within $within)"
        else -> "QueryPlan($terms)"
    }
}
