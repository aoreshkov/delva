package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.DocId
import app.oreshkov.delva.Durability
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.internal.engine.SecondaryFixtures.SMALL_LOG
import app.oreshkov.delva.internal.engine.SecondaryFixtures.State
import app.oreshkov.delva.internal.engine.SecondaryFixtures.doc
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo.Platform
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Randomized scenarios of a secondary beside a writer process on one simulated disk, on every platform model:
 *
 * - **Following** a busy writer (inserts, deletes, replaces, types, upserts, flushes, compactions, reclamation) whose
 *   newest write a reader may see page-torn: each refresh shows a state the writer acknowledged, never one older than
 *   the refresh before, and one more after the writer stops shows the writer's state. Documents, types and document keys
 *   agree, the secondary verifies clean and refuses no call.
 * - **A writer that dies** at a random disk call, without a power loss: a new writer recovers the store, and the
 *   secondary, which refreshed throughout, shows what it recovered.
 *
 * `-Ddelva.sim.scenarios=N` sets the number of scenarios of each (100 by default, 2 000 with
 * `-Pdelva.testProfile=long`); `-Ddelva.seed=S` the first seed.
 */
class SecondarySimTest {
    private val dir = TestEngines.DIR

    private fun scenarios(name: String, run: (Long) -> Unit) {
        val long = System.getProperty("delva.testProfile") == "long"
        val count = System.getProperty("delva.sim.scenarios")?.toInt() ?: if (long) 2_000 else 100
        val base = System.getProperty("delva.seed")?.toLong() ?: 0x5ECL
        for (s in 0 until count) {
            val seed = base + s
            try {
                run(seed)
            } catch (e: Throwable) {
                throw AssertionError("scenario $seed failed; reproduce: ./gradlew :delva:simTest --tests '*SecondarySimTest.$name*' -Ddelva.seed=$seed -Ddelva.sim.scenarios=1", e)
            }
        }
    }

    @Test
    fun `refreshes beside a busy writer show states it acknowledged, in order, and converge`() = scenarios("refreshes") { seed ->
        val random = Random(seed)
        val platform = Platform.entries[random.nextInt(Platform.entries.size)]
        val keyed = random.nextInt(3) == 0
        val options = SecondaryFixtures.options(
            documentKey = if (keyed) "$.id" else null,
            durability = if (random.nextBoolean()) Durability.SyncEachBatch else Durability.None,
            memtableBytes = listOf(8L shl 10, 64L shl 10)[random.nextInt(2)],
            log = SMALL_LOG,
            compression = random.nextInt(3),
        )
        val label = "$platform keyed=$keyed"
        val fs = SimulatedFileSystemIo(platform)
        val writer = Engine.open(dir, options, fs)
        writer.insert(doc(random, -1))
        val reader = fs.process()
        val secondary = SecondaryEngine.open(dir, reader, SimulatedFileSystemIo()) { EngineOptions(it, options.store) }
        reader.tearReads(Random(seed + 1000), landOnNextCall = true)
        val history = ArrayList<State>()
        history += writer.state()
        var seen = 0
        val live = ArrayList<DocId>(writer.documents().keys.map(::DocId))
        for (i in 0 until 120) {
            when (random.nextInt(14)) {
                in 0..4 -> live += writer.insert(doc(random, i, big = random.nextInt(6) == 0), if (random.nextBoolean()) mapOf("$" to "Root") else emptyMap())
                5 -> if (live.isNotEmpty()) writer.deleteAll(live.shuffled(random).take(random.nextInt(1, 3)).also { live.removeAll(it.toSet()) })
                6 -> if (live.isNotEmpty()) {
                    val old = live.removeAt(random.nextInt(live.size))
                    live += writer.replace(listOf(old), doc(random, i), mapOf("$" to "Replaced"))
                }
                7 -> if (live.isNotEmpty()) writer.assignTypes(live.shuffled(random).take(2).map { NodeRef(it, 0) to if (random.nextBoolean()) "Tagged" else null })
                8 -> if (keyed && live.isNotEmpty()) {
                    // An upsert of a key that exists: the old document goes.
                    val target = live.removeAt(random.nextInt(live.size))
                    val key = writer.keyOf(target)
                    live += writer.upsert(doc(random, i).replace("\"id\":\"d$i\"", "\"id\":\"$key\""))
                }
                9 -> writer.flush()
                10 -> writer.compact()
                11 -> writer.reclaim()
                else -> {}
            }
            history += writer.state()
            if (random.nextInt(3) == 0) {
                secondary.refresh()
                val after = history.subList(seen, history.size).indexOf(secondary.state())
                assertTrue(after >= 0, "$label op $i: the secondary shows a state the writer never had at or after state $seen of ${history.size}")
                seen += after
            }
        }
        reader.tearReads(null)
        secondary.refresh()
        assertEquals(writer.state(), secondary.state(), label)
        assertEquals(writer.itemHits(), secondary.itemHits(), label)
        assertEquals(emptyList(), secondary.verify().issues, label)
        assertEquals(0, secondary.refusedCalls, label)
        secondary.close()
        writer.close()
        reader.exit()
    }

    @Test
    fun `a writer that dies at any disk call reopens and the secondary converges`() {
        var died = 0
        var total = 0
        scenarios("a writer") { seed ->
            val random = Random(seed)
            val platform = Platform.entries[random.nextInt(Platform.entries.size)]
            val options = SecondaryFixtures.options(
                memtableBytes = 8L shl 10,
                log = SMALL_LOG,
                durability = if (random.nextBoolean()) Durability.SyncEachBatch else Durability.None,
            )
            val fs = SimulatedFileSystemIo(platform)
            Engine.open(dir, options, fs).use { writer -> repeat(5) { writer.insert(doc(random, it)) } }
            val secondary = SecondaryFixtures.secondary(fs, options)
            val process = fs.process()
            process.exitAfter(random.nextLong(1, 3000))
            total++
            try {
                Engine.open(dir, options, process).use { writer ->
                    for (i in 0 until 200) {
                        when (random.nextInt(6)) {
                            0 -> writer.flush()
                            1 -> writer.compact()
                            2 -> writer.documents().keys.firstOrNull()?.let { writer.delete(DocId(it)) }
                            else -> writer.insert(doc(random, i, big = random.nextInt(5) == 0))
                        }
                        if (random.nextInt(4) == 0) secondary.refresh()
                    }
                }
            } catch (_: Exception) {
                // The writer died: an IOException, or EngineFailedException once its engine noticed.
                died++
            }
            process.exit()
            secondary.refresh()
            Engine.open(dir, options, fs.process()).use { writer ->
                secondary.refresh()
                assertEquals(writer.documents(), secondary.documents(), "$platform")
                assertEquals(emptyList(), writer.verify().issues, "$platform")
                assertEquals(emptyList(), secondary.verify().issues, "$platform")
            }
            assertEquals(0, secondary.refusedCalls, "$platform")
            secondary.close()
        }
        assertTrue(died * 4 >= total * 3, "only $died of $total writers died before their work was done")
    }
}
