package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentNotFoundException
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.WriteConflictException
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.log.LogOptions
import app.oreshkov.delva.internal.query.TypeQueryPlan
import java.util.TreeMap
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Crash simulation for types, deletes and replaces: workloads of inserts with type paths, batches of assignments,
 * reassignments and removals, delete calls and replaces (some spanning several DELETE records), with a crash at a random disk call
 * (log appends, syncs, flushes writing type segments, dictionary and bitmap files, MANIFESTs, space reclamation copying,
 * relocating and deleting lane files), then power loss and reopen.
 * After each crash:
 *
 * - every acknowledged document that wasn't deleted has exactly the types its insert gave it, as changed by acknowledged
 *   assignments; a document whose insert was in progress is either absent or present with all of its types;
 * - a batch of assignments, a delete call or a replace in progress took effect completely or not at all: a replace's
 *   document is visible exactly when the documents it replaces are deleted;
 * - a replace of a document that isn't live conflicts and changes nothing;
 * - deleted documents never reappear, and reading or deleting them again behaves as for deleted documents;
 * - `typeOf`, `objectsOfType`, `countOfType` and type-filtered searches (both plans) agree with that state.
 *
 * `-Ddelva.sim.scenarios=N` sets the number of scenarios (300 by default, 5 000 with `-Pdelva.testProfile=long`);
 * `-Ddelva.seed=S` the first seed.
 */
class TypeDeleteCrashSimTest {
    private data class Node(val docId: Long, val pre: Long)

    private sealed interface Step {
        class Insert(val json: String, val types: Map<String, String>, val objects: Int) : Step

        class Assign(val picks: List<Long>) : Step

        class Delete(val picks: List<Long>) : Step

        /** Inserts [insert] replacing documents picked by [picks]: mostly live ones, now and then one that conflicts. */
        class Replace(val insert: Insert, val picks: List<Long>) : Step

        /** Flush, compact and reclaim lane files. */
        data object Reclaim : Step
    }

    private class Stats {
        var scenarios = 0
        var crashedMidway = 0
        var acknowledgedInserts = 0
        var acknowledgedAssignments = 0
        var acknowledgedDeletes = 0
        var batchesApplied = 0
        var batchesLost = 0
        var deletesApplied = 0
        var deletesLost = 0
        var acknowledgedReplaces = 0
        var conflicts = 0
        var replacesApplied = 0
        var replacesLost = 0
        var typedNodes = 0L
        var deletedDocs = 0L
        var reclaimedFiles = 0L
        var crashedReclaiming = 0
    }

    /** What the store must show: live documents with their object count, the type of every typed object, deleted docIds. */
    private class Expected {
        val docs = TreeMap<Long, Pair<String, Int>>()
        val types = HashMap<Node, String>()
        val deleted = HashSet<Long>()

        fun delete(docIds: Collection<Long>) {
            for (docId in docIds) {
                if (docs.remove(docId) == null) continue
                deleted += docId
                types.keys.removeIf { it.docId == docId }
            }
        }
    }

    @Test
    fun `types and deletes survive a crash at any disk call and stay atomic`() {
        val long = System.getProperty("delva.testProfile") == "long"
        val count = System.getProperty("delva.sim.scenarios")?.toInt() ?: if (long) 5_000 else 300
        val base = System.getProperty("delva.seed")?.toLong() ?: 0x7E5L
        val stats = Stats()
        for (s in 0 until count) {
            val seed = base + s
            try {
                scenario(seed, stats)
            } catch (e: Throwable) {
                throw AssertionError("scenario $seed failed; reproduce: ./gradlew :delva:simTest --tests '*TypeDeleteCrashSimTest' -Ddelva.seed=$seed -Ddelva.sim.scenarios=1", e)
            }
        }
        println(
            "type and delete crash simulation: ${stats.scenarios} scenarios (${stats.crashedMidway} crashes during work), ${stats.acknowledgedInserts} typed inserts, " +
                "${stats.acknowledgedAssignments} assignment batches and ${stats.acknowledgedDeletes} delete calls acknowledged, in-progress batches " +
                "${stats.batchesApplied} applied / ${stats.batchesLost} lost, in-progress delete calls ${stats.deletesApplied} applied / ${stats.deletesLost} lost, " +
                "${stats.acknowledgedReplaces} replaces acknowledged and ${stats.conflicts} conflicting, in-progress replaces ${stats.replacesApplied} applied / ${stats.replacesLost} lost, " +
                "${stats.typedNodes} typed objects and ${stats.deletedDocs} deleted documents checked, ${stats.reclaimedFiles} lane files reclaimed, ${stats.crashedReclaiming} crashes while reclaiming",
        )
    }

