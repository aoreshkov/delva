package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.VectorQuery
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import java.nio.file.Files
import java.util.Random
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What exact vector search costs: for each number of chunk documents and each number of dimensions, unit-length
 * Gaussian vectors under cosine, written as the shortest text that reads back as the same float32, in a store created
 * with the vector's key excluded from the index. Prints the vector index's size, insert throughput, and the median
 * latency of a top-10 search unfiltered and through a document filter that 1%, 10%, 30% or 50% of the documents pass;
 * then the same searches with segments read through memory mappings. The wide filters show where narrowing the
 * candidates by an index term stops paying for itself against the scan. For the first number of documents it also inserts the
 * same documents into a store without a vector field, which is what the vector field costs an insert.
 *
 * The block cache is the default one for the test JVM's heap. A search keeps a vector index of up to half of it cached;
 * a larger one, as every index of the default grid is, it reads from the operating system's page cache each time. Runs
 * under `reportTest`, not `check`.
 * `-Ddelva.report.vectors=<n,…>` sets the numbers of documents (10000,100000 by default) and
 * `-Ddelva.report.dimensions=<d,…>` the dimensions (384,768,1536), and `-Ddelva.report.vectorBlockBytes=<b,…>` the
 * target sizes of vector segment blocks to compare (the engine's default alone by default).
 */
@OptIn(ExperimentalDelvaApi::class)
class VectorSearchReportTest {
    @Test
    fun `vector search report`() {
        val counts = (System.getProperty("delva.report.vectors") ?: "10000,100000").split(',').map { it.trim().toInt() }
        val dimensionsList = (System.getProperty("delva.report.dimensions") ?: "384,768,1536").split(',').map { it.trim().toInt() }
        val blockSizes = System.getProperty("delva.report.vectorBlockBytes")?.split(',')?.map { it.trim().toInt() }
            ?: listOf(EngineOptions().vectorSegmentBlockBytes)
        val rows = ArrayList<String>()
        for (dimensions in dimensionsList) {
            for ((c, count) in counts.withIndex()) {
                for ((b, blockBytes) in blockSizes.withIndex()) {
                    val withVectors = measure(count, dimensions, blockBytes, vectorField = true)
                    val without = if (c == 0 && b == 0) measure(count, dimensions, blockBytes, vectorField = false) else null
                    rows += "| %,d | %d | %d | %,.0f | %,.0f | %s | %s |".format(
                        count, dimensions, blockBytes shr 10, withVectors.indexBytes / (1 shl 20).toDouble(),
                        count / (withVectors.insertNanos / 1e9), without?.let { "%,.0f".format(count / (it.insertNanos / 1e9)) } ?: "",
                        withVectors.millis.joinToString(" | ") { "%.2f".format(it) },
                    )
                }
            }
        }
        println(
            "\nVector search: unit-length Gaussian vectors under COSINE, top $TOP_K, median of $SEARCHES searches; " +
                "block cache ${EngineOptions().blockCacheBytes shr 20} MiB of a ${Runtime.getRuntime().maxMemory() shr 20} MiB heap\n\n" +
                "| Documents | Dimensions | Vector block KiB | Vector index MiB | Inserts/s | Inserts/s without a vector field | " +
                listOf("", ", mmap").joinToString(" | ") { m -> "Top $TOP_K ms$m | " + FILTERS.joinToString(" | ") { "${it.first}% filter ms$m" } } +
                " |\n" + "|---".repeat(6 + 2 * (1 + FILTERS.size)) + "|\n" + rows.joinToString("\n"),
        )
    }

    private fun measure(count: Int, dimensions: Int, blockBytes: Int, vectorField: Boolean): Measured {
        val dir = Files.createTempDirectory("delva-vector-report")
        try {
            val field = VectorField("$.embedding", dimensions, VectorMetric.COSINE)
            val creation = CreationOptions(excludedKeys = setOf("embedding")).withVectorField(if (vectorField) field else null)
            val random = Random(SEED)
            var insertNanos = 0L
            Engine.open(dir, EngineOptions(creation, vectorSegmentBlockBytes = blockBytes), JdkFileSystemIo()).use { store ->
                var next = 0
                while (next < count) {
                    val batch = List(minOf(BATCH, count - next)) { i -> chunk(next + i, unitGaussian(random, dimensions)) }
                    val start = System.nanoTime()
                    store.insertAll(batch.map { InsertSource(it.inputStream()) })
                    insertNanos += System.nanoTime() - start
                    next += batch.size
                }
                store.flush()
                while (store.compact() > 0) {
                    // Settle the LSM: one vector segment, as a store that has been idle has.
                }
            }
            if (!vectorField) return Measured(insertNanos, 0, DoubleArray(0))
            val indexBytes = Files.list(dir.resolve("index")).use { files -> files.filter { it.fileName.toString().startsWith("vector-") }.mapToLong { Files.size(it) }.sum() }
            val query = VectorQuery(unitGaussian(random, dimensions), k = TOP_K)
            val queries = listOf(query) + FILTERS.map { query.withDocumentFilter(DocumentFilter.compile(it.second)) }
            val millis = DoubleArray(2 * queries.size)
            for ((m, mmap) in listOf(false, true).withIndex()) {
                Engine.open(dir, EngineOptions(creation, StoreOptions(mmapReads = mmap)), JdkFileSystemIo(mmapReads = mmap)).use { store ->
                    for ((q, vq) in queries.withIndex()) {
                        // Warm-up, so that the first rows don't measure the compiler.
                        repeat(WARMUPS) { store.search(vq).use { assertEquals(TOP_K, it.hits.size) } }
                        millis[queries.size * m + q] = LongArray(SEARCHES) {
                            val start = System.nanoTime()
                            store.search(vq).use { r -> r.hits.size }
                            System.nanoTime() - start
                        }.sorted()[SEARCHES / 2] / 1e6
                    }
                }
            }
            return Measured(insertNanos, indexBytes, millis)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun unitGaussian(random: Random, dimensions: Int): FloatArray {
        val v = FloatArray(dimensions) { random.nextGaussian().toFloat() }
        var squares = 0.0
        for (x in v) squares += x.toDouble() * x
        val scale = (1 / sqrt(squares)).toFloat()
        for (i in v.indices) v[i] *= scale
        return v
    }

    /** Chunk [n]: the members [FILTERS] narrow by, and its [vector] as the shortest float32 text. */
    private fun chunk(n: Int, vector: FloatArray): ByteArray {
        val json = StringBuilder(vector.size * 12 + 64)
        json.append("""{"n":""").append(n).append(""","hundredth":""").append(n % 100).append(""","tenth":""").append(n % 10)
            .append(""","thirty":""").append(if (n % 10 < 3) 1 else 0).append(""","half":""").append(n % 2)
            .append(""","embedding":[""")
        for (i in vector.indices) {
            if (i > 0) json.append(',')
            json.append(vector[i])
        }
        return json.append("]}").toString().toByteArray()
    }

    private class Measured(val insertNanos: Long, val indexBytes: Long, val millis: DoubleArray)

    private companion object {
        const val TOP_K = 10
        const val BATCH = 500
        const val SEARCHES = 7
        const val WARMUPS = 5
        const val SEED = 42L

        /** The percentage of the documents each filter passes, and the filter; each narrows by one index term. */
        val FILTERS = listOf(1 to "@.hundredth == 7", 10 to "@.tenth == 3", 30 to "@.thirty == 1", 50 to "@.half == 1")
    }
}
