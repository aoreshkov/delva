package app.oreshkov.delva.internal.vector

import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.VectorMetric
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A set of embeddings on disk, in the layout public vector search benchmark sets are shared in: `vectors.npy`, a NumPy
 * array of little-endian float32 rows, each row a vector; and `tests.jsonl`, a query on each line, as a JSON object whose
 * `query` is the query vector and whose `closest_ids` are the rows of the whole set nearest to it, nearest first.
 *
 * Recall measured on generated vectors says little about real ones, which crowd into a few directions, so what a
 * search may lose is measured on a set like this. The vectors are read from the file as they are needed, never all
 * at once: a set is larger than a test's heap.
 */
internal class EmbeddingSet(dir: Path) {
    private val vectorsFile: Path = dir.resolve(VECTORS)
    private val queriesFile: Path = dir.resolve(QUERIES)

    /** How many vectors the set has. */
    val count: Int

    /** How many numbers each vector has. */
    val dimensions: Int

    private val dataOffset: Long

    init {
        FileChannel.open(vectorsFile, StandardOpenOption.READ).use { channel ->
            val start = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(start, 0)
            start.flip()
            require(start.remaining() == 12 && (0 until MAGIC.size).all { start.get(it) == MAGIC[it] }) { "$vectorsFile is not a NumPy array file" }
            // Version 1 counts its header's length in two bytes, the later versions in four.
            val major = start.get(6).toInt()
            val (lengthBytes, headerLength) = if (major == 1) 2 to (start.getShort(8).toInt() and 0xFFFF) else 4 to start.getInt(8)
            val header = ByteBuffer.allocate(headerLength)
            channel.read(header, 8L + lengthBytes)
            val text = String(header.array(), Charsets.ISO_8859_1)
            require("'descr': '<f4'" in text && "'fortran_order': False" in text) { "$vectorsFile is not rows of little-endian float32: $text" }
            val shape = requireNotNull(SHAPE.find(text)) { "$vectorsFile is not an array of two dimensions: $text" }
            count = shape.groupValues[1].toInt()
            dimensions = shape.groupValues[2].toInt()
            dataOffset = 8L + lengthBytes + headerLength
            require(channel.size() == dataOffset + 4L * count * dimensions) { "$vectorsFile is cut short" }
        }
    }

    /** Calls [action] with the row and the vector of each of the first [limit] vectors, in order; the array is reused. */
    fun forEachVector(limit: Int = count, action: (row: Int, vector: FloatArray) -> Unit) {
        require(limit in 0..count) { "the set has $count vectors, not $limit" }
        val vector = FloatArray(dimensions)
        val buffer = ByteBuffer.allocate(4 * dimensions * ROWS_PER_READ).order(ByteOrder.LITTLE_ENDIAN)
        FileChannel.open(vectorsFile, StandardOpenOption.READ).use { channel ->
            var row = 0
            while (row < limit) {
                val rows = minOf(ROWS_PER_READ, limit - row)
                buffer.clear().limit(4 * dimensions * rows)
                var position = dataOffset + 4L * dimensions * row
                while (buffer.hasRemaining()) position += channel.read(buffer, position)
                val floats = buffer.flip().asFloatBuffer()
                repeat(rows) {
                    floats.get(vector)
                    action(row++, vector)
                }
            }
        }
    }

    /** The vector of [row]. */
    fun vector(row: Int): FloatArray {
        require(row in 0 until count) { "the set has no row $row" }
        val buffer = ByteBuffer.allocate(4 * dimensions).order(ByteOrder.LITTLE_ENDIAN)
        FileChannel.open(vectorsFile, StandardOpenOption.READ).use { channel ->
            var position = dataOffset + 4L * dimensions * row
            while (buffer.hasRemaining()) position += channel.read(buffer, position)
        }
        return FloatArray(dimensions).also { buffer.flip().asFloatBuffer().get(it) }
    }

