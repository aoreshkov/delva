package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DocId
import app.oreshkov.delva.InvalidCursorException
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.bytes.Crc32c
import app.oreshkov.delva.internal.bytes.GrowableBytes
import app.oreshkov.delva.internal.bytes.LittleEndian
import app.oreshkov.delva.internal.lsm.PostingRun
import app.oreshkov.delva.internal.query.QueryTerm
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.io.encoding.Base64
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EngineScenarioTest {
    @Test
    fun `a hot term in one document is one hit in WHOLE_DOCUMENT mode and decodes no entries`() {
        TestEngines.open(EngineOptions(log = TestEngines.LOG)).use { store ->
            store.insert("""{"id":1,"items":[${(1..20_000).joinToString(",") { """{"k":"hot","n":$it}""" }}]}""")
            store.insert("""{"k":"cold"}""")
            val whole = store.search(KeyValueQuery("k", JsonQueryValue.Str("hot"))) as EngineSearchResult
            whole.use { result ->
                val page = result.nextPage()!!
                assertEquals(listOf(1L), page.hits.map { it.node.docId.raw })
                assertEquals(null, page.cursor)
                assertEquals(1, result.executor.stats.runsOpened, "one group")
                assertEquals(0, result.executor.entriesDecoded, "no entries decoded")
            }
            val containing = store.search(KeyValueQuery("k", JsonQueryValue.Str("hot"), ResultMode.CONTAINING_ELEMENT, limit = 7_000))
            containing.use { result ->
                val pages = generateSequence { result.nextPage() }.toList()
                assertEquals(listOf(7_000, 7_000, 6_000), pages.map { it.hits.size })
                val pres = pages.flatMap { p -> p.hits.map { it.node.nodePre } }
                assertEquals(pres.sorted(), pres)
                assertEquals("""{"k":"hot","n":20000}""", pages.last().hits.last().readJsonAsString())
            }
        }
    }

    @Test
    fun `a hash collision is rejected by the recheck`() {
        for (t in listOf(32, 64 * 1024)) {
            TestEngines.open(EngineOptions(CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024)), log = TestEngines.LOG)).use { store ->
                store.insert("""{"a":{"x":1,"y":[1,2,3]},"s":"${"v".repeat(100)}"}""")
                for ((stored, other) in listOf(
                    JsonQueryValue.Json("""{"y":[1,2,3],"x":1}""") to JsonQueryValue.Json("""{"y":[1,2,4],"x":1}"""),
                )) {
                    val real = QueryTerm.of("a", stored, true, 64)
                    val fake = QueryTerm.of("a", other, true, 64)
                    val runs = store.postings.runs(real.term, 0).asSequence().toList()
                    assertTrue(runs.isNotEmpty())
                    // Pretend the other literal's term collided with the stored value's.
                    for (run in runs) store.postings.add(PostingRun(fake.term, run.docId, run.runSeq, run.count, run.entries))
                    assertEquals(1, hits(store, KeyValueQuery("a", stored, ResultMode.CONTAINING_ELEMENT)), "T=$t real")
                    assertEquals(0, hits(store, KeyValueQuery("a", other, ResultMode.CONTAINING_ELEMENT)), "T=$t collision")
                    assertEquals(0, hits(store, KeyValueQuery("a", other, ResultMode.WHOLE_DOCUMENT)), "T=$t collision")
                }
                val long = QueryTerm.of("s", JsonQueryValue.Str("v".repeat(100)), true, 64)
                val prefixTwin = JsonQueryValue.Str("v".repeat(99) + "w")
                for (run in store.postings.runs(long.term, 0).asSequence().toList()) {
                    store.postings.add(PostingRun(QueryTerm.of("s", prefixTwin, true, 64).term, run.docId, run.runSeq, run.count, run.entries))
                }
                assertEquals(0, hits(store, KeyValueQuery("s", prefixTwin, ResultMode.CONTAINING_ELEMENT)), "T=$t long string collision")
            }
        }
    }

    private fun hits(store: Engine, query: KeyValueQuery): Int = store.search(query).use { r -> r.iterator().asSequence().count() }

    @Test
    fun `cursor text is validated`() {
        val cursor = Cursor(DocId(12), 345, 6, 7, SearchOrder.ASCENDING)
        assertEquals(cursor, Cursor.parse(cursor.toString()))
        assertTrue(cursor.mayMissLateCommits)
        assertFalse(Cursor(DocId(12), 0, 6, 13, SearchOrder.ASCENDING).mayMissLateCommits)
        val text = cursor.toString()
        val flipped = text.substring(0, 3) + (if (text[3] == 'A') 'B' else 'A') + text.substring(4)
        for (bad in listOf("", "!!!!", flipped, text.dropLast(2), text + "AA", "A".repeat(200))) {
            assertFailsWith<IllegalArgumentException>(bad) { Cursor.parse(bad) }
        }
    }

    @Test
    fun `cursor text keeps the order, and version 1 reads as ascending`() {
        for (bound in listOf(-1L, 0L, 11L, 13L)) {
            val cursor = Cursor(DocId(12), 345, 6, bound, SearchOrder.DESCENDING)
            val parsed = Cursor.parse(cursor.toString())
            assertEquals(cursor, parsed)
            assertEquals(SearchOrder.DESCENDING, parsed.order)
            assertEquals(bound > 12, parsed.mayMissLateCommits, "bound $bound")
        }
        assertNotEquals(Cursor(DocId(12), 345, 6, 7, SearchOrder.ASCENDING), Cursor(DocId(12), 345, 6, 7, SearchOrder.DESCENDING))

        fun text(vararg body: Int): String {
            val out = GrowableBytes(16)
            for (b in body) out.writeByte(b)
            out.writeIntLE(Crc32c.of(out.array, 0, out.size))
            return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(out.array, 0, out.size)
        }
        assertEquals(Cursor(DocId(12), 45, 6, 7, SearchOrder.ASCENDING), Cursor.parse(text(1, 12, 45, 6, 7)))
        assertEquals(Cursor(DocId(12), 45, 6, 7, SearchOrder.ASCENDING), Cursor.parse(text(2, 0, 12, 45, 6, 7)))
        assertEquals(Cursor(DocId(12), 45, 6, -1, SearchOrder.DESCENDING), Cursor.parse(text(2, 1, 12, 45, 6, 0)))
        assertEquals(Cursor(DocId(12), 45, 6, 7, SearchOrder.ASCENDING, forTypes = true), Cursor.parse(text(2, 2, 12, 45, 6, 7)))
        // A descending type cursor doesn't exist, nor a key cursor of types; a key cursor has its key.
        for (flags in listOf(3, 4, 6, 7, 8, 0x80)) assertFailsWith<InvalidCursorException>("flags $flags") { Cursor.parse(text(2, flags, 12, 45, 6, 7)) }
        assertFailsWith<InvalidCursorException> { Cursor.parse(text(3, 0, 12, 45, 6, 7)) }
        val key = Cursor(DocId(12), 0, 6, 1, SearchOrder.ASCENDING, forTypes = false, key = "ké")
        assertEquals(Cursor(DocId(12), 0, 6, 1, SearchOrder.ASCENDING, forTypes = false, key = "ké"), Cursor.parse(text(2, 4, 12, 0, 6, 1, 3, 'k'.code, 0xC3, 0xA9)))
        assertEquals(key, Cursor.parse(key.toString()))
        assertTrue(key.mayMissLateCommits)
        val descending = Cursor(DocId(12), 0, 6, 0, SearchOrder.DESCENDING, forTypes = false, key = "")
        assertEquals(descending, Cursor.parse(descending.toString()))
        assertFalse(descending.mayMissLateCommits)
        assertNotEquals(key, Cursor(DocId(12), 0, 6, 1, SearchOrder.ASCENDING))
        // Not UTF-8, a node in a key cursor, a bound above 1, a key longer than the limit.
        for (bad in listOf(text(2, 4, 12, 0, 6, 1, 1, 0xC3), text(2, 4, 12, 1, 6, 1, 1, 'k'.code), text(2, 4, 12, 0, 6, 2, 1, 'k'.code), text(2, 4, 12, 0, 6, 1, 0x81, 0x08))) {
            assertFailsWith<InvalidCursorException>(bad) { Cursor.parse(bad) }
        }
    }

    @Test
    fun `hostile cursor text fails only with InvalidCursorException`() {
        val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        fun text(body: ByteArray): String = base64.encode(body + ByteArray(4).also { LittleEndian.putInt(it, 0, Crc32c.of(body, 0, body.size)) })

        // A key length of -1: nine 0xFF bytes and a tenth of 0x01.
        val negative = byteArrayOf(2, 4, 12, 0, 6, 1) + ByteArray(9) { 0xFF.toByte() } + byteArrayOf(1, 'k'.code.toByte())
        assertFailsWith<InvalidCursorException> { Cursor.parse(text(negative)) }

        val valid = listOf(
            Cursor(DocId(12), 0, 6, 1, SearchOrder.ASCENDING, forTypes = false, key = "ké"),
            Cursor(DocId(12), 345, 6, 7, SearchOrder.DESCENDING),
            Cursor(DocId(1L shl 40), 45, 6, 7, SearchOrder.ASCENDING, forTypes = true),
        ).map { base64.decode(it.toString()).let { bytes -> bytes.copyOf(bytes.size - 4) } }
        val mutated = Gen { random, size ->
            val body = valid[random.nextInt(valid.size)].toMutableList()
            repeat(1 + random.nextInt(1 + size / 10)) {
                when (random.nextInt(4)) {
                    0 -> if (body.isNotEmpty()) body[random.nextInt(body.size)] = random.nextInt(256).toByte()
                    1 -> body.add(random.nextInt(body.size + 1), random.nextInt(256).toByte())
                    2 -> if (body.isNotEmpty()) body.removeAt(random.nextInt(body.size))
                    // A long varint, which may run past 64 bits or decode negative.
                    else -> {
                        val at = random.nextInt(body.size + 1)
                        body.add(at, random.nextInt(4).toByte())
                        repeat(random.nextInt(1, 11)) { body.add(at, 0xFF.toByte()) }
                    }
                }
            }
            body.toByteArray()
        }
        forAll("mutated cursor", mutated, iterations = 5000) { body ->
            try {
                Cursor.parse(text(body))
            } catch (_: InvalidCursorException) {
            }
        }
    }

    @Test
    fun `a query refuses a cursor of the other order`() {
        val ascending = Cursor(DocId(12), 0, 6, 7, SearchOrder.ASCENDING)
        val descending = Cursor(DocId(12), 0, 6, 7, SearchOrder.DESCENDING)
        val keyValue = KeyValueQuery("k", JsonQueryValue.Str("v"), after = ascending)
        assertFailsWith<IllegalArgumentException> { keyValue.copy(order = SearchOrder.DESCENDING) }
        assertFailsWith<IllegalArgumentException> { KeyValueQuery("k", JsonQueryValue.Str("v"), after = descending) }
        assertEquals(descending, keyValue.copy(after = descending, order = SearchOrder.DESCENDING).after)
        val path = JsonPathQuery(JsonPath.compile("$[?@.k == 'v']"), after = ascending)
        assertFailsWith<IllegalArgumentException> { path.copy(order = SearchOrder.DESCENDING) }
        assertFailsWith<IllegalArgumentException> { JsonPathQuery(JsonPath.compile("$[?@.k == 'v']"), after = descending) }
    }

    @Test
    fun `a well-formed cursor resumes after its node, wherever that is`() {
        TestEngines.open(EngineOptions(CreationOptions(fragmentBytes = 64), StoreOptions(encoderBudgetBytes = 1024), log = TestEngines.LOG, backgroundFlush = false)).use { store ->
            val docIds = List(6) { store.insert("""{"k":"v","i":$it,"n":[{"k":"v"}]}""").raw }
            store.delete(DocId(docIds[3]))
            fun nodes(result: SearchResult) = result.use { r -> r.iterator().asSequence().map { it.node.docId.raw to it.node.nodePre }.toList() }
            val keyValue = { after: Cursor? -> nodes(store.search(KeyValueQuery("k", JsonQueryValue.Str("v"), ResultMode.CONTAINING_ELEMENT, after = after))) }
            val path = { after: Cursor? -> nodes(store.search(JsonPathQuery(JsonPath.compile("$..[?@.k == 'v']"), after = after))) }
            val runs = listOf(keyValue, path)
            val lsn = store.search(KeyValueQuery("k", JsonQueryValue.Str("v"))).use { it.snapshotLsn }
            for ((i, run) in runs.withIndex()) {
                val all = run(null)
                assertTrue(all.none { it.first == docIds[3] }, "search $i: the deleted document has no hits")
                fun after(docId: Long, pre: Long) = all.filter { it.first > docId || (it.first == docId && it.second > pre) }
                val cursors = listOf(
                    Cursor(DocId(docIds.last() + 1_000), 0, lsn, lsn + 1, SearchOrder.ASCENDING) to emptyList(), // past the last document
                    Cursor(DocId(docIds[3]), 0, lsn, lsn + 1, SearchOrder.ASCENDING) to after(docIds[3], 0), // at a deleted document
                    Cursor(DocId(docIds[1]), 1_000_000, lsn, lsn + 1, SearchOrder.ASCENDING) to after(docIds[1], 1_000_000), // past the document's last node
                    Cursor(DocId(docIds[1]), 0, lsn + 1_000_000, lsn + 1_000_000, SearchOrder.ASCENDING) to after(docIds[1], 0), // a future snapshot
                )
                for ((cursor, expected) in cursors) assertEquals(expected, run(cursor), "search $i after $cursor")
                assertTrue(after(docIds[1], 1_000_000).first().first == docIds[2], "search $i")
            }
        }
    }

    @Test
    fun `parallel inserts and searches see consistent snapshots`() {
        val store = TestEngines.open(EngineOptions(CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024), smallInsertThreshold = 2048, log = TestEngines.LOG))
        val writers = 4
        val perWriter = 400
        val acked = ConcurrentHashMap<Long, String>()
        val done = AtomicBoolean()
        val searches = AtomicInteger()
        val pool = Executors.newFixedThreadPool(writers + 2)
        val start = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        repeat(writers) { w ->
            pool.execute {
                try {
                    start.await()
                    repeat(perWriter) { i ->
                        val json = """{"all":true,"w":$w,"i":$i,"pad":[${(0 until (i % 40)).joinToString(",") { """{"all":true,"x":"${"p".repeat(it)}"}""" }}]}"""
                        acked[store.insert(json).raw] = json
                    }
                } catch (e: Throwable) {
                    errors += e
                }
            }
        }
        val seenByReaders = ConcurrentHashMap.newKeySet<Long>()
        repeat(2) { r ->
            pool.execute {
                try {
                    start.await()
                    while (!done.get()) {
                        val before = HashMap(acked)
                        store.search(KeyValueQuery("all", JsonQueryValue.Bool(true), limit = 37)).use { result ->
                            var previous = -1L
                            val seen = HashSet<Long>()
                            while (true) {
                                val page = result.nextPage() ?: break
                                for (hit in page.hits) {
                                    val docId = hit.node.docId.raw
                                    check(docId > previous) { "docIds out of order: $docId after $previous" }
                                    previous = docId
                                    seen += docId
                                    if (r == 0 && docId % 7 == 0L) {
                                        val json = hit.readJsonAsString()
                                        check(acked[docId] == null || acked[docId] == json) { "JSON of $docId" }
                                    }
                                }
                            }
                            check(seen.containsAll(before.keys)) { "a search missed ${before.keys - seen} acknowledged before it started" }
                            seenByReaders += seen
                        }
                        searches.incrementAndGet()
                    }
                } catch (e: Throwable) {
                    errors += e
                    done.set(true)
                }
            }
        }
        start.countDown()
        while (acked.size < writers * perWriter && errors.isEmpty()) Thread.sleep(10)
        done.set(true)
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        errors.firstOrNull()?.let { throw AssertionError("concurrent run failed", it) }
        assertTrue(acked.keys.containsAll(seenByReaders), "searches returned unacknowledged documents")
        assertTrue(searches.get() > 0)
        println("concurrency smoke: ${writers * perWriter} inserts, ${searches.get()} searches")
        assertEquals(writers * perWriter, hits(store, KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT)))
        assertEquals(0, store.registry.current().visibility.inFlightCount)
        store.close()
    }
}
