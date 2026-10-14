package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.CompareOperand
import app.oreshkov.delva.CompareOptions
import app.oreshkov.delva.DiffOptions
import app.oreshkov.delva.DifferenceKind
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.compare.CompareSide
import app.oreshkov.delva.internal.compare.CompareStats
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonLimits
import app.oreshkov.delva.testkit.json.JsonMutator
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.json.OracleJson
import app.oreshkov.delva.testkit.json.canonicalEquals
import app.oreshkov.delva.testkit.oracle.DiffPatch
import app.oreshkov.delva.testkit.oracle.OCompareOptions
import app.oreshkov.delva.testkit.oracle.ODifference
import app.oreshkov.delva.testkit.oracle.ONodeRef
import app.oreshkov.delva.testkit.oracle.OOperand
import app.oreshkov.delva.testkit.oracle.OracleOptions
import app.oreshkov.delva.testkit.oracle.OracleStore
import app.oreshkov.delva.testkit.oracle.toOracle
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comparing and diffing values on the durable engine. The model-based comparisons with the
 * oracle, interleaved with every other operation, are in [EngineModelTest].
 */
class CompareEngineTest {
    private fun options(t: Int, literalMemoryBytes: Long? = null, compareCacheBytes: Long? = null, flushes: Boolean = false) = EngineOptions(
        CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024), maxDepth = 512),
        sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 5, log = TestEngines.LOG, backgroundFlush = false,
        memtableBytes = if (flushes) 16L shl 10 else EngineOptions.DEFAULT_MEMTABLE_BYTES,
        literalMemoryBytes = literalMemoryBytes, compareCacheBytes = compareCacheBytes,
    )

    private fun stored(docId: DocId, pre: Long = 0) = CompareOperand.Stored(NodeRef(docId, pre))

    private fun json(text: String) = CompareOperand.Json(text)

    @Test
    fun `equality ignores member order and number spelling at every fragment size`() {
        for (t in listOf(32, 256, 64 * 1024)) {
            TestEngines.open(options(t)).use { store ->
                val a = store.insert("""{"b":[1,2.0,{"x":"é","y":null}],"a":{"n":10,"s":"${"z".repeat(300)}"},"c":true}""")
                val b = store.insert("""{"c":true,"a":{"s":"${"z".repeat(300)}","n":1e1},"b":[1.00,2,{"y":null,"x":"é"}]}""")
                val c = store.insert("""{"c":true,"a":{"s":"${"z".repeat(299)}y","n":1e1},"b":[1.00,2,{"y":null,"x":"é"}]}""")
                assertTrue(store.isEqual(stored(a), stored(b)), "T=$t")
                assertTrue(store.isEqual(stored(b), stored(a)), "T=$t")
                assertTrue(store.isEqual(stored(a), stored(a)), "T=$t")
                assertFalse(store.isEqual(stored(a), stored(c)), "T=$t")
                assertTrue(store.isEqual(stored(a, 1), json("[1e0,2,{\"x\":\"\\u00e9\",\"y\":null}]")), "T=$t")
                assertTrue(store.isEqual(json("0.10e1"), json("1")), "T=$t")
                assertFalse(store.isEqual(json("\"1\""), json("1")), "T=$t")
                assertFalse(store.isEqual(json("[1,2]"), json("[2,1]")), "T=$t")
                assertTrue(store.diff(stored(a), stored(b)).isEmpty(), "T=$t")
                assertEquals(listOf("$['a']['s']"), store.diff(stored(a), stored(c)).map { it.path }, "T=$t")
            }
        }
    }

    @Test
    fun `differences name their path, kind and both values in canonical order`() {
        TestEngines.open(options(32)).use { store ->
            // [0]{"k":[1]1,"arr":[2][[3]1,[4]2,[5]3],"obj":[6]{"x":[7]true},"weird key":[8]"v","t":[9]"s"}
            val left = store.insert("""{"k":1,"arr":[1,2,3],"obj":{"x":true},"weird key":"v","t":"s"}""")
            val right = """{"k":1.5,"arr":[1,"2"],"obj":{"x":false,"y":null},"weird key":"v","t":[],"new":{"a":1}}"""
            val diffs = store.diff(stored(left), json(right))
            assertEquals(
                listOf(
                    "$['arr'][1]" to DifferenceKind.VALUE_TYPE_CHANGED,
                    "$['arr'][2]" to DifferenceKind.REMOVED,
                    "$['k']" to DifferenceKind.CHANGED,
                    "$['new']" to DifferenceKind.ADDED,
                    "$['obj']['x']" to DifferenceKind.CHANGED,
                    "$['obj']['y']" to DifferenceKind.ADDED,
                    "$['t']" to DifferenceKind.VALUE_TYPE_CHANGED,
                ),
                diffs.map { it.path to it.kind },
            )
            val removed = diffs[1]
            assertEquals(NodeRef(left, 5), removed.left!!.node)
            assertEquals("3", removed.left.json)
            assertNull(removed.right)
            val added = diffs[3]
            assertNull(added.left)
            assertNull(added.right!!.node)
            assertEquals("{\"a\":1}", added.right.json)
            assertEquals("1", diffs[2].left!!.json)
            assertEquals("1.5", diffs[2].right!!.json)

            val keys = store.diff(json("""{"a b":1,"é":1,"":1,"q\"\n":1,"_x-1":1}"""), json("{}")).map { it.path }
            assertEquals(listOf("$['']", "$['_x-1']", "$['a b']", "$['q\"\\n']", "$['é']"), keys)
            assertEquals(listOf("$" to DifferenceKind.VALUE_TYPE_CHANGED), store.diff(json("{}"), json("[]")).map { it.path to it.kind })
        }
    }

    @Test
    fun `limits, previews, ignored keys and paths`() {
        TestEngines.open(options(64)).use { store ->
            val big = "ä".repeat(600)
            val left = store.insert("""{"a":[1,2,3,4,5,6,7,8],"s":"$big","meta":{"at":1,"by":"x"},"list":[{"at":1,"v":1},{"at":2,"v":2}]}""")
            val right = """{"a":[0,0,0,0,0,0,0,0],"s":"${big}b","meta":{"at":2,"by":"y"},"list":[{"at":3,"v":1},{"at":4,"v":3}]}"""
            assertEquals(3, store.diff(stored(left), json(right), DiffOptions(maxDifferences = 3)).size)
            assertEquals(14, store.diff(stored(left), json(right)).size)
            val s = store.diff(stored(left), json(right), DiffOptions(maxValueBytes = 5)).single { it.path == "$['s']" }
            assertTrue(s.left!!.truncated)
            assertEquals("\"ää", s.left.json) // the first 5 bytes end on a character boundary
            assertEquals(0, store.diff(stored(left), json(right), DiffOptions(maxValueBytes = 0)).sumOf { it.left?.json?.length ?: 0 })

            val ignoring = DiffOptions(ignoreKeys = setOf("at", "s"), ignorePaths = setOf("$.a", "$.meta.by", "$.list[1]"))
            assertEquals(emptyList(), store.diff(stored(left), json(right), ignoring).map { it.path })
            assertTrue(store.isEqual(stored(left), json(right), CompareOptions(ignoreKeys = setOf("at", "s"), ignorePaths = setOf("$.a", "$.meta.by", "$.list[1]"))))
            assertFalse(store.isEqual(stored(left), json(right), CompareOptions(ignoreKeys = setOf("at", "s"), ignorePaths = setOf("$.a", "$.meta.by"))))
            assertTrue(store.isEqual(json("1"), json("2"), CompareOptions(ignorePaths = setOf("$"))))
            assertEquals(listOf("$['list'][1]['v']"), store.diff(stored(left), json(right), DiffOptions(ignoreKeys = setOf("at", "s"), ignorePaths = setOf("$.a", "$.meta"))).map { it.path })
            assertFailsWith<IllegalArgumentException> { DiffOptions(ignorePaths = setOf("$.a[01]")) }
            assertFailsWith<IllegalArgumentException> { CompareOptions(ignorePaths = setOf("a")) }
            assertFailsWith<IllegalArgumentException> { DiffOptions(maxDifferences = 0) }
        }
    }

    @Test
    fun `assigned types are compared only when asked`() {
        TestEngines.open(options(32)).use { store ->
            val doc = """{"order":{"id":1,"items":[{"sku":"a"},{"sku":"b"}]}}"""
            val a = store.insert(doc, mapOf("$.order" to "Order", "$.order.items[1]" to "Item"))
            val b = store.insert(doc, mapOf("$.order" to "Order"))
            assertTrue(store.isEqual(stored(a), stored(b)))
            assertFalse(store.isEqual(stored(a), stored(b), CompareOptions(includeTypes = true)))
            assertFalse(store.isEqual(stored(a), json(doc), CompareOptions(includeTypes = true)))
            val diffs = store.diff(stored(a), stored(b), DiffOptions(includeTypes = true))
            assertEquals(listOf("$['order']['items'][1]" to DifferenceKind.ASSIGNED_TYPE_CHANGED), diffs.map { it.path to it.kind })
            assertEquals("Item", diffs[0].left!!.type)
            assertNull(diffs[0].right!!.type)
            store.assignType(NodeRef(b, 6), "Item")
            assertTrue(store.isEqual(stored(a), stored(b), CompareOptions(includeTypes = true)))
            val c = store.insert("""{"order":{"id":2,"items":[{"sku":"a"},{"sku":"b"}]}}""", mapOf("$.order" to "Other"))
            assertEquals(
                listOf("$['order']" to DifferenceKind.ASSIGNED_TYPE_CHANGED, "$['order']['id']" to DifferenceKind.CHANGED, "$['order']['items'][1]" to DifferenceKind.ASSIGNED_TYPE_CHANGED),
                store.diff(stored(a), stored(c), DiffOptions(includeTypes = true)).map { it.path to it.kind },
            )
        }
    }

    @Test
    fun `operands are checked like other node reads`() {
        TestEngines.open(options(256)).use { store ->
            val a = store.insert("""{"x":[1,2]}""")
            val deleted = store.insert("""{"x":1}""")
            store.delete(deleted)
            assertFailsWith<DocumentNotFoundException> { store.isEqual(stored(deleted), stored(a)) }
            assertFailsWith<DocumentNotFoundException> { store.diff(stored(a), stored(DocId(99))) }
            assertFailsWith<IllegalArgumentException> { store.isEqual(stored(a, 5), stored(a)) }
            val rejected = assertFailsWith<DocumentRejectedException> { store.isEqual(stored(a), json("""{"x":1,"x":2}""")) }
            assertEquals(RejectReason.DUPLICATE_KEY, rejected.reason)
            assertEquals(RejectReason.MALFORMED_JSON, assertFailsWith<DocumentRejectedException> { store.diff(json("[1,"), stored(a)) }.reason)
            // A stored operand that fails is reported before a JSON stream is read.
            val stream = ByteArrayInputStream("[1,2]".toByteArray())
            assertFailsWith<DocumentNotFoundException> { store.isEqual(CompareOperand.JsonStream(stream), stored(deleted)) }
            assertEquals(5, stream.available())
            assertTrue(store.isEqual(CompareOperand.JsonStream(stream), stored(a, 1)))
            assertTrue(store.isEqual(stored(a, 2), json("1")))
        }
    }

    @Test
    fun `large values given as JSON spill to a temporary file that is removed`() {
        val fs = SimulatedFileSystemIo()
        TestEngines.open(options(256, literalMemoryBytes = 256), fs).use { store ->
            val value = JsonGen(JsonProfile.MIXED, 256).value().generate(Random(3), 400)
            val text = JsonText.minified(value)
            val id = store.insert(text)
            assertTrue(store.isEqual(stored(id), json(text)))
            val changed = JsonText.minified(JsonMutator.mutate(value, Random(4), 3))
            assertEquals(OracleStore().let { o -> o.insert(text); o.diff(OOperand.Stored(ONodeRef(1, 0)), OOperand.Json(changed)).size }, store.diff(stored(id), json(changed)).size)
            assertTrue(fs.list(TestEngines.DIR.resolve(LogFiles.TMP_DIR)).isEmpty(), "tmp/ is empty")
        }
    }

    @Test
    fun `when closing one operand's temporary file fails, the other's is still closed and removed`() {
        val fs = SimulatedFileSystemIo()
        TestEngines.open(options(256, literalMemoryBytes = 256), fs).use { store ->
            // Many times literalMemoryBytes, so each operand spills to a file of its own.
            val text = (0 until 2000).joinToString(",", "[", "]") { "\"value $it\"" }
            val handles = fs.openHandles
            fs.failNextClose { it.fileName.toString().startsWith("literal-") }
            assertFailsWith<IOException> { store.isEqual(json(text), json(text)) }
            assertEquals(handles, fs.openHandles, "every handle is closed")
            assertTrue(fs.list(TestEngines.DIR.resolve(LogFiles.TMP_DIR)).isEmpty(), "tmp/ is empty")
        }
    }

    @Test
    fun `the suspending diff streams differences on the store's dispatcher`() = runBlocking {
        val fs = SimulatedFileSystemIo()
        val store: JsonStore = JsonStore.open(TestEngines.DIR, options(64), fs)
        store.use {
            val a = it.insert("""[1,2,3]""")
            assertEquals(listOf("$[0]", "$[2]"), it.diff(stored(a), json("[0,2]")).toList().map { d -> d.path })
            assertTrue(it.isEqual(stored(a), CompareOperand.JsonStream(ByteArrayInputStream("[1,2,3.0]".toByteArray()))))
        }
    }

    @Test
    fun `split objects merge their parts in key order within a small part cache, and hashes skip or answer early`() {
        TestEngines.open(options(256, compareCacheBytes = 32L shl 10)).use { store ->
            val random = Random(7)
            // Keys in random order, so the key ranges of the parts interleave; members large enough to become subtrees.
            val keys = List(3000) { "k" + random.nextLong().toULong().toString(36) }.distinct()
            fun member(key: String, i: Int) = if (i % 10 == 0) "\"$key\":{\"i\":$i,\"pad\":\"${"p".repeat(200)}\",\"list\":[1,2,3]}" else "\"$key\":$i"
            val left = keys.mapIndexed { i, k -> member(k, i) }.joinToString(",", "{", "}")
            val changed = setOf(5, 10, 1500, keys.size - 1)
            val right = keys.mapIndexed { i, k -> if (i in changed) (if (i == 10) "\"$k\":{\"i\":10,\"pad\":\"x\",\"list\":[1,2,3]}" else "\"$k\":\"changed\"") else member(k, i) }
                .filterIndexed { i, _ -> i != 7 }.plus("\"added\":true").shuffled(random).joinToString(",", "{", "}")
            val oracle = OracleStore()
            val a = store.insert(left)
            val b = store.insert(right)
            oracle.skipTo(a.raw)
            oracle.insert(left)
            oracle.skipTo(b.raw)
            oracle.insert(right)

            val stats = store.compareStats
            val expected = oracle.diff(OOperand.Stored(ONodeRef(a.raw, 0)), OOperand.Stored(ONodeRef(b.raw, 0)), OCompareOptions(maxValueBytes = 64))
            assertEquals(6, expected.size)
            val before = stats.partIndexesBuilt.sum()
            assertEquals(expected, store.diff(stored(a), stored(b), DiffOptions(maxValueBytes = 64)).map { it.toOracle() })
            // Parts outnumber what the cache holds and their key ranges interleave, so indexes are rebuilt during the merge.
            val built = stats.partIndexesBuilt.sum() - before
            assertTrue(built > 4 * store.splitParts(a), "part indexes built: $built for ${store.splitParts(a)} parts")
            assertEquals(expected.map { it.withoutNodes(left = false, right = true) }, store.diff(stored(a), json(right), DiffOptions(maxValueBytes = 64)).map { it.toOracle() })
            assertTrue(stats.skippedByHash.sum() > 250, "subtrees skipped by hash: ${stats.skippedByHash.sum()}")
            assertEquals(expected.size, store.diff(stored(a), stored(b), DiffOptions(maxValueBytes = 64, verifySkippedSubtrees = true)).size)

            val c = store.insert(left)
            assertTrue(store.isEqual(stored(a), stored(c)))
            assertTrue(store.isEqual(stored(c), json(left)))
            val early = stats.unequalWithoutReading.sum()
            val trees = stats.treesBuilt.sum()
            assertFalse(store.isEqual(stored(a), stored(b)))
            assertFalse(store.isEqual(json(right), stored(a)))
            assertEquals(early + 2, stats.unequalWithoutReading.sum())
            assertEquals(trees, stats.treesBuilt.sum(), "a split root's hash is in its HEADER")
        }
    }

    /** Parts of the split root of [docId]. */
    private fun Engine.splitParts(docId: DocId): Int {
        val (_, version) = openView()
        try {
            val side = CompareSide(fragmentsOf(version), docId.raw, stored = true, partCacheBytes = 0, types = null, CompareStats())
            return side.splitInfo(0, 0, -1, -1).partCount
        } finally {
            version.release()
        }
    }

    // ---- properties -------------------------------------------------------------------------------------------------

    private class Case(val a: OValue, val b: OValue, val options: OCompareOptions)

    private fun cases(t: Int): Gen<Case> = Gen { random, size ->
        val profile = JsonProfile.entries[random.nextInt(JsonProfile.entries.size)]
        val a = JsonGen(profile, t).value().generate(random, maxOf(1, size))
        val b = JsonMutator.mutate(a, random, edits = if (random.nextInt(4) == 0) 0 else random.nextInt(1, 6))
        val options = OCompareOptions(
            ignoreKeys = if (random.nextInt(5) == 0) JsonGen.HOT_KEYS.shuffled(random).take(2).toSet() else emptySet(),
            maxValueBytes = if (random.nextInt(4) == 0) random.nextInt(0, 40) else Int.MAX_VALUE,
        )
        Case(a, b, options)
    }

    private fun engineOptions(o: OCompareOptions) = DiffOptions(ignoreKeys = o.ignoreKeys, ignorePaths = o.ignorePaths, maxDifferences = o.maxDifferences, maxValueBytes = o.maxValueBytes)

    private fun properties(t: Int, iterations: Int, maxSize: Int, flushes: Boolean) {
        val atom = maxOf(t / 4, 64)
        val oracleOptions = OracleOptions(limits = JsonLimits(maxDepth = 512, maxKeyBytes = atom, maxNumberLength = minOf(4096, atom)))
        var differences = 0L
        var equal = 0L
        var caseCount = 0
        var store: Engine? = null
        lateinit var oracle: OracleStore

        /** Inserts into both with the same docId, or into neither when the engine rejects the document. */
        fun insert(text: String): Long? {
            val docId = runCatching { store!!.insert(text).raw }.getOrElse { e ->
                assertEquals(DocumentRejectedException::class, e::class, "insert failed: $e")
                return null
            }
            oracle.skipTo(docId)
            assertEquals(docId, oracle.insert(text))
            return docId
        }

        try {
            forAll("compare T=$t" + if (flushes) " with flushes" else "", cases(t), iterations = iterations, maxSize = maxSize) { case ->
                // A new store and oracle now and then: the oracle keeps every document with its postings in memory.
                if (caseCount++ % STORE_CASES == 0) {
                    store?.close()
                    store = TestEngines.open(options(t, literalMemoryBytes = 2048, compareCacheBytes = 16L shl 10, flushes = flushes))
                    oracle = OracleStore(oracleOptions.copy(firstDocId = store.registry.peekNextDocId()))
                }
                val store = store!!
                val aText = JsonText.minified(case.a)
                val bText = JsonText.minified(case.b)
                val aId = insert(aText) ?: return@forAll
                val bId = insert(bText) ?: return@forAll
                val engineDiffOptions = engineOptions(case.options)
                val expected = oracle.diff(OOperand.Stored(ONodeRef(aId, 0)), OOperand.Stored(ONodeRef(bId, 0)), case.options)
                val actual = store.diff(stored(DocId(aId)), stored(DocId(bId)), engineDiffOptions).map { it.toOracle() }
                assertEquals(expected, actual, "stored diff\n  a: ${aText.take(500)}\n  b: ${bText.take(500)}")
                differences += actual.size
                // Values given as JSON give the same differences without node references.
                val literal = store.diff(json(aText), CompareOperand.JsonStream(ByteArrayInputStream(bText.toByteArray())), engineDiffOptions).map { it.toOracle() }
                assertEquals(expected.map { it.withoutNodes() }, literal, "literal diff")
                val mixed = store.diff(json(aText), stored(DocId(bId)), engineDiffOptions).map { it.toOracle() }
                assertEquals(expected.map { it.withoutNodes(left = true, right = false) }, mixed, "mixed diff")

                val same = canonicalEquals(case.a, case.b)
                if (same) equal++
                if (case.options.ignoreKeys.isEmpty()) {
                    assertEquals(same, store.isEqual(stored(DocId(aId)), stored(DocId(bId))), "isEqual")
                    assertEquals(same, store.isEqual(stored(DocId(bId)), json(aText)), "isEqual with JSON")
                    assertEquals(same, actual.isEmpty(), "diff is empty exactly for equal values")
                    if (case.options.maxValueBytes == Int.MAX_VALUE) {
                        val patched = DiffPatch.apply(OracleJson.parseOrThrow(aText), actual.map { DiffPatch.Change(it.path, it.kind, it.right?.json) })
                        assertTrue(canonicalEquals(patched, case.b), "apply(diff(a, b), a) == b\n  patched: ${JsonText.minified(patched).take(500)}")
                    }
                } else {
                    assertEquals(expected.isEmpty(), store.isEqual(stored(DocId(aId)), stored(DocId(bId)), CompareOptions(ignoreKeys = case.options.ignoreKeys)), "isEqual ignoring keys")
                }
                // A node inside each document against the corresponding node inside the other.
                val nodes = oracle.nodes(aId)
                val pre = nodes[Random(aId).nextInt(nodes.size)].first
                val bNodes = oracle.nodes(bId)
                val bPre = bNodes[Random(bId).nextInt(bNodes.size)].first
                val sub = oracle.diff(OOperand.Stored(ONodeRef(aId, pre)), OOperand.Stored(ONodeRef(bId, bPre)), case.options.copy(maxDifferences = 50))
                assertEquals(sub, store.diff(stored(DocId(aId), pre), stored(DocId(bId), bPre), engineDiffOptions.let { DiffOptions(ignoreKeys = it.ignoreKeys, maxDifferences = 50, maxValueBytes = it.maxValueBytes) }).map { it.toOracle() }, "diff of nodes $pre and $bPre")
                assertEquals(sub.isEmpty(), store.isEqual(stored(DocId(aId), pre), stored(DocId(bId), bPre), CompareOptions(ignoreKeys = case.options.ignoreKeys)), "isEqual of nodes $pre and $bPre")
            }
        } finally {
            store?.close()
        }
        println("compare T=$t: $differences differences, $equal equal pairs")
    }

    private fun ODifference.withoutNodes(left: Boolean = true, right: Boolean = true) =
        copy(left = this.left?.let { if (left) it.copy(node = null) else it }, right = this.right?.let { if (right) it.copy(node = null) else it })

    @Test
    fun `diff and isEqual agree with the oracle and diffs apply, T = 32`() = properties(32, ITERATIONS, maxSize = 150, flushes = false)

    @Test
    fun `diff and isEqual agree with the oracle and diffs apply, T = 256 with flushes`() = properties(256, ITERATIONS, maxSize = 300, flushes = true)

    @Test
    fun `diff and isEqual agree with the oracle and diffs apply, T = 64 KiB`() = properties(64 * 1024, ITERATIONS, maxSize = 400, flushes = false)

    private companion object {
        val ITERATIONS = System.getProperty("delva.compare.iterations")?.toInt() ?: 200
        const val STORE_CASES = 20
    }
}
