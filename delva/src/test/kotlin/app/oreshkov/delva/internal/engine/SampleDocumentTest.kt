package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.DocId
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import app.oreshkov.delva.internal.parse.JsonToken
import app.oreshkov.delva.internal.parse.ParseLimits
import app.oreshkov.delva.internal.parse.Utf8JsonTokenizer
import app.oreshkov.delva.testkit.generate.JsonEventListener
import app.oreshkov.delva.testkit.generate.StreamingExpectation
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONull
import app.oreshkov.delva.testkit.json.ONum
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A real document on a real disk: inserts the JSON file named by `-Ddelva.sample.json=<file>`
 * into a store in a temporary directory, checks `crateAmount = 1` in both result modes against a streaming
 * expectation, then reopens the store and checks again. Twice: with the default memtable limit (no flush, the open
 * replays the whole document), and with a 16 MiB limit, where the insert flushes mid-document and the open replays
 * only from the index watermark; then after a final flush the open replays nothing. Skipped without the
 * property.
 */
class SampleDocumentTest {
    @Test
    fun `a large real document is searchable in both modes, also after recovery`() {
        val file = System.getProperty("delva.sample.json")?.let { Path.of(it) } ?: return
        val key = System.getProperty("delva.sample.key") ?: "crateAmount"
        val value = ONum(System.getProperty("delva.sample.value") ?: "1")
        val expectation = StreamingExpectation(key, value)
        Files.newInputStream(file).use { feed(Utf8JsonTokenizer(it, ParseLimits(maxDepth = 10_000)), expectation) }

        for (memtableBytes in listOf(EngineOptions.DEFAULT_MEMTABLE_BYTES, 16L shl 20)) {
            val dir = Files.createTempDirectory("delva-sample")
            try {
                val options = EngineOptions(memtableBytes = memtableBytes)
                println("memtable limit ${memtableBytes shr 20} MiB")
                val docId = Engine.open(dir, options, JdkFileSystemIo()).use { store ->
                    val insertStart = System.nanoTime()
                    val docId = store.insert(file)
                    val insertMs = (System.nanoTime() - insertStart) / 1_000_000
                    store.indexes.awaitFlushes()
                    println("inserted ${Files.size(file)} bytes in $insertMs ms: ${store.indexes.flushes} flushes, ${store.postings.runCount} runs in the active memtable, ~${store.postings.approximateBytes shr 20} MiB")
                    check(store, docId, key, value, expectation)
                    docId
                }
                for (round in 0..1) {
                    val openStart = System.nanoTime()
                    Engine.open(dir, options, JdkFileSystemIo()).use { store ->
                        val openMs = (System.nanoTime() - openStart) / 1_000_000
                        val stats = store.recoveryStats
                        println("reopened in $openMs ms: replayed ${stats.replayedCommits} commits, ${stats.replayedFragments} fragments, ${stats.replayedBytes shr 10} KiB of lane bytes")
                        assertEquals(0, store.damagedDocIds.size)
                        check(store, docId, key, value, expectation)
                        if (round == 0) store.flush() else assertEquals(0, stats.replayedCommits)
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    private fun check(store: Engine, docId: DocId, key: String, value: ONum, expectation: StreamingExpectation) {
        fun run(mode: ResultMode, nested: NestedHits): List<Long> {
            val start = System.nanoTime()
            val pres = ArrayList<Long>()
            var bytes = 0L
            store.search(KeyValueQuery(key, JsonQueryValue.Json(value.text), mode, nested, limit = 1000)).use { result ->
                while (true) {
                    val page = result.nextPage() ?: break
                    for (hit in page.hits) {
                        assertEquals(docId, hit.node.docId)
                        pres += hit.node.nodePre
                        if (mode == ResultMode.CONTAINING_ELEMENT) bytes += hit.readJsonAsString().length
                    }
                }
            }
            println("$key = ${value.text} $mode $nested: ${pres.size} hits, $bytes chars of JSON, ${(System.nanoTime() - start) / 1_000_000} ms")
            return pres
        }

        assertEquals(if (expectation.documentMatches) listOf(0L) else emptyList(), run(ResultMode.WHOLE_DOCUMENT, NestedHits.ALL))
        val all = run(ResultMode.CONTAINING_ELEMENT, NestedHits.ALL)
        assertEquals(expectation.containingHits, all.size.toLong())
        assertEquals(all.sorted(), all)
        assertTrue(all.toSet().containsAll(expectation.firstHitContainers))
        assertEquals(expectation.outermostHits, run(ResultMode.CONTAINING_ELEMENT, NestedHits.OUTERMOST_ONLY).size.toLong())
    }

    /** Replays a document's tokens as [JsonEventListener] events. */
    private fun feed(tokens: Utf8JsonTokenizer, listener: JsonEventListener) {
        var inString = false
        while (true) {
            when (tokens.next()) {
                JsonToken.EOF -> return
                JsonToken.START_OBJECT -> listener.startObject()
                JsonToken.END_OBJECT -> listener.endObject()
                JsonToken.START_ARRAY -> listener.startArray()
                JsonToken.END_ARRAY -> listener.endArray()
                JsonToken.KEY -> listener.key(String(tokens.bytes, tokens.offset, tokens.length, Charsets.UTF_8))
                JsonToken.STRING -> {
                    listener.stringStart()
                    listener.stringPart(String(tokens.bytes, tokens.offset, tokens.length, Charsets.UTF_8))
                    listener.stringEnd()
                }
                JsonToken.STRING_PART -> {
                    if (!inString) listener.stringStart()
                    listener.stringPart(String(tokens.bytes, tokens.offset, tokens.length, Charsets.UTF_8))
                    inString = !tokens.isLastPart
                    if (!inString) listener.stringEnd()
                }
                JsonToken.NUMBER -> listener.number(String(tokens.bytes, tokens.offset, tokens.length, Charsets.US_ASCII))
                JsonToken.TRUE -> listener.literal(OBool(true))
                JsonToken.FALSE -> listener.literal(OBool(false))
                JsonToken.NULL -> listener.literal(ONull)
            }
        }
    }
}
