package app.oreshkov.delva.cli.commands

import app.oreshkov.clikit.CommandFailure
import app.oreshkov.clikit.CommandHandler
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.JsonLines
import app.oreshkov.clikit.field
import app.oreshkov.clikit.payloadStream
import app.oreshkov.clikit.writeObject
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.cli.DelvaCommands
import app.oreshkov.delva.cli.jsonFormat
import app.oreshkov.delva.cli.queryStatus
import app.oreshkov.delva.cli.withReader

/**
 * `delva get <doc> [--path <query>]`: the document, or each node the query selects from it, streamed from the store
 * to stdout — a document of gigabytes costs no more memory than one of bytes. With `--json`, each node is a JSON
 * Lines record of its Normalized Path and its value.
 *
 * `delva get --key <key>` finds the document by its key, in a store created with `--document-key`; when no document
 * has the key, it says so on stderr and exits 0, or 1 with `--exit-status`, as a query that finds nothing does.
 */
@OptIn(ExperimentalDelvaApi::class)
internal val get = CommandHandler { context ->
    val format = context.jsonFormat()
    val given = context.values[DelvaCommands.getDoc]
    val key = context.values[DelvaCommands.getKey]
    val query = context.values[DelvaCommands.getPath] ?: WHOLE_DOCUMENT
    context.withReader { store, _ ->
        val doc = given ?: run {
            if (store.creationOptions.documentKey == null) {
                throw CommandFailure(ExitStatus.USAGE, "${DelvaCommands.getKey.long} needs a store created with ${DelvaCommands.documentKey.long}")
            }
            store.blocking().get(key!!) ?: run {
                context.output.notice("no document has the key $key")
                return@withReader context.queryStatus(false)
            }
        }
        store.blocking().select(NodeRef(doc, 0), query).use { result ->
            val out = context.io.payloadStream()
            val lines = if (context.output.machineReadable) JsonLines(out) else null
            var found = 0
            for (node in result) {
                found++
                if (lines != null) {
                    lines.record {
                        writeObject {
                            field("path", node.path)
                            name("value").rawValue { node.writeJsonTo(lines.stream()) }
                        }
                    }
                } else {
                    node.writeJsonTo(out, format)
                    out.write('\n'.code)
                    out.flush()
                }
            }
            if (found == 0) context.output.notice("the query selects nothing in document ${doc.raw}")
            context.queryStatus(found > 0)
        }
    }
}

private val WHOLE_DOCUMENT: JsonPath = JsonPath.compile("$")
