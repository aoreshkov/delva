package app.oreshkov.delva.cli.commands

import app.oreshkov.clikit.CommandContext
import app.oreshkov.clikit.CommandFailure
import app.oreshkov.clikit.CommandHandler
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.InputSource
import app.oreshkov.clikit.JsonLines
import app.oreshkov.clikit.JsonWriter
import app.oreshkov.clikit.confirm
import app.oreshkov.clikit.field
import app.oreshkov.clikit.payloadStream
import app.oreshkov.clikit.uniqueKeys
import app.oreshkov.clikit.writeArray
import app.oreshkov.clikit.writeObject
import app.oreshkov.delva.BlockingJsonStore
import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.InsertOutcome
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyConflict
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.Normalization
import app.oreshkov.delva.cli.DelvaCommands
import app.oreshkov.delva.cli.ValueArgs
import app.oreshkov.delva.cli.storeDir
import app.oreshkov.delva.cli.withStore
import java.io.IOException
import java.io.InputStream
import java.nio.file.NoSuchFileException

/**
 * `delva init <dir>`: creates a store, with the options that are fixed for its life. The one command that creates
 * one; every other command opens a store that exists.
 */
@OptIn(ExperimentalDelvaApi::class)
internal val init = CommandHandler { context ->
    val values = context.values
    val dir = values.required(DelvaCommands.initDir)
    val defaults = CreationOptions()
    val creation = try {
        val fragment = values[DelvaCommands.fragmentSize]
        require(fragment == null || fragment <= Int.MAX_VALUE) { "--fragment-size $fragment is too large" }
        CreationOptions(
            fragmentBytes = fragment?.toInt() ?: defaults.fragmentBytes,
            hashTerms = !values[DelvaCommands.noHashTerms],
            indexedKeys = values[DelvaCommands.indexKey].toSet(),
            excludedKeys = values[DelvaCommands.excludeKey].toSet(),
            minIndexedStringBytes = values[DelvaCommands.minIndexedString] ?: defaults.minIndexedStringBytes,
            maxIndexedStringBytes = values[DelvaCommands.maxIndexedString] ?: defaults.maxIndexedStringBytes,
            normalization = values[DelvaCommands.normalization] ?: Normalization.NONE,
        ).withDocumentKey(values[DelvaCommands.documentKey])
    } catch (e: IllegalArgumentException) {
        throw CommandFailure(ExitStatus.USAGE, e.message ?: "invalid creation options")
    }
    JsonStore.create(dir, creation).close()
    if (context.output.machineReadable) {
        context.output.text(
            buildString {
                JsonWriter(this).writeObject {
                    field("store", dir.toString())
                    name("creationOptions").creationOptions(creation)
                }
            },
        )
    } else {
        context.output.notice("created a store at $dir")
    }
    ExitStatus.OK
}

/**
 * `delva insert <file>…`: each file one document, streamed in — its size doesn't bound the memory the insert needs.
 * The ids go to stdout, one per line and in the order of the files, so they can be piped on.
 *
 * A file that can't be read, or whose JSON is rejected, is reported and the rest are still inserted; the exit status
 * then says so: 66 when a file was missing, 65 when a document was rejected.
 *
 * `--replace <doc>` makes the one file a new version of those documents: they are deleted in the same atomic change,
 * and only if they all still exist. When one doesn't, nothing changes and the status is 66, as for a missing file.
 *
 * In a store created with `--document-key`, a document whose key another document has isn't inserted (66) unless
 * `--upsert` makes it replace that document in the same change.
 */
@OptIn(ExperimentalDelvaApi::class)
internal val insert = CommandHandler { context ->
    val sources = context.values[DelvaCommands.insertFiles]
    val types = context.values.uniqueKeys(DelvaCommands.insertTypes) { it.first }.mapValues { it.value.second }
    val replaces = context.values[DelvaCommands.insertReplaces]
    val upsert = context.values[DelvaCommands.insertUpsert]
    if (replaces.isNotEmpty() && sources.size != 1) {
        throw CommandFailure(ExitStatus.USAGE, "--replace takes one file, not ${sources.size}")
    }
    context.withStore { store, _ ->
        if (upsert && store.creationOptions.documentKey == null) {
            throw CommandFailure(ExitStatus.USAGE, "${DelvaCommands.insertUpsert.long} needs a store created with ${DelvaCommands.documentKey.long}")
        }
        Inserter(context, store.blocking(), types, replaces, upsert).run(sources)
    }
}

