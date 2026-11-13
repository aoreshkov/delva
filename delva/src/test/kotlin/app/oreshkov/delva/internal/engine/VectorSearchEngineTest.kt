package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.StoreClosedException
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.VectorQuery
import app.oreshkov.delva.VectorSearchResult
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The vector search of the engine (SPEC.md §11.3) beyond what the property test checks: refusals, `explain`, the filter
 * read only for documents near enough to be hits, snapshot isolation while a long search runs, the version released
 * when a search fails, and the coroutine facade's cancellation.
 */
class VectorSearchEngineTest {
    private val field = VectorField("$.v", 2, VectorMetric.EUCLIDEAN)

    private fun options(
        creation: CreationOptions = CreationOptions(fragmentBytes = 256).withVectorField(field),
        memtableBytes: Long? = null,
        maxRangeDocuments: Int = Int.MAX_VALUE,
    ) = EngineOptions(
        creation, StoreOptions(encoderBudgetBytes = 1024), sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 3,
        log = TestEngines.LOG, backgroundFlush = false, memtableBytes = memtableBytes, maxRangeDocuments = maxRangeDocuments,
        compaction = CompactionOptions(enabled = false, bytesPerSecond = 0, minMergeSegments = 2),
    )

    /** Document [i] at `(i, 0)`, in group `i % 4`; every tenth has no vector. */
    private fun doc(i: Int): String = if (i % 10 == 9) """{"i":$i,"g":${i % 4}}""" else """{"i":$i,"g":${i % 4},"v":[$i,0]}"""

    private fun VectorSearchResult.ids(): List<Long> = use { r -> r.hits.map { it.docId.raw } }

    private fun Engine.insertAll(n: Int): List<Long> = List(n) { insert(doc(it)).raw }

    @Test
    fun `hits are the nearest documents with a vector, by distance and then docId, within maxDistance`() {
        TestEngines.open(options()).use { store ->
            val ids = store.insertAll(30)
            store.search(VectorQuery(floatArrayOf(10f, 0f), k = 5)).use { r ->
                // 10 itself, then 11 (9 has no vector), then 8 and 12 and then 7 and 13, each pair tied and taken by docId.
                assertEquals(listOf(10, 11, 8, 12, 7).map { ids[it] }, r.hits.map { it.docId.raw })
                assertEquals(listOf(0f, 1f, 2f, 2f, 3f), r.hits.map { it.distance })
                assertEquals(doc(11), r.hits[1].readJsonAsString())
                assertEquals(r.hits[1].docId, r.hits[1].node.docId)
                assertNull(r.hits[0].type)
                assertNull(r.hits[0].documentKey)
                assertTrue(r.hits[0].encodedSize > 0)
            }
            assertEquals(listOf(10, 11, 8, 12).map { ids[it] }, store.search(VectorQuery(floatArrayOf(10f, 0f), k = 5, maxDistance = 2f)).ids())
            assertEquals(emptyList(), store.search(VectorQuery(floatArrayOf(10f, 0f), maxDistance = -1f)).ids())
            assertEquals(27, store.search(VectorQuery(floatArrayOf(0f, 0f), k = VectorQuery.MAX_K)).ids().size)
        }
    }

    @Test
    fun `a search refuses a store without a vector field and a query vector that doesn't fit the field`() {
        TestEngines.open(options(CreationOptions(fragmentBytes = 256))).use { store ->
            assertFailsWith<IllegalStateException> { store.search(VectorQuery(floatArrayOf(1f, 0f))) }
            assertFailsWith<IllegalStateException> { store.explain(VectorQuery(floatArrayOf(1f, 0f))) }
        }
        TestEngines.open(options()).use { store ->
            assertFailsWith<IllegalArgumentException> { store.search(VectorQuery(floatArrayOf(1f, 0f, 0f))) }
        }
        val cosine = CreationOptions(fragmentBytes = 256).withVectorField(VectorField("$.v", 2, VectorMetric.COSINE))
        TestEngines.open(options(cosine)).use { store ->
            val e = assertFailsWith<IllegalArgumentException> { store.search(VectorQuery(floatArrayOf(0f, -0f))) }
            assertEquals("the query vector is zero, which has no direction under COSINE", e.message)
        }
    }

