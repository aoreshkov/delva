package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentFilter
import app.oreshkov.delva.QueryRejectedException
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.VectorQuery
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Vector search against a brute-force oracle (SPEC.md §11.3): random stores of inserts, deletes, replaces and upserts,
 * with flushes and compactions at random points and small memtables, searched on the writer and on a secondary with
 * random query vectors, `k`, `maxDistance` and document filters with and without index terms.
 *
 * The oracle computes each distance in double precision term by term and rounds it to a float, so it agrees with the
 * engine up to the order of the sums: membership is checked with a tolerance at the `k`-th distance and at
 * `maxDistance`, and exact order only among the engine's own distances. Ties are generated on purpose (duplicate
 * vectors), and must be ordered by docId; vectors of tiny, huge and mixed magnitudes check that nothing overflows into
 * NaN. Every search runs twice and must give the same hits.
 */
class VectorSearchPropertyTest {
    private enum class Shape { GAUSSIAN, CLUSTERED, DUPLICATES, TINY, HUGE, MIXED, SPARSE }

    private class Doc(val n: Int, val key: String?, val vector: FloatArray?) {
        val g: Int get() = n % 5
        val tag: String get() = if (n % 2 == 0) "a" else "b"

        fun json(): String = buildString {
            append('{')
            if (key != null) append("\"key\":\"").append(key).append("\",")
            append("\"g\":").append(g).append(",\"tag\":\"").append(tag).append("\",\"n\":").append(n)
            vector?.let { append(",\"v\":[").append(it.joinToString(",")).append(']') }
            append('}')
        }
    }

    /** A document filter, whether its terms narrow the candidates, and what it means for a [Doc]. */
    private class Filter(val text: String, val hasTerms: Boolean, val accepts: (Doc) -> Boolean) {
        val compiled: DocumentFilter = DocumentFilter.compile(text)

        override fun toString(): String = text
    }

    private val filters = listOf(
        Filter("@.g == 2", true) { it.g == 2 },
        Filter("@.g == 1 || @.tag == 'b'", true) { it.g == 1 || it.tag == "b" },
        Filter("@.tag == 'a' && @.n >= 20", true) { it.tag == "a" && it.n >= 20 },
        Filter("@.n < 15", true) { it.n < 15 },
        Filter("@.g == 3 && !(@.tag == 'a')", true) { it.g == 3 && it.tag != "a" },
        Filter("@.missing == 1", true) { false },
        Filter("!(@.g == 2)", false) { it.g != 2 },
        Filter("@.g != 0", false) { it.g != 0 },
    )

    private class Scenario(val seed: Long, val size: Int) {
        override fun toString(): String = "Scenario(seed=$seed, size=$size)"
    }

    private class Stats {
        var searches = 0
        var hits = 0L
        var ties = 0
        var infinite = 0
        var withSegments = 0
        var rangesTooWide = 0
    }

    @Test
    fun `vector searches return a valid top k of the visible documents the filter accepts`() {
        val stats = Stats()
        forAll("vector search", Gen { random, size -> Scenario(random.nextLong(), size) }, iterations = 100, maxSize = 120) { scenario(it, stats) }
        println("vector search property: ${stats.searches} searches, ${stats.hits} hits, ${stats.ties} tied neighbours, ${stats.infinite} infinite distances, ${stats.withSegments} stores with vector segments, ${stats.rangesTooWide} ranges too wide")
        assertTrue(stats.ties > 0 && stats.withSegments > 0, "the cases cover ties and segments")
    }

