package app.oreshkov.delva.intellij.store

import app.oreshkov.delva.CorruptionException
import app.oreshkov.delva.DelvaException
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.InvalidJsonPathException
import app.oreshkov.delva.JsonFormat
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.LockHolder
import app.oreshkov.delva.ResultTooLargeException
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.SearchHit
import app.oreshkov.delva.SecondaryJsonStore
import app.oreshkov.delva.StoreNotFoundException
import app.oreshkov.delva.intellij.DelvaBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

/**
 * Reads stores with the Delva version bundled in the plugin, as secondaries: they take no lock and never write, so a
 * store can be read while an application has it open. Each store stays open until [close], and catches up with its
 * writer before every read.
 */
@OptIn(ExperimentalDelvaApi::class)
internal class EmbeddedStoreBackend : StoreBackend, AutoCloseable {
    private val stores = HashMap<Path, SecondaryJsonStore>()
    private var closed = false

    override suspend fun info(dir: Path): StoreSummary = reading(dir) { store ->
        val info = store.info()
        StoreSummary(info.approximateDocumentCount, info.deletedDocumentCount, store.writer()?.let(::describe))
    }

    override suspend fun searchPath(dir: Path, query: String, limit: Int, maxChars: Int): SearchOutcome {
        require(limit > 0) { "limit must be positive, was $limit" }
        val path = compile(query)
        return reading(dir) { store ->
            // One page more than asked for tells whether more hits are left.
            store.search(JsonPathQuery(path, limit = limit + 1, scan = ScanPolicy.ALLOW_SCAN)).use { result ->
                val hits = ArrayList<StoreHit>()
                var more = false
                for (hit in result) {
                    ensureActive()
                    if (hits.size == limit) {
                        more = true
                        break
                    }
                    hits += hit.toStoreHit(maxChars)
                }
                SearchOutcome(hits, more)
            }
        }
    }

    override fun checkQuery(query: String): String? = try {
        JsonPath.compile(query)
        null
    } catch (e: InvalidJsonPathException) {
        DelvaBundle.message("query.invalid", e.offset, e.reason)
    }

    override fun close() {
        val open = synchronized(stores) {
            closed = true
            stores.values.toList().also { stores.clear() }
        }
        for (store in open) store.close()
    }

    private fun compile(query: String): JsonPath = try {
        JsonPath.compile(query)
    } catch (e: InvalidJsonPathException) {
        throw StoreAccessException(DelvaBundle.message("query.invalid", e.offset, e.reason), e)
    }

    private suspend fun <T> reading(dir: Path, read: suspend CoroutineScope.(SecondaryJsonStore) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                val cached = synchronized(stores) {
                    check(!closed) { "The backend is closed" }
                    stores[dir]
                }
                // A store just opened has read the newest state already; a refresh costs as much as the open's replay.
                val store = cached?.also { it.refresh() } ?: open(dir)
                read(store)
            } catch (e: StoreNotFoundException) {
                throw StoreAccessException(DelvaBundle.message("store.notFound", dir), e)
            } catch (e: CorruptionException) {
                // Delva reports a store of another format version the way it reports damage; only the detail tells.
                val message = if (e.detail.startsWith("unsupported format version")) {
                    DelvaBundle.message("store.otherVersion", BUNDLED_DELVA_MINOR)
                } else {
                    DelvaBundle.message("store.corrupt", e.message.orEmpty())
                }
                throw StoreAccessException(message, e)
            } catch (e: DelvaException) {
                throw StoreAccessException(e.message.orEmpty(), e)
            } catch (e: IOException) {
                throw StoreAccessException(DelvaBundle.message("store.io", dir, e.message.orEmpty()), e)
            }
        }

    private fun open(dir: Path): SecondaryJsonStore {
        val store = JsonStore.openSecondary(dir)
        val kept = synchronized(stores) {
            if (closed) null else stores.putIfAbsent(dir, store) ?: store
        }
        // Lost a race with another opener, or the backend closed meanwhile.
        if (kept !== store) store.close()
        return kept ?: throw IllegalStateException("The backend is closed")
    }

    private fun SearchHit.toStoreHit(maxChars: Int): StoreHit {
        val json = try {
            readJsonAsString(JsonFormat.PRETTY, maxChars)
        } catch (_: ResultTooLargeException) {
            null
        }
        return StoreHit(docId.raw, documentKey, path, json, encodedSize)
    }

    private fun describe(holder: LockHolder): String {
        val host = holder.host
        return if (host != null) DelvaBundle.message("writer.processOnHost", holder.pid, host)
        else DelvaBundle.message("writer.process", holder.pid)
    }

    private companion object {
        /** The Delva minor this plugin bundles, whose store format is the only one it reads. */
        const val BUNDLED_DELVA_MINOR = "0.2"
    }
}
