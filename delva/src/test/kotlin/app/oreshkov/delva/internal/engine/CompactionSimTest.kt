package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo.Platform
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogOptions
import java.util.TreeMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Long runs of insert, type and delete churn on one store with compaction, reopened and crashed (power
 * loss between acknowledged operations) now and then, checked against a model at checkpoints: documents, types of
 * every object, `objectsOfType`, `countOfType` and type-filtered searches. The live documents stay around a target
 * count, so the index and the log must stay bounded too: their sizes at checkpoints are compared with their sizes early on.
 * Lane files are reclaimed now and then, and control files go once flushes cover them.
 *
 * `-Ddelva.sim.operations=N` sets the operations per run (10 000 by default, 100 000 with `-Pdelva.testProfile=long`);
 * `-Ddelva.seed=S` the seed.
 */
class CompactionSimTest {
    private data class Node(val docId: Long, val pre: Long)

    @Test
    fun `long insert, assignment and delete churn stays exact and keeps the index and the log bounded`() {
        val long = System.getProperty("delva.testProfile") == "long"
        val operations = System.getProperty("delva.sim.operations")?.toInt() ?: if (long) 100_000 else 10_000
        val base = System.getProperty("delva.seed")?.toLong() ?: 0xC0DEL
        for (run in 0 until 2) {
            val seed = base + run
            try {
                simulate(seed, operations)
            } catch (e: Throwable) {
                throw AssertionError("run $seed failed; reproduce: ./gradlew :delva:simTest --tests '*CompactionSimTest' -Ddelva.seed=$seed -Ddelva.sim.operations=$operations", e)
            }
        }
    }

