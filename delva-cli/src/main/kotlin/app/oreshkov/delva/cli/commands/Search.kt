package app.oreshkov.delva.cli.commands

import app.oreshkov.clikit.CommandContext
import app.oreshkov.clikit.CommandFailure
import app.oreshkov.clikit.CommandHandler
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.JsonWriter
import app.oreshkov.clikit.field
import app.oreshkov.clikit.writeObject
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
import app.oreshkov.delva.cli.withReader

/**
 * `delva search kv <key> <value>`: the value is JSON unless `--string` says it is text, because `42`, `"42"`, `true`
 * and `{"a":1}` all have to be expressible and only parsing tells them apart.
 */
internal val searchKv = CommandHandler { context ->
    val values = context.values
    val raw = values.required(DelvaCommands.kvValue)
    val asString = values[DelvaCommands.string]
    val query = KeyValueQuery(
        key = values.required(DelvaCommands.kvKey),
        value = if (asString) JsonQueryValue.Str(raw) else JsonQueryValue.Json(raw),
        mode = values[DelvaCommands.mode] ?: ResultMode.WHOLE_DOCUMENT,
        nested = values[DelvaCommands.nested] ?: NestedHits.ALL,
        limit = values[DelvaCommands.limit] ?: PAGE_HITS,
        maxBytes = values[DelvaCommands.maxBytes] ?: Long.MAX_VALUE,
        after = values[DelvaCommands.after],
        type = values[DelvaCommands.type],
        order = values[DelvaCommands.order] ?: SearchOrder.ASCENDING,
    )
    context.withReader { store, _ ->
        val blocking = store.blocking()
        try {
            if (values[DelvaCommands.count]) {
                context.printCount(blocking.count(query, context.countLimit()))
            } else {
                blocking.search(query).use { context.printHits(it) }
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
    )
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

/** With `--count`, `--limit` is where counting stops. */
private fun CommandContext.countLimit(): Long = values[DelvaCommands.limit]?.toLong() ?: Long.MAX_VALUE

/** `--count`: the number on stdout, or `{"count":n}` with `--json`. */
private fun CommandContext.printCount(count: Long): Int {
    output.text(if (output.machineReadable) buildString { JsonWriter(this).writeObject { field("count", count) } } else count.toString())
    return queryStatus(count > 0)
}

/**
 * The hits of [result]: all of them, or with `--limit` or `--max-bytes` one page, followed by a notice of the cursor
 * the next page starts at.
 */
private fun CommandContext.printHits(result: SearchResult): Int {
    val writer = HitWriter(this)
    val paged = values[DelvaCommands.limit] != null || values[DelvaCommands.maxBytes] != null
    if (paged) {
        val page = result.nextPage()
        page?.hits?.forEach(writer::write)
        writer.finish(empty = "no hits")
        page?.cursor?.let { output.notice("more: ${DelvaCommands.after.long} $it") }
    } else {
        for (hit in result) writer.write(hit)
        writer.finish(empty = "no hits")
    }
    return queryStatus(writer.count > 0)
}

/** Hits read per page when the command line sets no limit; the pages are read one after the other. */
private const val PAGE_HITS = 1_000
