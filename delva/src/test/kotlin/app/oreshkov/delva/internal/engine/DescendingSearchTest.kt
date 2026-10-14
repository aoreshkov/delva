package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.Cursor
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.SearchResult
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.query.PathSearch
import app.oreshkov.delva.internal.query.QueryExecutor
import app.oreshkov.delva.internal.query.TypeQueryPlan
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Descending searches against ascending ones, the main guard of the backward path: over random stores whose terms
 * repeat in hundreds of documents — so posting lists have several blocks — spread over memtables and segments, with
 * deletes, type reassignments, flushes and compactions in between, `DESCENDING` must be `ASCENDING` reversed, for
 * key–value and JSONPath searches of every candidate kind. That holds for the whole result, in both result modes and
 * nested-hit filters, with and without a type (either type plan), with documents kept in chunks as small as one hit,
 * and across page boundaries cut by random limits and byte budgets. Results opened before more changes keep their
 * snapshot in both orders, and a cursor resumed in a new search afterwards returns exactly the hits before it.
 */
class DescendingSearchTest {
    /** One hit as the tests compare it. */
    private data class Hit(val node: NodeRef, val type: String?, val encodedSize: Long, val path: String)

    private class Search(val label: String, val run: (order: SearchOrder, after: Cursor?, limit: Int, maxBytes: Long) -> SearchResult)

    /** Passes over documents that descending searches made after the first, as tiny buffers force. */
    private var extraPasses = 0L

    private fun hits(result: SearchResult): List<Hit> = result.use { r ->
        r.iterator().asSequence().map { Hit(it.node, it.type, it.encodedSize, it.path) }.toList()
            .also {
                when (val source = (r as EngineSearchResult).source) {
                    is QueryExecutor -> extraPasses += source.passesForOrder
                    is PathSearch -> extraPasses += source.passesForOrder
                }
            }
    }

    private fun doc(random: Random, i: Int, count: Int): String {
        fun obj(depth: Int): String {
            val members = ArrayList<String>()
            if (random.nextInt(3) != 0) members += """"k":${random.nextInt(4)}"""
            if (random.nextInt(4) == 0) members += """"s":"${"long ".repeat(random.nextInt(1, 30))}${random.nextInt(2)}""""
            if (random.nextInt(3) == 0) members += """"tags":[${List(random.nextInt(1, 4)) { random.nextInt(4) }.joinToString(",")}]"""
            if (depth < 3 && random.nextInt(3) == 0) members += """"child":${obj(depth + 1)}"""
            return members.joinToString(",", "{", "}")
        }
        val items = List(count) { obj(1) }
        return """{"all":true,"i":$i,"k":${random.nextInt(4)},"items":[${items.joinToString(",")}]}"""
    }

    private fun options(seed: Long) = EngineOptions(
        CreationOptions(fragmentBytes = listOf(64, 256, 4096)[Math.floorMod(seed, 3)], hashTerms = true), StoreOptions(encoderBudgetBytes = 8192),
        sorterBytes = 4096,
        keySeed = seed,
        log = TestEngines.LOG,
        backgroundFlush = false,
        segmentBlockBytes = 512,
        typeQueryPlan = TypeQueryPlan.entries[Math.floorMod(seed shr 2, 3)],
        // Tiny buffers read documents with more hits in several passes.
        descendingBufferHits = listOf(1, 2, 3, 1000)[Math.floorMod(seed shr 5, 4)],
        // Without compaction, segments pile up past the number above which sources drop their blocks between reads.
        compaction = if ((seed shr 4) and 1L == 0L) CompactionOptions(enabled = false) else CompactionOptions(minMergeSegments = 2, bytesPerSecond = 0, maxRunBytes = 64, listMemoryBytes = 1024),
    )

    /** Fills [store] from [random]: inserts with types, deletes, type changes, flushes and compactions. */
    private fun fill(store: Engine, random: Random, documents: Int): MutableList<Long> {
        val live = ArrayList<Long>()
        repeat(documents) { i ->
            val count = random.nextInt(0, 6)
            val types = if (count >= 3 && random.nextBoolean()) mapOf("$.items[0]" to "Item", "$.items[2]" to "Item") else emptyMap()
            live += store.insert(doc(random, i, count), types).raw
            if (!store.options.compaction.enabled && i % 12 == 11) store.flush()
            when (random.nextInt(60)) {
                0, 1, 2 -> store.flush()
                3 -> if (store.options.compaction.enabled) store.compact()
                in 4..7 -> if (live.isNotEmpty()) store.delete(DocId(live.removeAt(random.nextInt(live.size))))
                in 8..10 -> retype(store, random, live, 1)
            }
        }
        return live
    }