    @Test
    fun `a filter's terms narrow the candidates, and the filter is read only for documents that could be hits`() {
        TestEngines.open(options(memtableBytes = 4L shl 10)).use { store ->
            val ids = store.insertAll(400)
            store.flush()
            val query = VectorQuery(floatArrayOf(0f, 0f), k = 3).withDocumentFilter(DocumentFilter.compile("@.g == 2 && @.i > 100"))
            val plan = store.explain(query)
            assertEquals(false, plan.fullScan)
            assertEquals("'g' == 2 AND 'i' > 100", plan.terms)
            assertTrue(plan.readsDocuments)
            assertEquals(360L, plan.vectors)
            store.search(query).use { r ->
                // The three nearest of group 2 above 100.
                assertEquals(listOf(102, 106, 110).map { ids[it] }, r.hits.map { it.docId.raw })
                // Nearer candidates come first here, so once three are in, no farther document is read.
                val search = (r as EngineVectorSearchResult).search
                assertEquals(3L, search.filterTests)
                assertTrue(search.compared < 100, "only the documents of the terms are compared: ${search.compared}")
            }
        }
    }

    @Test
    fun `a search that compares nothing still reaches its checkpoints, and a cancelled caller stops it`() {
        TestEngines.open(options(memtableBytes = 4L shl 10)).use { store ->
            // Even documents have a vector, in group 0; odd ones have none, in group 1.
            val ids = List(2000) { store.insert(if (it % 2 == 0) """{"i":$it,"g":0,"v":[$it,0]}""" else """{"i":$it,"g":1}""").raw }
            store.flush()
            val withoutVectors = VectorQuery(floatArrayOf(0f, 0f)).withDocumentFilter(DocumentFilter.compile("@.g == 1"))
            assertEquals(false, store.explain(withoutVectors).fullScan)
            var checks = 0
            store.searchVectors(withoutVectors) { checks++ }.use { assertEquals(emptyList(), it.hits) }
            assertTrue(checks > 0, "a search whose candidates have no vector reached no checkpoint")

            for (i in ids.indices step 2) assertTrue(store.delete(DocId(ids[i])))
            val deleted = VectorQuery(floatArrayOf(0f, 0f))
            checks = 0
            store.searchVectors(deleted) { checks++ }.use { assertEquals(emptyList(), it.hits) }
            assertTrue(checks > 0, "a search over deleted vectors reached no checkpoint")

            // The suspending facade's checkpoint throws for a cancelled caller.
            val cancelled = Job().apply { cancel() }
            for (query in listOf(withoutVectors, deleted)) {
                assertFailsWith<CancellationException> { store.searchVectors(query) { cancelled.ensureActive() } }
            }
        }
    }

    @Test
    fun `a filter without index terms needs a scan, and a range too wide is refused unless a scan is allowed`() {
        TestEngines.open(options(maxRangeDocuments = 4)).use { store ->
            val ids = store.insertAll(20)
            val vector = floatArrayOf(5f, 0f)
            val negated = VectorQuery(vector, k = 2).withDocumentFilter(DocumentFilter.compile("!(@.g == 1)"))
            val e = assertFailsWith<QueryRejectedException> { store.search(negated) }
            assertEquals(QueryRejectedException.Reason.NEEDS_SCAN, e.reason)
            assertEquals("!(@.g == 1)", e.query)
            assertEquals(true, store.explain(negated).fullScan)
            assertEquals(listOf(4, 6).map { ids[it] }, store.search(negated.copy(scan = ScanPolicy.ALLOW_SCAN)).ids())

            val range = VectorQuery(vector, k = 2).withDocumentFilter(DocumentFilter.compile("@.i >= 6"))
            assertEquals(QueryRejectedException.Reason.RANGE_TOO_WIDE, assertFailsWith<QueryRejectedException> { store.search(range) }.reason)
            assertEquals(listOf(6, 7).map { ids[it] }, store.search(range.copy(scan = ScanPolicy.ALLOW_SCAN)).ids())

            // Without a filter, a search compares every vector and is never refused.
            val plain = store.explain(VectorQuery(vector))
            assertEquals(true, plain.fullScan)
            assertEquals(false, plain.readsDocuments)
            assertEquals("VectorQueryPlan(18 vectors, full scan)", plain.toString())
        }
    }