    private fun scenario(scenario: Scenario, stats: Stats) {
        val random = Random(scenario.seed)
        val metric = VectorMetric.entries[random.nextInt(3)]
        val dimensions = listOf(1, 3, 16, 64)[random.nextInt(4)]
        val shape = Shape.entries[random.nextInt(Shape.entries.size)]
        val keyed = random.nextBoolean()
        val creation = CreationOptions(fragmentBytes = listOf(64, 1024)[random.nextInt(2)], documentKey = if (keyed) "$.key" else null)
            .withVectorField(VectorField("$.v", dimensions, metric))
        val options = EngineOptions(
            creation, StoreOptions(encoderBudgetBytes = 2048), sorterBytes = 4096, keyArenaBytes = 1024, keySeed = scenario.seed,
            log = TestEngines.LOG, backgroundFlush = false,
            memtableBytes = listOf(0L, 4L shl 10, EngineOptions.DEFAULT_MEMTABLE_BYTES)[random.nextInt(3)],
            segmentBlockBytes = listOf(256, 4096)[random.nextInt(2)],
            compaction = CompactionOptions(enabled = false, bytesPerSecond = 0, minMergeSegments = 2, deletedRatio = 0.01),
            maxRangeDocuments = listOf(4, Int.MAX_VALUE)[random.nextInt(2)],
        )
        val vectors = Vectors(random, shape, dimensions, metric)
        val visible = LinkedHashMap<Long, Doc>()
        var next = 0
        fun newDoc(key: String?): Doc = Doc(next++, key, if (random.nextInt(4) == 0) null else vectors.next())
        val fs = SimulatedFileSystemIo()
        TestEngines.open(options, fs).use { writer ->
            var secondary: SecondaryEngine? = null
            try {
                val steps = 4 + scenario.size * 2
                val secondaryAt = random.nextInt(steps)
                for (step in 0 until steps) {
                    if (step == secondaryAt) secondary = SecondaryFixtures.secondary(fs, options)
                    val choice = random.nextInt(20)
                    when {
                        choice < 10 || visible.isEmpty() -> {
                            val doc = newDoc(if (keyed) "k$next" else null)
                            visible[writer.insert(doc.json()).raw] = doc
                        }
                        choice < 13 -> {
                            val old = visible.keys.elementAt(random.nextInt(visible.size))
                            assertTrue(writer.delete(DocId(old)))
                            visible.remove(old)
                        }
                        choice < 16 -> {
                            val old = visible.keys.elementAt(random.nextInt(visible.size))
                            val doc = newDoc(if (keyed) "r$next" else null)
                            val docId = writer.replace(listOf(DocId(old)), doc.json()).raw
                            visible.remove(old)
                            visible[docId] = doc
                        }
                        choice < 18 && keyed -> {
                            // Half the upserts replace a visible document by its key, half insert a new one.
                            val existing = if (random.nextBoolean()) visible.entries.elementAt(random.nextInt(visible.size)) else null
                            val doc = newDoc(existing?.value?.key ?: "u$next")
                            val docId = writer.upsert(doc.json()).raw
                            existing?.let { visible.remove(it.key) }
                            visible[docId] = doc
                        }
                        choice < 19 -> writer.flush()
                        else -> {
                            writer.flush()
                            writer.compact()
                        }
                    }
                }
                if (writer.indexes.versions.current().vectorSegments.isNotEmpty()) stats.withSegments++
                val reader = secondary?.also { it.refresh() }
                for (i in 0 until 6) {
                    val query = query(random, vectors, visible, metric)
                    try {
                        check(writer, query, visible, metric, stats)
                    } catch (e: QueryRejectedException) {
                        // A range over more documents than the writer collects, which the secondary's defaults allow.
                        assertEquals(QueryRejectedException.Reason.RANGE_TOO_WIDE, e.reason, "${query.first}")
                        assertTrue(query.first.scan == ScanPolicy.REJECT && options.maxRangeDocuments < Int.MAX_VALUE && query.second!!.text.contains("@.n "))
                        stats.rangesTooWide++
                        continue
                    }
                    if (reader != null) {
                        val a = hitsOf(writer, query.first)
                        val b = hitsOf(reader, query.first)
                        assertEquals(a, b, "the secondary's hits for ${query.first}")
                    }
                }
                val scan = filters.first { !it.hasTerms }
                assertFailsWith<QueryRejectedException> { writer.search(VectorQuery(vectors.next()).withDocumentFilter(scan.compiled)) }
            } finally {
                secondary?.close()
            }
        }
    }