    /** Gives [count] random nodes of [live] documents the type Item, or takes it away. */
    private fun retype(store: Engine, random: Random, live: List<Long>, count: Int) {
        if (live.isEmpty()) return
        repeat(count) {
            val docId = live[random.nextInt(live.size)]
            // Some picks aren't objects, or not nodes of the document at all; those assignments are refused.
            runCatching { store.assignType(NodeRef(DocId(docId), random.nextLong(1, 6)), if (random.nextBoolean()) "Item" else null) }
        }
    }

    private fun keyValueSearches(store: Engine): List<Search> {
        val searches = ArrayList<Search>()
        for (key in listOf("k", "tags", "s")) {
            for (value in 0..3) {
                val queryValue = if (key == "s") JsonQueryValue.Str("${"long ".repeat(value * 7 + 1)}${value % 2}") else JsonQueryValue.Num(value.toLong())
                for (mode in ResultMode.entries) for (nested in NestedHits.entries) for (type in listOf(null, "Item")) {
                    searches += Search("$key == $value $mode $nested type=$type") { order, after, limit, maxBytes ->
                        store.search(KeyValueQuery(key, queryValue, mode, nested, limit, maxBytes, after, type, order))
                    }
                }
            }
        }
        return searches
    }

    /**
     * JSONPath searches of every candidate kind: one term, a term within a path, ranges, AND and OR of terms, and
     * scans, one with scalar hits; and a query of 31 segments, the most a search takes.
     */
    private fun pathSearches(store: Engine): List<Search> {
        val searches = ArrayList<Search>()
        val paths = listOf(
            "$..[?@.k == 1]",
            "$.items[?@.k == 2]",
            "$..[?@.k < 2]",
            "$..[?@.k >= 3]",
            "$..[?@.k == 0 || @.k == 3]",
            "$..[?@.k == 1 && @.tags[0] == 2]",
            "$..[?@.k == 2].child",
            "$..child",
            "$..k",
            "$" + ".a".repeat(30) + "..a",
        )
        for (text in paths) {
            val path = JsonPath.compile(text)
            for (mode in ResultMode.entries) for (nested in NestedHits.entries) for (type in listOf(null, "Item")) {
                searches += Search("$text $mode $nested type=$type") { order, after, limit, maxBytes ->
                    store.search(JsonPathQuery(path, mode, nested, limit, maxBytes, after, type, ScanPolicy.ALLOW_SCAN, order))
                }
            }
        }
        return searches
    }

    /** Checks every search of [searches] on [store], resuming cursors after changes made by [change]. */
    private fun check(store: Engine, searches: List<Search>, random: Random, change: () -> Unit): Int {
        val resumes = ArrayList<Pair<Search, Cursor>>()
        var descendingHits = 0
        for (search in searches) {
            val ascending = hits(search.run(SearchOrder.ASCENDING, null, 100, Long.MAX_VALUE))
            val descending = hits(search.run(SearchOrder.DESCENDING, null, 100, Long.MAX_VALUE))
            assertEquals(ascending.reversed(), descending, search.label)
            descendingHits += descending.size
            if (descending.isEmpty()) continue

            // Pages cut by a random limit and byte budget, with a new search resumed from some of their cursors.
            val limit = if (random.nextBoolean()) random.nextInt(1, 4) else random.nextInt(1, descending.size + 2)
            val maxBytes = if (random.nextBoolean()) Long.MAX_VALUE else random.nextLong(1, 2 * descending.maxOf { it.encodedSize } + 1)
            val paged = ArrayList<Hit>()
            var checked = 0
            search.run(SearchOrder.DESCENDING, null, limit, maxBytes).use { result ->
                while (true) {
                    val page = result.nextPage() ?: break
                    assertTrue(page.hits.size in 1..limit, search.label)
                    if (page.hits.size > 1) assertTrue(page.hits.sumOf { it.encodedSize } <= maxBytes, search.label)
                    page.hits.mapTo(paged) { Hit(it.node, it.type, it.encodedSize, it.path) }
                    val cursor = page.cursor ?: continue
                    assertEquals(page.hits.last().node, NodeRef(cursor.docId, cursor.nodePre), search.label)
                    assertFalse(cursor.mayMissLateCommits, search.label)
                    assertEquals(cursor, Cursor.parse(cursor.toString()), search.label)
                    if (checked < 3 && random.nextInt(4) == 0) {
                        checked++
                        val rest = hits(search.run(SearchOrder.DESCENDING, Cursor.parse(cursor.toString()), 1000, Long.MAX_VALUE))
                        assertEquals(descending.drop(paged.size), rest, "${search.label} resumed after ${paged.size}")
                    }
                    if (random.nextInt(4) == 0) resumes += search to cursor
                }
            }
            assertEquals(descending, paged, "${search.label} limit $limit maxBytes $maxBytes")
        }
        assertTrue(descendingHits > 0)
        val segments = store.indexes.versions.current().postingSegments.size

        // Results opened before the changes keep reading their snapshot in both orders: the type index's older versions
        // decide, walking backwards as forwards.
        val open = searches.shuffled(random).take(8).map { search ->
            Triple(search, search.run(SearchOrder.ASCENDING, null, 1000, Long.MAX_VALUE), search.run(SearchOrder.DESCENDING, null, 1000, Long.MAX_VALUE))
        }
        // After more inserts, deletes and flushes, a cursor still returns exactly the hits before it.
        change()
        for ((search, ascending, descending) in open) assertEquals(hits(ascending).reversed(), hits(descending), "${search.label} on its snapshot")
        for ((search, cursor) in resumes) {
            val now = hits(search.run(SearchOrder.ASCENDING, null, 1000, Long.MAX_VALUE))
            val before = now.filter { it.node < NodeRef(cursor.docId, cursor.nodePre) }.reversed()
            assertEquals(before, hits(search.run(SearchOrder.DESCENDING, cursor, 1000, Long.MAX_VALUE)), "${search.label} resumed at $cursor after changes")
        }
        println("$descendingHits hits descending, $segments posting segments, ${resumes.size} cursors resumed after changes, $extraPasses extra passes")
        return descendingHits
    }