@OptIn(ExperimentalDelvaApi::class)
private class Inserter(
    private val context: CommandContext,
    private val store: BlockingJsonStore,
    private val types: Map<String, String>,
    private val replaces: List<DocId>,
    private val upsert: Boolean,
) {
    private val lines = if (context.output.machineReadable) JsonLines(context.io.payloadStream()) else null
    private var unreadable = 0
    private var rejected = 0
    private var conflicted = 0

    fun run(sources: List<InputSource>): Int {
        val progress = context.output.progress("inserting", sources.size.toLong())
        try {
            var done = 0L
            // Files are opened a batch at a time, so a thousand files are not a thousand open handles.
            for (batch in sources.chunked(BATCH_FILES)) {
                insertBatch(batch)
                done += batch.size
                progress.update(done)
            }
        } finally {
            progress.finish()
        }
        return when {
            unreadable > 0 || conflicted > 0 -> ExitStatus.NO_INPUT
            rejected > 0 -> ExitStatus.DATA_ERR
            else -> ExitStatus.OK
        }
    }

    private fun insertBatch(batch: List<InputSource>) {
        val opened = mutableListOf<Pair<InputSource, InputStream>>()
        try {
            for (source in batch) {
                try {
                    opened += source to source.open(context.io)
                } catch (e: IOException) {
                    unreadable++
                    val reason = if (e is NoSuchFileException) "no such file" else (e.message ?: e::class.simpleName!!)
                    report(source, "error", reason) { "${source.name}: $reason" }
                }
            }
            val onExistingKey = if (upsert) KeyConflict.REPLACE else KeyConflict.FAIL
            val outcomes = store.insertAll(opened.map { InsertSource(it.second, types, replaces, onExistingKey) })
            for ((index, outcome) in outcomes.withIndex()) {
                val source = opened[index].first
                when (outcome) {
                    is InsertOutcome.Inserted -> inserted(source, outcome.docId)
                    is InsertOutcome.Rejected -> {
                        rejected++
                        val e = outcome.exception
                        report(source, "rejected", e.reason.name) { "${source.name}: ${e.message}" }
                    }
                    is InsertOutcome.Conflicted -> {
                        conflicted++
                        val docs = outcome.exception.conflicting.joinToString(" ") { it.raw.toString() }
                        val key = outcome.exception.key
                        if (key == null) {
                            report(source, "conflict", docs) { "${source.name}: not inserted, no document to replace: $docs" }
                        } else {
                            report(source, "conflict", docs) {
                                "${source.name}: not inserted, document $docs has its key; add ${DelvaCommands.insertUpsert.long} to replace it"
                            }
                        }
                    }
                }
            }
        } finally {
            opened.forEach { it.second.close() }
        }
    }

    private fun inserted(source: InputSource, doc: DocId) {
        val lines = lines
        if (lines == null) {
            context.output.text(doc.raw.toString())
        } else {
            lines.record { writeObject { field("source", source.name); field("doc", doc.raw) } }
        }
    }

    private inline fun report(source: InputSource, field: String, value: String, message: () -> String) {
        context.output.error(message())
        lines?.record { writeObject { field("source", source.name); field(field, value) } }
    }

    private companion object {
        const val BATCH_FILES = 64
    }
}

/**
 * `delva delete <doc>… | -`: deletes documents. It checks which exist first, so it can say which didn't; asks at a
 * terminal, and needs `--force` without one; `--dry-run` reports and deletes nothing.
 */
