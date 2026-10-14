/*
 * Records named by a key, kept with document keys.
 *
 * A store created with a document key names each document by the string at a path, here `$.id`, and keeps one
 * visible document per key. Saving a record is an upsert: it inserts the new version and deletes the one with the same
 * key in one atomic change, so however many writers save one record at once, exactly one version stays visible, and a
 * crash never leaves two or none. Compare the versions example, which keeps the same promise with `replace` and a
 * lookup that has to be retried. A search over the records of one kind tests each record's root with a document filter.
 */
package app.oreshkov.delva.examples

import app.oreshkov.delva.BlockingJsonStore
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentFilterTemplate
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import java.nio.file.Path

/** Opens the record store in [dir], creating it with the document key `$.id` when there is none. */
@OptIn(ExperimentalDelvaApi::class)
fun openRecords(dir: Path): JsonStore = JsonStore.openOrCreate(dir, CreationOptions().withDocumentKey("$.id"))

/** Stores [json] as the current version of the record its `id` names, replacing the current one; returns its docId. */
@OptIn(ExperimentalDelvaApi::class)
fun saveRecord(db: BlockingJsonStore, json: String): DocId = db.upsert(json)

/**
 * The current version of the record [id] as JSON, or null when there is none. One search reads it, on one snapshot: a
 * [BlockingJsonStore.get] followed by a read could find a version that a save deletes in between.
 */
@OptIn(ExperimentalDelvaApi::class)
fun readRecord(db: BlockingJsonStore, id: String): String? =
    // The smallest key that starts with the id is the id itself, when a record has it.
    db.search(DocumentKeyQuery(prefix = id, limit = 1)).use { result ->
        result.nextPage()?.hits?.single()?.takeIf { it.documentKey == id }?.readJsonAsString()
    }

/** Whether a record has the id [id]. */
@OptIn(ExperimentalDelvaApi::class)
fun hasRecord(db: BlockingJsonStore, id: String): Boolean = db.get(id) != null

/**
 * The ids of the records whose id starts with [prefix], in id order, read in pages of [pageSize]: each page resumes from
 * the cursor of the one before, as a client paging through a listing would.
 */
@OptIn(ExperimentalDelvaApi::class)
fun listRecords(db: BlockingJsonStore, prefix: String, pageSize: Int = 100): List<String> {
    val ids = ArrayList<String>()
    var query = DocumentKeyQuery(prefix, limit = pageSize)
    while (true) {
        val page = db.search(query).use { it.nextPage() } ?: break
        page.hits.mapNotNullTo(ids) { it.documentKey }
        query = query.copy(after = page.cursor ?: break)
    }
    return ids
}

/**
 * What [path] selects in the records whose own `kind` member is [kind], each with its record's id, in the order the
 * records were saved. A document filter tests a record's root, so a `kind` inside a record's data doesn't count, and its
 * term lets the index find the records of that kind: even `$..sku` needs no scan. The kind is bound to the filter's
 * placeholder as a value, so no kind can change what the filter tests.
 */
@OptIn(ExperimentalDelvaApi::class)
fun findInRecords(db: BlockingJsonStore, kind: String, path: JsonPath): List<Pair<String, String>> {
    val query = JsonPathQuery(path).withDocumentFilter(OF_KIND.bind("kind" to JsonQueryValue.Str(kind)))
    return db.search(query).use { result ->
        result.iterator().asSequence().map { it.documentKey!! to it.readJsonAsString() }.toList()
    }
}

/** The filter of [findInRecords], compiled once. */
@OptIn(ExperimentalDelvaApi::class)
private val OF_KIND = DocumentFilterTemplate.compile("@.kind == \${kind}")
