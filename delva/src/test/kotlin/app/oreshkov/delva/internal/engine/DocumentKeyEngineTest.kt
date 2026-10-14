package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.CreationOptionsMismatchException
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.InsertOutcome
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.InvalidCursorException
import app.oreshkov.delva.InvalidJsonPathException
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyConflict
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.Normalization
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.WriteConflictException
import app.oreshkov.delva.internal.facade.CoroutineJsonStore
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.TreeMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Document keys (SPEC.md §3.3): uniqueness, upsert, lookups, key order, and the key index through flush and recovery. */
class DocumentKeyEngineTest {
    private fun options(
        t: Int = 256,
        threshold: Long = EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD,
        memtableBytes: Long = EngineOptions.DEFAULT_MEMTABLE_BYTES,
        normalization: Normalization = Normalization.NONE,
        keyPath: String? = "$.id",
        startWriterThread: Boolean = true,
    ) = EngineOptions(
        CreationOptions(fragmentBytes = t, normalization = normalization).withDocumentKey(keyPath), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024)),
        sorterBytes = 4096, keyArenaBytes = 1024, smallInsertThreshold = threshold, keySeed = 23, log = TestEngines.LOG,
        backgroundFlush = false, memtableBytes = memtableBytes, segmentBlockBytes = 512, startWriterThread = startWriterThread,
        compaction = CompactionOptions(bytesPerSecond = 0, minMergeSegments = 2),
    )

    private fun doc(id: String, v: Int = 0) = """{"all":true,"id":${json(id)},"v":$v}"""

    private fun json(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' || c == '\\' -> append('\\').append(c)
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    private fun Engine.keys(prefix: String = "", order: SearchOrder = SearchOrder.ASCENDING, limit: Int = 1000): List<Pair<String, Long>> =
        search(DocumentKeyQuery(prefix, limit = limit, order = order)).use { result ->
            result.iterator().asSequence().map { it.documentKey!! to it.docId.raw }.toList()
        }

    @Test
    fun `keys are unique, upsert replaces, and get finds the visible document, promoted or not, through replay and flush`() {
        for (t in listOf(32, 4096)) {
            for (threshold in listOf(0L, EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD)) {
                val label = "T=$t threshold=$threshold"
                val fs = SimulatedFileSystemIo()
                val options = options(t, threshold)
                val long = "k".repeat(CreationOptions.MAX_DOCUMENT_KEY_BYTES)
                val expected = TestEngines.open(options, fs).use { store ->
                    val a = store.insert(doc("a")).raw
                    val b = store.insert(doc(long)).raw
                    val e = assertFailsWith<WriteConflictException>(label) { store.insert(doc("a", 1)) }
                    assertEquals("a", e.key, label)
                    assertEquals(listOf(a), e.conflicting.map { it.raw }, label)
                    val a2 = store.upsert(doc("a", 2)).raw
                    assertEquals(a2, store.get("a")?.raw, label)
                    assertEquals(b, store.get(long)?.raw, label)
                    assertNull(store.get("missing"), label)
                    // A new key, by upsert, is an insert.
                    val c = store.upsert(doc("c")).raw
                    assertEquals(mapOf(a2 to doc("a", 2), b to doc(long), c to doc("c")), store.documents(), label)
                    assertEquals(listOf("a" to a2, "c" to c, long to b), store.keys(), label)
                    assertEquals(listOf(long to b, "c" to c, "a" to a2), store.keys(order = SearchOrder.DESCENDING), label)
                    // Every hit names its document's key.
                    store.search(KeyValueQuery("v", JsonQueryValue.Num(2), ResultMode.CONTAINING_ELEMENT)).use { r ->
                        assertEquals(listOf("a"), r.iterator().asSequence().map { it.documentKey }.toList(), label)
                    }
                    assertEquals(emptyList(), store.verify().issues.map { it.toString() }, label)
                    mapOf("a" to a2, "c" to c, long to b)
                }
                fs.restart()
                TestEngines.open(options, fs).use { store ->
                    assertEquals(expected.toList(), store.keys(), "$label after replay")
                    assertEquals(emptyList(), store.verify().issues.map { it.toString() }, "$label after replay")
                    store.flush()
                    assertEquals(expected.toList(), store.keys(), "$label after flush")
                    assertFailsWith<WriteConflictException>(label) { store.insert(doc("c")) }
                }
                fs.restart()
                TestEngines.open(options, fs).use { store ->
                    assertEquals(0, store.recoveryStats.replayedCommits, label)
                    assertEquals(expected.toList(), store.keys(), "$label from segments")
                    for ((key, docId) in expected) assertEquals(docId, store.get(key)?.raw, "$label: $key")
                    assertEquals(emptyList(), store.verify().issues.map { it.toString() }, label)
                }
            }
        }
    }

    @Test
    fun `a document without a string key is rejected, and so is one with a key over the limit`() {
        TestEngines.open(options(t = 32)).use { store ->
            for (json in listOf("""{"all":true}""", """{"id":1}""", """{"id":null}""", """{"id":{"x":"y"}}""", """{"id":["a"]}""", "[]", "\"id\"")) {
                assertEquals(RejectReason.KEY_MISSING, assertFailsWith<DocumentRejectedException>(json) { store.insert(json) }.reason, json)
            }
            val tooLong = "é".repeat(CreationOptions.MAX_DOCUMENT_KEY_BYTES / 2) + "x"
            assertEquals(RejectReason.LIMIT_EXCEEDED, assertFailsWith<DocumentRejectedException> { store.insert(doc(tooLong)) }.reason)
            assertEquals(emptyMap(), store.documents())
        }
        TestEngines.open(options(keyPath = "$.meta['the key'][1]")).use { store ->
            val docId = store.insert("""{"meta":{"the key":["x","k1"]}}""")
            assertEquals(docId, store.get("k1"))
            assertEquals(RejectReason.KEY_MISSING, assertFailsWith<DocumentRejectedException> { store.insert("""{"meta":{"the key":["x"]}}""") }.reason)
        }
        assertFailsWith<InvalidJsonPathException> { CreationOptions().withDocumentKey("$") }
        assertFailsWith<InvalidJsonPathException> { CreationOptions().withDocumentKey("$..id") }
    }

    @Test
    fun `key order is code point order, prefixes select, and pages resume from key cursors`() {
        val keys = listOf("", "a", "a\u0000", "a\u0000b", "ab", "b", "￿", "😀", "é", "é")
        for (flushAfter in listOf(0, 4, keys.size)) {
            val fs = SimulatedFileSystemIo()
            TestEngines.open(options(), fs).use { store ->
                for ((i, key) in keys.withIndex()) {
                    if (i == flushAfter) store.flush()
                    store.insert(doc(key, i))
                }
                val sorted = keys.sortedWith { x, y -> java.util.Arrays.compareUnsigned(x.toByteArray(), y.toByteArray()) }
                assertEquals(sorted, store.keys().map { it.first }, "flush after $flushAfter")
                assertEquals(sorted.reversed(), store.keys(order = SearchOrder.DESCENDING).map { it.first })
                for (prefix in listOf("", "a", "a\u0000", "b", "c", "\uD83D")) {
                    val expected = sorted.filter { it.startsWith(prefix) && !prefix.endsWith('\uD83D') }
                    assertEquals(expected, store.keys(prefix).map { it.first }, "prefix ${prefix.map { it.code }}")
                    assertEquals(expected.reversed(), store.keys(prefix, SearchOrder.DESCENDING).map { it.first }, "prefix ${prefix.map { it.code }} descending")
                }
                for (order in SearchOrder.entries) {
                    val all = if (order == SearchOrder.ASCENDING) sorted else sorted.reversed()
                    var query = DocumentKeyQuery(limit = 3, order = order)
                    val seen = ArrayList<String>()
                    while (true) {
                        val page = store.search(query).use { it.nextPage() } ?: break
                        seen += page.hits.map { it.documentKey!! }
                        val cursor = page.cursor ?: break
                        // Keys between pages: new documents behind the cursor aren't returned, ahead of it they are.
                        val parsed = Cursor.parse(cursor.toString())
                        assertEquals(cursor, parsed)
                        query = query.copy(after = parsed)
                    }
                    assertEquals(all, seen, "$order pages")
                }
                assertEquals(keys.size.toLong(), store.count(DocumentKeyQuery()))
                assertEquals(4L, store.count(DocumentKeyQuery("a")))
                assertEquals(2L, store.count(DocumentKeyQuery(), atMost = 2))
                assertEquals(emptyList(), store.verify().issues.map { it.toString() })
            }
        }
    }

    @Test
    fun `cursors resume only their own kind of search`() {
        TestEngines.open(options()).use { store ->
            for (i in 0 until 3) store.insert(doc("k$i", i))
            val keyCursor = store.search(DocumentKeyQuery(limit = 1)).use { it.nextPage()!!.cursor!! }
            assertTrue(keyCursor.toString().length < 100)
            val searchCursor = store.search(KeyValueQuery("all", JsonQueryValue.Bool(true), limit = 1)).use { it.nextPage()!!.cursor!! }
            assertFailsWith<InvalidCursorException> { KeyValueQuery("all", JsonQueryValue.Bool(true), after = keyCursor) }
            assertFailsWith<InvalidCursorException> { JsonPathQuery(JsonPath.compile("$.all"), after = keyCursor) }
            assertFailsWith<InvalidCursorException> { DocumentKeyQuery(after = searchCursor) }
            assertFailsWith<InvalidCursorException> { DocumentKeyQuery(after = keyCursor, order = SearchOrder.DESCENDING) }
            assertEquals(listOf("k1", "k2"), store.search(DocumentKeyQuery(after = keyCursor)).use { r -> r.iterator().asSequence().map { it.documentKey }.toList() })
            // The longest key fits a cursor.
            val long = "😀".repeat(CreationOptions.MAX_DOCUMENT_KEY_BYTES / 4)
            store.insert(doc(long))
            val cursor = store.search(DocumentKeyQuery(prefix = "\uD83D", limit = 1)).use { it.nextPage() }
            assertNull(cursor, "a prefix with a lone surrogate matches nothing")
            val longCursor = store.search(DocumentKeyQuery(prefix = long, limit = 1)).use { it.nextPage()!!.hits.single().cursor }
            assertEquals(longCursor, Cursor.parse(longCursor.toString()))
        }
    }

    @Test
    fun `a replace may change a document's key, and frees the old key`() {
        TestEngines.open(options()).use { store ->
            val a = store.insert(doc("a"))
            val b = store.insert(doc("b"))
            // The new key is held by a document the replace doesn't replace.
            val e = assertFailsWith<WriteConflictException> { store.replace(listOf(a), doc("b")) }
            assertEquals("b", e.key)
            val a2 = store.replace(listOf(a), doc("a2"))
            assertNull(store.get("a"))
            assertEquals(a2, store.get("a2"))
            // Replacing both by one document with one of their keys.
            val ab = store.replace(listOf(a2, b), doc("b", 1))
            assertEquals(listOf("b" to ab.raw), store.keys())
            val a3 = store.insert(doc("a"))
            assertEquals(a3, store.get("a"))
            assertTrue(store.delete(a3))
            assertNull(store.get("a"))
            assertEquals(emptyList(), store.verify().issues.map { it.toString() })
        }
    }

    @Test
    fun `insertAll takes effect in order, so a later source sees the keys of the earlier ones`() {
        for (threshold in listOf(0L, 600L, EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD)) {
            TestEngines.open(options(threshold = threshold)).use { store ->
                val old = store.insert(doc("x", 0)).raw
                fun source(json: String, conflict: KeyConflict = KeyConflict.FAIL) =
                    InsertSource(ByteArrayInputStream(json.toByteArray()), emptyMap(), emptyList(), conflict)
                // Large documents are promoted at threshold 600, small ones held: the order must hold either way.
                val big = "p".repeat(1000)
                val outcomes = store.insertAll(
                    listOf(
                        source(doc("y", 1)),
                        source("""{"all":true,"id":"y","v":2,"pad":"$big"}"""),
                        source("""{"all":true,"id":"y","v":3,"pad":"$big"}""", KeyConflict.REPLACE),
                        source(doc("y", 4), KeyConflict.REPLACE),
                        source(doc("x", 5)),
                        source(doc("x", 6), KeyConflict.REPLACE),
                        source("""{"all":true}"""),
                    ),
                )
                val label = "threshold $threshold"
                assertIs<InsertOutcome.Inserted>(outcomes[0], label)
                assertEquals("y", assertIs<InsertOutcome.Conflicted>(outcomes[1], label).exception.key)
                assertIs<InsertOutcome.Inserted>(outcomes[2], label)
                val y = assertIs<InsertOutcome.Inserted>(outcomes[3], label).docId.raw
                assertEquals(listOf(old), assertIs<InsertOutcome.Conflicted>(outcomes[4], label).exception.conflicting.map { it.raw })
                val x = assertIs<InsertOutcome.Inserted>(outcomes[5], label).docId.raw
                assertEquals(RejectReason.KEY_MISSING, assertIs<InsertOutcome.Rejected>(outcomes[6], label).exception.reason)
                assertEquals(listOf("x" to x, "y" to y), store.keys(), label)
                assertEquals(mapOf(x to doc("x", 6), y to doc("y", 4)), store.documents(), label)
                assertEquals(emptyList(), store.verify().issues.map { it.toString() }, label)
            }
        }
    }

    @Test
    fun `in one batch, the second insert of a key conflicts and the last upsert wins`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            TestEngines.open(options(startWriterThread = false)).use { store ->
                val inserts = (1..2).map { v -> executor.submit(Callable { runCatching { store.insert(doc("k", v)).raw } }) }
                while (store.writer.queued < 2) Thread.onSpinWait()
                store.writer.runBatch(wait = false)
                val outcomes = inserts.map { it.get(30, TimeUnit.SECONDS) }
                assertEquals(1, outcomes.count { it.isSuccess })
                assertIs<WriteConflictException>(outcomes.single { it.isFailure }.exceptionOrNull())
                val upserts = (3..4).map { v -> executor.submit(Callable { store.upsert(doc("k", v)).raw }) }
                while (store.writer.queued < 2) Thread.onSpinWait()
                store.writer.runBatch(wait = false)
                val docIds = upserts.map { it.get(30, TimeUnit.SECONDS) }
                val last = store.documents().entries.single()
                assertTrue(last.key in docIds)
                assertEquals(store.get("k")?.raw, last.key)
                assertEquals(1, store.keys().size)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `keys are normalized with NFC, as stored strings are`() {
        TestEngines.open(options(normalization = Normalization.NFC)).use { store ->
            val docId = store.insert(doc("é"))
            assertEquals(docId, store.get("é"))
            assertEquals(docId, store.get("é"))
            assertEquals(listOf("é"), store.keys("é").map { it.first })
            assertFailsWith<WriteConflictException> { store.insert(doc("é")) }
        }
    }

    @Test
    fun `a store without keys refuses key operations, and creation options must agree`() {
        val fs = SimulatedFileSystemIo()
        TestEngines.open(options(keyPath = null), fs).use { store ->
            assertFailsWith<IllegalStateException> { store.get("a") }
            assertFailsWith<IllegalStateException> { store.upsert(doc("a")) }
            assertFailsWith<IllegalStateException> { store.search(DocumentKeyQuery()) }
            store.insert(doc("a"))
            store.search(KeyValueQuery("all", JsonQueryValue.Bool(true))).use { r -> assertNull(r.iterator().next().documentKey) }
        }
        fs.restart()
        assertFailsWith<CreationOptionsMismatchException> { TestEngines.open(options(), fs).close() }
        val dir = java.nio.file.Files.createTempDirectory("delva-keys")
        try {
            JsonStore.create(dir, CreationOptions().withDocumentKey("$.id")).close()
            JsonStore.open(dir).use { store ->
                assertEquals("$.id", store.creationOptions.documentKey)
                store.blocking().insert(doc("a"))
                (store as CoroutineJsonStore).core.flush()
            }
            val e = assertFailsWith<CreationOptionsMismatchException> { JsonStore.openOrCreate(dir, CreationOptions()).close() }
            assertEquals("$.id", e.stored.documentKey)
            // A reset keeps the key path and forgets every key.
            assertEquals("$.id", JsonStore.reset(dir).documentKey)
            JsonStore.open(dir).use { store ->
                assertNull(store.blocking().get("a"))
                store.blocking().insert(doc("a"))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the suspending store upserts and gets`() = runBlocking {
        JsonStore.open(TestEngines.DIR, options(), SimulatedFileSystemIo()).use { store ->
            val a = store.insert(doc("a"))
            val b = store.upsert(ByteArrayInputStream(doc("a", 1).toByteArray()))
            assertTrue(b.raw > a.raw)
            assertEquals(b, store.get("a"))
            assertEquals(1L, store.count(DocumentKeyQuery()))
            // insertAll keeps each source's onExistingKey through the suspending face.
            val outcomes = store.insertAll(
                listOf(
                    InsertSource(ByteArrayInputStream(doc("a", 2).toByteArray()), onExistingKey = KeyConflict.REPLACE, replaces = emptyList()),
                    InsertSource(ByteArrayInputStream(doc("a", 3).toByteArray())),
                ),
            )
            val c = (outcomes[0] as InsertOutcome.Inserted).docId
            assertIs<InsertOutcome.Conflicted>(outcomes[1])
            assertEquals(c, store.get("a"))
            assertEquals(1L, store.count(DocumentKeyQuery()))
        }
    }

    /**
     * Random inserts, upserts, deletes, replaces, flushes, compactions and reopenings against a map of key to document:
     * after each step every key finds its document and key order matches the map.
     */
    @Test
    fun `the key index matches a model through flushes, compactions and recovery`() {
        for (seed in 1..6) {
            val random = Random(seed)
            val fs = SimulatedFileSystemIo()
            val options = options(t = if (seed % 2 == 0) 32 else 4096, threshold = if (seed % 3 == 0) 0L else EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD, memtableBytes = if (seed % 2 == 0) 0L else 1L shl 20)
            val model = TreeMap<String, Long>()
            var store = TestEngines.open(options, fs)
            try {
                val keys = listOf("", "a", "ab", "b", "b\u0000", "z", "é", "😀") + (0 until 12).map { "k%02d".format(it) }
                for (step in 0 until 200) {
                    val key = keys[random.nextInt(keys.size)]
                    val label = "seed $seed step $step"
                    when (random.nextInt(10)) {
                        0, 1 -> {
                            val result = runCatching { store.insert(doc(key, step)).raw }
                            if (key in model) assertIs<WriteConflictException>(result.exceptionOrNull(), label) else model[key] = result.getOrThrow()
                        }
                        2, 3 -> model[key] = store.upsert(doc(key, step)).raw
                        4 -> model.remove(key)?.let { assertTrue(store.delete(DocId(it)), label) }
                        5 -> {
                            val other = model.keys.randomOrNull(random)
                            if (other != null) {
                                val result = runCatching { store.replace(listOf(DocId(model.getValue(other))), doc(key, step)).raw }
                                if (key in model && key != other) {
                                    assertIs<WriteConflictException>(result.exceptionOrNull(), label)
                                } else {
                                    model.remove(other)
                                    model[key] = result.getOrThrow()
                                }
                            }
                        }
                        6 -> store.flush()
                        7 -> store.compact()
                        8 -> if (random.nextInt(4) == 0) {
                            store.close()
                            fs.restart()
                            store = TestEngines.open(options, fs)
                        }
                        else -> {}
                    }
                    if (step % 10 == 0 || step == 199) {
                        assertEquals(model.entries.sortedWith { x, y -> java.util.Arrays.compareUnsigned(x.key.toByteArray(), y.key.toByteArray()) }.map { it.key to it.value }, store.keys(), label)
                        for (k in keys) assertEquals(model[k], store.get(k)?.raw, "$label: get $k")
                    }
                }
                store.flush()
                store.compact()
                assertEquals(emptyList(), store.verify().issues.map { it.toString() }, "seed $seed")
            } finally {
                store.close()
            }
        }
    }
}