    private fun simulate(seed: Long, operations: Int) {
        val random = Random(seed)
        val t = listOf(64, 1024)[(seed and 1).toInt()]
        val options = EngineOptions(
            CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024), maxDepth = 64),
            sorterBytes = 4096,
            keyArenaBytes = 1024,
            smallInsertThreshold = 2048,
            keySeed = seed,
            log = LogOptions(laneFileBytes = 32L shl 10, controlFileBytes = 32L shl 10, preallocateBytes = 0, writeBufferBytes = 4096),
            memtableBytes = 32L shl 10,
            backgroundFlush = false,
            segmentBlockBytes = 1024,
            compaction = CompactionOptions(minMergeSegments = 3, deletedRatio = 0.1, tombstoneRatio = 0.2, bytesPerSecond = 0, maxRunBytes = 256, listMemoryBytes = 4096),
            compression = TestEngines.compression(Math.floorMod(seed shr 2, 3)),
        )
        // Linux, or one of the three Windows models.
        val fs = SimulatedFileSystemIo(if ((seed shr 1) and 1L == 0L) Platform.LINUX else WINDOWS[Math.floorMod(seed shr 5, WINDOWS.size)])
        val docs = TreeMap<Long, Int>()
        val typed = HashMap<Node, String>()
        var store = Engine.open(TestEngines.DIR, options, fs)
        val sizes = ArrayList<Long>()
        val logSizes = ArrayList<Long>()
        var reclaimed = 0L
        var reopens = 0
        var crashes = 0
        var compactions = 0L
        var flushes = 0L
        val target = 150
        try {
            for (op in 1..operations) {
                when (random.nextInt(20)) {
                    in 0..8 -> if (docs.size < 2 * target) {
                        val objects = random.nextInt(1, 6)
                        val json = """{"all":true,"n":$op,"objs":[${(0 until objects).joinToString(",") { """{"k":$it,"hot":"yes"}""" }}],"pad":"${"p".repeat(random.nextInt(0, 3 * t))}"}"""
                        val types = HashMap<String, String>()
                        for (j in 0 until objects) if (random.nextInt(3) == 0) types["$.objs[$j]"] = NAMES[random.nextInt(NAMES.size)]
                        val docId = store.insert(json, types).raw
                        docs[docId] = objects
                        for ((path, name) in types) typed[Node(docId, pre(path))] = name
                    }
                    in 9..14 -> if (docs.size > target / 2) {
                        val keys = docs.keys.toList()
                        val gone = List(random.nextInt(1, 6)) { keys[random.nextInt(keys.size)] }.distinct()
                        assertEquals(gone.size, store.deleteAll(gone.map { DocId(it) }))
                        for (docId in gone) docs.remove(docId)
                        typed.keys.removeIf { it.docId in gone }
                    }
                    in 15..18 -> if (docs.isNotEmpty()) {
                        val keys = docs.keys.toList()
                        val assignments = List(random.nextInt(1, 5)) {
                            val docId = keys[random.nextInt(keys.size)]
                            Node(docId, 4L + 3 * random.nextInt(docs.getValue(docId))) to if (random.nextInt(4) == 0) null else NAMES[random.nextInt(NAMES.size)]
                        }
                        store.assignTypes(assignments.map { (node, name) -> NodeRef(DocId(node.docId), node.pre) to name })
                        for ((node, name) in assignments) if (name == null) typed.remove(node) else typed[node] = name
                    }
                    else -> when (random.nextInt(40)) {
                        0 -> {
                            flushes += store.indexes.flushes
                            compactions += store.indexes.compactor.compactions
                            store.close()
                            fs.restart()
                            store = Engine.open(TestEngines.DIR, options, fs)
                            reopens++
                        }
                        1 -> {
                            flushes += store.indexes.flushes
                            compactions += store.indexes.compactor.compactions
                            // Power loss after the last acknowledgement: every acknowledged operation was synced.
                            fs.crashAfter(1)
                            runCatching { store.close() }
                            fs.crash(random)
                            store = Engine.open(TestEngines.DIR, options, fs)
                            crashes++
                        }
                        in 2..5 -> {
                            store.flush()
                            reclaimed += store.reclaim()
                        }
                        else -> store.flush()
                    }
                }
                if (op % CHECKPOINT == 0) {
                    verify(store, docs, typed, "operation $op")
                    sizes += store.indexes.segmentBytes
                    logSizes += fs.files().filter { it.parent?.fileName?.toString() in LOG_DIRS }.sumOf { fs.bytes(it).size.toLong() }
                }
            }
            flushes += store.indexes.flushes
            compactions += store.indexes.compactor.compactions
            store.compact()
            reclaimed += store.reclaim()
            verify(store, docs, typed, "the end")
        } finally {
            runCatching { store.close() }
        }
        // The live documents stay around the target: so must the index, once it has filled.
        val early = sizes.subList(0, minOf(3, sizes.size)).max()
        assertTrue(sizes.all { it <= 3 * early }, "index bytes at checkpoints: $sizes")
        val earlyLog = logSizes.subList(0, minOf(3, logSizes.size)).max()
        assertTrue(logSizes.all { it <= 3 * earlyLog }, "log bytes at checkpoints: $logSizes")
        assertTrue(reclaimed > 0, "no lane file was reclaimed")
        println("compaction simulation $seed (T=$t): $operations operations, $flushes flushes, $compactions compactions, $reopens reopens, $crashes crashes, ${docs.size} documents at the end, $reclaimed lane files reclaimed, index bytes at checkpoints $sizes, log bytes $logSizes")
    }

    /** `$.objs[j]` is the object at pre 4 + 3j: the root is 0, `true` 1, `n` 2, the array 3, and each object is followed by its `k` and `hot`. */
    private fun pre(path: String): Long = 4L + 3 * path.substringAfter('[').substringBefore(']').toLong()

    private fun verify(store: Engine, docs: Map<Long, Int>, typed: Map<Node, String>, label: String) {
        assertEquals(docs.keys.toList(), store.documents().keys.sorted(), "$label: documents")
        for ((docId, objects) in docs) {
            for (j in 0 until objects) assertEquals(typed[Node(docId, 4L + 3 * j)], store.typeOf(NodeRef(DocId(docId), 4L + 3 * j)), "$label: type of ($docId, ${4 + 3 * j})")
        }
        for (name in NAMES) {
            val nodes = typed.filterValues { it == name }.keys.sortedWith(compareBy({ it.docId }, { it.pre }))
            assertEquals(nodes.map { NodeRef(DocId(it.docId), it.pre) }, store.objectsOfType(name, limit = 1_000_000), "$label: objects of $name")
            assertEquals(nodes.size.toLong(), store.countOfType(name), "$label: count of $name")
            val hits = store.search(KeyValueQuery("hot", JsonQueryValue.Str("yes"), ResultMode.CONTAINING_ELEMENT, limit = 1_000_000, type = name)).use { result ->
                result.iterator().asSequence().map { Node(it.node.docId.raw, it.node.nodePre) }.toList()
            }
            assertEquals(nodes, hits, "$label: hot = yes in objects of $name")
        }
        assertEquals(0, store.damagedDocIds.size, label)
    }

    private companion object {
        const val CHECKPOINT = 1000
        val LOG_DIRS = setOf(LogFiles.DATA_DIR, LogFiles.CONTROL_DIR)
        val NAMES = listOf("A", "Bé", "C")
        val WINDOWS = Platform.entries.filter { it.isWindows }
    }
}
