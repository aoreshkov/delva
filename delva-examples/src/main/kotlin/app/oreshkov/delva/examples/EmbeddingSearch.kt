/*
 * The chunks nearest to a query embedding, searched in the program's own code.
 *
 * Retrieval data is made of chunks: each is a document of its own, with a reference to its source, the name of the
 * model that embedded it, and its embedding, an array of float32 values. The store leaves the embedding's key out of
 * its index, which would otherwise hold a posting for every one of its numbers, and the numbers are written as the
 * shortest text that reads back as the same float32.
 *
 * A search for the embedding's path returns the embedding of every candidate chunk, and the program keeps the k
 * nearest by cosine distance. A document filter picks the candidates through the index, here the chunks of one model
 * or of one source of that model, so the more selective it is, the fewer chunks are read. Each candidate's embedding
 * is read and parsed from JSON, which is fine for a few thousand chunks and slow well beyond that.
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
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.NodeRef
import java.nio.file.Files
import java.nio.file.Path
import java.util.PriorityQueue
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
data class Neighbor(val docId: DocId, val distance: Double)

/** One chunk of a source text, with the embedding [model] made of it. */
class Chunk(val source: String, val index: Int, val model: String, val text: String, val embedding: FloatArray)

@OptIn(ExperimentalPathApi::class)
fun main() {
    val dir = Files.createTempDirectory("delva-embedding-search")
    try {
        createChunkStore(dir.resolve("chunks")).use { store ->
            val db = store.blocking()
            val random = Random(SEED)
            val chunks = (0 until SOURCES).flatMap { s ->
                (0 until CHUNKS_PER_SOURCE).map { c -> Chunk("doc-$s", c, MODEL, "chunk $c of doc-$s", randomUnitVector(random, DIMENSIONS)) }
            }
            insertChunks(db, chunks)
            println("${chunks.size} chunks of $SOURCES sources, $DIMENSIONS dimensions")

            // A query close to one stored chunk, as a question about that chunk's text would be.
            val target = chunks[123]
            val query = nudge(target.embedding, random)
            println("\nNearest $K to a query near '${target.text}', among every chunk of model $MODEL:")
            printNeighbors(db, nearestChunks(db, query, K, model = MODEL))
            println("\nThe same among the chunks of ${target.source}:")
            printNeighbors(db, nearestChunks(db, query, K, model = MODEL, source = target.source))
        }
    } finally {
        dir.deleteRecursively()
    }
}

/**
 * Creates a store for chunk documents in [dir]. [EMBEDDING_KEY] is left out of the index: an indexed array holds a
 * posting per element, and nobody searches for one number of an embedding. The option is fixed when the store is
 * created and applies to that key at any depth.
 */
fun createChunkStore(dir: Path): JsonStore = JsonStore.create(dir, CreationOptions(excludedKeys = setOf(EMBEDDING_KEY)))

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
    append(""","model":""").append(jsonString(chunk.model))
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
 * The [k] chunks of [model] nearest to [query] by cosine distance, nearest first, and among equally near ones the
 * oldest first, so the same store and query always give the same answer. With a [source], only that source's chunks
 * are candidates.
 *
 * Vectors from different models, or from different versions of one model, can't be compared, so the model is always
 * part of the filter. Its comparison with a literal is an index term, which the search looks up rather than reading
 * every chunk; so is the source's. Only the candidates' embeddings are read. The values are bound to the filter's
 * placeholders as literals, so no model or source name can change what the filter tests.
 */
@OptIn(ExperimentalDelvaApi::class)
fun nearestChunks(db: BlockingReadableJsonStore, query: FloatArray, k: Int, model: String, source: String? = null): List<Neighbor> {
    require(k > 0) { "k must be positive, was $k" }
    val filter = if (source == null) {
        OF_MODEL.bind("model" to JsonQueryValue.Str(model))
    } else {
        OF_MODEL_AND_SOURCE.bind("model" to JsonQueryValue.Str(model), "source" to JsonQueryValue.Str(source))
    }
    val search = JsonPathQuery(EMBEDDING_PATH).withDocumentFilter(filter)
    // The worst of the best k so far is at the head, so a nearer candidate replaces it in O(log k).
    val best = PriorityQueue(k, NEAREST_FIRST.reversed())
    db.search(search).use { result ->
        for (hit in result) {
            val candidate = Neighbor(hit.docId, cosineDistance(query, parseVector(hit.readJsonAsString())))
            if (best.size < k) {
                best += candidate
            } else if (NEAREST_FIRST.compare(candidate, best.peek()) < 0) {
                best.poll()
                best += candidate
            }
        }
    }
    return best.sortedWith(NEAREST_FIRST)
}

/**
 * The cosine distance of [a] and [b], `1 − cos θ`, summed in double precision. Distances of a zero vector are NaN,
 * which [nearestChunks] ranks after every number.
 */
fun cosineDistance(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size) { "vectors of ${a.size} and ${b.size} dimensions can't be compared" }
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

/** A compact JSON array of numbers, as a hit reads it, as float32 values: each is rounded once, from its decimal text. */
fun parseVector(json: String): FloatArray {
    val numbers = json.removeSurrounding("[", "]").split(',')
    return FloatArray(numbers.size) { numbers[it].toFloat() }
}

/** A random vector of [dimensions] float32 values with length 1, Gaussian in each dimension and so uniform in direction. */
fun randomUnitVector(random: Random, dimensions: Int): FloatArray {
    val vector = DoubleArray(dimensions) { random.nextGaussian() }
    val length = sqrt(vector.sumOf { it * it })
    return FloatArray(dimensions) { (vector[it] / length).toFloat() }
}

private fun printNeighbors(db: BlockingReadableJsonStore, neighbors: List<Neighbor>) {
    for (neighbor in neighbors) {
        // The search read only the embeddings; the text is selected from the chunk now.
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

/** The filters of [nearestChunks], compiled once. */
@OptIn(ExperimentalDelvaApi::class)
private val OF_MODEL = DocumentFilterTemplate.compile("@.model == \${model}")

@OptIn(ExperimentalDelvaApi::class)
private val OF_MODEL_AND_SOURCE = DocumentFilterTemplate.compile("@.model == \${model} && @.source == \${source}")

private val NEAREST_FIRST = compareBy<Neighbor>({ it.distance }, { it.docId.raw })
private val EMBEDDING_PATH = JsonPath.compile("$.$EMBEDDING_KEY")
private val TEXT_PATH = JsonPath.compile("$.text")

private const val SEED = 42
private const val SOURCES = 60
private const val CHUNKS_PER_SOURCE = 5
private const val DIMENSIONS = 64
private const val MODEL = "pseudo-64"
private const val K = 5
