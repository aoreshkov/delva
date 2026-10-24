package app.oreshkov.delva.intellij.store

import java.nio.file.Path

/**
 * Reads Delva stores for the IDE. Everything it returns is the plugin's own type, so no Delva type reaches the UI, and
 * a backend that runs the project's own Delva version out of process can take this one's place.
 */
internal interface StoreBackend {
    /** What the store in [dir] holds, as of now. Throws [StoreAccessException]. */
    suspend fun info(dir: Path): StoreSummary

    /**
     * Up to [limit] documents of the store in [dir]: in key order in a store with document keys, in id order otherwise.
     * The first page when [after] is null, else the page after the one whose [DocumentPage.next] it is. Throws
     * [StoreAccessException].
     */
    suspend fun documents(dir: Path, after: String?, limit: Int): DocumentPage

    /**
     * The node [nodePre] of the document [docId] of the store in [dir] as indented JSON, 0 being the document itself, or
     * null when that's longer than [maxChars] characters. Throws [StoreAccessException], also when the document is no
     * longer in the store.
     */
    suspend fun readNode(dir: Path, docId: Long, nodePre: Long, maxChars: Int): String?

    /**
     * Up to [limit] hits of [query] in the store in [dir], in the order they were stored. The first page when [after] is
     * null, else the page after the one whose [HitPage.next] it is. Throws [StoreAccessException], also for a query the
     * store refuses.
     */
    suspend fun search(dir: Path, query: StoreQuery, after: String?, limit: Int): HitPage

    /** How the store in [dir] would find the documents [query] searches, without reading any. */
    suspend fun explain(dir: Path, query: StoreQuery.ByPath): SearchPlan

    /** Why [query] isn't a JSONPath query the store can run, or null when it is one. */
    fun checkQuery(query: String): String?

    /** Why [filter] isn't a filter on a document's root the store can apply, or null when it is one. */
    fun checkFilter(filter: String): String?

    /**
     * Closes the store in [dir] if this backend has it open, so no file of a store the project no longer lists stays
     * open. The next read opens it again.
     */
    fun release(dir: Path)
}

internal data class StoreSummary(
    val documents: Long,
    val deletedDocuments: Long,
    /** The process that has the store open as its writer, as a person reads it, or null when none has. */
    val writer: String?,
    /** The JSON path of the string that names each document, or null in a store without document keys. */
    val documentKey: String?,
)

internal data class DocumentPage(
    val documents: List<DocumentEntry>,
    /** Where the next page starts, for [StoreBackend.documents], or null when this page is the last. */
    val next: String?,
)

internal data class DocumentEntry(
    val docId: Long,
    /** The document's key, or null in a store without document keys. */
    val key: String?,
    /** Payload bytes that store the document, before compression: roughly the size of its JSON. */
    val encodedSize: Long,
)

/** A search of a store, as a person puts it together in the IDE. */
internal sealed interface StoreQuery {
    /** Whether each hit is a whole document, rather than the node the query matched or the object that holds it. */
    val wholeDocuments: Boolean

    /**
     * The nodes the RFC 9535 JSONPath [path] selects, in the documents whose root passes [where] when it isn't null.
     * Unless [scan] allows reading every document, a query that no index term narrows is refused.
     */
    data class ByPath(val path: String, val where: String?, override val wholeDocuments: Boolean, val scan: Boolean) : StoreQuery

    /**
     * The objects with a member [key] equal to [value], or holding it in an array: [value] is JSON text, or the string
     * itself when [valueIsText].
     */
    data class ByKeyValue(val key: String, val value: String, val valueIsText: Boolean, override val wholeDocuments: Boolean) :
        StoreQuery
}

internal data class HitPage(
    val hits: List<StoreHit>,
    /** Where the next page starts, for [StoreBackend.search], or null when this page is the last. */
    val next: String?,
)

internal data class StoreHit(
    val docId: Long,
    /** The node's number in its document, in pre-order: 0 for the document itself. */
    val nodePre: Long,
    val documentKey: String?,
    /** The node's normalized path in its document. */
    val path: String,
    /** The node as compact JSON, cut off after a line's worth of characters. */
    val preview: String,
    /** Whether [preview] is only the start of the node's JSON. */
    val previewCut: Boolean,
    val encodedSize: Long,
)

internal data class SearchPlan(
    /** Whether the search reads every document, as no index term narrows it. */
    val fullScan: Boolean,
    /** The index terms every document it reads has, such as `'kind' == 'order'`, or null for a full scan. */
    val terms: String?,
    /** The path of the parent of the objects the query's filter tests, when the query fixes it, else null. */
    val within: String?,
)

/** A store couldn't be read; [message] says why, for a person. */
internal class StoreAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)
