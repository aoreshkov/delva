package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import java.nio.file.Files
import java.util.PriorityQueue
import java.util.Random
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What an embedding costs a document store: chunk documents that each carry one unit-length vector of 768 float32
 * values, written as the shortest text that reads back as the same float32 or as the up to 17 digits of a serializer
 * that widens it to float64 first, in a store that indexes the vector's key and in one created with it in
 * `excludedKeys`. Prints raw, stored and index bytes per document, insert throughput, and how long the guide's
 * brute-force top 10 in application code takes over every chunk, for the guide's advice on storing embeddings.
 *
 * Runs under `reportTest`, not `check`. `-Ddelva.report.embeddings=<n>` sets the number of documents (10 000 by
 * default); every variant gets the same vectors.
 */
class EmbeddingSizeReportTest {
    @Test
    fun `embedding size report`() {
        val documents = (System.getProperty("delva.report.embeddings") ?: "10000").toInt()
        val rows = ArrayList<String>()
        val sizes = HashMap<Pair<Spelling, Boolean>, Sizes>()
        for (spelling in Spelling.entries) {
            for (excluded in listOf(false, true)) {
                val measured = measure(documents, spelling, excluded)
                sizes[spelling to excluded] = measured
                rows += "| %s | %s | %,d | %,d | %,d | %.0f | %.1f | %.0f |".format(
                    spelling.label, if (excluded) "excluded" else "indexed",
                    measured.raw / documents, measured.lane / documents, measured.index / documents,
                    documents / (measured.insertNanos / 1e9), measured.raw / (1 shl 20).toDouble() / (measured.insertNanos / 1e9),
                    measured.searchNanos / 1e6,
                )
            }
        }
        println(
            "\nEmbeddings: $documents chunk documents, $DIMENSIONS dimensions, deflate with dictionaries; " +
                "top $TOP_K is the median of $SEARCHES brute-force searches over every chunk, in application code\n\n" +
                "| Numbers | Vector key | Raw bytes/doc | Stored bytes/doc | Index bytes/doc | Inserts/s | Insert MiB/s | Top $TOP_K ms |\n" +
                "|---|---|---|---|---|---|---|---|\n" + rows.joinToString("\n"),
        )
        for (spelling in Spelling.entries) {
            assertTrue(sizes.getValue(spelling to true).index < sizes.getValue(spelling to false).index, "excluding the key shrinks the index")
        }
        assertTrue(sizes.getValue(Spelling.FLOAT32 to true).raw < sizes.getValue(Spelling.FLOAT64 to true).raw, "float32 text is shorter")
    }

    private fun measure(documents: Int, spelling: Spelling, excluded: Boolean): Sizes {
        val dir = Files.createTempDirectory("delva-embedding-size")
        try {
            val creation = if (excluded) CreationOptions(excludedKeys = setOf("embedding")) else CreationOptions()
            Engine.open(dir, EngineOptions(creation), JdkFileSystemIo()).use { store ->
                val random = Random(SEED)
                val vector = FloatArray(DIMENSIONS)
                var raw = 0L
                var insertNanos = 0L
                var next = 0
                while (next < documents) {
                    val batch = List(minOf(BATCH, documents - next)) { i -> chunk(next + i, random, vector, spelling) }
                    raw += batch.sumOf { it.size.toLong() }
                    val start = System.nanoTime()
                    store.insertAll(batch.map { InsertSource(it.inputStream()) })
                    insertNanos += System.nanoTime() - start
                    next += batch.size
                }
                store.flush()
                while (store.compact() > 0) {
                    // Settle the LSM: the index size to report is the compacted one.
                }
                assertEquals(CHUNKS_PER_SOURCE.toLong(), store.count(KeyValueQuery("source", JsonQueryValue.Str("doc-0"))))
                val query = FloatArray(DIMENSIONS) { if (it == 0) 1f else 0f }
                val searchNanos = LongArray(SEARCHES) {
                    val start = System.nanoTime()
                    assertEquals(TOP_K, bruteForceTopK(store, query).size)
                    System.nanoTime() - start
                }.sorted()[SEARCHES / 2]
                return Sizes(raw, PerformanceReportChild.sizeOf(dir.resolve("data")), PerformanceReportChild.sizeOf(dir.resolve("index")), insertNanos, searchNanos)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * The guide's search in application code: every chunk's embedding read through a document filter on the model,
     * parsed, and kept in a heap of the best [TOP_K] by cosine distance.
     */
    @OptIn(ExperimentalDelvaApi::class)
    private fun bruteForceTopK(store: Engine, query: FloatArray): List<Pair<Double, DocId>> {
        val nearestFirst = compareBy<Pair<Double, DocId>>({ it.first }, { it.second.raw })
        val best = PriorityQueue(TOP_K, nearestFirst.reversed())
        val search = JsonPathQuery(JsonPath.compile("$.embedding")).withDocumentFilter(DocumentFilter.compile("@.model == 'm-$DIMENSIONS'"))
        store.search(search).use { result ->
            for (hit in result.iterator()) {
                val numbers = hit.readJsonAsString().removeSurrounding("[", "]").split(',')
                val vector = FloatArray(numbers.size) { numbers[it].toFloat() }
                var dot = 0.0
                var squares = 0.0
                for (i in vector.indices) {
                    dot += query[i].toDouble() * vector[i]
                    squares += vector[i].toDouble() * vector[i]
                }
                val candidate = 1 - dot / sqrt(squares) to hit.docId
                if (best.size < TOP_K) {
                    best += candidate
                } else if (nearestFirst.compare(candidate, best.peek()) < 0) {
                    best.poll()
                    best += candidate
                }
            }
        }
        return best.sortedWith(nearestFirst)
    }

    /** Chunk [n] of its source, with the next unit-length Gaussian vector of [random]. */
    private fun chunk(n: Int, random: Random, vector: FloatArray, spelling: Spelling): ByteArray {
        var squares = 0.0
        for (i in vector.indices) {
            vector[i] = random.nextGaussian().toFloat()
            squares += vector[i].toDouble() * vector[i]
        }
        val scale = (1 / sqrt(squares)).toFloat()
        val json = StringBuilder(DIMENSIONS * 24)
        json.append("""{"source":"doc-""").append(n / CHUNKS_PER_SOURCE).append("""","chunk":""").append(n % CHUNKS_PER_SOURCE)
            .append(""","model":"m-$DIMENSIONS","embedding":[""")
        for (i in vector.indices) {
            if (i > 0) json.append(',')
            val value = vector[i] * scale
            json.append(if (spelling == Spelling.FLOAT32) value.toString() else value.toDouble().toString())
        }
        return json.append("]}").toString().toByteArray()
    }

    private enum class Spelling(val label: String) {
        /** `Float.toString`: the shortest text that reads back as the same float32. */
        FLOAT32("float32, shortest"),

        /** `Double.toString` of the value widened to float64, as a serializer that only knows doubles writes it. */
        FLOAT64("float64, up to 17 digits"),
    }

    private class Sizes(val raw: Long, val lane: Long, val index: Long, val insertNanos: Long, val searchNanos: Long)

    private companion object {
        const val DIMENSIONS = 768
        const val CHUNKS_PER_SOURCE = 8
        const val BATCH = 100
        const val SEED = 42L
        const val TOP_K = 10
        const val SEARCHES = 5
    }
}
