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
import app.oreshkov.delva.SelectedNode
import app.oreshkov.delva.StoreNotFoundException
import app.oreshkov.delva.UnsupportedFormatException
import app.oreshkov.delva.VerifyIssue
import app.oreshkov.delva.VerifyIssueKind
import app.oreshkov.delva.VerifyPhase
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

    override suspend fun probe(dir: Path): StoreProbe = withContext(Dispatchers.IO) {
        try {
            val format = JsonStore.probe(dir)
            when {
                format == null -> StoreProbe.NoStore
                format.supported -> StoreProbe.Readable(format.version)
                else -> StoreProbe.OtherFormat(format.version, format.supportedVersions)
            }
        } catch (e: CorruptionException) {
            StoreProbe.Damaged(DelvaBundle.message("store.corrupt", e.message.orEmpty()))
        } catch (e: IOException) {
            throw StoreAccessException(DelvaBundle.message("store.io", dir, e.message.orEmpty()), e)
        }
    }

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

    override suspend fun types(dir: Path): List<TypeSummary> = reading(dir) { store ->
        store.listTypes().map { TypeSummary(it.name, it.approximateCount) }
    }

    override suspend fun objectsOfType(dir: Path, type: String, after: String?, limit: Int): HitPage {
        require(limit > 0) { "limit must be positive, was $limit" }
        return reading(dir) { store ->
            // Paged by the last object, not by Delva's cursor, which only the page it came with can hand out: asking for
            // one object more than kept tells whether the page is the last.
            val nodes = store.objectsOfType(type, after?.let(::parseNodeRef), limit + 1)
            val kept = nodes.take(limit)
            val keyPath = store.creationOptions.documentKey?.let(JsonPath::compile)
            val keys = HashMap<Long, String?>()
            val hits = kept.map { node ->
                ensureActive()
                val key = keyPath?.let { path -> keys.getOrPut(node.docId.raw) { store.documentKey(node.docId, path) } }
                val (preview, cut) = store.select(node, ROOT).use { result ->
                    val selected: SelectedNode = result.iterator().next()
                    preview { out -> selected.writeJsonTo(out, JsonFormat.COMPACT) }
                }
                StoreHit(node.docId.raw, node.nodePre, key, store.pathOf(node), preview, cut, encodedSize = null)
            }
            HitPage(hits, if (nodes.size > limit) kept.last().let { "${it.docId.raw}:${it.nodePre}" } else null)
        }
    }

    override suspend fun verify(dir: Path, progress: (VerifyStep, Long, Long) -> Unit): VerifyResult = reading(dir) { store ->
        val report = store.verify { phase, checked, total -> progress(phase.toStep(), checked, total) }
        VerifyResult(report.filesChecked, report.bytesChecked, report.issues.map { it.toProblem() })
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
            } catch (e: UnsupportedFormatException) {
                throw StoreAccessException(otherFormatMessage(e.version, e.supportedVersions), e)
            } catch (e: CorruptionException) {
                throw StoreAccessException(DelvaBundle.message("store.corrupt", e.message.orEmpty()), e)
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
        val (preview, cut) = preview { out -> writeJsonTo(out, JsonFormat.COMPACT) }
        return StoreHit(docId.raw, node.nodePre, documentKey, path, preview, cut, encodedSize)
    }

    /** The start of the JSON [write] writes, and whether it was cut off there. */
    private fun preview(write: (OutputStream) -> Unit): Pair<String, Boolean> {
        // Writing stops after the preview's bytes, so a large node isn't read to the end for its first line.
        val preview = PreviewOutput(PREVIEW_BYTES)
        try {
            write(preview)
        } catch (_: PreviewOutput.Full) {
        }
        return preview.text() to preview.cut
    }

    /** The key of the document [docId], which the string at [keyPath] holds, or null if it's longer than keys get. */
    private fun SecondaryJsonStore.documentKey(docId: DocId, keyPath: JsonPath): String? =
        select(NodeRef(docId, 0), keyPath).use { result ->
            val selected = result.iterator()
            if (!selected.hasNext()) return@use null
            try {
                unquote(selected.next().readJsonAsString(JsonFormat.COMPACT, MAX_KEY_CHARS))
            } catch (_: ResultTooLargeException) {
                null
            }
        }

    private fun parseNodeRef(text: String): NodeRef {
        val (docId, nodePre) = text.split(':').map(String::toLong)
        return NodeRef(DocId(docId), nodePre)
    }

    private fun VerifyPhase.toStep(): VerifyStep = when (this) {
        VerifyPhase.LOG -> VerifyStep.LOG
        VerifyPhase.INDEX -> VerifyStep.INDEX
        VerifyPhase.FRAGMENTS -> VerifyStep.FRAGMENTS
    }

    private fun VerifyIssue.toProblem(): VerifyProblem {
        val kind = when (kind) {
            VerifyIssueKind.FILE -> VerifyProblem.Kind.FILE
            VerifyIssueKind.LOG_RECORD -> VerifyProblem.Kind.LOG_RECORD
            VerifyIssueKind.SEGMENT -> VerifyProblem.Kind.SEGMENT
            VerifyIssueKind.COMMIT -> VerifyProblem.Kind.COMMIT
            VerifyIssueKind.FRAGMENT_INDEX -> VerifyProblem.Kind.FRAGMENT_INDEX
            VerifyIssueKind.KEY_INDEX -> VerifyProblem.Kind.KEY_INDEX
        }
        return VerifyProblem(kind, location, detail, damagedBytes, document?.raw)
    }

    private fun describe(holder: LockHolder): String {
        val host = holder.host
        return if (host != null) DelvaBundle.message("writer.processOnHost", holder.pid, host)
        else DelvaBundle.message("writer.process", holder.pid)
    }

    private companion object {
        /** Selects a document's root: each document once, in a search of every document. */
        val ROOT: JsonPath = JsonPath.compile("$")

        /** UTF-8 bytes of a hit's JSON kept for its preview: more than a list row shows. */
        const val PREVIEW_BYTES = 400

        /** Characters of a document key's JSON read at most; far more than a list row or a tab title shows. */
        const val MAX_KEY_CHARS = 64 * 1024
    }
}

/** The string the JSON string literal [json] stands for. */
private fun unquote(json: String): String {
    require(json.length >= 2 && json.first() == '"' && json.last() == '"') { "Not a JSON string: $json" }
    val text = StringBuilder(json.length - 2)
    var i = 1
    while (i < json.length - 1) {
        val c = json[i++]
        if (c != '\\') {
            text.append(c)
            continue
        }
        when (val escaped = json[i++]) {
            'b' -> text.append('\b')
            'f' -> text.append('\u000C')
            'n' -> text.append('\n')
            'r' -> text.append('\r')
            't' -> text.append('\t')
            'u' -> text.append(json.substring(i, i + 4).toInt(16).toChar()).also { i += 4 }
            else -> text.append(escaped)
        }
    }
    return text.toString()
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
