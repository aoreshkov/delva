package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.InsertOutcome
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.VectorQuery
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import app.oreshkov.delva.internal.vector.EmbeddingSet
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Recall of vector search on real embeddings: the vectors of an [EmbeddingSet] go into a store, the set's queries are
 * searched, and each result is held against the true nearest vectors, found by comparing every pair in double
 * precision. Prints recall at [TOP_K], the lowest recall of a query, how far that truth agrees with the nearest rows
 * the set itself names, and the latency of the searches, read plainly and through memory mappings.
 *
 * Exact search has to reach a recall of 1, on any set; a search that trades recall for speed is held to its bar here,
 * on vectors of the kind it will meet.
 *
 * Runs under `reportTest`, not `check`, and needs the directory of a set in `-Ddelva.recall.set=<dir>`; skipped without
 * it. `-Ddelva.recall.vectors=<n>` takes only the set's first vectors (all by default), `-Ddelva.recall.queries=<n>`
 * sets how many of its queries are searched (100), and `-Ddelva.recall.metric=<metric>` the metric (cosine).
 */
@OptIn(ExperimentalDelvaApi::class)
class VectorRecallReportTest {
    @Test
    fun `vector recall report`() {
        val dir = System.getProperty("delva.recall.set")?.let { Path.of(it) } ?: return
        val set = EmbeddingSet(dir)
        val vectors = System.getProperty("delva.recall.vectors")?.toInt() ?: set.count
        val queries = (System.getProperty("delva.recall.queries") ?: "100").toInt()
        val metric = VectorMetric.valueOf((System.getProperty("delva.recall.metric") ?: "cosine").uppercase())
        val measured = measureRecall(set, vectors, queries, TOP_K, metric)
        println(
            "\nVector recall: ${dir.fileName}, exact search, top $TOP_K, against every pair compared in double precision; " +
                "block cache ${EngineOptions().blockCacheBytes shr 20} MiB of a ${Runtime.getRuntime().maxMemory() shr 20} MiB heap\n\n" +
                "| Vectors | Dimensions | Metric | Queries | Recall@$TOP_K | Lowest of a query | Agrees with the set's nearest | " +
                "Vector index MiB | Inserts/s | p50 ms | p95 ms | p50 ms, mmap | p95 ms, mmap |\n" + "|---".repeat(13) + "|\n" +
                "| %,d | %d | %s | %d | %.4f | %.2f | %s | %,.0f | %,.0f | %s |".format(
                    vectors, set.dimensions, metric.name.lowercase(), measured.queries, measured.recall, measured.lowestRecall,
                    measured.agreement?.let { "%.4f".format(it) } ?: "", measured.indexBytes / (1 shl 20).toDouble(),
                    vectors / (measured.insertNanos / 1e9), measured.millis.joinToString(" | ") { "%.2f".format(it) },
                ),
        )
        assertEquals(1.0, measured.recall, "exact search finds the nearest vectors")
    }

    private companion object {
        const val TOP_K = 10
    }
}

/**
 * What searching the first [vectors] vectors of [set] for its first [queries] queries finds, against the truth: the
 * mean [recall] of the queries and the [lowestRecall] of one; the [agreement] of the truth with the nearest rows the
 * set names, when all of its vectors are searched and it names some; and [millis], the median and the 95th percentile
 * of a search's latency, read plainly and then through memory mappings.
 */
internal class RecallMeasured(
    val queries: Int,
    val recall: Double,
    val lowestRecall: Double,
    val agreement: Double?,
    val insertNanos: Long,
    val indexBytes: Long,
    val millis: DoubleArray,
)

/**
 * Inserts the first [vectors] vectors of [set] into a new store with a vector field under [metric], one document
 * each, and searches it for the [k] nearest to each of the set's first [queryCount] queries.
 */
