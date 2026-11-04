package app.oreshkov.delva

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.PriorityQueue
import kotlin.io.path.deleteRecursively
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The quick start of README.md and the snippets of project/GUIDE.md, kept compiling and working. Change them together.
 */
class GuideExampleTest {
    private val dir: Path = Files.createTempDirectory("delva-guide")

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    @Test
    fun `the quick start works`() {
        JsonStore.openOrCreate(dir.resolve("store")).use { store ->
            val blocking = store.blocking()

            val id = blocking.insert("""{"name":"ada","tags":["math","engines"],"score":42}""")

            val documents = blocking.search(KeyValueQuery("name", JsonQueryValue.Str("ada"))).use { result ->
                result.iterator().asSequence().map { it.readJsonAsString() }.toList()
            }
            assertEquals(listOf("""{"name":"ada","tags":["math","engines"],"score":42}"""), documents)

            // An element of an array value matches the array's member too.
            val nodes = blocking.search(KeyValueQuery("tags", JsonQueryValue.Str("math"), ResultMode.CONTAINING_ELEMENT)).use { result ->
                result.iterator().asSequence().map { it.node }.toList()
            }
            assertEquals(listOf(NodeRef(id, 0)), nodes)

            assertTrue(blocking.delete(id))
            assertTrue(blocking.search(KeyValueQuery("name", JsonQueryValue.Str("ada"))).use { !it.iterator().hasNext() })
        }
    }

    @Test
    fun `a secondary reads beside the writer and refreshes, as the guide shows`() {
        val path = dir.resolve("store")
        JsonStore.openOrCreate(path).use { store ->
            store.blocking().insert("""{"name":"ada"}""")

            JsonStore.openSecondary(path).use { reader ->
                val db = reader.blocking()
                assertEquals(1L, db.count(KeyValueQuery("name", JsonQueryValue.Str("ada"))))
                assertTrue(!db.refresh())
                store.blocking().insert("""{"name":"ada","again":true}""")
                assertEquals(1L, db.count(KeyValueQuery("name", JsonQueryValue.Str("ada"))))
                assertTrue(db.refresh()) // true when the writer wrote something since
                assertEquals(2L, db.count(KeyValueQuery("name", JsonQueryValue.Str("ada"))))
                val readable: ReadableJsonStore = reader
                assertEquals(2L, readable.info().approximateDocumentCount)
            }
        }
    }

    @Test
    fun `a document is inserted from a file, as the guide shows`() {
        val file = dir.resolve("catalog.json")
        Files.writeString(file, """{"items":[{"sku":"a-1","price":10},{"sku":"b-2","price":20}]}""")
        JsonStore.openOrCreate(dir.resolve("from-file")).use { store ->
            val id = store.blocking().insert(file)
            val hits = store.blocking().search(KeyValueQuery("sku", JsonQueryValue.Str("b-2"), ResultMode.CONTAINING_ELEMENT)).use { result ->
                result.iterator().asSequence().map { it.readJsonAsString() }.toList()
            }
            assertEquals(listOf("""{"sku":"b-2","price":20}"""), hits)
            assertEquals(1L, id.raw)
        }
    }

    @Test
    fun `a replace swaps in a new version and conflicts once the old one is gone, as the guide shows`() {
        JsonStore.openOrCreate(dir.resolve("replace")).use { store ->
            val id = store.blocking().insert("""{"session":"s-42","state":"open"}""")

            @OptIn(ExperimentalDelvaApi::class)
            val next = store.blocking().replace(listOf(id), """{"session":"s-42","state":"closed"}""")

            val states = store.blocking().search(KeyValueQuery("session", JsonQueryValue.Str("s-42"))).use { result ->
                result.iterator().asSequence().map { it.docId to it.readJsonAsString() }.toList()
            }
            assertEquals(listOf(next to """{"session":"s-42","state":"closed"}"""), states)
            val conflict = assertFailsWith<WriteConflictException> {
                @OptIn(ExperimentalDelvaApi::class)
                store.blocking().replace(listOf(id), """{"session":"s-42","state":"lost"}""")
            }
            assertEquals(listOf(id), conflict.conflicting)
        }
    }