    @Test
    fun `key-value searches descending are ascending reversed`() {
        forAll("descending key-value", Gen { random, size -> random.nextLong() to 20 + random.nextInt(size, 3 * size + 1) }, iterations = 20, maxSize = 150) { (seed, documents) ->
            TestEngines.open(options(seed)).use { store ->
                val random = Random(seed)
                val live = fill(store, random, documents)
                check(store, keyValueSearches(store), random) {
                    fill(store, random, 20)
                    retype(store, random, live, 30)
                    repeat(5) { if (live.isNotEmpty()) store.delete(DocId(live.removeAt(random.nextInt(live.size)))) }
                    if (random.nextBoolean()) store.flush()
                }
            }
        }
    }

    @Test
    fun `JSONPath searches descending are ascending reversed`() {
        forAll("descending JSONPath", Gen { random, size -> random.nextLong() to 20 + random.nextInt(size, 2 * size + 1) }, iterations = 12, maxSize = 100) { (seed, documents) ->
            TestEngines.open(options(seed)).use { store ->
                val random = Random(seed)
                val live = fill(store, random, documents)
                // Chains 34 deep, where the query of 31 segments selects four nodes each.
                repeat(3) { live += store.insert("{\"a\":".repeat(34) + "1" + "}".repeat(34)).raw }
                check(store, pathSearches(store), random) {
                    fill(store, random, 20)
                    retype(store, random, live, 30)
                    repeat(5) { if (live.isNotEmpty()) store.delete(DocId(live.removeAt(random.nextInt(live.size)))) }
                    if (random.nextBoolean()) store.flush()
                }
            }
        }
    }