    @Test
    fun `a search sees the store as it started, whatever is written while it compares, and its hits stay readable`() {
        val fs = SimulatedFileSystemIo()
        TestEngines.open(options(memtableBytes = 4L shl 10), fs).use { store ->
            val ids = store.insertAll(1000)
            store.flush()
            val query = VectorQuery(floatArrayOf(500f, 0f), k = 3)
            val before = store.search(query).ids()
            assertEquals(listOf(500, 501, 498).map { ids[it] }, before)
            var writes = 0
            val result = store.searchVectors(query) {
                // In the middle of the comparisons: the hits-to-be go, and a nearer document arrives.
                if (writes++ == 0) {
                    for (docId in before) assertTrue(store.delete(DocId(docId)))
                    store.insert("""{"i":-1,"g":0,"v":[500,0]}""")
                    store.flush()
                    store.compact()
                    store.reclaim()
                }
            }
            assertTrue(writes > 1, "the search ran past several checkpoints")
            result.use { r ->
                assertEquals(before, r.hits.map { it.docId.raw })
                assertEquals(listOf(doc(500), doc(501), doc(498)), r.hits.map { it.readJsonAsString() })
            }
            assertEquals(listOf(-1, 502, 497), store.search(query).use { r -> r.hits.map { Regex(""""i":(-?\d+)""").find(it.readJsonAsString())!!.groupValues[1].toInt() } })
        }
    }

    @Test
    fun `a search that fails midway releases its version, and a closed result can't be read`() {
        TestEngines.open(options(memtableBytes = 4L shl 10)).use { store ->
            store.insertAll(1000)
            store.flush()
            val version = store.indexes.versions.current()
            val e = assertFailsWith<IllegalStateException> { store.searchVectors(VectorQuery(floatArrayOf(0f, 0f))) { error("stop") } }
            assertEquals("stop", e.message)
            store.insert(doc(0))
            store.flush()
            assertFalse(version.tryRetain(), "the failed search released the version it read")

            val result = store.search(VectorQuery(floatArrayOf(0f, 0f), k = 1))
            val hit = result.hits.single()
            result.close()
            assertFailsWith<IllegalStateException> { hit.readJsonAsString() }
            assertFailsWith<IllegalStateException> { hit.encodedSize }
        }
        val store = TestEngines.open(options())
        store.insertAll(3)
        store.close()
        assertFailsWith<StoreClosedException> { store.search(VectorQuery(floatArrayOf(0f, 0f))) }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `the suspending search returns its hits, and a cancelled caller stops it`() {
        val dir = Files.createTempDirectory("delva-vector")
        try {
            JsonStore.create(dir, CreationOptions().withVectorField(field)).use { store ->
                val blocking = store.blocking()
                repeat(6) { blocking.insert(doc(it)) }
                runBlocking {
                    store.search(VectorQuery(floatArrayOf(3f, 0f), k = 2)).use { r -> assertEquals(listOf(0f, 1f), r.hits.map { it.distance }) }
                    var thrown: Throwable? = null
                    launch {
                        cancel()
                        thrown = runCatching { store.search(VectorQuery(floatArrayOf(3f, 0f))) }.exceptionOrNull()
                    }.join()
                    assertTrue(thrown is CancellationException, "$thrown")
                }
                assertEquals(2, blocking.search(VectorQuery(floatArrayOf(3f, 0f), k = 2)).use { it.hits.size })
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
