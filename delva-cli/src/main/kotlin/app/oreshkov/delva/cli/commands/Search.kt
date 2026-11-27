package app.oreshkov.delva.cli.commands

import app.oreshkov.clikit.CommandContext
import app.oreshkov.clikit.CommandFailure
import app.oreshkov.clikit.CommandHandler
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.InputSource
import app.oreshkov.clikit.JsonWriter
import app.oreshkov.clikit.field
import app.oreshkov.clikit.writeObject
import app.oreshkov.delva.BlockingReadableJsonStore
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorQuery
import app.oreshkov.delva.cli.DelvaCommands
import app.oreshkov.delva.cli.HitWriter
import app.oreshkov.delva.cli.ValueArgs
import app.oreshkov.delva.cli.printNearest
import app.oreshkov.delva.cli.queryStatus
import app.oreshkov.delva.cli.warnAbout
import app.oreshkov.delva.cli.withReader

/**
 * `delva search kv <key> <value>`: the value is JSON unless `--string` says it is text, because `42`, `"42"`, `true`
 * and `{"a":1}` all have to be expressible and only parsing tells them apart.
 */
@OptIn(ExperimentalDelvaApi::class)
internal val searchKv = CommandHandler { context ->
    val values = context.values
    val key = values.required(DelvaCommands.kvKey)
    val raw = values.required(DelvaCommands.kvValue)
    val asString = values[DelvaCommands.string]
    // A page resumed with --after continues a search that had hits, so finding nothing there says nothing about the key.
    val ifNone = if (values[DelvaCommands.after] == null) pathLikeKeyNotice(key) else null
    val query = KeyValueQuery(
        key = key,
        value = if (asString) JsonQueryValue.Str(raw) else JsonQueryValue.Json(raw),
        mode = values[DelvaCommands.mode] ?: ResultMode.WHOLE_DOCUMENT,
        nested = values[DelvaCommands.nested] ?: NestedHits.ALL,
        limit = values[DelvaCommands.limit] ?: PAGE_HITS,
        maxBytes = values[DelvaCommands.maxBytes] ?: Long.MAX_VALUE,
        after = values[DelvaCommands.after],
        type = values[DelvaCommands.type],
        order = values[DelvaCommands.order] ?: SearchOrder.ASCENDING,
    ).withMaxDocuments(values[DelvaCommands.maxDocuments])
    context.withReader { store, _ ->
        val blocking = store.blocking()
        try {
            if (values[DelvaCommands.count]) {
                context.printCount(blocking.count(query, context.countLimit()), ifNone)
            } else {
                blocking.search(query).use { context.printHits(it, ifNone) }
            }
        } catch (e: DocumentRejectedException) {
            // The value is parsed when the search starts; text that isn't JSON most likely meant a string.
            if (asString) throw e
            throw CommandFailure(
                ExitStatus.USAGE,
                "the value '$raw' is not JSON (${e.message}); add ${DelvaCommands.string.long} to search for it as text",
            )
        }
    }
}

/**
 * `delva search path <query> [--where <filter>]`. A query that no index term narrows would read every document; that
 * has to be asked for with `--scan`. Otherwise delva refuses it before anything is read, and [app.oreshkov.delva.cli.Failures] words the
 * refusal in this command line's options.
 */
@OptIn(ExperimentalDelvaApi::class)
internal val searchPath = CommandHandler { context ->
    val values = context.values
    val path = values.required(DelvaCommands.query)
    val scan = values[DelvaCommands.scan]
    val query = JsonPathQuery(
        path = path,
        mode = values[DelvaCommands.mode] ?: ResultMode.WHOLE_DOCUMENT,
        nested = values[DelvaCommands.nested] ?: NestedHits.ALL,
        limit = values[DelvaCommands.limit] ?: PAGE_HITS,
        maxBytes = values[DelvaCommands.maxBytes] ?: Long.MAX_VALUE,
        after = values[DelvaCommands.after],
        type = values[DelvaCommands.type],
        scan = if (scan) ScanPolicy.ALLOW_SCAN else ScanPolicy.REJECT,
        order = values[DelvaCommands.order] ?: SearchOrder.ASCENDING,
        documentFilter = values[DelvaCommands.where],
    ).withMaxDocuments(values[DelvaCommands.maxDocuments])
    context.warnAbout(path, query.documentFilter)
    context.withReader { store, _ ->
        val blocking = store.blocking()
        if (values[DelvaCommands.count]) {
            context.printCount(blocking.count(query, context.countLimit()))
        } else {
            blocking.search(query).use { context.printHits(it) }
        }
    }
}