    @Test
    fun `a store with a document key upserts, gets and lists by key, as the guide shows`() {
        var id: DocId? = null
        var keys: List<String?> = emptyList()
        @OptIn(ExperimentalDelvaApi::class)
        JsonStore.create(dir.resolve("keys"), CreationOptions().withDocumentKey("$.session")).use { sessions ->
            val db = sessions.blocking()
            db.upsert("""{"session":"s-42","state":"open"}""")
            db.upsert("""{"session":"s-42","state":"closed"}""") // replaces the first
            id = db.get("s-42")
            keys = db.search(DocumentKeyQuery(prefix = "s-")).use { result -> result.iterator().asSequence().map { it.documentKey }.toList() }
            assertFailsWith<WriteConflictException> { db.insert("""{"session":"s-42","state":"lost"}""") }
            assertEquals("""{"session":"s-42","state":"closed"}""", db.search(DocumentKeyQuery("s-42")).use { it.iterator().next().readJsonAsString() })
        }
        assertEquals(DocId(2), id)
        assertEquals(listOf<String?>("s-42"), keys)
    }

    @Test
    fun `the newest version of a key is one hit, as the guide shows`() {
        JsonStore.openOrCreate(dir.resolve("newest")).use { store ->
            val old = store.blocking().insert("""{"session":"s-42","user":"ada"}""")
            store.blocking().insert("""{"session":"s-7","user":"bob"}""")
            store.blocking().insert("""{"session":"s-42","user":"grace"}""")
            store.blocking().delete(old)
            store.blocking().insert("""{"session":"s-42","user":"alan"}""")
            val current = store.blocking().search(KeyValueQuery("session", JsonQueryValue.Str("s-42"), limit = 1, order = SearchOrder.DESCENDING)).use { result ->
                result.nextPage()?.hits?.single()?.readJsonAsString()
            }
            assertEquals("""{"session":"s-42","user":"alan"}""", current)
        }
    }

    @Test
    fun `every hit of the newest version is one bounded search, as the guide shows`() {
        JsonStore.openOrCreate(dir.resolve("newest-items")).use { store ->
            store.blocking().insert("""{"session":"s-42","items":[{"sku":"A1"},{"sku":"B2"}]}""")
            store.blocking().insert("""{"session":"s-42","items":[{"sku":"A1"},{"sku":"C3"},{"sku":"D4"}]}""")
            store.blocking().insert("""{"session":"s-7","items":[{"sku":"E5"}]}""")

            @OptIn(ExperimentalDelvaApi::class)
            val newestItems = JsonPathQuery(JsonPath.compile("$.items[*].sku"), order = SearchOrder.DESCENDING)
                .withDocumentFilter(DocumentFilter.compile("@.session == 's-42'"))
                .withMaxDocuments(1)
            val skus = store.blocking().search(newestItems).use { result -> result.iterator().asSequence().map { it.readJsonAsString() }.toList() }
            assertEquals(listOf("\"D4\"", "\"C3\"", "\"A1\""), skus)

            // Without the bound: the descending hits up to the first of another document, from one result.
            val unbounded = newestItems.withMaxDocuments(null)
            val looped = store.blocking().search(unbounded).use { result ->
                val hits = result.iterator()
                val first = hits.next()
                buildList {
                    add(first.readJsonAsString())
                    while (hits.hasNext()) {
                        val hit = hits.next()
                        if (hit.docId != first.docId) break
                        add(hit.readJsonAsString())
                    }
                }
            }
            assertEquals(skus, looped)
        }
    }

    @Test
    fun `types come with each document of insertAll and are listed as a flow, as the guide shows`() = runTest {
        JsonStore.openOrCreate(dir.resolve("types")).use { store ->
            val outcomes = store.blocking().insertAll(
                listOf(
                    InsertSource("""{"order":1,"customer":{"name":"ada"}}""".byteInputStream(), mapOf("$" to "Order", "$.customer" to "Customer")),
                    InsertSource("""{"invoice":7}""".byteInputStream(), mapOf("$" to "Invoice")),
                ),
            )
            val (order, invoice) = outcomes.map { (it as InsertOutcome.Inserted).docId }

            val customers = mutableListOf<NodeRef>()
            store.objectsOfTypeFlow("Customer").collect { node -> customers += node }
            assertEquals(listOf(NodeRef(order, 2)), customers)
            assertEquals(listOf(NodeRef(invoice, 0)), store.objectsOfType("Invoice"))
            assertEquals(1L, store.countOfType("Order"))
            assertEquals(1L, store.clearType("Order"))
            assertEquals(0L, store.countOfType("Order"))
        }
    }

