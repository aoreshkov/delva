package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.term.PathHash
import app.oreshkov.delva.internal.term.PostingEntryReader
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The path hash in every posting (SPEC.md §15) is the hash of the label path of its containing object's parent,
 * computed here from the JSON tree alone. Documents are split into tiny fragments, so postings come from SUBTREEs,
 * PARTs of objects and arrays, and in-array postings whose owner object is in another fragment; and the store is reopened,
 * so the postings rebuilt from the log's fragment contexts are checked too.
 */
class PathHashEngineTest {
    private fun options(t: Int) = EngineOptions(
        CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024), maxDepth = 512),
        sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 13, log = TestEngines.LOG, backgroundFlush = false,
    )

    /** `pre` → path hash of the node at that `pre`, and `pre` → `pre` of its parent. */
    private fun paths(document: OValue): Pair<Map<Long, Int>, Map<Long, Long>> {
        val hashes = HashMap<Long, Int>()
        val parents = HashMap<Long, Long>()
        var next = 0L
        fun walk(v: OValue, hash: Int, parent: Long) {
            val pre = next++
            hashes[pre] = hash
            parents[pre] = parent
            when (v) {
                is OObj -> for ((k, m) in v.members) {
                    val key = k.toByteArray()
                    walk(m, PathHash.member(hash, key, 0, key.size), pre)
                }
                is OArr -> for (m in v.items) walk(m, PathHash.element(hash), pre)
                else -> {}
            }
        }
        walk(document, PathHash.ROOT, -1)
        return hashes to parents
    }

    /** Every posting of [docId] in the active memtable: (containerPre, pathHash). */
    private fun postings(store: Engine, docId: Long): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        val reader = PostingEntryReader()
        for (run in store.postings.allRuns()) {
            if (run.docId != docId) continue
            reader.reset(run.entries, 0, run.entries.size, run.count)
            while (reader.next()) out += reader.containerPre to reader.pathHash
        }
        return out
    }

    @Test
    fun `every posting carries the path hash of its container's parent`() {
        val gen = Gen { random, size -> JsonGen(JsonProfile.entries[random.nextInt(JsonProfile.entries.size)], fragmentSize = 64).value().generate(random, size) }
        var checked = 0
        for (t in listOf(32, 256)) {
            forAll("path hashes T=$t", gen, iterations = 60) { document ->
                val fs = SimulatedFileSystemIo()
                val (hashes, parents) = paths(document)
                fun check(store: Engine, docId: Long, what: String) {
                    for ((container, hash) in postings(store, docId)) {
                        val parent = parents.getValue(container)
                        val expected = PathHash.posting(if (parent < 0) PathHash.NONE else hashes.getValue(parent))
                        assertEquals(expected, hash, "$what: posting of container $container in ${JsonText.minified(document)}")
                        checked++
                    }
                }
                val docId = TestEngines.open(options(t), fs).use { store ->
                    store.insert(JsonText.minified(document)).raw.also { check(store, it, "live") }
                }
                fs.restart()
                TestEngines.open(options(t), fs).use { store -> check(store, docId, "recovered") }
            }
        }
        // Profiles are drawn at random, so the count varies by seed (4 146 seen); this only guards against a vacuous run.
        assertTrue(checked > 1000, "$checked postings checked")
    }
}