    /** A random query and the filter it carries, if any. */
    private fun query(random: Random, vectors: Vectors, visible: Map<Long, Doc>, metric: VectorMetric): Pair<VectorQuery, Filter?> {
        val stored = visible.values.mapNotNull { it.vector }
        val vector = when {
            stored.isEmpty() || random.nextInt(10) < 5 -> vectors.next()
            random.nextInt(10) < 6 -> stored[random.nextInt(stored.size)].copyOf()
            else -> FloatArray(stored[0].size) { -stored[random.nextInt(stored.size)][it] }.also { if (it.all { v -> v == 0f }) it[0] = 1f }
        }
        val k = when (random.nextInt(4)) {
            0 -> 1
            1 -> visible.size + 2
            else -> random.nextInt(1, maxOf(2, visible.size))
        }
        val maxDistance = if (stored.isEmpty() || random.nextInt(10) < 6) null else oracle(metric, vector, stored[random.nextInt(stored.size)])
        val filter = if (random.nextInt(3) == 0) null else filters[random.nextInt(filters.size)]
        val scan = if (filter != null && !filter.hasTerms || random.nextBoolean()) ScanPolicy.ALLOW_SCAN else ScanPolicy.REJECT
        return VectorQuery(vector, k, maxDistance, scan).withDocumentFilter(filter?.compiled) to filter
    }

    private data class Hit(val docId: Long, val distanceBits: Int, val json: String)

    private fun hitsOf(store: ReadEngine, query: VectorQuery): List<Hit> = store.search(query).use { r ->
        r.hits.map { Hit(it.docId.raw, it.distance.toRawBits(), it.readJsonAsString()) }
    }

    private fun check(store: ReadEngine, q: Pair<VectorQuery, Filter?>, visible: Map<Long, Doc>, metric: VectorMetric, stats: Stats) {
        val (query, filter) = q
        val label = "$query in a store of ${visible.size} documents"
        val hits = hitsOf(store, query)
        assertEquals(hits, hitsOf(store, query), "$label: the same search twice")
        stats.searches++
        stats.hits += hits.size

        val candidates = visible.filter { (_, doc) -> doc.vector != null && (filter == null || filter.accepts(doc)) }
        val distances = candidates.mapValues { (_, doc) -> oracle(metric, query.queryValues, doc.vector!!) }
        val tolerance = candidates.mapValues { (docId, doc) -> tolerance(metric, query.queryValues, doc.vector!!, distances.getValue(docId)) }
        val max = query.maxDistance

        // Every hit is a distinct candidate, at the oracle's distance, within maxDistance, in (distance, docId) order.
        assertEquals(hits.size, hits.map { it.docId }.distinct().size, "$label: distinct hits")
        for ((i, hit) in hits.withIndex()) {
            val doc = visible[hit.docId] ?: throw AssertionError("$label: hit ${hit.docId} isn't visible")
            assertEquals(doc.json(), hit.json, "$label: hit ${hit.docId}'s document")
            assertTrue(hit.docId in candidates, "$label: hit ${hit.docId} isn't a candidate")
            val d = Float.fromBits(hit.distanceBits)
            assertTrue(!d.isNaN() && d.toRawBits() != (-0f).toRawBits(), "$label: distance $d")
            if (d.isInfinite()) stats.infinite++
            assertTrue(close(d, distances.getValue(hit.docId), tolerance.getValue(hit.docId)), "$label: hit ${hit.docId} at $d, the oracle says ${distances[hit.docId]}")
            if (max != null) assertTrue(d <= max, "$label: hit ${hit.docId} at $d is past maxDistance")
            if (i > 0) {
                val p = Float.fromBits(hits[i - 1].distanceBits)
                assertTrue(p < d || (p == d && hits[i - 1].docId < hit.docId), "$label: hits $i-1 and $i out of order")
                if (p == d) stats.ties++
            }
        }

        // As many hits as there are candidates within maxDistance, up to k; those within its tolerance may go either way.
        val surely = candidates.keys.count { max == null || distances.getValue(it) <= max - tolerance.getValue(it) }
        val maybe = candidates.keys.count { max == null || distances.getValue(it) <= max + tolerance.getValue(it) }
        assertTrue(hits.size in minOf(query.k, surely)..minOf(query.k, maybe), "$label: ${hits.size} hits, $surely to $maybe candidates within maxDistance")

        // No candidate left out is nearer than a hit, and one at the same distance as a hit (a duplicate vector) comes
        // after it by docId.
        val hitIds = hits.mapTo(HashSet()) { it.docId }
        val farthest = hits.lastOrNull()?.let { distances.getValue(it.docId) to tolerance.getValue(it.docId) }
        for ((docId, doc) in candidates) {
            if (docId in hitIds) continue
            val d = distances.getValue(docId)
            if (max != null && d > max - tolerance.getValue(docId)) continue
            assertTrue(hits.size == query.k, "$label: candidate $docId at $d left out of ${hits.size} hits")
            val (f, t) = farthest!!
            assertTrue(d >= f || close(d, f, t + tolerance.getValue(docId)), "$label: candidate $docId at $d left out, nearer than a hit at $f")
            for (hit in hits) {
                if (visible.getValue(hit.docId).vector!!.contentEquals(doc.vector!!)) assertTrue(hit.docId < docId, "$label: tie of $docId with hit ${hit.docId}")
            }
        }
    }

