package app.oreshkov.delva.cli.commands

import app.oreshkov.clikit.Align
import app.oreshkov.clikit.Column
import app.oreshkov.clikit.CommandHandler
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.JsonLines
import app.oreshkov.clikit.JsonWriter
import app.oreshkov.clikit.Output
import app.oreshkov.clikit.field
import app.oreshkov.clikit.payloadStream
import app.oreshkov.clikit.writeObject
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.cli.DelvaCommands
import app.oreshkov.delva.cli.ValueArgs
import app.oreshkov.delva.cli.queryStatus
import app.oreshkov.delva.cli.withReader

/** `delva explain <query> [--where <filter>]`: how a JSONPath search would find its documents, without reading any. */
@OptIn(ExperimentalDelvaApi::class)
internal val explain = CommandHandler { context ->
    val query = context.values.required(DelvaCommands.explainQuery)
    context.withReader { store, _ ->
        val plan = store.blocking().explain(JsonPathQuery(query, documentFilter = context.values[DelvaCommands.where]))
        if (context.output.machineReadable) {
            context.output.text(
                buildString {
                    JsonWriter(this).writeObject {
                        field("fullScan", plan.fullScan)
                        field("terms", plan.terms)
                        field("within", plan.within)
                    }
                },
            )
        } else {
            context.output.properties(
                listOf(
                    "reads" to if (plan.fullScan) "every document (a full scan; search needs --scan)" else "the documents the terms select",
                    "terms" to (plan.terms ?: "none"),
                    "within" to (plan.within ?: "anywhere in the document"),
                ),
            )
        }
    }
    ExitStatus.OK
}

/** `delva path <doc:node>`: where a node is, as the Normalized Path every other command prints. */
internal val nodePath = CommandHandler { context ->
    val node = context.values.required(DelvaCommands.node)
    context.withReader { store, _ ->
        val path = store.blocking().pathOf(node)
        if (context.output.machineReadable) {
            context.output.text(
                buildString {
                    JsonWriter(this).writeObject {
                        field("doc", node.docId.raw)
                        field("node", node.nodePre)
                        field("path", path)
                    }
                },
            )
        } else {
            context.output.text(path)
        }
    }
    ExitStatus.OK
}

/** `delva types list`: every type, with the approximate count the index keeps, which costs no scan. */
internal val typesList = CommandHandler { context ->
    context.withReader { store, _ ->
        val types = store.listTypes()
        if (context.output.machineReadable) {
            val lines = JsonLines(context.io.payloadStream())
            for (type in types) {
                lines.record { writeObject { field("name", type.name); field("approximateCount", type.approximateCount) } }
            }
            if (types.isEmpty()) context.output.notice("no types")
        } else {
            context.output.table(
                listOf(Column("TYPE"), Output.fixed("OBJECTS", Align.Right)),
                types.map { listOf(it.name, it.approximateCount.toString()) },
                empty = "no types",
            )
        }
    }
    ExitStatus.OK
}

/** `delva types count <type>`: the exact count, which reads the entry of every object of the type. */
internal val typesCount = CommandHandler { context ->
    val type = context.values.required(DelvaCommands.typeName)
    context.withReader { store, _ ->
        val count = store.blocking().countOfType(type)
        if (context.output.machineReadable) {
            context.output.text(buildString { JsonWriter(this).writeObject { field("type", type); field("count", count) } })
        } else {
            context.output.text(count.toString())
        }
    }
    ExitStatus.OK
}

/**
 * `delva types objects <type>`: the objects of a type as `doc:node`, one per line, oldest first. With `--limit`, one
 * page and a notice of where the next starts; without, every one, read a page at a time.
 */
internal val typesObjects = CommandHandler { context ->
    val type = context.values.required(DelvaCommands.typeName)
    val limit = context.values[DelvaCommands.limit]
    context.withReader { store, _ ->
        val blocking = store.blocking()
        val lines = if (context.output.machineReadable) JsonLines(context.io.payloadStream()) else null
        var found = 0L
        fun print(node: NodeRef) {
            found++
            if (lines != null) {
                lines.record { writeObject { field("doc", node.docId.raw); field("node", node.nodePre) } }
            } else {
                context.output.text(ValueArgs.text(node))
            }
        }

        var after = context.values[DelvaCommands.typesAfter]
        if (limit != null) {
            val page = blocking.objectsOfType(type, after, limit)
            page.forEach(::print)
            if (page.size == limit) context.output.notice("more: ${DelvaCommands.typesAfter.long} ${ValueArgs.text(page.last())}")
        } else {
            while (true) {
                val page = blocking.objectsOfType(type, after, OBJECTS_PAGE)
                page.forEach(::print)
                if (page.size < OBJECTS_PAGE) break
                after = page.last()
            }
        }
        if (found == 0L) context.output.notice("no objects of type $type")
        context.queryStatus(found > 0)
    }
}

private const val OBJECTS_PAGE = 1_000