/**
 * `delva search key [<prefix>]`: whole documents in the order of their keys, in a store created with
 * `--document-key`; the cursor of a page resumes after its last key.
 */
internal val searchKey = CommandHandler { context ->
    val values = context.values
    val query = DocumentKeyQuery(
        prefix = values[DelvaCommands.keyPrefix] ?: "",
        limit = values[DelvaCommands.limit] ?: PAGE_HITS,
        maxBytes = values[DelvaCommands.maxBytes] ?: Long.MAX_VALUE,
        after = values[DelvaCommands.after],
        order = values[DelvaCommands.keyOrder] ?: SearchOrder.ASCENDING,
    )
    context.withReader { store, _ ->
        if (store.creationOptions.documentKey == null) {
            throw CommandFailure(ExitStatus.USAGE, "the store has no document keys: it wasn't created with ${DelvaCommands.documentKey.long}")
        }
        val blocking = store.blocking()
        if (values[DelvaCommands.count]) {
            context.printCount(blocking.count(query, context.countLimit()))
        } else {
            blocking.search(query).use { context.printHits(it) }
        }
    }
}

/**
 * `delva search vector <vector> [--where <filter>]`: the documents whose vectors are nearest to the one given, nearest
 * first, in a store created with `--vector-field`. The vector is a JSON array of numbers, written out, in a file or
 * on standard input, or it's the vector of a stored document, which is then its own nearest hit.
 *
 * A filter that no index term narrows would compare every vector, which has to be asked for with `--scan`, as
 * `search path` asks for its scan.
 */
@OptIn(ExperimentalDelvaApi::class)
internal val searchVector = CommandHandler { context ->
    val values = context.values
    val given = values.required(DelvaCommands.queryVector)
    val filter = values[DelvaCommands.where]
    context.warnAbout(null, filter)
    // Read before the store is opened: text that isn't a vector is the same mistake whatever the store holds.
    val read = when (given) {
        is ValueArgs.QueryVector.Given -> given.values
        is ValueArgs.QueryVector.Input -> readVector(context, given.source)
        is ValueArgs.QueryVector.Stored -> null
    }
    context.withReader { store, _ ->
        val field = store.creationOptions.vectorField
            ?: throw CommandFailure(ExitStatus.USAGE, "the store has no vector field: it wasn't created with ${DelvaCommands.vectorField.long}")
        val blocking = store.blocking()
        val vector = read ?: storedVector(blocking, field, (given as ValueArgs.QueryVector.Stored).doc)
        val query = VectorQuery(
            vector = vector,
            k = values[DelvaCommands.nearest] ?: NEAREST,
            maxDistance = values[DelvaCommands.maxDistance],
            scan = if (values[DelvaCommands.vectorScan]) ScanPolicy.ALLOW_SCAN else ScanPolicy.REJECT,
        ).withDocumentFilter(filter)
        try {
            blocking.search(query).use { context.printNearest(it) }
        } catch (e: QueryRejectedException) {
            // What a scan costs here isn't what it costs a JSONPath search, so the remedy is worded for this command.
            if (e.reason != QueryRejectedException.Reason.NEEDS_SCAN) throw e
            throw CommandFailure(
                ExitStatus.USAGE,
                "${e.detail}, so it would compare every vector; add ${DelvaCommands.vectorScan.long} to allow that, or " +
                    "filter on a member equal to a value",
            )
        }
    }
}

