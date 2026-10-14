/*
 * Two conditions in one search, by making one of them a type.
 *
 * A query is a single `key = value` pair, so "the object whose @type is Battlefield and whose id is bf-101" is not
 * one query. Searching for both and keeping the objects that appear in both results works — a hit's node is the
 * object itself, so the same NodeRef in both results is one object with both members — but it collects every object
 * of the broader condition on every lookup.
 *
 * This example does it the other way round: the objects carrying the first condition are tagged once with a type,
 * which is metadata and leaves the stored JSON untouched, and each lookup afterwards is one search restricted to
 * objects of that type.
 *
 *     ./gradlew :delva-examples:run --args="catalog.json @type=Battlefield id=bf-101"
 *
 * The store is created beside the file and reused on the next run, so the insert and the tagging happen once.
 */
package app.oreshkov.delva.examples

import app.oreshkov.delva.BlockingJsonStore
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.system.exitProcess

private val usage = """
    Usage: typed-search <json-file> <tag-key>=<tag-value> <find-key>=<find-value>

    Inserts <json-file> into a store beside it, tags every object whose own <tag-key> member is <tag-value> with a
    type named after that value, then prints the objects of that type whose <find-key> member is <find-value>.
""".trimIndent()

fun main(args: Array<String>) {
    if (args.size != 3) {
        System.err.println(usage)
        exitProcess(2)
    }
    val json = Path.of(args[0])
    val tag = Condition.parse(args[1])
    val lookup = Condition.parse(args[2])

    // One store per file, reused across runs: a store is a directory, opened by one process at a time.
    val storeDir = json.resolveSibling("${json.fileName}.delva")
    if (Files.notExists(storeDir)) buildStore(json, tag, storeDir)

    JsonStore.open(storeDir).use { store ->
        val db = store.blocking()
        println("$storeDir holds ${db.countOfType(tag.value)} objects of type '${tag.value}'")

        val hits = findInType(db, tag.value, lookup)
        println("${hits.size} object(s) with ${lookup.key}=${lookup.value}:")
        for ((node, text) in hits) println("  $node $text")
    }
}

/**
 * Inserts [json] and tags its objects in a store of a temporary name, then moves that store to [storeDir].
 *
 * Only a store that was built to the end gets the final name, so a run interrupted halfway leaves nothing the next
 * run would mistake for a finished store.
 */
@OptIn(ExperimentalPathApi::class)
private fun buildStore(json: Path, tag: Condition, storeDir: Path) {
    val building = storeDir.resolveSibling("${storeDir.fileName}.building")
    building.deleteRecursively()

    JsonStore.create(building).use { store ->
        val db = store.blocking()
        // The file is split and indexed as it is read, so its size doesn't bound what this needs in memory.
        val docId = db.insert(json)
        val tagged = tagObjects(db, tag, tag.value)
        println("inserted ${json.fileName} as document ${docId.raw}; tagged $tagged objects as '${tag.value}'")
    }
    Files.move(building, storeDir)
}

/** One `key=value` condition, split on the first `=` so a value may contain one. */
data class Condition(val key: String, val value: String) {
    companion object {
        fun parse(text: String): Condition {
            val separator = text.indexOf('=')
            require(separator > 0) { "expected key=value, got '$text'" }
            return Condition(text.substring(0, separator), text.substring(separator + 1))
        }
    }
}

/**
 * Assigns [typeName] to every object whose own [marker] member has that value, and returns how many were tagged.
 *
 * A type name is at most 256 bytes of UTF-8, so a longer value needs a name of its own rather than the value itself.
 */
fun tagObjects(db: BlockingJsonStore, marker: Condition, typeName: String): Int {
    val nodes = db.search(marker.asQuery()).use { result -> result.iterator().asSequence().map { it.node }.toList() }
    // In batches, because one call holds all its assignments in memory; each batch is atomic on its own.
    for (batch in nodes.chunked(1000)) db.assignTypes(batch.map { it to typeName })
    return nodes.size
}

/** The objects of type [typeName] whose own [lookup] member has that value, with the JSON of each. */
fun findInType(db: BlockingJsonStore, typeName: String, lookup: Condition): List<Pair<NodeRef, String>> =
    db.search(lookup.asQuery(type = typeName)).use { result ->
        // A hit can be read only while its result is open, so read the JSON here rather than keeping the hits.
        // Objects larger than a megabyte need writeJsonTo, which streams instead of building a string.
        result.iterator().asSequence().map { it.node to it.readJsonAsString() }.toList()
    }

/** CONTAINING_ELEMENT: a hit is the object that has the member, not the whole document. */
private fun Condition.asQuery(type: String? = null) =
    KeyValueQuery(key, JsonQueryValue.Str(value), ResultMode.CONTAINING_ELEMENT, limit = 500, type = type)
