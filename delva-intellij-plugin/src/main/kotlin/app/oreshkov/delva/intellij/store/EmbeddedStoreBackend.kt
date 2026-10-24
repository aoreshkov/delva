package app.oreshkov.delva.intellij.store

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DelvaException
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.InvalidJsonPathException
import app.oreshkov.delva.JsonFormat
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.LockHolder
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.ResultTooLargeException
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.SearchHit
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.SecondaryJsonStore
import app.oreshkov.delva.StoreNotFoundException
import app.oreshkov.delva.intellij.DelvaBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path

/**
 * Reads stores with the Delva version bundled in the plugin, as secondaries: they take no lock and never write, so a
 * store can be read while an application has it open. Each store stays open until [close], and catches up with its
 * writer before every read.
 */
@OptIn(ExperimentalDelvaApi::class)
internal class EmbeddedStoreBackend : StoreBackend, AutoCloseable {
    private val stores = HashMap<Path, SecondaryJsonStore>()
    private var closed = false

    override suspend fun info(dir: Path): StoreSummary = reading(dir) { store ->
        val info = store.info()
        StoreSummary(
            info.approximateDocumentCount,
            info.deletedDocumentCount,
            store.writer()?.let(::describe),
            store.creationOptions.documentKey,
        )
    }

    override suspend fun documents(dir: Path, after: String?, limit: Int): DocumentPage {
        require(limit > 0) { "limit must be positive, was $limit" }
        return reading(dir) { store ->
            val cursor = after?.let(Cursor::parse)
            val result = if (store.creationOptions.documentKey != null) {
                store.search(DocumentKeyQuery(limit = limit + 1, after = cursor))
            } else {
                store.search(JsonPathQuery(ROOT, limit = limit + 1, after = cursor, scan = ScanPolicy.ALLOW_SCAN))
            }
            val (documents, next) = result.use { page(it, limit) { hit -> DocumentEntry(hit.docId.raw, hit.documentKey, hit.encodedSize) } }
            DocumentPage(documents, next)
        }
    }

    override suspend fun readNode(dir: Path, docId: Long, nodePre: Long, maxChars: Int): String? = reading(dir) { store ->
        store.select(NodeRef(DocId(docId), nodePre), ROOT).use { result ->
            try {
                result.iterator().next().readJsonAsString(JsonFormat.PRETTY, maxChars)
            } catch (_: ResultTooLargeException) {
                null
            }
        }
    }

    override suspend fun search(dir: Path, query: StoreQuery, after: String?, limit: Int): HitPage {
        require(limit > 0) { "limit must be positive, was $limit" }
        // Compiled before the store is opened, so a mistake in the query is reported as one even where no store is.
        val pathQuery = (query as? StoreQuery.ByPath)?.toDelva(limit + 1)
        return reading(dir) { store ->
            val cursor = after?.let(Cursor::parse)
            try {
                val result = when (query) {
                    is StoreQuery.ByPath -> store.search(pathQuery!!.copy(after = cursor))
                    is StoreQuery.ByKeyValue -> store.search(query.toDelva(limit + 1, cursor))
                }
                val (hits, next) = result.use { page(it, limit) { hit -> hit.toStoreHit() } }
                HitPage(hits, next)
            } catch (e: QueryRejectedException) {
                if (e.reason != QueryRejectedException.Reason.NEEDS_SCAN) throw e
                throw StoreAccessException(DelvaBundle.message("search.needsScan"), e)
            } catch (e: DocumentRejectedException) {
                // The value is parsed when the search starts; text that isn't JSON most likely meant a string.
                if (query !is StoreQuery.ByKeyValue || query.valueIsText) throw e
                throw StoreAccessException(DelvaBundle.message("search.valueNotJson", query.value), e)
            }
        }
    }

    override suspend fun explain(dir: Path, query: StoreQuery.ByPath): SearchPlan {
        val pathQuery = query.toDelva(limit = 1)
        return reading(dir) { store ->
            val plan = store.explain(pathQuery)
            SearchPlan(plan.fullScan, plan.terms, plan.within)
        }
    }

    override fun checkQuery(query: String): String? = try {
        JsonPath.compile(query)
        null
    } catch (e: InvalidJsonPathException) {
        DelvaBundle.message("query.invalid", e.offset, e.reason)
    }

    override fun checkFilter(filter: String): String? = try {
        DocumentFilter.compile(filter)
        null
    } catch (e: InvalidJsonPathException) {
        DelvaBundle.message("filter.invalid", e.offset, e.reason)
    }

    override fun release(dir: Path) {
        synchronized(stores) { stores.remove(dir) }?.close()
    }

    override fun close() {
        val open = synchronized(stores) {
            closed = true
            stores.values.toList().also { stores.clear() }
        }
        for (store in open) store.close()
    }

    /**
     * Up to [limit] of [result]'s hits, each made an entry by [entry], and where the next page starts, or null when no
     * hits are left. The search asks for one hit more than it keeps, so a page that ends on the last hit says so.
     */
    private fun <T> CoroutineScope.page(result: SearchResult, limit: Int, entry: (SearchHit) -> T): Pair<List<T>, String?> {
        val entries = ArrayList<T>()
        var last: SearchHit? = null
        for (hit in result) {
            ensureActive()
            if (entries.size == limit) return entries to last!!.cursor.toString()
            entries += entry(hit)
            last = hit
        }
        return entries to null
    }

