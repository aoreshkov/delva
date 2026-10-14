package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyConflict
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Measurement
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import kotlinx.benchmark.Warmup
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Finding a document by its key, over a store of 100 000 small documents on a real disk, each with a unique `id` and
 * every tenth upserted three times, so the key index holds versions that only visibility tells apart until compaction.
 * [get] uses the document-key index; [newestByTerm] is what a store without keys does instead: a key–value search for
 * the member, newest first, one hit. [keyPage] reads 100 documents in key order, and [hitKey] names the document of a
 * key–value hit through the key index.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(BenchmarkTimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
class DocumentKeyBenchmark {
    private lateinit var dir: Path
    private lateinit var store: Engine
    private var next = 0

    @Setup
    fun setUp() {
        dir = Files.createTempDirectory("delva-key-bench")
        store = Engine.open(dir, EngineOptions(CreationOptions().withDocumentKey("$.id"), StoreOptions()), JdkFileSystemIo())
        for (round in 0 until 4) {
            for (batch in 0 until 100) {
                val ids = (0 until 1000).map { batch * 1000 + it }.filter { round == 0 || it % 10 == 0 }
                store.insertAll(
                    ids.map { id ->
                        val json = """{"id":"user-${"%06d".format(id)}","round":$round,"name":"user $id","tags":["red","green"]}"""
                        InsertSource(ByteArrayInputStream(json.toByteArray()), emptyMap(), emptyList(), KeyConflict.REPLACE)
                    },
                )
            }
        }
        store.flush()
        println("\n${store.info().approximateDocumentCount} documents, ${store.info().deletedDocumentCount} replaced versions")
    }

    @TearDown
    fun tearDown() {
        store.close()
        dir.toFile().deleteRecursively()
    }

    private fun nextKey(): String {
        next = (next + 7919) % 100_000
        return "user-${"%06d".format(next)}"
    }

    /** The document of a key, through the key index. */
    @Benchmark
    fun get(): Long = store.get(nextKey())!!.raw

    /** The newest document with the key as a member, through a descending key–value search of one hit. */
    @Benchmark
    fun newestByTerm(): Long =
        store.search(KeyValueQuery("id", JsonQueryValue.Str(nextKey()), ResultMode.WHOLE_DOCUMENT, limit = 1, order = SearchOrder.DESCENDING)).use { result ->
            result.nextPage()!!.hits.single().docId.raw
        }

    /** One page of 100 documents in key order, from a key on. */
    @Benchmark
    fun keyPage(): Int = store.search(DocumentKeyQuery(prefix = "user-0${nextKey().substring(7, 8)}", limit = 100)).use { result -> result.nextPage()!!.hits.size }

    /** The key of a key–value hit's document, looked up by its docId. */
    @Benchmark
    fun hitKey(): String =
        store.search(KeyValueQuery("name", JsonQueryValue.Str("user ${next++ % 100_000}"), ResultMode.WHOLE_DOCUMENT, limit = 1)).use { result ->
            result.nextPage()!!.hits.single().documentKey!!
        }
}