internal val delete = CommandHandler { context ->
    val targets = context.values[DelvaCommands.deleteTargets]
    val ids = targets.flatMap { target ->
        when (target) {
            is ValueArgs.DeleteTarget.One -> listOf(target.doc)
            ValueArgs.DeleteTarget.FromStdin -> idsFromStdin(context)
        }
    }.distinct()
    context.withStore { store, _ ->
        val blocking = store.blocking()
        val (existing, missing) = ids.partition { blocking.exists(it) }
        val dryRun = context.values[DelvaCommands.dryRun]
        val deleted = when {
            existing.isEmpty() || dryRun -> emptyList()
            context.confirm("delete ${plural(existing.size, "document")}?") -> existing.also { blocking.deleteAll(it) }
            else -> {
                context.output.notice("nothing deleted")
                return@withStore ExitStatus.FAILURE
            }
        }
        if (context.output.machineReadable) {
            context.output.text(
                buildString {
                    JsonWriter(this).writeObject {
                        name(if (dryRun) "wouldDelete" else "deleted").writeArray {
                            (if (dryRun) existing else deleted).forEach { value(it.raw) }
                        }
                        name("missing").writeArray { missing.forEach { value(it.raw) } }
                    }
                },
            )
        } else {
            if (dryRun) {
                context.output.notice("would delete ${plural(existing.size, "document")}: ${existing.joinToString(" ") { it.raw.toString() }}")
            } else if (deleted.isNotEmpty()) {
                context.output.notice("deleted ${plural(deleted.size, "document")}")
            }
            if (missing.isNotEmpty()) context.output.warn("not found: ${missing.joinToString(" ") { it.raw.toString() }}")
        }
        if (existing.isEmpty()) ExitStatus.NO_INPUT else ExitStatus.OK
    }
}

/**
 * `delva reset`: empties the store, leaving it as `init` created it, with the options it was created with. Asks at a
 * terminal and needs `--force` without one.
 *
 * The store is opened first, so a missing or busy store fails before anything is asked, and the question can say how
 * many documents go. A store too damaged to open can still be reset: that is said, and then asked.
 */
internal val reset: CommandHandler = CommandHandler { context ->
    val dir = context.storeDir()
    val question = try {
        val documents = JsonStore.open(dir).use { it.info().approximateDocumentCount }
        "reset $dir, deleting ${plural(documents, "document")}?"
    } catch (e: CorruptionException) {
        context.output.warn("the store doesn't open: ${e.message}")
        "reset $dir, deleting whatever it holds?"
    }
    if (!context.confirm(question)) {
        context.output.notice("nothing changed")
        return@CommandHandler ExitStatus.FAILURE
    }
    val creation = JsonStore.reset(dir)
    if (context.output.machineReadable) {
        context.output.text(
            buildString {
                JsonWriter(this).writeObject {
                    field("store", dir.toString())
                    name("creationOptions").creationOptions(creation)
                }
            },
        )
    } else {
        context.output.notice("reset $dir: it is empty, with the options it was created with")
    }
    ExitStatus.OK
}

/** Document ids from standard input, separated by whitespace — what `jq .doc` prints. */
private fun idsFromStdin(context: CommandContext): List<DocId> {
    val text = InputSource.Stdin.open(context.io).use { it.readAllBytes().toString(Charsets.UTF_8) }
    return text.split(WHITESPACE).filter { it.isNotEmpty() }.map { token ->
        val id = token.toLongOrNull()
        if (id == null || id < 1) {
            throw CommandFailure(ExitStatus.USAGE, "'$token' on standard input is not a document id")
        }
        DocId(id)
    }
}

private val WHITESPACE = Regex("\\s+")

private fun BlockingJsonStore.exists(doc: DocId): Boolean = try {
    pathOf(NodeRef(doc, 0))
    true
} catch (_: DocumentNotFoundException) {
    false
}

private fun plural(count: Number, noun: String): String = if (count.toLong() == 1L) "1 $noun" else "$count ${noun}s"

/** `delva types assign <doc:node> <type>`: gives the object a type, replacing the one it had. */
internal val typesAssign = CommandHandler { context ->
    val node = context.values.required(DelvaCommands.node)
    val type = context.values.required(DelvaCommands.assignType)
    context.withStore { store, _ -> store.blocking().assignType(node, type) }
    context.output.notice("${ValueArgs.text(node)} is a $type")
    ExitStatus.OK
}

/** `delva types clear <doc:node>`: takes the object's type away. */
internal val typesClear = CommandHandler { context ->
    val node = context.values.required(DelvaCommands.node)
    context.withStore { store, _ -> store.blocking().assignType(node, null) }
    context.output.notice("${ValueArgs.text(node)} has no type")
    ExitStatus.OK
}
