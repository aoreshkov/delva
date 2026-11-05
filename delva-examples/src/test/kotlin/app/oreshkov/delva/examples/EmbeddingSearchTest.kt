package app.oreshkov.delva.examples

import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.math.abs
import kotlin.math.sqrt
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
        createChunkStore(dir.resolve("chunks"), 16).use { store ->
            val db = store.blocking()
            val random = Random(7)
            val chunks = (0 until 120).map { n -> Chunk("doc-${n / 4}", n % 4, "chunk $n", randomUnitVector(random, 16)) }
            // Two chunks with the very same embedding as an earlier one: a tie, which docId order breaks.
            val twins = (0 until 2).map { Chunk("twins", it, "twin", chunks[11].embedding) }
            val ids = insertChunks(db, chunks + twins)
            val all = (chunks + twins).zip(ids)

            for (k in listOf(1, 5, 50, 500)) {
                val query = randomUnitVector(random, 16)
                val expected = all.map { (chunk, id) -> id to cosineDistance(query, chunk.embedding) }
                    .sortedWith(compareBy({ it.second }, { it.first.raw }))
                    .take(k)
                val found = nearestChunks(db, query, k)
                assertEquals(expected.map { it.first }, found.map { it.docId }, "k = $k")
                for ((neighbor, oracle) in found.zip(expected)) {
                    assertTrue(abs(neighbor.distance - oracle.second) < 1e-6, "k = $k: $neighbor, expected ${oracle.second}")
                }
            }

            val ofSource = nearestChunks(db, chunks[9].embedding, 10, source = "doc-2")
            assertEquals(ids[9], ofSource.first().docId, "a chunk is nearest to itself")
            assertEquals(ids.subList(8, 12).toSet(), ofSource.map { it.docId }.toSet(), "doc-2 has four chunks")

            val tied = nearestChunks(db, chunks[11].embedding, 3)
            assertEquals(listOf(ids[11], ids[120], ids[121]), tied.map { it.docId })
            assertTrue(tied.all { it.distance < 1e-6 }, "$tied")
        }
    }

    @Test
    fun `numbers written short read back as the same float32`() {
        val random = Random(11)
        val vector = FloatArray(1000) { Float.fromBits(random.nextInt()).takeIf { f -> f.isFinite() } ?: 0f } +
            floatArrayOf(Float.MIN_VALUE, -Float.MAX_VALUE, -0f, 1e-7f, 0.1f)
        val json = chunkJson(Chunk("s", 0, "t", vector))
        val array = json.substring(json.indexOf('[') + 1, json.lastIndexOf(']')).split(',')
        assertContentEquals(vector, FloatArray(array.size) { array[it].toFloat() })
        assertTrue(array.sumOf { it.length + 1 } <= vector.size * 16, "at most 15 characters and a comma per number")
    }

    @OptIn(ExperimentalDelvaApi::class)
    @Test
    fun `the store has a required vector field and leaves the embedding key out of the index`() {
        createChunkStore(dir.resolve("chunks"), 2).use { store ->
            val db = store.blocking()
            insertChunks(db, listOf(Chunk("s", 0, "t", floatArrayOf(0.5f, -0.25f))))
            val rejected = assertFailsWith<QueryRejectedException> { db.count(KeyValueQuery(EMBEDDING_KEY, JsonQueryValue.Str("x"))) }
            assertEquals(QueryRejectedException.Reason.NOT_INDEXED, rejected.reason)
            val missing = assertFailsWith<DocumentRejectedException> { db.insert("""{"source":"s","chunk":1,"text":"t"}""") }
            assertEquals(RejectReason.INVALID_VECTOR, missing.reason)
            val tooLong = assertFailsWith<IllegalStateException> { insertChunks(db, listOf(Chunk("s", 2, "t", floatArrayOf(1f, 0f, 0f)))) }
            assertTrue("INVALID_VECTOR" in tooLong.message.orEmpty(), tooLong.message)
        }
        val options = JsonStore.open(dir.resolve("chunks")).use { it.creationOptions }
        assertEquals(setOf(EMBEDDING_KEY), options.excludedKeys)
        assertEquals(VectorField("$.embedding", 2, VectorMetric.COSINE, required = true), options.vectorField)
    }
}

/** The oracle: the cosine distance of [a] and [b], `1 − cos θ`, in double precision. */
private fun cosineDistance(a: FloatArray, b: FloatArray): Double {
    var dot = 0.0
    var aa = 0.0
    var bb = 0.0
    for (i in a.indices) {
        dot += a[i].toDouble() * b[i]
        aa += a[i].toDouble() * a[i]
        bb += b[i].toDouble() * b[i]
    }
    return 1 - dot / sqrt(aa * bb)
}
