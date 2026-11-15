package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.internal.vector.EmbeddingSet
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The recall measurement of [VectorRecallReportTest], on a small generated set: what reads a set, finds the true
 * nearest vectors and counts a search's recall is itself checked here, where `check` runs it, since the report needs
 * a set that isn't in the repository.
 */
@OptIn(ExperimentalDelvaApi::class)
class VectorRecallTest {
    private val dir: Path = Files.createTempDirectory("delva-recall-set")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private val random = Random(7)

    /**
     * Vectors as embeddings are: around a few centers, with a mean far from zero and most of their spread in the first
     * dimensions. Every tenth repeats the one before it, so equal distances are common.
     */
    private val vectors: List<FloatArray> = run {
        val centers = List(CENTERS) { FloatArray(DIMENSIONS) { i -> (MEAN + random.nextGaussian() / (1 + i)).toFloat() } }
        val made = ArrayList<FloatArray>(VECTORS)
        repeat(VECTORS) { n ->
            made += if (n % 10 == 9) made[n - 1].copyOf() else near(centers[random.nextInt(CENTERS)])
        }
        made
    }

    private val queries: List<FloatArray> = List(QUERIES) { near(vectors[random.nextInt(VECTORS)]) }

    private fun near(vector: FloatArray): FloatArray = FloatArray(DIMENSIONS) { i -> (vector[i] + NOISE * random.nextGaussian() / (1 + i)).toFloat() }

    /** The distance of SPEC.md §11.3, written out once more for each metric: the truth the set's own code is held to. */
    private fun distance(metric: VectorMetric, a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var aa = 0.0
        var bb = 0.0
        var differences = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            aa += a[i].toDouble() * a[i]
            bb += b[i].toDouble() * b[i]
            differences += (a[i].toDouble() - b[i]) * (a[i].toDouble() - b[i])
        }
        return when (metric) {
            VectorMetric.COSINE -> 1 - dot / sqrt(aa * bb)
            VectorMetric.DOT_PRODUCT -> -dot
            VectorMetric.EUCLIDEAN -> sqrt(differences)
        }
    }

    /** The [k] rows of the first [count] vectors nearest to [query], by sorting them all. */
    private fun sorted(metric: VectorMetric, query: FloatArray, count: Int, k: Int): IntArray =
        (0 until count).sortedWith(compareBy<Int> { distance(metric, query, vectors[it]) }.thenBy { it }).take(k).toIntArray()

    @Test
    fun `a set is read back as it was written`() {
        EmbeddingSet.write(dir, vectors, queries) { q -> intArrayOf(q, q + 1) }
        val set = EmbeddingSet(dir)
        assertEquals(VECTORS, set.count)
        assertEquals(DIMENSIONS, set.dimensions)
        val read = ArrayList<FloatArray>()
        set.forEachVector { row, vector ->
            assertEquals(read.size, row)
            read += vector.copyOf()
        }
        assertEquals(VECTORS, read.size)
        for (row in vectors.indices) assertContentEquals(vectors[row], read[row], "row $row")
        assertContentEquals(vectors[VECTORS - 1], set.vector(VECTORS - 1))
        var first = 0
        set.forEachVector(limit = 70) { _, _ -> first++ }
        assertEquals(70, first)

        val some = set.queries(3)
        assertEquals(3, some.size)
        assertContentEquals(queries[2], some[2].vector)
        assertContentEquals(intArrayOf(2, 3), some[2].closestRows)
        assertEquals(QUERIES, set.queries(Int.MAX_VALUE).size)

        Files.write(dir.resolve("vectors.npy"), ByteArray(40))
        assertFailsWith<IllegalArgumentException> { EmbeddingSet(dir) }
    }

    @Test
    fun `the nearest vectors are the first of all of them sorted by distance, under each metric`() {
        EmbeddingSet.write(dir, vectors, queries)
        val set = EmbeddingSet(dir)
        for (metric in VectorMetric.entries) {
            for (count in listOf(VECTORS, 333)) {
                val nearest = set.nearest(count, queries, K, metric)
                for (q in queries.indices) {
                    // Sorting adds the squares in another order, so two vectors a rounding apart may swap places.
                    assertEquals(1.0, set.recall(sorted(metric, queries[q], count, K), nearest[q], queries[q], metric), "$metric, $count vectors, query $q")
                    assertEquals(K, nearest[q].rows.size)
                    assertEquals(nearest[q].distances.sorted(), nearest[q].distances.toList(), "nearest first")
                }
            }
        }
    }

    @Test
    fun `recall counts the hits that are as near as the true neighbors, whichever of equal vectors they are`() {
        EmbeddingSet.write(dir, vectors, queries)
        val set = EmbeddingSet(dir)
        val metric = VectorMetric.EUCLIDEAN
        // Row 8 and its repeat, row 9, are the two nearest to row 8's vector; the truth for one neighbor names row 8.
        val query = vectors[8]
        val one = set.nearest(VECTORS, listOf(query), 1, metric).single()
        assertContentEquals(intArrayOf(8), one.rows)
        assertEquals(1.0, set.recall(intArrayOf(8), one, query, metric))
        assertEquals(1.0, set.recall(intArrayOf(9), one, query, metric), "an equal vector is as right")
        val far = sorted(metric, query, VECTORS, VECTORS).last()
        assertEquals(0.0, set.recall(intArrayOf(far), one, query, metric))

        val ten = set.nearest(VECTORS, listOf(query), K, metric).single()
        assertEquals(1.0, set.recall(ten.rows, ten, query, metric))
        assertEquals(0.9, set.recall(ten.rows.copyOf().also { it[3] = far }, ten, query, metric))
        assertEquals(0.5, set.recall(ten.rows.copyOf(5), ten, query, metric), "fewer hits than neighbors")
        assertEquals(0.1, set.recall(IntArray(K) { ten.rows[0] }, ten, query, metric), "a hit counts once")
    }

    @Test
    fun `exact search has a recall of one, under each metric`() {
        EmbeddingSet.write(dir, vectors, queries) { q -> sorted(VectorMetric.COSINE, queries[q], VECTORS, K) }
        val set = EmbeddingSet(dir)
        for (metric in VectorMetric.entries) {
            val measured = measureRecall(set, VECTORS, QUERIES, K, metric)
            assertEquals(QUERIES, measured.queries)
            assertEquals(1.0, measured.recall, "$metric")
            assertEquals(1.0, measured.lowestRecall, "$metric")
            assertEquals(4, measured.millis.size)
        }
        // The rows the set names are its nearest under cosine, and of all its vectors.
        assertEquals(1.0, measureRecall(set, VECTORS, 5, K, VectorMetric.COSINE).agreement)
        assertEquals(null, measureRecall(set, VECTORS / 2, 5, K, VectorMetric.COSINE).agreement)
    }

    private companion object {
        const val VECTORS = 1_000
        const val DIMENSIONS = 24
        const val QUERIES = 20
        const val CENTERS = 6
        const val K = 10
        const val MEAN = 0.5
        const val NOISE = 0.2
    }
}
