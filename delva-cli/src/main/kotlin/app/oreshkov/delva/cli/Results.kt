package app.oreshkov.delva.cli

import app.oreshkov.clikit.Align
import app.oreshkov.clikit.Column
import app.oreshkov.clikit.CommandContext
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.Format
import app.oreshkov.clikit.JsonLines
import app.oreshkov.clikit.Output
import app.oreshkov.clikit.field
import app.oreshkov.clikit.payloadStream
import app.oreshkov.clikit.writeObject
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonFormat
import app.oreshkov.delva.SearchHit
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * How `delva` writes stored JSON: never materialized, always streamed from the store to stdout.
 *
 * Human output is indented at a terminal and compact down a pipe, so `delva get 7 | jq` gets compact bytes;
 * `--pretty`, `--compact`, `--line-width` and `--max-levels` override it. Machine output (`--json`) is JSON Lines, one
 * record per line, and so always compact. The flags that contradict each other are refused by the command's
 * constraints (see [DelvaCommands.formatConstraints]) before this runs.
 */
internal fun CommandContext.jsonFormat(): JsonFormat {
    val pretty = values[DelvaCommands.pretty]
    val compact = values[DelvaCommands.compact]
    val maxLevels = values[DelvaCommands.maxLevels]
    if (output.machineReadable) return JsonFormat.COMPACT
    val indented = pretty || maxLevels != null || (io.interactive && !compact)
    if (!indented) return JsonFormat.COMPACT
    return JsonFormat(
        lineWidth = values[DelvaCommands.lineWidth] ?: io.width.coerceIn(0, JsonFormat.MAX_LINE_WIDTH),
        maxLevels = maxLevels ?: Int.MAX_VALUE,
    )
}

/** The exit status of a query that found [found] results: 0, or 1 for nothing when `--exit-status` asks for it. */
internal fun CommandContext.queryStatus(found: Boolean): Int =
    if (!found && values[DelvaCommands.exitStatus]) ExitStatus.FAILURE else ExitStatus.OK

/**
 * Search hits, as they are read: a table whose last column is a short preview of each hit's JSON, or JSON Lines
 * whose `value` is the whole hit streamed in. Each JSON record carries the hit's cursor, so a consumer can resume
 * from any line with `--after`.
 */
internal class HitWriter(private val context: CommandContext) {
    private val lines: JsonLines? = if (context.output.machineReadable) JsonLines(context.io.payloadStream()) else null
    private val rows = mutableListOf<List<String>>()
    var count: Int = 0
        private set

    /** Whether the store has document keys, which the first hit tells: then each hit names its document's key. */
    private var keyed: Boolean? = null

    @OptIn(ExperimentalDelvaApi::class)
    fun write(hit: SearchHit) {
        count++
        val key = hit.documentKey
        if (keyed == null) keyed = key != null
        val lines = lines
        if (lines != null) {
            lines.record {
                writeObject {
                    field("doc", hit.docId.raw)
                    if (key != null) field("key", key)
                    field("node", hit.node.nodePre)
                    field("path", hit.path)
                    field("type", hit.type)
                    field("size", hit.encodedSize)
                    field("cursor", hit.cursor.toString())
                    name("value").rawValue { hit.writeJsonTo(lines.stream()) }
                }
            }
        } else {
            val row = listOf(ValueArgs.text(hit.node), hit.path, hit.type ?: "", Format.bytes(hit.encodedSize), preview(hit))
            rows += if (keyed == true) listOf(key ?: "") + row else row
            // A table sizes its columns from its rows, so a long result is printed a batch at a time: memory stays
            // bounded, at the price of columns that may shift a little from one batch to the next.
            if (rows.size == TABLE_BATCH_ROWS) printRows()
        }
    }

    /** The rest of the table, in human mode; a notice on stderr when there were no hits. */
    fun finish(empty: String) {
        if (lines != null) {
            if (count == 0) context.output.notice(empty)
            return
        }
        if (count == 0) {
            context.output.notice(empty)
        } else if (rows.isNotEmpty()) {
            printRows()
        }
    }

    private fun printRows() {
        val columns = listOf(
            Output.fixed("NODE", Align.Right),
            Column("PATH"),
            Column("TYPE"),
            Output.fixed("SIZE", Align.Right),
            Column("VALUE"),
        )
        context.output.table(if (keyed == true) listOf(Column("KEY")) + columns else columns, rows, empty = "")
        rows.clear()
    }
}

/**
 * The start of a hit's JSON on one line, its nested containers cut to `{...}` and `[...]`: enough to recognize it,
 * read in a bounded number of bytes however large the hit is.
 */
private fun preview(hit: SearchHit): String {
    val bytes = Bounded(PREVIEW_BYTES)
    try {
        hit.writeJsonTo(bytes, PREVIEW_FORMAT)
    } catch (_: Bounded.Full) {
        // Enough read; the rest of the hit is never touched.
    }
    val text = bytes.text().replace(WHITESPACE_RUN, " ")
    return if (bytes.full) text.trimEnd('�') + "…" else text
}

private const val PREVIEW_BYTES = 512
private const val TABLE_BATCH_ROWS = 1_000
private val PREVIEW_FORMAT = JsonFormat(lineWidth = JsonFormat.MAX_LINE_WIDTH, maxLevels = 0)
private val WHITESPACE_RUN = Regex("\\s*\\n\\s*")

/** Keeps the first [limit] bytes written to it, then stops the writer by throwing [Full]. */
private class Bounded(private val limit: Int) : OutputStream() {
    class Full : RuntimeException(null, null, false, false)

    private val bytes = ByteArrayOutputStream()
    var full = false
        private set

    override fun write(b: Int) {
        if (bytes.size() >= limit) stop()
        bytes.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val room = limit - bytes.size()
        bytes.write(b, off, minOf(room, len))
        if (len > room) stop()
    }

    private fun stop(): Nothing {
        full = true
        throw Full()
    }

    fun text(): String = bytes.toString(Charsets.UTF_8)
}
