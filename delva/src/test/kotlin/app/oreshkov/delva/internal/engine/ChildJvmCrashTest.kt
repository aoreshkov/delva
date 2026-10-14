package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.StoreLockedException
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import app.oreshkov.delva.internal.log.LogOptions
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real crash smoke tests: a child JVM writes in a loop and reports each acknowledged docId; the parent kills it with
 * `destroyForcibly()` at a random time and reopens the store. Several rounds on one store.
 *
 * - Inserts: every acknowledged document is there, and any other visible document is the one that was being inserted.
 *   While the child runs, opening the store fails with [StoreLockedException] naming the child as the holder.
 * - Replaces: each document replaces the one before it, so exactly one is visible after every kill, the last
 *   acknowledged one or the one that was being written: a replace never takes effect in part.
 * - Upserts: documents of five keys in turn; after every kill each key has exactly one visible document, the last
 *   acknowledged one or the one that was being written.
 */
class ChildJvmCrashTest {
    @Test
    fun `a killed process loses no acknowledged insert`() {
        val dir = Files.createTempDirectory("delva-crash")
        val random = Random(System.getProperty("delva.seed")?.toLong() ?: System.nanoTime())
        try {
            val acked = HashMap<Long, Int>()
            var next = 0
            repeat(ROUNDS) { round ->
                val whileRunning = { process: Process ->
                    // The other process's view of the lock: the child's pid, from its record.
                    val e = assertFailsWith<StoreLockedException> { JsonStore.open(dir) }
                    assertEquals(process.pid(), e.holder?.pid, "round $round: the holder")
                    assertEquals(process.info().startInstant().orElse(null), e.holder?.startedAt)
                }
                runChild(dir, random, next.toString(), whileRunning = whileRunning) { docId, i ->
                    acked[docId] = i
                    next = i + 1
                }
                Engine.open(dir, crashChildOptions(), JdkFileSystemIo()).use { store ->
                    val visible = store.documents()
                    for ((docId, i) in acked) assertEquals(CrashChild.doc(i), visible[docId], "round $round: acknowledged document $docId")
                    for ((docId, json) in visible) {
                        if (docId in acked) continue
                        assertEquals(CrashChild.doc(next), json, "round $round: unacknowledged visible document $docId")
                        acked[docId] = next
                        next++
                    }
                    assertEquals(0, store.damagedDocIds.size)
                }
                println("round $round: ${acked.size} documents")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a killed process applies no replace in part`() {
        val dir = Files.createTempDirectory("delva-crash-replace")
        val random = Random(System.getProperty("delva.seed")?.toLong() ?: System.nanoTime())
        try {
            var live = 0L
            var next = 0
            var replaced = 0
            repeat(ROUNDS) { round ->
                var lastAcked: Pair<Long, Int>? = null
                runChild(dir, random, next.toString(), "replace", live.toString()) { docId, i ->
                    lastAcked = docId to i
                    replaced++
                }
                lastAcked?.let { (docId, i) ->
                    live = docId
                    next = i + 1
                }
                Engine.open(dir, crashChildOptions(), JdkFileSystemIo()).use { store ->
                    val visible = store.documents()
                    if (live == 0L && visible.isEmpty()) return@use
                    val (docId, json) = visible.entries.singleOrNull()?.toPair() ?: throw AssertionError("round $round: ${visible.size} documents visible: ${visible.keys}")
                    if (docId == live) {
                        assertEquals(CrashChild.doc(next - 1), json, "round $round: the last acknowledged document")
                    } else {
                        assertTrue(docId > live, "round $round: document $docId came back")
                        assertEquals(CrashChild.doc(next), json, "round $round: the unacknowledged replace")
                        live = docId
                        next++
                    }
                    assertEquals(0, store.damagedDocIds.size)
                    assertEquals(0, store.verify().issues.size, "round $round: verify")
                }
                println("round $round: $replaced replaces acknowledged")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a killed process leaves one document per key`() {
        val dir = Files.createTempDirectory("delva-crash-upsert")
        val random = Random(System.getProperty("delva.seed")?.toLong() ?: System.nanoTime())
        try {
            // key → index of the last document written with it.
            val current = HashMap<String, Int>()
            var next = 0
            repeat(ROUNDS) { round ->
                runChild(dir, random, next.toString(), "upsert") { _, i ->
                    current[CrashChild.key(i)] = i
                    next = maxOf(next, i + 1)
                }
                Engine.open(dir, crashChildOptions(keyed = true), JdkFileSystemIo()).use { store ->
                    val visible = store.documents()
                    val byKey = visible.entries.groupBy { (_, json) -> CrashChild.key(Regex(""""i":(\d+)""").find(json)!!.groupValues[1].toInt()) }
                    for ((key, docs) in byKey) assertEquals(1, docs.size, "round $round: documents of key $key: ${docs.map { it.key }}")
                    // The document being written when the child was killed may have been committed.
                    val pending = CrashChild.key(next)
                    val written = visible.values.firstOrNull { it == CrashChild.keyedDoc(next) }
                    if (written != null) {
                        current[pending] = next
                        next++
                    }
                    assertEquals(current.mapValues { (_, i) -> CrashChild.keyedDoc(i) }, byKey.mapValues { (_, docs) -> docs.single().value }, "round $round")
                    for ((key, docs) in byKey) assertEquals(docs.single().key, store.get(key)?.raw, "round $round: get $key")
                    assertEquals(0, store.damagedDocIds.size)
                    assertEquals(emptyList(), store.verify().issues.map { it.toString() }, "round $round: verify")
                }
                println("round $round: ${current.size} keys, $next documents written")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * Runs [CrashChild] with [args] after the directory, calls [whileRunning] once it acknowledged a write, kills it at a
     * random time, and reports every acknowledgement.
     */
    private fun runChild(dir: Path, random: Random, vararg args: String, whileRunning: (Process) -> Unit = {}, onAck: (Long, Int) -> Unit) {
        val process = ProcessBuilder(
            listOf(ProcessHandle.current().info().command().orElse("java"), "-cp", System.getProperty("java.class.path"), CrashChild::class.java.name, dir.toString()) + args,
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val killAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(random.nextLong(300, 1500))
        val reader = process.inputStream.bufferedReader()
        var started = false
        val ended: Boolean
        try {
            while (System.nanoTime() < killAt || !started) {
                val ack = readAck(reader, onAck) ?: break
                if (ack && !started) {
                    started = true
                    whileRunning(process)
                }
            }
        } finally {
            // Also when an assertion failed: the child would otherwise write forever.
            // Through the handle: Process.destroyForcibly also closes the pipe, and the lines left in it are read below.
            process.toHandle().destroyForcibly()
            ended = process.waitFor(30, TimeUnit.SECONDS)
        }
        assertTrue(ended, "the child didn't end within 30 seconds of the kill")
        // Lines written before the kill still count.
        while (readAck(reader, onAck) != null) {
            // next line
        }
    }

    /** Reads one line: true for an acknowledgement, false for another line, null at the end. */
    private fun readAck(reader: BufferedReader, onAck: (Long, Int) -> Unit): Boolean? {
        val parts = (reader.readLine() ?: return null).split(' ')
        if (parts[0] != "ACK") return false
        onAck(parts[1].toLong(), parts[2].toInt())
        return true
    }

    private companion object {
        const val ROUNDS = 4
    }
}

internal fun crashChildOptions(keyed: Boolean = false) = EngineOptions(
    CreationOptions(fragmentBytes = 256).withDocumentKey(if (keyed) "$.id" else null), StoreOptions(encoderBudgetBytes = 1024),
    smallInsertThreshold = 4096,
    log = LogOptions(laneFileBytes = 64 * 1024, controlFileBytes = 16 * 1024, preallocateBytes = 8192),
    // Flushes on the flush thread every few dozen documents, so kills also hit segment and MANIFEST writes.
    memtableBytes = 64L * 1024,
)

/**
 * Options under which the writer of `CrashChild … churn` flushes, compacts and reclaims lane files all the time: tiny
 * log files and memtables, compaction from two segments on, and reclamation of any file a tenth dead, unthrottled.
 */
internal fun churnChildOptions() = EngineOptions(
    CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024),
    smallInsertThreshold = 4096,
    log = LogOptions(laneFileBytes = 16 * 1024, controlFileBytes = 8 * 1024, preallocateBytes = 4096),
    memtableBytes = 16L * 1024,
    compaction = CompactionOptions(minMergeSegments = 2, bytesPerSecond = 0),
    reclaim = ReclaimOptions(maxLiveRatio = 0.9, bytesPerSecond = 0),
)

/**
 * The child process of [ChildJvmCrashTest]: `CrashChild <dir> <first index>` inserts documents, and
 * `CrashChild <dir> <first index> replace <docId>` replaces the document `docId` (none when 0), then each document it
 * wrote with the next, and `CrashChild <dir> <first index> upsert` upserts documents of five keys in turn in a store
 * with document keys. `CrashChild <dir> <first index> churn` ([churnChildOptions]) inserts documents and, after every
 * other one, deletes the oldest live document, reporting `DEL <docId>` once a delete is acknowledged.
 */
object CrashChild {
    fun doc(i: Int): String = """{"all":true,"i":$i,"items":[${(0 until i % 40).joinToString(",") { """{"k":$it,"s":"${"z".repeat(it * 3)}"}""" }}]}"""

    fun key(i: Int): String = "k${i % 5}"

    fun keyedDoc(i: Int): String = """{"all":true,"id":"${key(i)}",""" + doc(i).removePrefix("""{"all":true,""")

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.getOrNull(2) == "churn") return churn(Path.of(args[0]), args[1].toInt())
        val replacing = args.getOrNull(2) == "replace"
        val upserting = args.getOrNull(2) == "upsert"
        var previous = if (replacing) args[3].toLong() else 0L
        Engine.open(Path.of(args[0]), crashChildOptions(keyed = upserting), JdkFileSystemIo()).use { store ->
            var i = args[1].toInt()
            while (true) {
                val docId = when {
                    upserting -> store.upsert(keyedDoc(i))
                    previous > 0 -> store.replace(listOf(DocId(previous)), doc(i))
                    else -> store.insert(doc(i))
                }.raw
                println("ACK $docId $i")
                System.out.flush()
                if (replacing) previous = docId
                i++
            }
        }
    }

    private fun churn(dir: Path, first: Int) {
        Engine.open(dir, churnChildOptions(), JdkFileSystemIo()).use { store ->
            val live = ArrayDeque(store.documents().keys.sorted())
            var i = first
            while (true) {
                val docId = store.insert(doc(i)).raw
                println("ACK $docId $i")
                live.addLast(docId)
                if (i % 2 == 1 && live.size > 1) {
                    val oldest = live.removeFirst()
                    store.delete(DocId(oldest))
                    println("DEL $oldest")
                }
                System.out.flush()
                i++
            }
        }
    }
}
