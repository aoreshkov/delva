package app.oreshkov.delva.internal.split

import app.oreshkov.delva.DocumentRejectedException
import app.oreshkov.delva.RejectReason
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.parse.Utf8JsonTokenizer
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import java.io.IOException
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DupKeyCheckerTest {
    private sealed interface Node
    private class Obj(val members: MutableList<Pair<String, Node>>) : Node
    private class Arr(val elements: List<Node>) : Node
    private class Num(val value: Int) : Node

    private class Case(val json: String, val duplicate: Boolean) {
        override fun toString(): String = "duplicate=$duplicate json=$json"
    }

    /** Random nesting; a quarter of the objects are wide, so outer objects fill the minimum arena before inner ones get keys. */
    private class Tree(private val random: Random, private val size: Int) {
        val objects = ArrayList<Obj>()
        private var budget = size * 100 + 10

        fun value(depth: Int): Node {
            val roll = random.nextInt(10)
            return when {
                budget > 0 && depth < 6 && roll < 3 -> obj(depth + 1)
                budget > 0 && depth < 6 && roll < 4 -> Arr(List(random.nextInt(3)) { value(depth + 1) })
                else -> Num(random.nextInt(100))
            }
        }

        fun obj(depth: Int): Obj {
            val width = minOf(maxOf(budget, 0), if (random.nextInt(4) == 0) random.nextInt(size * 40 + 1) else random.nextInt(8))
            budget -= width
            return Obj(MutableList(width) { "k$it" to value(depth) }).also { objects += it }
        }
    }

    /** Half the cases rename one key to an earlier key of the same object. */
    private val cases = Gen { random, size ->
        val tree = Tree(random, size)
        val root = tree.obj(0)
        val wide = tree.objects.filter { it.members.size >= 2 }
        val duplicate = wide.isNotEmpty() && random.nextBoolean()
        if (duplicate) {
            val target = wide[random.nextInt(wide.size)].members
            val at = 1 + random.nextInt(target.size - 1)
            target[at] = target[random.nextInt(at)].first to target[at].second
        }
        val out = StringBuilder()
        fun write(node: Node) {
            when (node) {
                is Obj -> {
                    out.append('{')
                    node.members.forEachIndexed { i, (key, value) ->
                        if (i > 0) out.append(',')
                        out.append('"').append(key).append("\":")
                        write(value)
                    }
                    out.append('}')
                }
                is Arr -> {
                    out.append('[')
                    node.elements.forEachIndexed { i, element -> if (i > 0) out.append(','); write(element) }
                    out.append(']')
                }
                is Num -> out.append(node.value)
            }
        }
        write(root)
        Case(out.toString(), duplicate)
    }

    @Test
    fun `duplicates are found exactly at the minimum arena, whatever the nesting`() {
        // A budget of 64 flushes pending members early, so objects go to hashed mode with few keys.
        for (budget in listOf(64, 1 shl 20)) {
            val options = SplitOptions(32, budget, sorterBytes = 4096, keyArenaBytes = 1024)
            forAll("duplicate keys B=$budget", cases, iterations = 150, maxSize = 120) { case ->
                val run = newSplitter(options, keep = false)
                val tokenizer = Utf8JsonTokenizer(case.json.byteInputStream(), options.parseLimits())
                if (case.duplicate) {
                    val e = assertFailsWith<DocumentRejectedException> { run.splitter.split(1, tokenizer, keySeed = 5) }
                    assertEquals(RejectReason.DUPLICATE_KEY, e.reason)
                } else {
                    run.splitter.split(1, tokenizer, keySeed = 5)
                }
                assertEquals(0, run.spill.live, "runs left behind")
                assertTrue(run.spill.peakOpen <= DupKeyChecker.MERGE_FAN_IN + 1, "${run.spill.peakOpen} runs open at once")
            }
        }
    }

    @Test
    fun `a repeat stored in the same fragment as the key it repeats is a duplicate, not corruption`() {
        // The nested objects need the whole minimum arena, so the root's table is spilled and dropped; its second
        // "k1" then finds no table entry, and the root's last fragment holds both before any run merges.
        val options = SplitOptions(32, 64, sorterBytes = 4096, keyArenaBytes = 1024)
        val json = """{"k0":71,"k1":{"k0":7,"k1":{"k0":30,"k1":72,"k2":{"k0":0,"k1":{"k0":70}},"k3":29,"k4":{"k0":15}}},"k1":54}"""
        val e = assertFailsWith<DocumentRejectedException> { split(json, options) }
        assertEquals(RejectReason.DUPLICATE_KEY, e.reason)

        // Equal values under the repeated key, in different fragments, give two equal postings; the members after them
        // make the term sorter sort a run before the root closes.
        val inner = """{"k0":7,"k1":{"k0":30,"k1":72,"k2":{"k0":0,"k1":{"k0":70}},"k3":29,"k4":{"k0":15}}}"""
        val filler = (0 until 300).joinToString(",") { "\"f$it\":$it" }
        val postings = """{"k0":71,"k1":$inner,"p0":1,"p1":2,"p2":3,"p3":4,"k1":$inner,"z":{$filler}}"""
        val p = assertFailsWith<DocumentRejectedException> { split(postings, options) }
        assertEquals(RejectReason.DUPLICATE_KEY, p.reason)
    }

    private class Driver(private val arena: Long) {
        val spill = MemorySpillFiles()
        val checker = DupKeyChecker(arena, spill).also { it.reset(9) }

        fun open(depth: Int) {
            checker.openObject(depth)
            checker.beforeFlush(depth, ByteArray(0), 0)
        }

        fun add(depth: Int, key: String): Boolean {
            val bytes = key.toByteArray()
            val added = checker.addKey(depth, bytes, 0, bytes.size, ByteArray(0), 0, 0)
            assertTrue(checker.arenaUsedBytes <= arena, "arena holds ${checker.arenaUsedBytes} bytes")
            return added
        }

        fun addAll(depth: Int, keys: IntRange) {
            for (k in keys) assertTrue(add(depth, "k$k"), "k$k")
        }
    }

    @Test
    fun `an outer object that fills the arena leaves room for an inner one, and merges stay within the fan-in`() {
        for (arena in listOf(1024L, 64L * 1024)) {
            // Half load fills a table: arena / 32 keys make the outer table as large as the arena.
            val outerKeys = (arena / 32).toInt()
            val d = Driver(arena)
            d.open(0)
            d.addAll(0, 0 until outerKeys)
            d.open(1)
            d.addAll(1, outerKeys until outerKeys + 1_000_000)
            assertTrue(d.checker.closeObject(1))
            d.addAll(0, 2_000_000 until 2_000_100)
            assertTrue(d.checker.closeObject(0))
            assertTrue(d.checker.peakMergeWidth <= DupKeyChecker.MERGE_FAN_IN, "arena $arena: a merge read ${d.checker.peakMergeWidth} runs")
            assertTrue(d.spill.peakOpen <= DupKeyChecker.MERGE_FAN_IN + 1, "arena $arena: ${d.spill.peakOpen} runs open at once")
            assertEquals(0, d.spill.live)

            // The outer table was spilled to make room; a repeat of one of its keys is found when the outer object closes.
            val r = Driver(arena)
            r.open(0)
            r.addAll(0, 0 until outerKeys)
            r.open(1)
            r.addAll(1, outerKeys until outerKeys + 10_000)
            assertTrue(r.checker.closeObject(1))
            assertTrue(r.add(0, "k3"))
            assertFalse(r.checker.closeObject(0), "arena $arena")
            r.checker.abort()
            assertEquals(0, r.spill.live)
        }
    }

    @Test
    fun `a repeat found by a merge while the object is open is reported at once`() {
        // At the minimum arena a run holds 32 keys, and 64 runs merge after about 2048: the repeat of k7 must be
        // rejected then, not when the object closes.
        val d = Driver(1024)
        d.open(0)
        d.addAll(0, 0 until 100)
        assertTrue(d.add(0, "k7"))
        val rejectedAt = (100 until 3_000).firstOrNull { !d.add(0, "k$it") }
        assertTrue(rejectedAt != null, "the repeat was not found while the object was open")
        d.checker.abort()
        assertEquals(0, d.spill.live)
    }

    @Test
    fun `a spill file that fails to close doesn't hide the duplicate key, and every run is still deleted`() {
        val sim = SimulatedFileSystemIo()
        val dir = Path.of("tmp")
        var failedCloses = 0
        // The runs a merge was reading when it found the repeat are closed by abort(), before their end: a run of 512
        // keys outgrows the merge buffer, and 64 of them merge after about 32K keys.
        val fs = object : FileSystemIo by sim {
            override fun open(path: Path, mode: OpenMode): FileIo {
                val file = sim.open(path, mode)
                if (mode != OpenMode.READ) return file
                return object : FileIo by file {
                    var ended = false

                    override fun read(position: Long, destination: ByteArray, offset: Int, length: Int): Int =
                        file.read(position, destination, offset, length).also { if (it <= 0) ended = true }

                    override fun close() {
                        file.close()
                        if (!ended) {
                            failedCloses++
                            throw IOException("simulated close failure on $path")
                        }
                    }
                }
            }
        }
        val options = SplitOptions(32, 1 shl 20, sorterBytes = 4096, keyArenaBytes = 16L shl 10)
        val splitter = Splitter(options, CollectingFragments(), CollectingRuns(), CollectingTypes(), FileSystemSpillFiles(fs, dir))
        val keys = (0 until 600).map { "k$it" } + "k7" + (600 until 40_000).map { "k$it" }
        val json = keys.joinToString(",", "{", "}") { "\"$it\":1" }
        val e = assertFailsWith<DocumentRejectedException> {
            splitter.split(1, Utf8JsonTokenizer(json.byteInputStream(), options.parseLimits()), keySeed = 5)
        }
        assertEquals(RejectReason.DUPLICATE_KEY, e.reason)
        assertTrue(failedCloses > 0, "no run was open when the repeat was found: ${e.message}")
        assertEquals(emptyList(), sim.list(dir))
    }

    @Test
    fun `abort deletes every run though one of them fails to`() {
        val memory = MemorySpillFiles()
        var failing = true
        val spill = object : SpillFiles {
            override fun create(): SpillRun {
                val run = memory.create()
                return object : SpillRun by run {
                    override fun delete() {
                        if (failing) {
                            failing = false
                            throw IllegalStateException("simulated delete failure")
                        }
                        run.delete()
                    }
                }
            }
        }
        val checker = DupKeyChecker(1024, spill).also { it.reset(9) }
        checker.openObject(0)
        checker.beforeFlush(0, ByteArray(0), 0)
        for (k in 0 until 1_000) {
            val key = "k$k".toByteArray()
            assertTrue(checker.addKey(0, key, 0, key.size, ByteArray(0), 0, 0))
        }
        assertTrue(memory.live > 2, "${memory.live} runs")
        assertFailsWith<IllegalStateException> { checker.abort() }
        assertEquals(1, memory.live, "only the run that failed is left")
    }
}
