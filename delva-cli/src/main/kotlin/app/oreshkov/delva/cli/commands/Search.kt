package app.oreshkov.delva.cli.commands

import app.oreshkov.clikit.CommandContext
import app.oreshkov.clikit.CommandFailure
import app.oreshkov.clikit.CommandHandler
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.JsonWriter
import app.oreshkov.clikit.field
import app.oreshkov.clikit.writeObject
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.cli.DelvaCommands
import app.oreshkov.delva.cli.HitWriter
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
@OptIn(ExperimentalDelvaApi::class)
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