    private fun StoreQuery.ByPath.toDelva(limit: Int): JsonPathQuery {
        val compiled = try {
            JsonPath.compile(path)
        } catch (e: InvalidJsonPathException) {
            throw StoreAccessException(DelvaBundle.message("query.invalid", e.offset, e.reason), e)
        }
        val filter = try {
            where?.let(DocumentFilter::compile)
        } catch (e: InvalidJsonPathException) {
            throw StoreAccessException(DelvaBundle.message("filter.invalid", e.offset, e.reason), e)
        }
        return JsonPathQuery(
            compiled,
            mode = mode(wholeDocuments),
            limit = limit,
            scan = if (scan) ScanPolicy.ALLOW_SCAN else ScanPolicy.REJECT,
            documentFilter = filter,
        )
    }

    private fun StoreQuery.ByKeyValue.toDelva(limit: Int, after: Cursor?) = KeyValueQuery(
        key,
        if (valueIsText) JsonQueryValue.Str(value) else JsonQueryValue.Json(value),
        mode = mode(wholeDocuments),
        limit = limit,
        after = after,
    )

    private fun mode(wholeDocuments: Boolean) = if (wholeDocuments) ResultMode.WHOLE_DOCUMENT else ResultMode.CONTAINING_ELEMENT

    private suspend fun <T> reading(dir: Path, read: suspend CoroutineScope.(SecondaryJsonStore) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                val cached = synchronized(stores) {
                    check(!closed) { "The backend is closed" }
                    stores[dir]
                }
                // A store just opened has read the newest state already; a refresh costs as much as the open's replay.
                val store = cached?.also { it.refresh() } ?: open(dir)
                read(store)
            } catch (e: StoreNotFoundException) {
                throw StoreAccessException(DelvaBundle.message("store.notFound", dir), e)
            } catch (e: CorruptionException) {
                // Delva reports a store of another format version the way it reports damage; only the detail tells.
                val message = if (e.detail.startsWith("unsupported format version")) {
                    DelvaBundle.message("store.otherVersion", BUNDLED_DELVA_MINOR)
                } else {
                    DelvaBundle.message("store.corrupt", e.message.orEmpty())
                }
                throw StoreAccessException(message, e)
            } catch (e: DocumentNotFoundException) {
                throw StoreAccessException(DelvaBundle.message("document.notFound", e.docId.raw.toString()), e)
            } catch (e: DelvaException) {
                throw StoreAccessException(e.message.orEmpty(), e)
            } catch (e: IOException) {
                throw StoreAccessException(DelvaBundle.message("store.io", dir, e.message.orEmpty()), e)
            }
        }

    private fun open(dir: Path): SecondaryJsonStore {
        val store = JsonStore.openSecondary(dir)
        val kept = synchronized(stores) {
            if (closed) null else stores.putIfAbsent(dir, store) ?: store
        }
        // Lost a race with another opener, or the backend closed meanwhile.
        if (kept !== store) store.close()
        return kept ?: throw IllegalStateException("The backend is closed")
    }

    private fun SearchHit.toStoreHit(): StoreHit {
        // Writing stops after the preview's bytes, so a large node isn't read to the end for its first line.
        val preview = PreviewOutput(PREVIEW_BYTES)
        try {
            writeJsonTo(preview, JsonFormat.COMPACT)
        } catch (_: PreviewOutput.Full) {
        }
        return StoreHit(docId.raw, node.nodePre, documentKey, path, preview.text(), preview.cut, encodedSize)
    }

    private fun describe(holder: LockHolder): String {
        val host = holder.host
        return if (host != null) DelvaBundle.message("writer.processOnHost", holder.pid, host)
        else DelvaBundle.message("writer.process", holder.pid)
    }

    private companion object {
        /** The Delva minor this plugin bundles, whose store format is the only one it reads. */
        const val BUNDLED_DELVA_MINOR = "0.2"

        /** Selects a document's root: each document once, in a search of every document. */
        val ROOT: JsonPath = JsonPath.compile("$")

        /** UTF-8 bytes of a hit's JSON kept for its preview: more than a list row shows. */
        const val PREVIEW_BYTES = 400
    }
}

/** Keeps the first [limit] bytes written to it, and stops the writer with [Full] when more come. */
private class PreviewOutput(private val limit: Int) : OutputStream() {
    private val bytes = ByteArrayOutputStream(limit)

    /** Whether more was written than kept. */
    var cut = false
        private set

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        val room = limit - bytes.size()
        bytes.write(b, off, minOf(len, room))
        if (len > room) {
            cut = true
            throw Full()
        }
    }

    /** The bytes kept, as text: a character the limit cut in two is left out. */
    fun text(): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val out = CharBuffer.allocate(limit)
        // With endOfInput false, an incomplete sequence at the end stays in the input instead of being replaced.
        decoder.decode(ByteBuffer.wrap(bytes.toByteArray()), out, !cut)
        return out.flip().toString()
    }

    /** Thrown to the writer once the preview is full; it carries no stack trace, as it's no error. */
    class Full : RuntimeException(null, null, false, false)
}
