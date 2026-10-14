package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Secondaries beside a real writer process: a child JVM ([CrashChild] `churn`) inserts and deletes documents while
 * it flushes, compacts and reclaims lane files all the time, and is killed at a random moment; then the parent recovers
 * the store as its writer, and the next round starts another child. Throughout, secondaries in this process refresh and
 * read in a loop, one open from the start and one opened while each child runs, on the real file system:
 *
 * - every refresh shows every document whose insert the child acknowledged before the refresh began, as written, and
 *   none whose delete it acknowledged before; any other document it shows is one the child was writing;
 * - after each kill, once the parent recovered the store, a refresh shows exactly the recovered documents, and both the
 *   recovered writer and the secondaries verify clean.
 */
class SecondaryCrashTest {
    @Test
    fun `secondaries follow a writer process that is killed, and agree with its recovery`() {
        val dir = Files.createTempDirectory("delva-secondary-crash")
        val random = Random(System.getProperty("delva.seed")?.toLong() ?: System.nanoTime())
        try {
            // Creates the store, so the first secondary has one to open.
            Engine.open(dir, churnChildOptions(), JdkFileSystemIo()).close()
            val options: (CreationOptions) -> EngineOptions = { EngineOptions(it, StoreOptions()) }
            SecondaryEngine.open(dir, JdkFileSystemIo(), options = options).use { longLived ->
                var next = 0
                var refreshes = 0L
                var epochs = 0L
                repeat(ROUNDS) { round ->
                    val events = Collections.synchronizedList(ArrayList<Event>())
                    val child = ProcessBuilder(
                        listOf(ProcessHandle.current().info().command().orElse("java"), "-cp", System.getProperty("java.class.path"), CrashChild::class.java.name, dir.toString(), next.toString(), "churn"),
                    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
                    val reader = thread(name = "child-output") {
                        child.inputStream.bufferedReader().forEachLine { line ->
                            val parts = line.split(' ')
                            when (parts[0]) {
                                "ACK" -> events += Event(parts[1].toLong(), parts[2].toInt())
                                "DEL" -> events += Event(parts[1].toLong(), -1)
                            }
                        }
                    }
                    val killAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(random.nextLong(1000, 4000))
                    var fresh: SecondaryEngine? = null
                    // Acknowledged documents a refresh didn't show: checked once the child's every report is read.
                    val missedWhileRunning = HashSet<Long>()
                    try {
                        try {
                            while (System.nanoTime() < killAt) {
                                if (fresh == null && events.size > 20) fresh = SecondaryEngine.open(dir, JdkFileSystemIo(), options = options)
                                for (secondary in listOfNotNull(longLived, fresh)) {
                                    val known = synchronized(events) { events.toList() }
                                    secondary.refresh()
                                    val name = if (secondary === longLived) "long-lived" else "fresh"
                                    try {
                                        missedWhileRunning += check(secondary, known, "round $round, $name secondary")
                                    } catch (e: AssertionError) {
                                        val before = "view LSN ${secondary.viewLsn}, ${secondary.epochsOpened} epochs, ${secondary.stats.controlRecordsScanned} records scanned"
                                        secondary.refresh()
                                        val again = runCatching { check(secondary, known, "again") }.exceptionOrNull()
                                        throw AssertionError("$before; after another refresh: view LSN ${secondary.viewLsn}, ${again?.message ?: "it agrees"}", e)
                                    }
                                    refreshes++
                                }
                            }
                        } finally {
                            child.destroyForcibly()
                            assertTrue(child.waitFor(30, TimeUnit.SECONDS))
                            reader.join()
                        }
                        // The parent recovers the store as its writer; the secondaries show what it recovered.
                        Engine.open(dir, churnChildOptions(), JdkFileSystemIo()).use { writer ->
                            val recovered = writer.documents()
                            // Only the delete the child made but didn't report before it was killed may be unaccounted for.
                            val unreported = check(writer, events, "round $round, recovered", allowance = 1)
                            val deleted = events.filter { it.index < 0 }.map { it.docId }.toSet()
                            val unexplained = missedWhileRunning - deleted
                            assertTrue(
                                unreported.containsAll(unexplained),
                                "round $round: refreshes didn't show $unexplained, which the child never reported deleting",
                            )
                            assertEquals(emptyList(), writer.verify().issues, "round $round: the writer verifies")
                            for (secondary in listOfNotNull(longLived, fresh)) {
                                secondary.refresh()
                                assertEquals(recovered, secondary.documents(), "round $round: a secondary after recovery")
                                assertEquals(emptyList(), secondary.verify().issues, "round $round: a secondary verifies")
                                assertEquals(0, secondary.refusedCalls)
                            }
                            next = (recovered.values.maxOfOrNull { indexOf(it) } ?: -1).coerceAtLeast(events.maxOfOrNull { it.index } ?: -1) + 1
                        }
                    } finally {
                        fresh?.close()
                    }
                    epochs = longLived.epochsOpened
                    println("round $round: ${events.count { it.index >= 0 }} inserts and ${events.count { it.index < 0 }} deletes acknowledged; $refreshes refreshes, $epochs epochs so far")
                }
                assertTrue(epochs > ROUNDS, "the long-lived secondary followed flushes and compactions: $epochs epochs")
                assertTrue(longLived.stats.replayedRelocations > 0, "the long-lived secondary followed space reclamation")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** An acknowledgement: the insert of document [index] as [docId], or with index -1 the delete of [docId]. */
    private class Event(val docId: Long, val index: Int)

    /**
     * What [store] shows agrees with the acknowledgements [known], all made before it read: every acknowledged insert
     * not deleted since is there as written, except the oldest ones, at most [allowance] of them, which deletes the
     * child made but hadn't reported yet may have taken (the child deletes its oldest document); no acknowledged delete
     * is there; and anything else is a document being written. Returns the acknowledged documents missing.
     */
    private fun check(store: ReadEngine, known: List<Event>, label: String, allowance: Int = Int.MAX_VALUE): List<Long> {
        val visible = store.documents()
        val inserted = HashMap<Long, Int>()
        val deleted = HashSet<Long>()
        for (e in known) if (e.index >= 0) inserted[e.docId] = e.index else deleted += e.docId
        val live = inserted.keys.filter { it !in deleted }.sorted()
        val missing = live.filter { it !in visible }
        assertEquals(live.take(missing.size), missing, "$label: acknowledged documents missing, not the oldest")
        assertTrue(missing.size <= allowance, "$label: ${missing.size} acknowledged documents missing, at most $allowance may be: $missing")
        for (docId in live.drop(missing.size)) assertEquals(CrashChild.doc(inserted[docId]!!), visible[docId], "$label: acknowledged document $docId")
        for (docId in deleted) if (docId in visible) fail("$label: document $docId shows after its delete was acknowledged")
        for ((docId, json) in visible) {
            if (docId in inserted) continue
            assertEquals(CrashChild.doc(indexOf(json)), json, "$label: unacknowledged document $docId")
        }
        return missing
    }

    private fun indexOf(json: String): Int = Regex(""""i":(\d+)""").find(json)!!.groupValues[1].toInt()

    private companion object {
        const val ROUNDS = 5

    }
}