    @Test
    fun `a cursor may miss a late commit only above it when descending`() {
        TestEngines.open(EngineOptions(CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 4096), smallInsertThreshold = 256, log = TestEngines.LOG)).use { store ->
            val doc = """{"k":1}"""
            val below = List(5) { store.insert(doc).raw }
            val streaming = CountDownLatch(1)
            val release = CountDownLatch(1)
            val late = store.registry.peekNextDocId()
            val executor = Executors.newSingleThreadExecutor()
            try {
                val bytes = """{"k":1,"pad":"${"x".repeat(4000)}"}""".toByteArray()
                val insert = executor.submit(Callable { store.insert(HalfwayStream(bytes) { streaming.countDown(); release.await() }).raw })
                assertTrue(streaming.await(30, TimeUnit.SECONDS))
                val above = List(2) { store.insert(doc).raw }
                assertTrue(above.first() > late)
                val query = KeyValueQuery("k", JsonQueryValue.Num(1), limit = 1)
                fun cursors(order: SearchOrder) = store.search(query.copy(order = order)).use { result ->
                    generateSequence { result.nextPage() }.mapNotNull { it.cursor }.associate { it.docId.raw to it }
                }
                // The late docId is in flight: an ascending cursor above it may miss it, a descending one below it.
                val ascending = cursors(SearchOrder.ASCENDING)
                val descending = cursors(SearchOrder.DESCENDING)
                for (docId in below) assertFalse(ascending.getValue(docId).mayMissLateCommits, "ascending at $docId")
                assertTrue(ascending.getValue(above.first()).mayMissLateCommits)
                for (docId in above) assertFalse(descending.getValue(docId).mayMissLateCommits, "descending at $docId")
                for (docId in below.drop(1)) assertTrue(descending.getValue(docId).mayMissLateCommits, "descending at $docId")

                release.countDown()
                assertEquals(late, insert.get(30, TimeUnit.SECONDS))
                fun resumed(cursor: Cursor) = hits(store.search(query.copy(after = cursor, limit = 100, order = SearchOrder.DESCENDING))).map { it.node.docId.raw }
                // Resumed above the late commit, the search still reaches it; resumed below, it is behind the cursor.
                assertEquals(listOf(above.first(), late) + below.reversed(), resumed(descending.getValue(above.last())))
                assertEquals(below.reversed().drop(1), resumed(descending.getValue(below.last())))
            } finally {
                release.countDown()
                executor.shutdown()
            }
        }
    }

    @Test
    fun `a cursor reports no late commit for an insert that failed`() {
        TestEngines.open(EngineOptions(CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 4096), log = TestEngines.LOG)).use { store ->
            val doc = """{"k":1}"""
            val below = List(3) { store.insert(doc).raw }
            val failed = store.registry.peekNextDocId()
            // A small insert whose document turns out to be cut off, while other inserts commit in the middle of it: the
            // snapshots they publish have its docId in flight.
            val cut = """{"k":1,"pad":"${"x".repeat(1000)}"""".toByteArray()
            val above = ArrayList<Long>()
            assertFailsWith<DocumentRejectedException> { store.insert(HalfwayStream(cut) { repeat(2) { above += store.insert(doc).raw } }) }
            assertTrue(above.first() > failed)
            assertFalse(store.registry.current().visibility.isInFlight(failed))
            val query = KeyValueQuery("k", JsonQueryValue.Num(1), limit = 1)
            for (order in SearchOrder.entries) {
                store.search(query.copy(order = order)).use { result ->
                    val cursors = generateSequence { result.nextPage() }.mapNotNull { it.cursor }.toList()
                    assertEquals(below.size + above.size - 1, cursors.size, "$order")
                    for (cursor in cursors) assertFalse(cursor.mayMissLateCommits, "$order at ${cursor.docId}")
                }
            }
        }
    }

    /** Streams [bytes], calling [atHalf] once halfway through. */
    private class HalfwayStream(private val bytes: ByteArray, private val atHalf: () -> Unit) : InputStream() {
        private var position = 0
        private var fired = false

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!fired && position >= bytes.size / 2) {
                fired = true
                atHalf()
            }
            if (position == bytes.size) return -1
            val n = minOf(len, 256, bytes.size - position)
            System.arraycopy(bytes, position, b, off, n)
            position += n
            return n
        }
    }

    @Test
    fun `newest copy of a key in one hit`() {
        TestEngines.open(options(1)).use { store ->
            // Upserts by insert-then-delete, with the delete sometimes lost (as after a crash between the two).
            val random = Random(7)
            var newest = -1L
            repeat(400) { i ->
                val previous = newest
                newest = store.insert("""{"key":"session","value":$i}""").raw
                if (previous >= 0 && random.nextInt(5) != 0) store.delete(DocId(previous))
                // The first segment holds 300 copies of the key: a posting list of three blocks.
                if (i == 299) store.flush()
                val query = KeyValueQuery("key", JsonQueryValue.Str("session"), limit = 1, order = SearchOrder.DESCENDING)
                store.search(query).use { result ->
                    val page = result.nextPage()!!
                    assertEquals(newest, page.hits.single().docId.raw)
                    assertEquals("""{"key":"session","value":$i}""", page.hits.single().readJsonAsString())
                    if (i == 0) assertNull(page.cursor)
                }
            }
            // Every copy still visible, through the segment's three blocks and the memtable, both ways.
            val all = KeyValueQuery("key", JsonQueryValue.Str("session"), limit = 7)
            val ascending = hits(store.search(all))
            assertTrue(ascending.size > 50)
            assertEquals(ascending.reversed(), hits(store.search(all.copy(order = SearchOrder.DESCENDING))))
        }
    }
}