    @Test
    fun `JSONPath selects and searches as the guide shows`() {
        JsonStore.openOrCreate(dir.resolve("jsonpath")).use { store ->
            val blocking = store.blocking()
            val order = blocking.insert("""{"items":[{"sku":"A1","qty":2},{"sku":"B2","qty":0}]}""")

            val selected = blocking.select(NodeRef(order, 0), JsonPath.compile("$.items[*].sku")).use { result ->
                result.iterator().asSequence().map { "${it.path} = ${it.readJsonAsString()}" }.toList()
            }
            assertEquals(listOf("""$['items'][0]['sku'] = "A1"""", """$['items'][1]['sku'] = "B2""""), selected)

            val lowStock = JsonPathQuery(JsonPath.compile("$..[?@.qty < 1].sku"))
            assertEquals("QueryPlan('qty' < 1)", blocking.explain(lowStock).toString())
            val hits = blocking.search(lowStock).use { result -> result.iterator().asSequence().map { "${it.path} = ${it.readJsonAsString()}" }.toList() }
            assertEquals(listOf("""$['items'][1]['sku'] = "B2""""), hits)

            val paths = blocking.search(KeyValueQuery("sku", JsonQueryValue.Str("B2"), ResultMode.CONTAINING_ELEMENT)).use { result ->
                result.iterator().asSequence().map { it.path }.toList()
            }
            assertEquals(listOf("$['items'][1]"), paths)
            assertEquals("$['items'][1]['sku']", blocking.pathOf(NodeRef(order, 6)))

            assertFailsWith<QueryRejectedException> { blocking.search(JsonPathQuery(JsonPath.compile("$..sku"))) }
            val all = blocking.search(JsonPathQuery(JsonPath.compile("$..sku"), scan = ScanPolicy.ALLOW_SCAN)).use { it.iterator().asSequence().count() }
            assertEquals(2, all)

            @OptIn(ExperimentalDelvaApi::class)
            val warnings = JsonPath.compile("$..[?match(@.sku, '(?i)a1')]").warnings.map { "offset ${it.offset}: ${it.reason}" }
            assertTrue(warnings.single().startsWith("offset 18: '(?i)a1' isn't I-Regexp (RFC 9485), so match() is false for every value: "), warnings.single())
            val caseless = JsonPathQuery(JsonPath.compile("$..[?match(@.sku, '[Aa]1')].qty"), scan = ScanPolicy.ALLOW_SCAN)
            assertEquals(listOf("2"), blocking.search(caseless).use { result -> result.iterator().asSequence().map { it.readJsonAsString() }.toList() })

            blocking.insert("""{"kind":"order","items":[{"sku":"C3","qty":5}]}""")
            @OptIn(ExperimentalDelvaApi::class)
            val orderSkus = JsonPathQuery(JsonPath.compile("$..sku")).withDocumentFilter(DocumentFilter.compile("@.kind == 'order'"))
            assertEquals("QueryPlan('kind' == 'order')", blocking.explain(orderSkus).toString())
            val orders = blocking.search(orderSkus).use { result -> result.iterator().asSequence().map { "${it.path} = ${it.readJsonAsString()}" }.toList() }
            assertEquals(listOf("""$['items'][0]['sku'] = "C3""""), orders)

            @OptIn(ExperimentalDelvaApi::class)
            val qtyOf = JsonPathTemplate.compile("$..[?@.sku == \${sku}].qty")
            @OptIn(ExperimentalDelvaApi::class)
            val a1 = JsonPathQuery(qtyOf.bind("sku" to JsonQueryValue.Str("A1")))
            assertEquals("$..[?@.sku == 'A1'].qty", a1.path.toString())
            assertEquals(listOf("2"), blocking.search(a1).use { result -> result.iterator().asSequence().map { it.readJsonAsString() }.toList() })
            // A value with quotes in it stays a value.
            @OptIn(ExperimentalDelvaApi::class)
            val injected = JsonPathQuery(qtyOf.bind("sku" to JsonQueryValue.Str("x' || @.qty > -1 || 'x")))
            assertEquals(0L, blocking.count(injected))

            val pretty = blocking.select(NodeRef(order, 0), JsonPath.compile("$")).use { result ->
                result.iterator().next().readJsonAsString(JsonFormat(lineWidth = 32))
            }
            assertEquals("{\n  \"items\": [\n    {\"sku\": \"A1\", \"qty\": 2},\n    {\"sku\": \"B2\", \"qty\": 0}\n  ]\n}", pretty)
            val outline = blocking.select(NodeRef(order, 0), JsonPath.compile("$")).use { result ->
                result.iterator().next().readJsonAsString(JsonFormat(maxLevels = 0))
            }
            assertEquals("""{"items": [...]}""", outline)
        }
    }

    @Test
    fun `the nearest embeddings are found in application code, as the guide shows`() {
        JsonStore.create(dir.resolve("chunks"), CreationOptions(excludedKeys = setOf("embedding"))).use { chunks ->
            val db = chunks.blocking()
            val near = db.insert("""{"source":"doc-17","chunk":3,"model":"embed-v1","text":"…","embedding":[0.0123,-0.441,0.2087]}""")
            val far = db.insert("""{"source":"doc-17","chunk":4,"model":"embed-v1","text":"…","embedding":[-0.0123,0.441,-0.2087]}""")
            val twin = db.insert("""{"source":"doc-18","chunk":0,"model":"embed-v1","text":"…","embedding":[0.0123,-0.441,0.2087]}""")
            db.insert("""{"source":"doc-18","chunk":1,"model":"embed-v2","text":"…","embedding":[0.0123,-0.441,0.2087,0.5]}""")
            val queryVector = floatArrayOf(0.01f, -0.4f, 0.2f)

            @OptIn(ExperimentalDelvaApi::class)
            val hits = nearest(db, queryVector, k = 10, DocumentFilter.compile("@.model == 'embed-v1'"))

            assertEquals(listOf(near, twin, far), hits.map { it.second }, "ties in docId order, the other model left out")
            assertTrue(hits[0].first < 1e-3 && hits[2].first > 1.999, "$hits")
            assertEquals(listOf(near), nearest(db, queryVector, k = 1, DocumentFilter.compile("@.model == 'embed-v1'")).map { it.second })
            assertFailsWith<QueryRejectedException> { db.count(KeyValueQuery("embedding", JsonQueryValue.Num(java.math.BigDecimal("0.0123")))) }
        }
    }

    @Test
    fun `the suspending API offers the same operations`() = runTest {
        JsonStore.openOrCreate(dir.resolve("suspending")).use { store ->
            val id = store.insert("""{"name":"ada"}""")
            assertTrue(store.verify().intact)
            assertTrue(store.delete(id))
        }
    }
}

/** The guide's top k in application code: the [k] candidates of [filter] nearest to [query], nearest first. */
@OptIn(ExperimentalDelvaApi::class)
private fun nearest(db: BlockingReadableJsonStore, query: FloatArray, k: Int, filter: DocumentFilter): List<Pair<Double, DocId>> {
    val nearestFirst = compareBy<Pair<Double, DocId>>({ it.first }, { it.second.raw })
    val best = PriorityQueue(k, nearestFirst.reversed())                      // the farthest of the best k on top
    db.search(JsonPathQuery(JsonPath.compile("$.embedding")).withDocumentFilter(filter)).use { result ->
        for (hit in result) {
            val numbers = hit.readJsonAsString().removeSurrounding("[", "]").split(',')
            val candidate = cosineDistance(query, FloatArray(numbers.size) { numbers[it].toFloat() }) to hit.docId
            if (best.size < k) {
                best += candidate
            } else if (nearestFirst.compare(candidate, best.peek()) < 0) {
                best.poll()
                best += candidate
            }
        }
    }
    return best.sortedWith(nearestFirst)
}

private fun cosineDistance(a: FloatArray, b: FloatArray): Double {
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
