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
     * The first [limit] nodes the RFC 9535 JSONPath [query] selects in the store in [dir], each node's JSON cut off at
     * [maxChars] characters. Scans every document when the indexes can't narrow the query down. Throws
     * [StoreAccessException].
     */
    suspend fun searchPath(dir: Path, query: String, limit: Int, maxChars: Int): SearchOutcome

    /** Why [query] isn't a JSONPath query the store can run, or null when it is one. */
    fun checkQuery(query: String): String?

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
)

internal data class SearchOutcome(val hits: List<StoreHit>, val more: Boolean)

internal data class StoreHit(
    val docId: Long,
    val documentKey: String?,
    /** The node's normalized path in its document. */
    val path: String,
    /** The node as JSON text, or null when it's longer than the search allowed. */
    val json: String?,
    val encodedSize: Long,
)

/** A store couldn't be read; [message] says why, for a person. */
internal class StoreAccessException(message: String, cause: Throwable? = null) : Exception(message, cause)