/** The vector [source] holds: a JSON array of numbers, and nothing else. */
private fun readVector(context: CommandContext, source: InputSource): FloatArray {
    val bytes = source.open(context.io).use { it.readNBytes(MAX_VECTOR_BYTES + 1) }
    if (bytes.size > MAX_VECTOR_BYTES) throw CommandFailure(ExitStatus.DATA_ERR, "${source.name}: too large to be a vector")
    return try {
        ValueArgs.parseVector(bytes.toString(Charsets.UTF_8))
    } catch (e: IllegalArgumentException) {
        throw CommandFailure(ExitStatus.DATA_ERR, "${source.name}: ${e.message}")
    }
}

/** The vector of document [doc], read from the document's text at the vector field's path. */
@OptIn(ExperimentalDelvaApi::class)
private fun storedVector(store: BlockingReadableJsonStore, field: VectorField, doc: DocId): FloatArray {
    val text = store.select(NodeRef(doc, 0), JsonPath.compile(field.path)).use { result ->
        val selected = result.iterator()
        if (selected.hasNext()) selected.next().readJsonAsString(MAX_VECTOR_BYTES) else null
    }
    // A document may have null where its vector would be, which is having none.
    if (text == null || text == "null") throw CommandFailure(ExitStatus.NO_INPUT, "document ${doc.raw} has no vector at ${field.path}")
    return ValueArgs.parseVector(text)
}

/**
 * The notice for a key–value search of [key] that found nothing, when the key looks like a JSONPath query or a dotted
 * path, such as `$.index.accountId`; `null` for any other key. The key is a member's name as written, so that search
 * looks for a member called exactly `$.index.accountId`, and finds nothing without saying why. A member's name may hold
 * these characters, which is why this is a notice after the result and not a refusal.
 */
private fun pathLikeKeyNotice(key: String): String? {
    val pathLike = key.startsWith('$') || key.startsWith('@') || '.' in key || '[' in key
    return if (pathLike) "'$key' is taken as one member's name, not as a path; to search by path, use delva search path" else null
}

/** With `--count`, `--limit` is where counting stops. */
private fun CommandContext.countLimit(): Long = values[DelvaCommands.limit]?.toLong() ?: Long.MAX_VALUE

/** `--count`: the number on stdout, or `{"count":n}` with `--json`; [ifNone], if any, on stderr when it is 0. */
private fun CommandContext.printCount(count: Long, ifNone: String? = null): Int {
    output.text(if (output.machineReadable) buildString { JsonWriter(this).writeObject { field("count", count) } } else count.toString())
    if (count == 0L && ifNone != null) output.notice(ifNone)
    return queryStatus(count > 0)
}

/**
 * The hits of [result]: all of them, or with `--limit` or `--max-bytes` one page, followed by a notice of the cursor
 * the next page starts at. All of them end with that notice too when `--max-documents` ended the result before the
 * documents ran out. When there are none, the notice `no hits` is followed by [ifNone], if any.
 */
private fun CommandContext.printHits(result: SearchResult, ifNone: String? = null): Int {
    val writer = HitWriter(this)
    val paged = values[DelvaCommands.limit] != null || values[DelvaCommands.maxBytes] != null
    var cursor: Cursor? = null
    if (paged) {
        val page = result.nextPage()
        page?.hits?.forEach(writer::write)
        cursor = page?.cursor
    } else {
        // Without a document bound the last page never has a cursor.
        while (true) {
            val page = result.nextPage() ?: break
            page.hits.forEach(writer::write)
            cursor = page.cursor
        }
    }
    writer.finish(empty = "no hits")
    cursor?.let { output.notice("more: ${DelvaCommands.after.long} $it") }
    if (writer.count == 0 && ifNone != null) output.notice(ifNone)
    return queryStatus(writer.count > 0)
}

/** Hits read per page when the command line sets no limit; the pages are read one after the other. */
private const val PAGE_HITS = 1_000

/** How many documents a vector search finds when the command line doesn't say. */
private const val NEAREST = 10

/** The most text read as a vector: room for the 4,096 numbers a vector may have, at hundreds of digits each. */
private const val MAX_VECTOR_BYTES = 1 shl 20