    private fun close(a: Float, b: Float, tolerance: Double): Boolean = a == b || (a.isFinite() && b.isFinite() && abs(a.toDouble() - b) <= tolerance)

    /** The distance from [q] to [v] as SPEC.md §11.3 defines it, in double precision term by term, rounded once. */
    private fun oracle(metric: VectorMetric, q: FloatArray, v: FloatArray): Float {
        var dot = 0.0
        var qq = 0.0
        var vv = 0.0
        var diff = 0.0
        for (i in q.indices) {
            dot += q[i].toDouble() * v[i].toDouble()
            qq += q[i].toDouble() * q[i].toDouble()
            vv += v[i].toDouble() * v[i].toDouble()
            diff += (q[i].toDouble() - v[i].toDouble()).pow(2)
        }
        val d = when (metric) {
            VectorMetric.COSINE -> (1 - dot / (sqrt(qq) * sqrt(vv))).coerceIn(0.0, 2.0).toFloat()
            VectorMetric.DOT_PRODUCT -> (-dot).toFloat()
            VectorMetric.EUCLIDEAN -> sqrt(diff).toFloat()
        }
        return if (d == 0f) 0f else d
    }

    /**
     * How far the engine's distance may be from the oracle's: a few float steps, and for a dot product what summing
     * products of [q] and [v] in another order can cancel differently.
     */
    private fun tolerance(metric: VectorMetric, q: FloatArray, v: FloatArray, d: Float): Double {
        val steps = 1e-6 * (1 + abs(d.toDouble()))
        if (metric != VectorMetric.DOT_PRODUCT) return steps
        var magnitude = 0.0
        for (i in q.indices) magnitude += abs(q[i].toDouble() * v[i])
        return steps + 1e-12 * magnitude
    }

    /** Vectors of one [shape]; never zero, so every metric takes them. */
    private class Vectors(private val random: Random, private val shape: Shape, private val dimensions: Int, private val metric: VectorMetric) {
        private val centers = List(3) { gaussian(1.0) }
        private val pool = List(4) { gaussian(1.0) }

        private fun gaussian(scale: Double): FloatArray = FloatArray(dimensions) {
            // Box–Muller: the standard library's Random has no Gaussian.
            (sqrt(-2 * ln(1 - random.nextDouble())) * cos(2 * Math.PI * random.nextDouble()) * scale).toFloat()
        }

        fun next(): FloatArray {
            val v = when (shape) {
                Shape.GAUSSIAN -> gaussian(1.0)
                Shape.CLUSTERED -> centers[random.nextInt(centers.size)].let { c -> FloatArray(dimensions) { c[it] + random.nextFloat() * 0.01f } }
                Shape.DUPLICATES -> pool[random.nextInt(pool.size)].copyOf()
                Shape.TINY -> gaussian(1e-40)
                Shape.HUGE -> gaussian(1e18)
                Shape.MIXED -> gaussian(10.0.pow(random.nextInt(-20, 21)))
                Shape.SPARSE -> FloatArray(dimensions) { if (random.nextInt(4) == 0) random.nextInt(-2, 3).toFloat() else 0f }
            }
            if (v.all { it == 0f }) v[random.nextInt(dimensions)] = if (shape == Shape.TINY) Float.MIN_VALUE else 1f
            check(metric != VectorMetric.COSINE || v.any { it != 0f })
            return v
        }
    }
}
