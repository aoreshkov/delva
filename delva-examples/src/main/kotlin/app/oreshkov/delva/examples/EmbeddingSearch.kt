/*
 * The chunks nearest to a query embedding, found by the store's vector search.
 *
 * Retrieval data is made of chunks: each is a document of its own, with a reference to its source and its embedding,
 * an array of float32 values. The store is created with a vector field at the embedding's path, so it keeps each
 * chunk's vector as 32-bit floats beside the document, and with the embedding's key left out of its index, which would
 * otherwise hold a posting for every one of its numbers. The numbers are written as the shortest text that reads back
 * as the same float32.
 *
 * A vector search compares the query with the vector of every candidate and returns the k nearest by cosine distance.
 * A document filter picks the candidates through the index, here the chunks of one source, so the more selective it
 * is, the fewer vectors are compared; the hits are still the exact k nearest of those chunks.
 *
 *     ./gradlew :delva-examples:runEmbeddingSearch
 *
 * The embeddings are random unit vectors from a seeded generator, so the example needs no model and prints the same
 * hits on every run.
 */
package app.oreshkov.delva.examples

import app.oreshkov.delva.BlockingJsonStore
import app.oreshkov.delva.BlockingReadableJsonStore
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentFilterTemplate
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.InsertOutcome
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.VectorQuery
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** The key of a chunk's embedding, which the chunk store doesn't index. */
const val EMBEDDING_KEY: String = "embedding"

/** A chunk near a query: its document and its cosine distance, from 0 for the same direction to 2 for the opposite. */
data class Neighbor(val docId: DocId, val distance: Float)

/** One chunk of a source text, with its embedding. */
class Chunk(val source: String, val index: Int, val text: String, val embedding: FloatArray)

@OptIn(ExperimentalPathApi::class)
fun main() {
    val dir = Files.createTempDirectory("delva-embedding-search")
    try {
        createChunkStore(dir.resolve("chunks"), DIMENSIONS).use { store ->
            val db = store.blocking()
            val random = Random(SEED)
            val chunks = (0 until SOURCES).flatMap { s ->
                (0 until CHUNKS_PER_SOURCE).map { c -> Chunk("doc-$s", c, "chunk $c of doc-$s", randomUnitVector(random, DIMENSIONS)) }
            }
            insertChunks(db, chunks)
            println("${chunks.size} chunks of $SOURCES sources, $DIMENSIONS dimensions")

            // A query close to one stored chunk, as a question about that chunk's text would be.
            val target = chunks[123]
            val query = nudge(target.embedding, random)
            println("\nNearest $K to a query near '${target.text}', among every chunk:")
            printNeighbors(db, nearestChunks(db, query, K))
            println("\nThe same among the chunks of ${target.source}:")
            printNeighbors(db, nearestChunks(db, query, K, source = target.source))
        }
    } finally {
        dir.deleteRecursively()
    }
}

/**
 * Creates a store in [dir] for chunks whose embeddings have [dimensions] numbers, all from one model: vectors of two
 * models can't be compared, and a store's vector field is fixed when it is created. Every chunk must have an embedding.
 * [EMBEDDING_KEY] is left out of the index: an indexed array holds a posting per element, and nobody searches for one
 * number of an embedding.
 */
@OptIn(ExperimentalDelvaApi::class)
fun createChunkStore(dir: Path, dimensions: Int): JsonStore = JsonStore.create(
    dir,
    CreationOptions(excludedKeys = setOf(EMBEDDING_KEY))
        .withVectorField(VectorField("$.$EMBEDDING_KEY", dimensions, VectorMetric.COSINE, required = true)),
)

/** Inserts [chunks] in one call, and returns their docIds in the same order. */
fun insertChunks(db: BlockingJsonStore, chunks: List<Chunk>): List<DocId> =
    db.insertAll(chunks.map { InsertSource(chunkJson(it).byteInputStream()) }).map { outcome ->
        check(outcome is InsertOutcome.Inserted) { "a chunk wasn't inserted: $outcome" }
        outcome.docId
    }

/**
 * [chunk] as a JSON document. Each number is written as `Float.toString` writes it, the shortest text that reads back
 * as the same float32: a serializer that widens float32 to float64 first writes up to 17 digits instead, and nearly
 * doubles the document.
 */
fun chunkJson(chunk: Chunk): String = buildString {
    append("""{"source":""").append(jsonString(chunk.source))
    append(""","chunk":""").append(chunk.index)
    append(""","text":""").append(jsonString(chunk.text))
    append(""","$EMBEDDING_KEY":[""")
    chunk.embedding.forEachIndexed { i, value ->
        require(value.isFinite()) { "JSON has no number for $value" }
        if (i > 0) append(',')
        append(value.toString())
    }
    append("]}")
}

/**
 * The [k] chunks nearest to [query] by cosine distance, nearest first, and among equally near ones the oldest first,
 * so the same store and query always give the same answer. With a [source], only that source's chunks are candidates.
 *
 * The source's comparison with a literal is an index term, which the search looks up rather than comparing every
 * vector. The name is bound to the filter's placeholder as a literal, so no source name can change what the filter
 * tests.
 */
@OptIn(ExperimentalDelvaApi::class)
fun nearestChunks(db: BlockingReadableJsonStore, query: FloatArray, k: Int, source: String? = null): List<Neighbor> {
    val search = VectorQuery(query, k).withDocumentFilter(source?.let { OF_SOURCE.bind("source" to JsonQueryValue.Str(it)) })
    return db.search(search).use { result -> result.hits.map { Neighbor(it.docId, it.distance) } }
}

/** A random vector of [dimensions] float32 values with length 1, Gaussian in each dimension and so uniform in direction. */
fun randomUnitVector(random: Random, dimensions: Int): FloatArray {
    val vector = DoubleArray(dimensions) { random.nextGaussian() }
    val length = sqrt(vector.sumOf { it * it })
    return FloatArray(dimensions) { (vector[it] / length).toFloat() }
}

private fun printNeighbors(db: BlockingReadableJsonStore, neighbors: List<Neighbor>) {
    for (neighbor in neighbors) {
        // The search read only the vector index; the text is selected from the chunk now.
        val text = db.select(NodeRef(neighbor.docId, 0), TEXT_PATH).use { result -> result.iterator().next().readJsonAsString() }
        println("  %.4f  %s".format(neighbor.distance, text))
    }
}

/** [vector] moved a little in a random direction and returned to unit length. */
private fun nudge(vector: FloatArray, random: Random): FloatArray {
    val noise = randomUnitVector(random, vector.size)
    val moved = DoubleArray(vector.size) { vector[it] + 0.3 * noise[it] }
    val length = sqrt(moved.sumOf { it * it })
    return FloatArray(vector.size) { (moved[it] / length).toFloat() }
}

/** The Box–Muller transform, which kotlin.random lacks: a standard normal value. */
private fun Random.nextGaussian(): Double {
    val u = 1.0 - nextDouble() // (0, 1], so the logarithm is finite
    return sqrt(-2 * ln(u)) * cos(2 * PI * nextDouble())
}

/** [text] as a JSON string literal. */
private fun jsonString(text: String): String = buildString {
    append('"')
    for (c in text) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

/** The filter of [nearestChunks], compiled once. */
@OptIn(ExperimentalDelvaApi::class)
private val OF_SOURCE = DocumentFilterTemplate.compile("@.source == \${source}")

private val TEXT_PATH = JsonPath.compile("$.text")

private const val SEED = 42
private const val SOURCES = 60
private const val CHUNKS_PER_SOURCE = 5
private const val DIMENSIONS = 64
private const val K = 5
