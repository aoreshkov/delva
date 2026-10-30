package app.oreshkov.delva.cli.commands

import app.oreshkov.clikit.Align
import app.oreshkov.clikit.Column
import app.oreshkov.clikit.CommandHandler
import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.Format
import app.oreshkov.clikit.JsonWriter
import app.oreshkov.clikit.Output
import app.oreshkov.clikit.field
import app.oreshkov.clikit.writeArray
import app.oreshkov.clikit.writeObject
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.LockHolder
import app.oreshkov.delva.StoreInfo
import app.oreshkov.delva.TypeInfo
import app.oreshkov.delva.cli.withSecondary
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

/**
 * `delva info`: what a store holds — documents, types, bytes on disk —, the options it was created with, and the
 * process that has it open as its writer, if one does.
 *
 * Every number comes from memory or a directory listing; nothing is scanned, so it answers as fast for a store of a
 * terabyte as for an empty one.
 */
internal val info = CommandHandler { context ->
    context.withSecondary { store, dir ->
        val summary = Summary(dir, store.info(), store.listTypes(), store.creationOptions, diskBytes(dir), store.writer())
        if (context.output.machineReadable) {
            context.output.text(summary.json())
        } else {
            summary.print(context.output)
        }
    }
    ExitStatus.OK
}

private class Summary(
    val dir: Path,
    val info: StoreInfo,
    val types: List<TypeInfo>,
    val creation: CreationOptions,
    val diskBytes: Long,
    val writer: LockHolder?,
) {
    fun print(output: Output) {
        output.properties(
            listOf(
                "store" to dir.toString(),
                "writer" to (writer?.toString() ?: "none"),
                "documents" to info.approximateDocumentCount.toString(),
                "deleted documents" to info.deletedDocumentCount.toString(),
                "types" to types.size.toString(),
                "size on disk" to Format.bytes(diskBytes),
                "fragment size" to Format.bytes(creation.fragmentBytes.toLong()),
                "object and array terms" to if (creation.hashTerms) "indexed" else "not indexed",
                "normalization" to creation.normalization.name.lowercase(),
                "indexed keys" to keys(creation.indexedKeys, "every key"),
                "excluded keys" to keys(creation.excludedKeys, "none"),
                "indexed strings" to stringLengths(),
                "document key" to (creation.documentKeyPath ?: "none"),
            ),
        )
        if (types.isNotEmpty()) output.text("")
        output.table(
            listOf(Column("TYPE"), Output.fixed("OBJECTS", Align.Right)),
            types.map { listOf(it.name, it.approximateCount.toString()) },
            empty = "no types",
        )
    }

    fun json(): String = buildString {
        JsonWriter(this).writeObject {
            field("store", dir.toString())
            val holder = writer
            if (holder == null) {
                name("writer").nullValue()
            } else {
                name("writer").writeObject {
                    field("pid", holder.pid)
                    field("startedAt", holder.startedAt?.toString())
                    field("host", holder.host)
                }
            }
            field("approximateDocumentCount", info.approximateDocumentCount)
            field("deletedDocumentCount", info.deletedDocumentCount)
            field("diskBytes", diskBytes)
            name("types").writeArray {
                for (type in types) writeObject { field("name", type.name); field("approximateCount", type.approximateCount) }
            }
            name("creationOptions").creationOptions(creation)
        }
    }

    private fun keys(keys: Set<String>, whenEmpty: String): String =
        if (keys.isEmpty()) whenEmpty else keys.sorted().joinToString(", ")

    private fun stringLengths(): String {
        val min = creation.minIndexedStringBytes
        val max = creation.maxIndexedStringBytes
        return when {
            min == 0 && max == Int.MAX_VALUE -> "every length"
            max == Int.MAX_VALUE -> "$min bytes or more"
            else -> "$min to $max bytes"
        }
    }
}

/**
 * The bytes of the files under [dir]. A writer may be running and delete files while they are counted: a file gone
 * by the time its size is read counts 0, and a directory gone while it is walked ends the count early.
 */
private fun diskBytes(dir: Path): Long {
    var total = 0L
    try {
        Files.walk(dir).use { paths ->
            for (path in paths) {
                total += try {
                    if (path.isRegularFile()) path.fileSize() else 0L
                } catch (_: IOException) {
                    0L
                }
            }
        }
    } catch (_: UncheckedIOException) {
        // A directory went away under the walk.
    }
    return total
}

/** [creation] as the JSON object `info --json` and `init --json` both write. */
internal fun JsonWriter.creationOptions(creation: CreationOptions): JsonWriter = writeObject {
    field("fragmentBytes", creation.fragmentBytes)
    field("hashTerms", creation.hashTerms)
    field("normalization", creation.normalization.name.lowercase())
    name("indexedKeys").writeArray { creation.indexedKeys.sorted().forEach { value(it) } }
    name("excludedKeys").writeArray { creation.excludedKeys.sorted().forEach { value(it) } }
    field("minIndexedStringBytes", creation.minIndexedStringBytes)
    field("maxIndexedStringBytes", creation.maxIndexedStringBytes)
    field("documentKey", creation.documentKeyPath)
}

/** [CreationOptions.documentKey], which the CLI reports and passes on as it is. */
@OptIn(ExperimentalDelvaApi::class)
internal val CreationOptions.documentKeyPath: String? get() = documentKey
