/*
 * One current version per key, kept with replace.
 *
 * Documents are immutable, so a new version of a record is a new document. `replace` inserts it and deletes the
 * version it supersedes in one atomic change, and only if that version is still there: when another writer replaced
 * it first, the replace conflicts and changes nothing, and the writer looks up the current version again. However many
 * writers save versions of one key at once, exactly one version stays visible, and a crash never leaves two or none.
 */
package app.oreshkov.delva.examples

import app.oreshkov.delva.BlockingJsonStore
import app.oreshkov.delva.DocId
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.WriteConflictException

/**
 * Stores [json] as the current version of the record whose [keyMember] is [key], replacing the version that is current
 * now, and returns the new version's docId. The newest document with the key is the current version: a descending
 * search with a limit of one finds it.
 *
 * With no version stored yet, two writers can both insert a first one; the newer is then current, and the next save
 * replaces only that one.
 */
@OptIn(ExperimentalDelvaApi::class)
fun saveVersion(db: BlockingJsonStore, keyMember: String, key: String, json: String): DocId {
    val lookup = KeyValueQuery(keyMember, JsonQueryValue.Str(key), limit = 1, order = SearchOrder.DESCENDING)
    while (true) {
        val current = db.search(lookup).use { result -> result.nextPage()?.hits?.single()?.docId }
        try {
            return db.replace(listOfNotNull(current), json)
        } catch (_: WriteConflictException) {
            // Another writer replaced it after the lookup: look again.
        }
    }
}