    private fun scenario(seed: Long, stats: Stats) {
        val random = Random(seed)
        val t = listOf(32, 256, 4096)[random.nextInt(3)]
        val options = EngineOptions(
            CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024), maxDepth = 64),
            sorterBytes = 4096,
            keyArenaBytes = 1024,
            smallInsertThreshold = listOf(0L, 1L shl 22)[random.nextInt(2)],
            keySeed = seed,
            log = LogOptions(
                // Small lane files, so reclamation has sealed files to pick.
                laneFileBytes = listOf(4096L, 4096L, 1L shl 20)[random.nextInt(3)],
                controlFileBytes = listOf(4096L, 1L shl 20)[random.nextInt(2)],
                preallocateBytes = listOf(0L, 512L)[random.nextInt(2)],
                writeBufferBytes = 4096,
            ),
            memtableBytes = listOf(0L, 4L shl 10, EngineOptions.DEFAULT_MEMTABLE_BYTES)[random.nextInt(3)],
            backgroundFlush = false,
            segmentBlockBytes = listOf(256, 4096)[random.nextInt(2)],
            typeQueryPlan = TypeQueryPlan.entries[random.nextInt(3)],
            maxDeleteRecordDocIds = listOf(1, 2, EngineOptions.DEFAULT_MAX_DELETE_RECORD_DOC_IDS)[random.nextInt(3)],
            // Compaction runs inline after flushes, so crashes also hit its merges and commits.
            reclaim = ReclaimOptions(maxLiveRatio = listOf(0.5, 0.95)[random.nextInt(2)], bytesPerSecond = 0, maxRelocateFragments = listOf(1, 5, 16_384)[random.nextInt(3)]),
            compaction = if (random.nextBoolean()) {
                CompactionOptions(minMergeSegments = 2, deletedRatio = 0.01, tombstoneRatio = 0.01, bytesPerSecond = 0, maxRunBytes = 64, listMemoryBytes = 1024)
            } else {
                CompactionOptions(bytesPerSecond = 0)
            },
            compression = TestEngines.compression(random.nextInt(3)),
        )
        val windows = random.nextBoolean()
        val fs = SimulatedFileSystemIo(isWindows = windows)
        val expected = Expected()
        stats.scenarios++
        var calls = 0L
        repeat(CYCLES) { cycle ->
            val work = List(random.nextInt(4, 14)) { step(random) }
            if (cycle == 0) {
                val dry = SimulatedFileSystemIo(isWindows = windows)
                Engine.open(TestEngines.DIR, options, dry).use { store -> run(store, work, Expected(), null, stats, dry = true) }
                calls = dry.calls
            }
            fs.crashAfter(random.nextLong(1, calls + 1))
            val inProgress = InProgress()
            var store: Engine? = null
            try {
                store = Engine.open(TestEngines.DIR, options, fs)
                run(store, work, expected, inProgress, stats, dry = false)
            } catch (_: Exception) {
                // the crash
            } finally {
                runCatching { store?.close() }
            }
            if (fs.isDown) stats.crashedMidway++
            if (fs.isDown && inProgress.reclaiming) stats.crashedReclaiming++
            fs.crash(random)
            Engine.open(TestEngines.DIR, options, fs).use { reopened -> verify(reopened, expected, inProgress, stats, cycle) }
            fs.restart()
        }
    }

    /** The operation that was running when the store failed, if any. */
    private class InProgress {
        var insert: Step.Insert? = null
        var assignments: List<Pair<Node, String?>>? = null
        var deletes: List<Long>? = null

        /** The documents the in-progress [insert] replaces. */
        var replacing: List<Long>? = null
        var reclaiming = false
    }

    private fun step(random: Random): Step = when (random.nextInt(12)) {
        in 0..5 -> insertStep(random)
        in 6..8 -> Step.Assign(List(random.nextInt(1, 5)) { random.nextLong() })
        9 -> Step.Delete(List(random.nextInt(1, 5)) { random.nextLong() })
        10 -> Step.Replace(insertStep(random), List(random.nextInt(1, 4)) { random.nextLong() })
        else -> Step.Reclaim
    }

    private fun insertStep(random: Random): Step.Insert {
        val objects = random.nextInt(1, 7)
        val pad = "p".repeat(random.nextInt(0, 300))
        val json = """{"all":true,"objs":[${(0 until objects).joinToString(",") { """{"k":$it}""" }}],"pad":"$pad"}"""
        val types = HashMap<String, String>()
        for (j in 0 until objects) if (random.nextBoolean()) types["$.objs[$j]"] = NAMES[random.nextInt(NAMES.size)]
        return Step.Insert(json, types, objects)
    }

    private fun run(store: Engine, work: List<Step>, expected: Expected, inProgress: InProgress?, stats: Stats, dry: Boolean) {
        for (step in work) {
            when (step) {
                is Step.Insert -> {
                    inProgress?.insert = step
                    val docId = store.insert(step.json, step.types).raw
                    inProgress?.insert = null
                    expected.docs[docId] = step.json to step.objects
                    for ((path, name) in step.types) expected.types[Node(docId, objectPre(path))] = name
                    if (!dry) stats.acknowledgedInserts++
                }
                is Step.Assign -> {
                    if (expected.docs.isEmpty()) continue
                    val docIds = expected.docs.keys.toList()
                    val assignments = step.picks.map { pick ->
                        val r = Random(pick)
                        val docId = docIds[r.nextInt(docIds.size)]
                        Node(docId, 3L + 2 * r.nextInt(expected.docs.getValue(docId).second)) to if (r.nextInt(4) == 0) null else NAMES[r.nextInt(NAMES.size)]
                    }
                    inProgress?.assignments = assignments
                    store.assignTypes(assignments.map { (node, name) -> NodeRef(DocId(node.docId), node.pre) to name })
                    inProgress?.assignments = null
                    apply(expected.types, assignments)
                    if (!dry) stats.acknowledgedAssignments++
                }
                is Step.Delete -> {
                    if (expected.docs.isEmpty()) continue
                    // Mostly live documents, now and then one already deleted.
                    val candidates = expected.docs.keys.toList()
                    val deleted = expected.deleted.toList()
                    val docIds = step.picks.map { pick ->
                        val r = Random(pick)
                        if (deleted.isNotEmpty() && r.nextInt(5) == 0) deleted[r.nextInt(deleted.size)] else candidates[r.nextInt(candidates.size)]
                    }
                    inProgress?.deletes = docIds
                    val count = store.deleteAll(docIds.map { DocId(it) })
                    inProgress?.deletes = null
                    assertEquals(docIds.toSet().count { it in expected.docs }, count, "deleteAll $docIds")
                    expected.delete(docIds)
                    if (!dry) stats.acknowledgedDeletes++
                }
                is Step.Replace -> {
                    if (expected.docs.isEmpty()) continue
                    val candidates = expected.docs.keys.toList()
                    val deleted = expected.deleted.toList()
                    val docIds = step.picks.map { pick ->
                        val r = Random(pick)
                        if (deleted.isNotEmpty() && r.nextInt(8) == 0) deleted[r.nextInt(deleted.size)] else candidates[r.nextInt(candidates.size)]
                    }
                    val targets = docIds.map { DocId(it) }
                    val insert = step.insert
                    if (docIds.any { it !in expected.docs }) {
                        // Any other exception is the crash.
                        val e = try {
                            store.replace(targets, insert.json, insert.types)
                            throw AssertionError("a replace of $docIds didn't conflict")
                        } catch (conflict: WriteConflictException) {
                            conflict
                        }
                        assertEquals(docIds.filter { it !in expected.docs }.distinct().sorted(), e.conflicting.map { it.raw })
                        if (!dry) stats.conflicts++
                        continue
                    }
                    inProgress?.insert = insert
                    inProgress?.replacing = docIds
                    val docId = store.replace(targets, insert.json, insert.types).raw
                    inProgress?.insert = null
                    inProgress?.replacing = null
                    expected.delete(docIds)
                    expected.docs[docId] = insert.json to insert.objects
                    for ((path, name) in insert.types) expected.types[Node(docId, objectPre(path))] = name
                    if (!dry) stats.acknowledgedReplaces++
                }
                Step.Reclaim -> {
                    inProgress?.reclaiming = true
                    store.flush()
                    store.compact()
                    val n = store.reclaim()
                    inProgress?.reclaiming = false
                    if (!dry) stats.reclaimedFiles += n
                }
            }
        }
    }

    /** `$.objs[j]` is the object at pre 3 + 2j: the root is 0, `true` 1, the array 2, and each object is followed by its `k`. */
    private fun objectPre(path: String): Long = 3L + 2 * path.substringAfter('[').substringBefore(']').toLong()

    private fun apply(types: MutableMap<Node, String>, assignments: List<Pair<Node, String?>>) {
        for ((node, name) in assignments) if (name == null) types.remove(node) else types[node] = name
    }

    private fun verify(store: Engine, expected: Expected, inProgress: InProgress, stats: Stats, cycle: Int) {
        val visible = store.documents()
        inProgress.deletes?.let { docIds ->
            val targets = docIds.filter { it in expected.docs }.toSet()
            val gone = targets.filter { it !in visible }
            when {
                gone.isEmpty() -> if (targets.isNotEmpty()) stats.deletesLost++
                gone.size == targets.size -> {
                    expected.delete(targets)
                    stats.deletesApplied++
                }
                else -> throw AssertionError("a delete call was applied in part: $docIds, gone $gone")
            }
        }
        inProgress.replacing?.let { docIds ->
            val insert = inProgress.insert!!
            val committed = visible.any { (docId, json) -> docId !in expected.docs && json == insert.json }
            val gone = docIds.filter { it !in visible }.toSet()
            when {
                committed && gone == docIds.toSet() -> {
                    expected.delete(docIds)
                    stats.replacesApplied++
                }
                !committed && gone.isEmpty() -> stats.replacesLost++
                else -> throw AssertionError("a replace was applied in part: its document ${if (committed) "is" else "isn't"} visible, of $docIds $gone are gone")
            }
        }
        for ((docId, doc) in expected.docs) assertEquals(doc.first, visible[docId], "acknowledged document $docId after the crash in cycle $cycle")
        for (docId in expected.deleted) {
            assertFalse(docId in visible, "deleted document $docId reappeared after the crash in cycle $cycle")
            assertFailsWith<DocumentNotFoundException> { store.typeOf(NodeRef(DocId(docId), 3)) }
            assertFalse(store.delete(DocId(docId)))
            stats.deletedDocs++
        }
        for ((docId, json) in visible) {
            if (docId in expected.docs) continue
            val insert = inProgress.insert
            assertTrue(insert != null && insert.json == json, "document $docId is visible but its insert wasn't in progress")
            // Committed without an acknowledgement: all of its types came with it.
            expected.docs[docId] = json to insert.objects
            for ((path, name) in insert.types) expected.types[Node(docId, objectPre(path))] = name
        }
        inProgress.assignments?.let { assignments ->
            val before = HashMap(expected.types)
            val after = HashMap(expected.types).also { apply(it, assignments) }
            val actual = assignments.map { (node, _) -> node to store.typeOf(NodeRef(DocId(node.docId), node.pre)) }
            when {
                actual.all { (node, type) -> type == before[node] } -> {
                    if (actual.any { (node, _) -> before[node] != after[node] }) stats.batchesLost++
                }
                actual.all { (node, type) -> type == after[node] } -> {
                    expected.types.clear()
                    expected.types.putAll(after)
                    stats.batchesApplied++
                }
                else -> throw AssertionError("a batch of assignments was applied in part: $assignments, now $actual")
            }
        }
        for ((docId, doc) in expected.docs) {
            for (j in 0 until doc.second) {
                val node = Node(docId, 3L + 2 * j)
                assertEquals(expected.types[node], store.typeOf(NodeRef(DocId(docId), node.pre)), "type of $node after the crash in cycle $cycle")
            }
        }
        for (name in NAMES) {
            val nodes = expected.types.filterValues { it == name }.keys.sortedWith(compareBy({ it.docId }, { it.pre }))
            assertEquals(nodes.map { NodeRef(DocId(it.docId), it.pre) }, store.objectsOfType(name, limit = 100_000), "objects of $name")
            assertEquals(nodes.size.toLong(), store.countOfType(name))
            stats.typedNodes += nodes.size
            // k = 0 matches the first object of each document.
            val hits = store.search(KeyValueQuery("k", JsonQueryValue.Num(0), ResultMode.CONTAINING_ELEMENT, limit = 1000, type = name)).use { result ->
                result.iterator().asSequence().map { Node(it.node.docId.raw, it.node.nodePre) }.toList()
            }
            assertEquals(nodes.filter { it.pre == 3L }, hits, "search k = 0 in objects of $name")
        }
        assertEquals(0, store.damagedDocIds.size)
    }

    private companion object {
        const val CYCLES = 3
        val NAMES = listOf("A", "Bé", "C")
    }
}
