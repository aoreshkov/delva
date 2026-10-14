package app.oreshkov.delva

import java.math.BigDecimal

/**
 * A key–value search (SPEC.md §4): objects with a member [key] whose value, or an element of whose array value,
 * equals [value].
 *
 * - [mode]: [ResultMode.WHOLE_DOCUMENT] (the default) makes each matching document a hit, with the root as the hit's
 *   node; [ResultMode.CONTAINING_ELEMENT] each object that contains a match.
 * - [nested]: with [NestedHits.OUTERMOST_ONLY], a containing object inside another containing object of the same
 *   document is dropped.
 * - [limit]: at most this many hits per page.
 * - [maxBytes]: a page also ends before a hit that would take the sum of the page's [SearchHit.encodedSize]s above
 *   this; a page always holds at least one hit. [Long.MAX_VALUE] means no byte budget.
 * - [after]: resume strictly past this cursor's `(docId, nodePre)` in [order]. A cursor from a search of the other
 *   order, or from a [TypePage], is refused with [InvalidCursorException].
 * - [type]: only containing objects of this type match (SPEC.md §6.2); in WHOLE_DOCUMENT mode a document is a hit when
 *   one of its objects of this type contains a match. A search with an invalid name throws [InvalidTypeNameException].
 * - [order]: the order of the hits (SPEC.md §4.4). [SearchOrder.DESCENDING] with `limit = 1` gives the newest hit.
 *
 * Queries are values: two instances with the same settings are equal, and [copy] derives a variant.
 */
public class KeyValueQuery(
    public val key: String,
    public val value: JsonQueryValue,
    public val mode: ResultMode = ResultMode.WHOLE_DOCUMENT,
    public val nested: NestedHits = NestedHits.ALL,
    public val limit: Int = 100,
    public val maxBytes: Long = Long.MAX_VALUE,
    public val after: Cursor? = null,
    public val type: String? = null,
    public val order: SearchOrder = SearchOrder.ASCENDING,
) {
    init {
        require(limit > 0) { "limit must be positive, was $limit" }
        require(maxBytes > 0) { "maxBytes must be positive, was $maxBytes" }
        requireSearchCursor(after, order)
    }

    /** This query with the given settings changed; the result is validated like a new instance. */
    public fun copy(
        key: String = this.key,
        value: JsonQueryValue = this.value,
        mode: ResultMode = this.mode,
        nested: NestedHits = this.nested,
        limit: Int = this.limit,
        maxBytes: Long = this.maxBytes,
        after: Cursor? = this.after,
        type: String? = this.type,
        order: SearchOrder = this.order,
    ): KeyValueQuery = KeyValueQuery(key, value, mode, nested, limit, maxBytes, after, type, order)

    override fun equals(other: Any?): Boolean =
        other is KeyValueQuery && other.key == key && other.value == value && other.mode == mode && other.nested == nested &&
            other.limit == limit && other.maxBytes == maxBytes && other.after == after && other.type == type && other.order == order

    override fun hashCode(): Int = listOf(key, value, mode, nested, limit, maxBytes, after, type, order).hashCode()

    override fun toString(): String = buildString {
        append("KeyValueQuery(key=").append(key).append(", value=").append(value)
        append(", mode=").append(mode).append(", nested=").append(nested).append(", limit=").append(limit)
        if (maxBytes != Long.MAX_VALUE) append(", maxBytes=").append(maxBytes)
        if (after != null) append(", after=").append(after)
        if (type != null) append(", type=").append(type)
        if (order != SearchOrder.ASCENDING) append(", order=").append(order)
        append(')')
    }
}

/** A query's cursor must come from a search of the same order, in `(docId, nodePre)` order. */
internal fun requireSearchCursor(after: Cursor?, order: SearchOrder) {
    when {
        after == null -> {}
        after.forTypes -> throw InvalidCursorException("the cursor is from a page of objects of a type, not from a search")
        after.key != null -> throw InvalidCursorException("the cursor is from a search in key order, not in (docId, nodePre) order")
        after.order != order -> throw InvalidCursorException("the cursor is from a search in ${after.order} order, this one is in $order order")
    }
}

