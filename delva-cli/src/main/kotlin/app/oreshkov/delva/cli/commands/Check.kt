package app.oreshkov.delva.cli.commands

import app.oreshkov.clikit.Column
import app.oreshkov.clikit.CommandContext
import app.oreshkov.clikit.CommandHandler
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.Format
import app.oreshkov.clikit.JsonLines
import app.oreshkov.clikit.JsonWriter
import app.oreshkov.clikit.Output
import app.oreshkov.clikit.field
import app.oreshkov.clikit.payloadStream
import app.oreshkov.clikit.writeArray
import app.oreshkov.clikit.writeObject
import app.oreshkov.delva.CompareOperand
import app.oreshkov.delva.CompareOptions
import app.oreshkov.delva.DiffOptions
import app.oreshkov.delva.DiffValue
import app.oreshkov.delva.Difference
import app.oreshkov.delva.VerifyReport
import app.oreshkov.delva.cli.DelvaCommands
import app.oreshkov.delva.cli.ValueArgs
import app.oreshkov.delva.cli.withReader
import java.io.InputStream

/**
 * `delva verify`: reads every record, block and index entry the store can reach and reports what is damaged, without
 * changing anything. Exits 0 when the store is intact and 1 when it is not, so a scheduled job can act on it.
 */
internal val verify = CommandHandler { context ->
    context.withReader { store, dir ->
        val progress = context.output.progress("verifying")
        val report = try {
            store.blocking().verify { phase, done, total -> progress.update(done, total, phase.name.lowercase()) }
        } finally {
            progress.finish()
        }
        if (context.output.machineReadable) {
            context.output.text(json(report))
        } else {
            print(context.output, report, dir.toString())
        }
        if (report.intact) ExitStatus.OK else ExitStatus.FAILURE
    }
}

private fun print(output: Output, report: VerifyReport, store: String) {
    val fields = mutableListOf(
        "store" to store,
        "result" to if (report.intact) "intact" else "damaged",
        "files" to report.filesChecked.toString(),
        "log records" to report.logRecordsChecked.toString(),
        "segment blocks" to report.segmentBlocksChecked.toString(),
        "fragments" to report.fragmentsChecked.toString(),
        "read" to Format.bytes(report.bytesChecked),
    )
    if (!report.intact) {
        fields += "damaged" to Format.bytes(report.damagedBytes)
        fields += "damaged documents" to report.damagedDocuments.joinToString(" ") { it.raw.toString() }.ifEmpty { "none" }
    }
    output.properties(fields)
    if (report.issues.isNotEmpty()) {
        output.text("")
        output.table(
            listOf(Column("KIND", flexible = false), Column("LOCATION"), Output.fixed("DOC"), Column("DETAIL")),
            report.issues.map { listOf(it.kind.name.lowercase(), it.location, it.document?.raw?.toString() ?: "", it.detail) },
            empty = "",
        )
    }
}

private fun json(report: VerifyReport): String = buildString {
    JsonWriter(this).writeObject {
        field("intact", report.intact)
        field("filesChecked", report.filesChecked)
        field("logRecordsChecked", report.logRecordsChecked)
        field("segmentBlocksChecked", report.segmentBlocksChecked)
        field("fragmentsChecked", report.fragmentsChecked)
        field("bytesChecked", report.bytesChecked)
        field("damagedBytes", report.damagedBytes)
        name("damagedDocuments").writeArray { report.damagedDocuments.forEach { value(it.raw) } }
        name("issues").writeArray {
            for (issue in report.issues) {
                writeObject {
                    field("kind", issue.kind.name.lowercase())
                    field("location", issue.location)
                    name("doc").also { w -> issue.document?.let { w.value(it.raw) } ?: w.nullValue() }
                    field("damagedBytes", issue.damagedBytes)
                    field("detail", issue.detail)
                }
            }
        }
    }
}

/**
 * `delva compare <a> <b>`: whether two JSON values are equal — stored documents or nodes, files, or standard input —
 * compared as values: members in any order, numbers by value. Exits 0 when equal and 1 when not, as `cmp` does, and
 * with `--diff` lists what differs.
 */
internal val compare = CommandHandler { context ->
    val values = context.values
    val a = values.required(DelvaCommands.left)
    val b = values.required(DelvaCommands.right)
    val includeTypes = values[DelvaCommands.compareTypes]
    val ignoreKeys = values[DelvaCommands.ignoreKey].toSet()
    val ignorePaths = values[DelvaCommands.ignorePath].toSet()
    val streams = mutableListOf<InputStream>()
    fun operand(side: ValueArgs.Operand): CompareOperand = when (side) {
        is ValueArgs.Operand.Stored -> CompareOperand.Stored(side.node)
        is ValueArgs.Operand.Input -> CompareOperand.JsonStream(side.source.open(context.io).also { streams += it })
    }
    try {
        context.withReader { store, _ ->
            val blocking = store.blocking()
            if (!values[DelvaCommands.diff]) {
                val equal = blocking.isEqual(operand(a), operand(b), CompareOptions(includeTypes = includeTypes, ignoreKeys = ignoreKeys, ignorePaths = ignorePaths))
                if (context.output.machineReadable) {
                    context.output.text(buildString { JsonWriter(this).writeObject { field("equal", equal) } })
                } else {
                    context.output.notice(if (equal) "equal" else "different")
                }
                if (equal) ExitStatus.OK else ExitStatus.FAILURE
            } else {
                val max = values[DelvaCommands.maxDifferences] ?: DiffOptions().maxDifferences
                val differences = blocking.diff(
                    operand(a),
                    operand(b),
                    DiffOptions(includeTypes = includeTypes, ignoreKeys = ignoreKeys, ignorePaths = ignorePaths, maxDifferences = max),
                )
                printDifferences(context, differences)
                if (differences.size >= max) context.output.notice("stopped after $max differences")
                if (differences.isEmpty()) ExitStatus.OK else ExitStatus.FAILURE
            }
        }
    } finally {
        streams.forEach { it.close() }
    }
}

private fun printDifferences(context: CommandContext, differences: List<Difference>) {
    if (context.output.machineReadable) {
        val lines = JsonLines(context.io.payloadStream())
        for (difference in differences) {
            lines.record {
                writeObject {
                    field("kind", difference.kind.name.lowercase())
                    field("path", difference.path)
                    name("left").side(difference.left)
                    name("right").side(difference.right)
                }
            }
        }
    } else {
        context.output.table(
            listOf(Column("KIND", flexible = false), Column("PATH"), Column("LEFT"), Column("RIGHT")),
            differences.map { listOf(it.kind.name.lowercase().replace('_', ' '), it.path, text(it.left), text(it.right)) },
            empty = "no differences",
        )
    }
}

/** A side of a difference: its JSON as a string (it may be cut short), where it is stored, and its type. */
private fun JsonWriter.side(value: DiffValue?): JsonWriter {
    if (value == null) return nullValue()
    return writeObject {
        field("json", value.json)
        field("truncated", value.truncated)
        field("node", value.node?.let(ValueArgs::text))
        field("type", value.type)
    }
}

private fun text(value: DiffValue?): String = when {
    value == null -> "—"
    value.truncated -> value.json + "…"
    else -> value.json
} + (value?.type?.let { " : $it" } ?: "")
