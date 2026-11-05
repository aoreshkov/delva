package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.VectorQuery
import app.oreshkov.delva.VectorSearchResult
import app.oreshkov.delva.internal.ChunkedInputStream
import app.oreshkov.delva.internal.LiveHeapSampler
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Vector search under `heapCappedTest` (256 MB heap), on a real disk with the default options, which size the
 * engine's budgets to the heap:
 * - a million vectors of 256 dimensions, a gigabyte as float32, are inserted and searched, unfiltered and through a
 *   document filter that one document in 100 passes, before and after the store is reopened. The nearest are checked
 *   against an oracle kept while the documents were generated, so neither the test nor the store holds the vectors.
 * - a vector at the end of a 2 GiB document, after everything else in it, is captured as it streams past and found.
 *
 * `-Ddelva.heapCapped.vectors=<n>` changes the number of vectors and `-Ddelva.heapCapped.bytes=<n>` the size of the
 * large document.
 */
@OptIn(ExperimentalDelvaApi::class)
class VectorHeapCappedTest {
    private val vectors = System.getProperty("delva.heapCapped.vectors")?.toInt() ?: 1_000_000
    private val documentBytes = System.getProperty("delva.heapCapped.bytes")?.toLong() ?: (2L shl 30)

    @Test
    fun `a million vectors are searched in a heap smaller than they are`() {
        val dir = Files.createTempDirectory("delva-vector-heap")
        try {
            val field = VectorField("$.v", DIMENSIONS, VectorMetric.EUCLIDEAN)
            val options = EngineOptions(CreationOptions(excludedKeys = setOf("v")).withVectorField(field))
            val random = Random(SEED)
            // Values of three decimals, read the way the engine reads them: from their text, straight to float32.
            fun nextValue(): Pair<String, Float> = (random.nextInt(-999, 1000) / 1000.0).toString().let { it to it.toFloat() }
            val query = FloatArray(DIMENSIONS) { nextValue().second }
            val nearest = Nearest(K)
            val nearestInGroup = Nearest(K)
            LiveHeapSampler(intervalMillis = 1000).use { sampler ->
                Engine.open(dir, options, JdkFileSystemIo()).use { store ->
                    var start = System.nanoTime()
                    val text = StringBuilder()
                    var n = 0
                    while (n < vectors) {
                        val batch = ArrayList<InsertSource>(BATCH)
                        repeat(minOf(BATCH, vectors - n)) {
                            text.setLength(0)
                            text.append("""{"n":""").append(n).append(""","g":""").append(n % 100).append(""","v":[""")
                            var squares = 0.0
                            for (i in 0 until DIMENSIONS) {
                                val (spelled, value) = nextValue()
                                if (i > 0) text.append(',')
                                text.append(spelled)
                                val d = query[i].toDouble() - value
                                squares += d * d
                            }
                            text.append("]}")
                            nearest.offer(sqrt(squares), n)
                            if (n % 100 == GROUP) nearestInGroup.offer(sqrt(squares), n)
                            batch += InsertSource(ByteArrayInputStream(text.toString().toByteArray()))
                            n++
                        }
                        store.insertAll(batch)
                    }
                    println("$vectors vectors of $DIMENSIONS inserted in ${seconds(start)}, peak live heap ${sampler.peak shr 20} MiB")
                    assertTrue(store.indexes.peakMemtableBytes <= options.memtableBudgetBytes + (8L shl 20), "memtables peaked at ${store.indexes.peakMemtableBytes}")
                    start = System.nanoTime()
                    check(store, query, nearest, nearestInGroup)
                    println("searched in ${seconds(start)}, peak live heap ${sampler.peak shr 20} MiB")
                }
                Engine.open(dir, options, JdkFileSystemIo()).use { store ->
                    val start = System.nanoTime()
                    check(store, query, nearest, nearestInGroup)
                    println("reopened and searched in ${seconds(start)}, peak live heap ${sampler.peak shr 20} MiB")
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun check(store: Engine, query: FloatArray, nearest: Nearest, nearestInGroup: Nearest) {
        val unfiltered = VectorQuery(query, k = K)
        assertNearest(nearest, store.search(unfiltered), "unfiltered")
        assertNearest(nearestInGroup, store.search(unfiltered.withDocumentFilter(DocumentFilter.compile("@.g == $GROUP"))), "filtered")
    }

    /** The hits are the oracle's documents in its order, at its distances up to the rounding of a different sum. */
    private fun assertNearest(expected: Nearest, result: VectorSearchResult, what: String) {
        val hits = result.use { r -> r.hits.map { Regex(""""n":(\d+)""").find(it.readJsonAsString())!!.groupValues[1].toInt() to it.distance } }
        assertEquals(expected.ns, hits.map { it.first }, what)
        for ((hit, distance) in hits.zip(expected.distances)) assertTrue(abs(hit.second - distance) <= 1e-5 * distance, "$what: ${hit.second} for $distance")
    }

    @Test
    fun `a vector at the end of a huge document is found`() {
        val dir = Files.createTempDirectory("delva-vector-heap-document")
        try {
            val field = VectorField("$.deep.inner.v", 3, VectorMetric.EUCLIDEAN)
            val options = EngineOptions(CreationOptions(excludedKeys = setOf("pad")).withVectorField(field))
            val pad = "x".repeat(1022)
            val elements = documentBytes / (pad.length + 3)
            val document = sequence {
                yield("""{"pad":[""")
                var e = 0L
                while (e < elements) {
                    val chunk = StringBuilder()
                    for (j in 0 until minOf(64L, elements - e)) {
                        if (e + j > 0) chunk.append(',')
                        chunk.append('"').append(pad).append('"')
                    }
                    e += 64
                    yield(chunk.toString())
                }
                yield("""],"deep":{"inner":{"v":[1.5,-2,0.25]}}}""")
            }
            LiveHeapSampler(intervalMillis = 1000).use { sampler ->
                Engine.open(dir, options, JdkFileSystemIo()).use { store ->
                    val start = System.nanoTime()
                    val small = store.insert("""{"deep":{"inner":{"v":[0,0,0]}}}""").raw
                    val huge = store.insert(ChunkedInputStream(document), emptyMap()).raw
                    println("${documentBytes shr 20} MiB document inserted in ${seconds(start)}, peak live heap ${sampler.peak shr 20} MiB")
                    val hits = store.search(VectorQuery(floatArrayOf(1.5f, -2f, 0.25f), k = 3)).use { r -> r.hits.map { it.docId.raw to it.distance } }
                    assertEquals(listOf(huge to 0f, small to sqrt(1.5 * 1.5 + 4 + 0.0625).toFloat()), hits)
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** The [k] smallest distances offered, with the document numbers they belong to, nearest first. */
    private class Nearest(private val k: Int) {
        val distances = ArrayList<Double>()
        val ns = ArrayList<Int>()

        fun offer(distance: Double, n: Int) {
            if (distances.size == k && distance >= distances.last()) return
            var at = distances.binarySearch(distance).let { if (it < 0) -it - 1 else it }
            // Ties keep the earlier document first, as the engine orders equal distances by docId.
            while (at < distances.size && distances[at] == distance) at++
            distances.add(at, distance)
            ns.add(at, n)
            if (distances.size > k) {
                distances.removeLast()
                ns.removeLast()
            }
        }
    }

    private fun seconds(start: Long) = "%.1f s".format((System.nanoTime() - start) / 1e9)

    private companion object {
        const val DIMENSIONS = 256
        const val K = 10
        const val GROUP = 7
        const val BATCH = 1000
        const val SEED = 17
    }
}