@OptIn(ExperimentalDelvaApi::class)
internal fun measureRecall(set: EmbeddingSet, vectors: Int, queryCount: Int, k: Int, metric: VectorMetric): RecallMeasured {
    val queries = set.queries(queryCount)
    val truth = set.nearest(vectors, queries.map { it.vector }, k, metric)
    // The set's own nearest rows are those of all its vectors, so they say nothing about a part of it.
    val agreement = if (vectors == set.count && queries.all { it.closestRows.isNotEmpty() }) {
        queries.indices.map { q ->
            val named = queries[q].closestRows.take(k)
            named.count { it in truth[q].rows } / named.size.toDouble()
        }.average()
    } else {
        null
    }
    val dir = Files.createTempDirectory("delva-vector-recall")
    try {
        val creation = CreationOptions(excludedKeys = setOf("embedding")).withVectorField(VectorField("$.embedding", set.dimensions, metric))
        var insertNanos = 0L
        Engine.open(dir, EngineOptions(creation), JdkFileSystemIo()).use { store ->
            val batch = ArrayList<ByteArray>(BATCH)
            fun insertBatch(firstRow: Int) {
                val start = System.nanoTime()
                val outcomes = store.insertAll(batch.map { InsertSource(it.inputStream()) })
                insertNanos += System.nanoTime() - start
                // A new store numbers its documents from 1 in the order they are inserted, which is how a hit names its row.
                outcomes.forEachIndexed { i, outcome -> assertEquals(firstRow + i + 1L, (outcome as InsertOutcome.Inserted).docId.raw) }
                batch.clear()
            }
            set.forEachVector(vectors) { row, vector ->
                batch += chunk(row, vector)
                if (batch.size == BATCH) insertBatch(row + 1 - BATCH)
            }
            if (batch.isNotEmpty()) insertBatch(vectors - batch.size)
            store.flush()
            while (store.compact() > 0) {
                // Settle the LSM: one vector segment, as a store that has been idle has.
            }
        }
        val indexBytes = Files.list(dir.resolve("index")).use { files -> files.filter { it.fileName.toString().startsWith("vector-") }.mapToLong { Files.size(it) }.sum() }
        // Of the two ways the segments are read, a query's recall is the lower one.
        val recalls = DoubleArray(queries.size) { 1.0 }
        val millis = DoubleArray(4)
        for ((m, mmap) in listOf(false, true).withIndex()) {
            Engine.open(dir, EngineOptions(creation, StoreOptions(mmapReads = mmap)), JdkFileSystemIo(mmapReads = mmap)).use { store ->
                // Warm-up, so that the first queries don't measure the compiler.
                repeat(WARMUPS) { store.search(VectorQuery(queries[it % queries.size].vector, k)).close() }
                val nanos = LongArray(queries.size) { q ->
                    val start = System.nanoTime()
                    val rows = store.search(VectorQuery(queries[q].vector, k)).use { result -> IntArray(result.hits.size) { (result.hits[it].docId.raw - 1).toInt() } }
                    val elapsed = System.nanoTime() - start
                    recalls[q] = minOf(recalls[q], set.recall(rows, truth[q], queries[q].vector, metric))
                    elapsed
                }.sorted()
                millis[2 * m] = nanos[nanos.size / 2] / 1e6
                millis[2 * m + 1] = nanos[minOf(nanos.size - 1, nanos.size * 95 / 100)] / 1e6
            }
        }
        return RecallMeasured(queries.size, recalls.average(), recalls.min(), agreement, insertNanos, indexBytes, millis)
    } finally {
        dir.toFile().deleteRecursively()
    }
}

/** The chunk document of [row]: its number, and its [vector] as the shortest text that reads back as the same float32. */
private fun chunk(row: Int, vector: FloatArray): ByteArray {
    val json = StringBuilder(vector.size * 12 + 32)
    json.append("""{"n":""").append(row).append(""","embedding":[""")
    for (i in vector.indices) {
        if (i > 0) json.append(',')
        json.append(vector[i])
    }
    return json.append("]}").toString().toByteArray()
}

private const val BATCH = 500
private const val WARMUPS = 5
