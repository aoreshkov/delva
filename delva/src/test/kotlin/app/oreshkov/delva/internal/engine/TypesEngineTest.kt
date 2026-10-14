package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.Durability
import app.oreshkov.delva.EngineFailedException
import app.oreshkov.delva.InsertOutcome
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.InvalidJsonPathException
import app.oreshkov.delva.InvalidTypeNameException
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NestedHits
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.TypeInfo
import app.oreshkov.delva.internal.facade.CoroutineJsonStore
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.query.TypeQueryPlan
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Object types on the durable engine. The oracle comparisons are in [EngineModelTest]. */
class TypesEngineTest {
    private val dir = TestEngines.DIR

    private fun options(
        t: Int = 256,
        threshold: Long = EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD,
        memtableBytes: Long = EngineOptions.DEFAULT_MEMTABLE_BYTES,
        plan: TypeQueryPlan = TypeQueryPlan.AUTO,
        durability: Durability = Durability.SyncEachBatch,
    ) = EngineOptions(
        CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024), durability = durability),
        sorterBytes = 4096, keyArenaBytes = 1024, smallInsertThreshold = threshold, keySeed = 7, log = TestEngines.LOG,
        memtableBytes = memtableBytes, backgroundFlush = false, typeQueryPlan = plan,
    )

    // [0]{"order":[1]{"id":[2]7,"items":[3][[4]{"sku":[5]"A1","qty":[6]2},[7]{"sku":[8]"B2","qty":[9]1.0}],"tags":[10][[11]"x",[12]"y"]}}
    private val example = """{"order":{"id":7,"items":[{"sku":"A1","qty":2},{"sku":"B2","qty":1.0}],"tags":["x","y"]}}"""
    private val exampleTypes = mapOf("$.order" to "Order", "$.order.items[0]" to "Item", "$[\"order\"][\"items\"][1]" to "Item")

    private fun Engine.hits(query: KeyValueQuery): List<Triple<Long, Long, String?>> =
        search(query).use { result -> result.iterator().asSequence().map { Triple(it.node.docId.raw, it.node.nodePre, it.type) }.toList() }

    private fun node(docId: Long, pre: Long) = NodeRef(DocId(docId), pre)

    @Test
    fun `types given with an insert are readable at every fragment size, promoted or not, and survive reopen and flush`() {
        for (t in listOf(32, 4096, 64 * 1024)) {
            for (threshold in listOf(0L, EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD)) {
                val label = "T=$t threshold=$threshold"
                val fs = SimulatedFileSystemIo()
                val options = options(t, threshold)
                val docId = TestEngines.open(options, fs).use { store ->
                    val docId = store.insert(example, exampleTypes).raw
                    check(store, docId, label)
                    docId
                }
                fs.restart()
                TestEngines.open(options, fs).use { store ->
                    check(store, docId, "$label after replay")
                    store.flush()
                }
                fs.restart()
                TestEngines.open(options, fs).use { store ->
                    assertEquals(0, store.recoveryStats.replayedCommits, label)
                    assertEquals(1, store.indexes.versions.current().typeSegments.size, label)
                    check(store, docId, "$label from segments")
                }
                assertTrue(fs.files().any { LogFiles.parseTypeDictionary(it.fileName.toString()) != null }, "$label: dictionary file")
            }
        }
    }

    private fun check(store: Engine, docId: Long, label: String) {
        assertEquals("Order", store.typeOf(node(docId, 1)), label)
        assertEquals("Item", store.typeOf(node(docId, 4)), label)
        assertEquals("Item", store.typeOf(node(docId, 7)), label)
        assertNull(store.typeOf(node(docId, 0)), label)
        assertNull(store.typeOf(node(docId, 2)), "$label: a scalar has no type")
        assertEquals(listOf(node(docId, 4), node(docId, 7)), store.objectsOfType("Item"), label)
        assertEquals(listOf(node(docId, 7)), store.objectsOfType("Item", after = node(docId, 4)), label)
        assertEquals(listOf(node(docId, 4)), store.objectsOfType("Item", limit = 1), label)
        assertEquals(2, store.countOfType("Item"), label)
        assertEquals(0, store.countOfType("Nothing"), label)
        assertEquals(listOf(TypeInfo("Item", 2), TypeInfo("Order", 1)), store.listTypes(), label)
        val qty = KeyValueQuery("qty", JsonQueryValue.Num(1), ResultMode.CONTAINING_ELEMENT)
        assertEquals(listOf(Triple(docId, 7L, "Item")), store.hits(qty), label)
        assertEquals(listOf(Triple(docId, 0L, null)), store.hits(qty.copy(mode = ResultMode.WHOLE_DOCUMENT, type = "Item")), label)
        assertEquals(emptyList(), store.hits(qty.copy(type = "Order")), label)
        assertEquals(listOf(Triple(docId, 1L, "Order")), store.hits(KeyValueQuery("tags", JsonQueryValue.Str("y"), ResultMode.CONTAINING_ELEMENT, type = "Order")), label)
    }

    @Test
    fun `invalid paths, names and unmatched paths fail as specified`() {
        TestEngines.open(options()).use { store ->
            val next = store.registry.peekNextDocId()
            assertFailsWith<InvalidJsonPathException> { store.insert(example, mapOf("order" to "Order")) }
            assertFailsWith<IllegalArgumentException> { store.insert(example, mapOf("$.order" to "A", "$[\"order\"]" to "B")) }
            assertFailsWith<InvalidTypeNameException> { store.insert(example, mapOf("$.order" to "")) }
            assertFailsWith<InvalidTypeNameException> { store.insert(example, mapOf("$.order" to "a")) }
            assertFailsWith<InvalidTypeNameException> { store.insert(example, mapOf("$.order" to "x".repeat(257))) }
            assertEquals(next, store.registry.peekNextDocId(), "nothing was read, no docId used")
            for (path in listOf("$.missing", "$.order.id", "$.order.items[2]", "$.order.tags")) {
                val e = assertFailsWith<DocumentRejectedException>(path) { store.insert(example, mapOf(path to "T")) }
                assertEquals(RejectReason.TYPE_PATH_UNMATCHED, e.reason, path)
                assertEquals(path, e.jsonPath)
            }
            assertEquals(emptyList(), store.listTypes(), "rejected inserts define no types")
            val rejectedDocId = store.registry.peekNextDocId() - 1
            val docId = store.insert(example).raw

            assertFailsWith<IllegalArgumentException> { store.assignType(node(docId, 2), "T") }
            assertFailsWith<IllegalArgumentException> { store.assignType(node(docId, 3), "T") }
            assertFailsWith<IllegalArgumentException> { store.assignType(node(docId, 13), "T") }
            assertFailsWith<IllegalArgumentException> { store.typeOf(node(docId, 13)) }
            assertFailsWith<DocumentNotFoundException> { store.assignType(node(rejectedDocId, 0), "T") }
            assertFailsWith<DocumentNotFoundException> { store.typeOf(node(docId + 100, 0)) }
            assertFailsWith<InvalidTypeNameException> { store.assignType(node(docId, 1), "") }
            assertFailsWith<InvalidTypeNameException> { store.objectsOfType("") }
            assertFailsWith<InvalidTypeNameException> { store.search(KeyValueQuery("id", JsonQueryValue.Num(7), type = "\n")) }
            assertFailsWith<IllegalArgumentException> { store.objectsOfType("T", limit = 0) }
            // One failing assignment fails the whole call.
            assertFailsWith<IllegalArgumentException> { store.assignTypes(listOf(node(docId, 1) to "Order", node(docId, 2) to "Order")) }
            assertNull(store.typeOf(node(docId, 1)))
            assertEquals(emptyList(), store.listTypes())
        }
    }

    @Test
    fun `assignments reassign and remove, later ones in a call see earlier ones, and no-ops write nothing`() {
        val fs = SimulatedFileSystemIo()
        val options = options()
        TestEngines.open(options, fs).use { store ->
            val docId = store.insert(example).raw
            val order = node(docId, 1)
            store.assignType(order, "A")
            assertEquals("A", store.typeOf(order))
            store.assignType(order, "B")
            assertEquals(listOf(order), store.objectsOfType("B"))
            assertEquals(emptyList(), store.objectsOfType("A"), "the tombstone hides the old type")

            val lsn = store.writer.lsn
            store.assignType(order, "B")
            store.assignType(node(docId, 4), null)
            store.assignTypes(listOf(order to "A", order to "B"))
            assertEquals(lsn + 2, store.writer.lsn, "only the call that changes and changes back writes")

            val beforeLsn = store.writer.lsn
            store.assignTypes(listOf(order to "C", order to null, node(docId, 4) to "C", order to "C"))
            assertEquals(beforeLsn + 5, store.writer.lsn, "TYPEDEF C, then four TYPE records")
            assertEquals("C", store.typeOf(order))
            assertEquals(listOf(order, node(docId, 4)), store.objectsOfType("C"))
            store.assignType(order, null)
            assertNull(store.typeOf(order))
            assertEquals(1, store.countOfType("C"))
            assertEquals(listOf("A", "B", "C"), store.listTypes().map { it.name })
        }
        fs.restart()
        TestEngines.open(options, fs).use { store ->
            assertNull(store.typeOf(node(1, 1)))
            assertEquals(listOf(node(1, 4)), store.objectsOfType("C"))
            assertEquals(0, store.countOfType("B"))
        }
    }

    @Test
    fun `a search sees the types of its snapshot through reassignments and flushes`() {
        TestEngines.open(options(memtableBytes = 0)).use { store ->
            val docs = (0 until 20).map { store.insert(example, exampleTypes).raw }
            val qty = KeyValueQuery("qty", JsonQueryValue.Num(1), ResultMode.CONTAINING_ELEMENT, limit = 1)
            val old = store.search(qty.copy(type = "Item"))
            val oldUnfiltered = store.search(qty)
            val first = old.nextPage()!!.hits.single()
            // Reassign in the active memtable, then flush it: the flushed segment only has the newest versions.
            for (d in docs) store.assignType(node(d, 7), "Other")
            store.flush()
            for (d in docs) store.assignType(node(d, 7), "Third")
            store.flush()
            assertEquals("Item", first.type)
            assertEquals(docs.map { Triple(it, 7L, "Item") }, (listOf(first) + old.iterator().asSequence()).map { Triple(it.node.docId.raw, it.node.nodePre, it.type) })
            assertEquals(docs.map { "Item" }, oldUnfiltered.iterator().asSequence().map { it.type }.toList())
            old.close()
            oldUnfiltered.close()
            assertEquals(emptyList(), store.hits(qty.copy(type = "Item")))
            assertEquals(docs.map { Triple(it, 7L, "Third") }, store.hits(qty.copy(type = "Third")))
            assertEquals(docs.size.toLong(), store.countOfType("Item"), "the objects at pre 4 are still Items")
        }
    }

    @Test
    fun `term-driven and type-driven plans give the same hits`() {
        val random = Random(99)
        val fs = SimulatedFileSystemIo()
        val base = options(t = 64, memtableBytes = 16L shl 10)
        val docIds = ArrayList<Long>()
        TestEngines.open(base, fs).use { store ->
            repeat(300) { i ->
                val items = (0 until random.nextInt(0, 6)).joinToString(",") { """{"k":${random.nextInt(3)},"inner":{"k":${random.nextInt(3)}}}""" }
                val json = """{"k":${random.nextInt(3)},"items":[$items]}"""
                val types = HashMap<String, String>()
                if (random.nextBoolean()) types["$"] = listOf("Rare", "Common")[random.nextInt(2)]
                docIds += store.insert(json, types).raw
            }
            // Assign some nested objects afterwards, with flushes in between.
            for (docId in docIds) {
                val pres = store.hits(KeyValueQuery("k", JsonQueryValue.Num(random.nextInt(3).toLong()), ResultMode.CONTAINING_ELEMENT, limit = 1000)).filter { it.first == docId }.map { it.second }
                if (pres.isNotEmpty() && random.nextInt(3) == 0) store.assignType(node(docId, pres.random(random)), if (random.nextInt(5) == 0) "Rare" else "Common")
            }
        }
        val results = HashMap<TypeQueryPlan, List<List<Triple<Long, Long, String?>>>>()
        for (plan in TypeQueryPlan.entries) {
            fs.restart()
            TestEngines.open(options(t = 64, memtableBytes = 16L shl 10, plan = plan), fs).use { store ->
                val list = ArrayList<List<Triple<Long, Long, String?>>>()
                for (type in listOf("Rare", "Common", "Missing")) {
                    for (k in 0L..3L) {
                        for (mode in ResultMode.entries) {
                            for (nested in NestedHits.entries) {
                                val query = KeyValueQuery("k", JsonQueryValue.Num(k), mode, nested, limit = 7, type = type)
                                store.search(query).use { result ->
                                    val page = result.nextPage()
                                    if (type != "Missing" && plan != TypeQueryPlan.AUTO) {
                                        assertEquals(plan == TypeQueryPlan.TYPE_DRIVEN, (result as EngineSearchResult).executor.stats.typeDriven)
                                    }
                                    val hits = (page?.hits.orEmpty() + result.iterator().asSequence()).map { Triple(it.node.docId.raw, it.node.nodePre, it.type) }
                                    // Resuming from a cursor in the middle gives the rest.
                                    if (page?.cursor != null) {
                                        val resumed = store.hits(query.copy(after = page.cursor))
                                        assertEquals(hits.drop(page.hits.size), resumed, "$plan $query resumed")
                                    }
                                    list += hits
                                }
                            }
                        }
                    }
                }
                results[plan] = list
            }
        }
        assertEquals(results[TypeQueryPlan.TERM_DRIVEN], results[TypeQueryPlan.TYPE_DRIVEN])
        assertEquals(results[TypeQueryPlan.TERM_DRIVEN], results[TypeQueryPlan.AUTO])
        assertTrue(results.getValue(TypeQueryPlan.AUTO).sumOf { it.size } > 100, "the queries have hits")
    }

    @Test
    fun `types of an insert whose COMMIT was cut off never attach to a later document`() {
        val fs = SimulatedFileSystemIo()
        val periodic = options(durability = Durability.Periodic(1.hours))
        val store = TestEngines.open(periodic, fs)
        val kept = store.insert(example, mapOf("$.order" to "Kept")).raw
        store.flush()
        store.insert(example, mapOf("$.order" to "Lost", "$" to "Root"))
        fs.crashKeeping { it.parent?.fileName?.toString() == LogFiles.CONTROL_DIR }
        // Its last sync meets the crashed disk.
        assertFailsWith<EngineFailedException> { store.close() }
        repeat(2) { round ->
            TestEngines.open(periodic, fs).use { store ->
                if (round == 0) assertEquals(1, store.recoveryStats.truncatedCommits)
                val docId = store.insert(example).raw
                assertTrue(docId > kept + 1, "the docId of the TYPE records isn't reused")
                assertEquals(listOf(node(kept, 1)), store.objectsOfType("Kept"))
                assertEquals(emptyList(), store.objectsOfType("Lost"))
                assertEquals(emptyList(), store.objectsOfType("Root"))
                assertNull(store.typeOf(node(docId, 0)))
                assertNull(store.typeOf(node(docId, 1)))
                store.flush()
            }
            fs.restart()
        }
    }

    @Test
    fun `the suspending store offers the type operations`() = runBlocking {
        val store = JsonStore.open(dir, EngineOptions(CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024), log = TestEngines.LOG), SimulatedFileSystemIo())
        store.use {
            val docId = it.insert(example, exampleTypes)
            it.assignTypes(listOf(NodeRef(docId, 0) to "Root"))
            assertEquals("Root", it.typeOf(NodeRef(docId, 0)))
            it.assignType(NodeRef(docId, 0), null)
            assertEquals(listOf(NodeRef(docId, 4), NodeRef(docId, 7)), it.objectsOfType("Item"))
            assertEquals(1, it.countOfType("Order"))
            assertEquals(listOf("Item", "Order", "Root"), it.listTypes().map { t -> t.name })
        }
    }

    @Test
    fun `insertAll assigns each source's types, and checks them all before reading any`() {
        for (threshold in listOf(0L, EngineOptions.DEFAULT_SMALL_INSERT_THRESHOLD)) {
            TestEngines.open(options(threshold = threshold)).use { store ->
                val next = store.registry.peekNextDocId()
                val invalid = listOf(InsertSource(example.byteInputStream(), exampleTypes), InsertSource(example.byteInputStream(), mapOf("order" to "Order")))
                assertFailsWith<IllegalArgumentException> { store.insertAll(invalid) }
                assertFailsWith<InvalidTypeNameException> { store.insertAll(listOf(InsertSource(example.byteInputStream(), mapOf("$.order" to "")))) }
                assertEquals(next, store.registry.peekNextDocId(), "threshold=$threshold: nothing was read, no docId used")

                val outcomes = store.insertAll(
                    listOf(
                        InsertSource(example.byteInputStream(), exampleTypes),
                        InsertSource(example.byteInputStream()),
                        InsertSource(example.byteInputStream(), mapOf("$.missing" to "T")),
                        InsertSource(example.byteInputStream(), mapOf("$.order" to "Other")),
                    ),
                )
                val typed = assertIs<InsertOutcome.Inserted>(outcomes[0]).docId.raw
                val untyped = assertIs<InsertOutcome.Inserted>(outcomes[1]).docId.raw
                assertEquals(RejectReason.TYPE_PATH_UNMATCHED, assertIs<InsertOutcome.Rejected>(outcomes[2]).exception.reason)
                val other = assertIs<InsertOutcome.Inserted>(outcomes[3]).docId.raw
                assertEquals(listOf(node(typed, 4), node(typed, 7)), store.objectsOfType("Item"), "threshold=$threshold")
                assertNull(store.typeOf(node(untyped, 1)))
                assertEquals(listOf(node(typed, 1)), store.objectsOfType("Order"))
                assertEquals(listOf(node(other, 1)), store.objectsOfType("Other"))
            }
        }
    }

    @Test
    fun `the type flow emits what the pages return, from the snapshot its collection started on`() = runBlocking {
        val store = JsonStore.open(dir, EngineOptions(CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024), log = TestEngines.LOG), SimulatedFileSystemIo())
        store.use {
            val docIds = List(5) { _ -> it.insert(example, exampleTypes) }
            val pages = ArrayList<NodeRef>()
            while (true) {
                val page = it.objectsOfType("Item", after = pages.lastOrNull(), limit = 3)
                if (page.isEmpty()) break
                pages += page
            }
            assertEquals(10, pages.size)
            assertEquals(pages, it.objectsOfTypeFlow("Item").toList())
            assertEquals(pages.drop(4), it.objectsOfTypeFlow("Item", after = pages[3]).toList())
            assertEquals(emptyList(), it.objectsOfTypeFlow("Nothing").toList())
            assertFailsWith<InvalidTypeNameException> { it.objectsOfTypeFlow("").toList() }

            // Changes made while a scan runs don't reach it; the next collection sees them.
            val seen = ArrayList<NodeRef>()
            (it as CoroutineJsonStore).core.openTypeScan("Item", after = null).use { scan ->
                seen += scan.next()!!
                it.delete(docIds.last())
                it.insert(example, exampleTypes)
                while (true) seen += scan.next() ?: break
            }
            assertEquals(pages, seen)
            assertEquals(pages.dropLast(2) + it.objectsOfType("Item", after = pages.last()), it.objectsOfTypeFlow("Item").toList())
        }
    }
}