/**
 * A search of documents in key order (SPEC.md §3.3), in a store with a [CreationOptions.documentKey]: every visible
 * document whose key starts with [prefix] ("" for all), once, with its root as the hit and [SearchHit.documentKey] set.
 * Keys compare by code points, which is the order of their UTF-8 bytes; with [Normalization.NFC] the prefix is
 * normalized as keys are.
 *
 * - [limit], [maxBytes]: as for [KeyValueQuery].
 * - [after]: resume strictly past this cursor's key in [order]; only the cursor of a [DocumentKeyQuery] of the same
 *   order is taken, anything else throws [InvalidCursorException].
 * - [order]: [SearchOrder.ASCENDING] from the smallest key, [SearchOrder.DESCENDING] from the largest.
 *
 * A store without document keys refuses the query with [IllegalStateException]. Queries are values: two instances with
 * the same settings are equal, and [copy] derives a variant.
 */
@ExperimentalDelvaApi
public class DocumentKeyQuery(
    public val prefix: String = "",
    public val limit: Int = 100,
    public val maxBytes: Long = Long.MAX_VALUE,
    public val after: Cursor? = null,
    public val order: SearchOrder = SearchOrder.ASCENDING,
) {
    init {
        require(limit > 0) { "limit must be positive, was $limit" }
        require(maxBytes > 0) { "maxBytes must be positive, was $maxBytes" }
        when {
            after == null -> {}
            after.key == null -> throw InvalidCursorException("the cursor isn't from a search in key order")
            after.order != order -> throw InvalidCursorException("the cursor is from a search in ${after.order} order, this one is in $order order")
        }
    }

    /** This query with the given settings changed; the result is validated like a new instance. */
    public fun copy(
        prefix: String = this.prefix,
        limit: Int = this.limit,
        maxBytes: Long = this.maxBytes,
        after: Cursor? = this.after,
        order: SearchOrder = this.order,
    ): DocumentKeyQuery = DocumentKeyQuery(prefix, limit, maxBytes, after, order)

    override fun equals(other: Any?): Boolean =
        other is DocumentKeyQuery && other.prefix == prefix && other.limit == limit && other.maxBytes == maxBytes &&
            other.after == after && other.order == order

    override fun hashCode(): Int = listOf(prefix, limit, maxBytes, after, order).hashCode()

    override fun toString(): String = buildString {
        append("DocumentKeyQuery(prefix=").append(prefix).append(", limit=").append(limit)
        if (maxBytes != Long.MAX_VALUE) append(", maxBytes=").append(maxBytes)
        if (after != null) append(", after=").append(after)
        if (order != SearchOrder.ASCENDING) append(", order=").append(order)
        append(')')
    }
}

/** What an insert does when a visible document already has its document key ([CreationOptions.documentKey]). */
@ExperimentalDelvaApi
public enum class KeyConflict {
    /** The insert throws [WriteConflictException] naming the document and the key; nothing changes. */
    FAIL,

    /** The insert replaces that document, as [JsonStore.replace] would: both in one atomic change. */
    REPLACE,
}

/** A query value (SPEC.md §2): equality is typed, numbers compare by decimal value, strings by code points. */
public sealed interface JsonQueryValue {
    /** The JSON string [value], compared by code points. */
    public data class Str(val value: String) : JsonQueryValue

    /** The JSON number [value], compared by decimal value: `1`, `1.0` and `1e0` are the same value. */
    public data class Num(val value: BigDecimal) : JsonQueryValue {
        /** The number [value], given as a [Long]. */
        public constructor(value: Long) : this(BigDecimal.valueOf(value))
    }

    /** The boolean [value], `true` or `false`, which matches neither strings nor numbers. */
    public data class Bool(val value: Boolean) : JsonQueryValue

    /** JSON `null`, which matches only a stored `null`. */
    public data object Null : JsonQueryValue

    /**
     * Any JSON value given as JSON text, [literal], typically an object or array literal. Objects match regardless of
     * member order, arrays element by element. Numbers keep their full range, for example `0e3000000000`.
     */
    public data class Json(val literal: String) : JsonQueryValue
}

/** What a hit stands for (SPEC.md §4.3). */
public enum class ResultMode {
    /** Each matching document once; the hit's node is the root. */
    WHOLE_DOCUMENT,

    /** Each object that contains a match; the hit's node is that object. */
    CONTAINING_ELEMENT,
}

/** Handling of hits nested inside other hits of the same document (SPEC.md §4.5). */
public enum class NestedHits {
    /** Keep every hit, including those nested inside another hit's node. The default. */
    ALL,

    /** Drop a hit whose node is inside the node of another hit. No effect in [ResultMode.WHOLE_DOCUMENT]. */
    OUTERMOST_ONLY,
}

/** The order of a search's hits (SPEC.md §4.4). */
public enum class SearchOrder {
    /** `(docId, nodePre)` ascending: oldest document first. */
    ASCENDING,

    /** `(docId, nodePre)` descending: newest document first, and within a document the last node first. */
    DESCENDING,
}