    /** The first [limit] queries of the set. */
    fun queries(limit: Int): List<Query> {
        val queries = ArrayList<Query>()
        Files.newBufferedReader(queriesFile).use { reader ->
            while (queries.size < limit) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val vector = numbers(line, "query").let { text -> FloatArray(text.size) { text[it].toFloat() } }
                require(vector.size == dimensions) { "query ${queries.size} of $queriesFile has ${vector.size} numbers, not $dimensions" }
                queries += Query(vector, numbers(line, "closest_ids").let { text -> IntArray(text.size) { text[it].toInt() } })
            }
        }
        return queries
    }

    /** A query of the set: its [vector], and the rows the set itself names as the nearest in the whole set, nearest first. */
    class Query(val vector: FloatArray, val closestRows: IntArray)

    /**
     * The [k] of the first [count] vectors nearest to each of [queries] under [metric], nearest first and vectors of
     * equal distance by row: what a search of those vectors has to find. Every pair is compared, in double precision,
     * with no code of the engine's, so this is the truth the engine's results are measured against.
     */
    @OptIn(ExperimentalDelvaApi::class)
    fun nearest(count: Int, queries: List<FloatArray>, k: Int, metric: VectorMetric): List<Neighbors> {
        val found = queries.map { Nearest(k) }
        val querySquares = DoubleArray(queries.size) { squares(queries[it]) }
        forEachVector(count) { row, vector ->
            val vectorSquares = squares(vector)
            // Each query keeps its own nearest, so the queries share nothing but the vector they read.
            IntStream.range(0, queries.size).parallel().forEach { q ->
                found[q].offer(row, distance(metric, queries[q], querySquares[q], vector, vectorSquares))
            }
        }
        return found.map { Neighbors(it.rows.copyOf(it.size), it.distances.copyOf(it.size)) }
    }

    /**
     * The share of [truth]'s neighbors that [foundRows], a search's hits for [query], are as good as: a hit counts when
     * its vector is as near as the farthest of the true neighbors is, give or take the rounding of a float32 distance.
     * Vectors of equal distance make more than one right answer, and a search may return any of them.
     */
    @OptIn(ExperimentalDelvaApi::class)
    fun recall(foundRows: IntArray, truth: Neighbors, query: FloatArray, metric: VectorMetric): Double {
        if (truth.rows.isEmpty()) return 1.0
        val farthest = truth.distances.last()
        val bound = farthest + max(abs(farthest), 1.0) * FLOAT_ROUNDING
        val querySquares = squares(query)
        val right = foundRows.distinct().count { row ->
            row in truth.rows || vector(row).let { distance(metric, query, querySquares, it, squares(it)) } <= bound
        }
        return minOf(right, truth.rows.size) / truth.rows.size.toDouble()
    }

    /** The nearest vectors to one query: their [rows], nearest first, and the [distances] of each. */
    class Neighbors(val rows: IntArray, val distances: DoubleArray)

    /** The [k] nearest of the rows offered in ascending order, kept sorted: an equal distance never displaces an earlier row. */
    private class Nearest(k: Int) {
        val rows = IntArray(k)
        val distances = DoubleArray(k)
        var size = 0

        fun offer(row: Int, distance: Double) {
            if (size == rows.size && distance >= distances[size - 1]) return
            var i = if (size < rows.size) size++ else size - 1
            while (i > 0 && distances[i - 1] > distance) {
                rows[i] = rows[i - 1]
                distances[i] = distances[i - 1]
                i--
            }
            rows[i] = row
            distances[i] = distance
        }
    }

    companion object {
        private const val VECTORS = "vectors.npy"
        private const val QUERIES = "tests.jsonl"
        private val MAGIC = byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte())
        private val SHAPE = Regex("""'shape': \((\d+), (\d+)\)""")
        private const val ROWS_PER_READ = 64

        /** What rounding a distance to float32 can move it by, relative to its size or to 1, generously. */
        private const val FLOAT_ROUNDING = 1e-6

        /**
         * Writes [vectors] and [queries] to [dir] as a set this class reads, each query with the rows [closestRows]
         * gives it: for tests, and for sets that are generated.
         */
        fun write(dir: Path, vectors: List<FloatArray>, queries: List<FloatArray>, closestRows: (Int) -> IntArray = { IntArray(0) }) {
            val dimensions = vectors.first().size
            var header = "{'descr': '<f4', 'fortran_order': False, 'shape': (${vectors.size}, $dimensions), }"
            // The data starts at a multiple of 64 bytes, and the header ends with a newline.
            header = header.padEnd((10 + header.length + 1 + 63) / 64 * 64 - 11) + "\n"
            val bytes = ByteBuffer.allocate(10 + header.length + 4 * dimensions * vectors.size).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put(MAGIC).put(1.toByte()).put(0.toByte()).putShort(header.length.toShort()).put(header.toByteArray(Charsets.ISO_8859_1))
            for (vector in vectors) for (x in vector) bytes.putFloat(x)
            Files.write(dir.resolve(VECTORS), bytes.array())
            Files.write(
                dir.resolve(QUERIES),
                queries.mapIndexed { q, query ->
                    """{"query": [${query.joinToString(", ")}], "conditions": null, "closest_ids": [${closestRows(q).joinToString(", ")}]}"""
                },
            )
        }

        /** The elements of the array that is the member [name] of the JSON object on [line], as their text. */
        private fun numbers(line: String, name: String): List<String> {
            val member = line.indexOf("\"$name\"")
            require(member >= 0) { "no $name in ${line.take(80)}" }
            val open = line.indexOf('[', member)
            val close = line.indexOf(']', open)
            require(open >= 0 && close > open) { "$name is not an array in ${line.take(80)}" }
            val elements = line.substring(open + 1, close)
            return if (elements.isBlank()) emptyList() else elements.split(',').map { it.trim() }
        }

        private fun squares(vector: FloatArray): Double {
            var sum = 0.0
            for (x in vector) sum += x.toDouble() * x
            return sum
        }

        /** The distance of SPEC.md §11.3 between [query] and [vector], whose sums of squares are given, in double precision. */
        @OptIn(ExperimentalDelvaApi::class)
        private fun distance(metric: VectorMetric, query: FloatArray, querySquares: Double, vector: FloatArray, vectorSquares: Double): Double {
            if (metric == VectorMetric.EUCLIDEAN) {
                var sum = 0.0
                for (i in query.indices) {
                    val difference = query[i].toDouble() - vector[i]
                    sum += difference * difference
                }
                return sqrt(sum)
            }
            var dot = 0.0
            for (i in query.indices) dot += query[i].toDouble() * vector[i]
            return if (metric == VectorMetric.COSINE) 1 - dot / sqrt(querySquares * vectorSquares) else -dot
        }
    }
}
