package app.oreshkov.delva.examples

import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.QueryRejectedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The embedding-search example, kept working: its top k is the brute-force top k, and its numbers read back exactly. */
class EmbeddingSearchTest {
    private val dir: Path = Files.createTempDirectory("delva-embedding-search")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    @Test
    fun `the nearest chunks are the brute-force top k, with ties in docId order`() {
        createChunkStore(dir.resolve("chunks")).use { store ->
            val db = store.blocking()
            val random = Random(7)
            val chunks = (0 until 120).map { n -> Chunk("doc-${n / 4}", n % 4, if (n % 10 == 0) "other" else "m", "chunk $n", randomUnitVector(random, 16)) }
            // Two chunks with the very same embedding as an earlier one: a tie, which docId order breaks.
            val twins = (0 until 2).map { Chunk("twins", it, chunks[11].model, "twin", chunks[11].embedding) }
            val ids = insertChunks(db, chunks + twins)
            val all = (chunks + twins).zip(ids)

            for (k in listOf(1, 5, 50, 500)) {
                val query = randomUnitVector(random, 16)
                val expected = all.filter { (chunk, _) -> chunk.model == "m" }
                    .map { (chunk, id) -> Neighbor(id, cosineDistance(query, chunk.embedding)) }
                    .sortedWith(compareBy({ it.distance }, { it.docId.raw }))
                    .take(k)
                assertEquals(expected, nearestChunks(db, query, k, model = "m"), "k = $k")
            }

            val ofSource = nearestChunks(db, chunks[9].embedding, 10, model = "m", source = "doc-2")
            assertEquals(ids[9], ofSource.first().docId, "a chunk is nearest to itself")
            assertEquals(3, ofSource.size, "doc-2 has four chunks, one of another model")

            val tied = nearestChunks(db, chunks[11].embedding, 3, model = "m")
            assertEquals(listOf(ids[11], ids[120], ids[121]), tied.map { it.docId })
            assertTrue(tied.all { it.distance < 1e-6 }, "$tied")
        }
    }

    @Test
    fun `numbers written short read back as the same float32`() {
        val random = Random(11)
        val vector = FloatArray(1000) { Float.fromBits(random.nextInt()).takeIf { f -> f.isFinite() } ?: 0f } +
            floatArrayOf(Float.MIN_VALUE, -Float.MAX_VALUE, -0f, 1e-7f, 0.1f)
        val json = chunkJson(Chunk("s", 0, "m", "t", vector))
        val array = json.substring(json.indexOf('['), json.lastIndexOf(']') + 1)
        assertContentEquals(vector, parseVector(array))
        assertTrue(array.length <= vector.size * 16 + 1, "at most 15 characters and a comma per number, was ${array.length}")
    }

    @Test
    fun `the embedding key is left out of the index`() {
        createChunkStore(dir.resolve("chunks")).use { store ->
            insertChunks(store.blocking(), listOf(Chunk("s", 0, "m", "t", floatArrayOf(0.5f, -0.25f))))
            val rejected = assertFailsWith<QueryRejectedException> { store.blocking().count(KeyValueQuery(EMBEDDING_KEY, JsonQueryValue.Str("x"))) }
            assertEquals(QueryRejectedException.Reason.NOT_INDEXED, rejected.reason)
        }
        assertEquals(setOf(EMBEDDING_KEY), JsonStore.open(dir.resolve("chunks")).use { it.creationOptions.excludedKeys })
    }

    @Test
    fun `vectors of another length can't be compared`() {
        assertFailsWith<IllegalArgumentException> { cosineDistance(floatArrayOf(1f), floatArrayOf(1f, 0f)) }
    }
}
