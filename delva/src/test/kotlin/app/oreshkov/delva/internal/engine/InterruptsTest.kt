package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.Durability
import app.oreshkov.delva.EngineFailedException
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.io.uninterruptibly
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogOptions
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Interrupted callers of a store: they never break it for other callers, and never learn a false outcome. */
class InterruptsTest {
    private fun <T> withTempDir(block: (Path) -> T): T {
        val dir = Files.createTempDirectory("delva-interrupts")
        try {
            return block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /** Small files, segments and no caches, so searches read the disk. */
    private val diskOptions = EngineOptions(
        CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024),
        smallInsertThreshold = 2048,
        log = LogOptions(laneFileBytes = 16 shl 10, controlFileBytes = 8 shl 10, preallocateBytes = 2048, writeBufferBytes = 4096),
        memtableBytes = 16L shl 10, blockCacheBytes = 0, fragmentCacheBytes = 0,
    )

    private fun doc(i: Int) = """{"all":true,"i":$i,"pad":[${(0 until i % 20).joinToString(",") { "\"${"p".repeat(it * 3)}\"" }}]}"""

    @Test
    fun `readers interrupted while a writer inserts read intact data, and the store stays usable`() = withTempDir { dir ->
        val expected = HashMap<Long, String>()
        Engine.open(dir, diskOptions, JdkFileSystemIo()).use { store ->
            repeat(100) { i -> doc(i).let { expected[store.insert(it).raw] = it } }
            val failure = AtomicReference<Throwable>()
            val writing = AtomicBoolean(true)
            val readers = List(8) {
                thread(name = "reader-$it") {
                    try {
                        while (writing.get() && failure.get() == null) {
                            Thread.interrupted()
                            for ((docId, json) in store.documents()) {
                                synchronized(expected) { expected[docId] }?.let { assertEquals(it, json, "document $docId") }
                            }
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }
            val interrupter = thread(name = "interrupter") {
                while (writing.get()) for (reader in readers) reader.interrupt()
            }
            try {
                repeat(300) { i ->
                    val json = doc(100 + i)
                    val docId = store.insert(json).raw
                    synchronized(expected) { expected[docId] = json }
                }
            } finally {
                writing.set(false)
                interrupter.join()
                readers.forEach { it.join() }
            }
            failure.get()?.let { throw it }
            assertEquals(expected, store.documents())
            assertEquals(emptyList(), store.verify().issues.map { it.toString() })
        }
        Engine.open(dir, diskOptions, JdkFileSystemIo()).use { store ->
            assertEquals(expected, store.documents())
        }
    }

    @Test
    fun `an insert interrupted while it waits for the writer returns its DocId, keeps the interrupt and its resources till then`() {
        for (large in listOf(false, true)) {
            val fs = SimulatedFileSystemIo()
            TestEngines.open(EngineOptions(CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024), log = TestEngines.LOG, smallInsertThreshold = 4096), fs).use { store ->
                store.insert(doc(0))
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                // The writer syncs the control log before it acknowledges the insert.
                fs.beforeForce { path ->
                    if (path.parent.fileName.toString() == "control" && entered.count > 0) {
                        entered.countDown()
                        uninterruptibly { release.await() }
                    }
                }
                val json = if (large) """{"all":true,"items":[${(0 until 2000).joinToString(",") { "\"item $it\"" }}]}""" else doc(1)
                var docId: DocId? = null
                var failure: Throwable? = null
                var keptInterrupt = false
                val inserter = thread {
                    try {
                        docId = store.insert(json)
                        keptInterrupt = Thread.currentThread().isInterrupted
                    } catch (t: Throwable) {
                        failure = t
                    }
                }
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS), "large=$large: the writer syncs")
                    inserter.interrupt()
                    inserter.join(200)
                    assertTrue(inserter.isAlive, "large=$large: the insert still waits for the writer")
                    assertTrue(store.budget.availableBytes < store.budget.totalBytes, "large=$large: and holds its budget")
                    if (large) assertEquals(store.lanes.count - 1, store.lanes.freeCount, "and its lane")
                } finally {
                    release.countDown()
                    inserter.join()
                }
                failure?.let { throw it }
                assertTrue(keptInterrupt, "large=$large: the interrupt is kept")
                assertEquals(json, store.documents()[docId!!.raw], "large=$large")
                assertEquals(store.budget.totalBytes, store.budget.availableBytes, "large=$large")
                assertEquals(store.lanes.count, store.lanes.freeCount, "large=$large")
            }
        }
    }

    private fun delvaThreads(): Set<Thread> = Thread.getAllStackTraces().keys.filter { it.name.startsWith("delva-") }.toSet()

    /** A store on [fs] with background threads, flushed segments and open lane files. */
    private val busyOptions = EngineOptions(CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024), log = TestEngines.LOG, memtableBytes = 16L shl 10)

    private fun busyStore(fs: SimulatedFileSystemIo): Engine =
        TestEngines.open(busyOptions, fs).also { store ->
            repeat(200) { store.insert(doc(it)) }
        }

    @Test
    fun `close with the interrupt flag set stops every thread, closes every file and keeps the interrupt`() {
        val fs = SimulatedFileSystemIo()
        val before = delvaThreads()
        val store = busyStore(fs)
        val expected = store.documents()
        Thread.currentThread().interrupt()
        try {
            store.close()
            assertTrue(Thread.currentThread().isInterrupted, "the interrupt is kept")
        } finally {
            Thread.interrupted()
        }
        assertEquals(emptySet(), delvaThreads() - before, "no thread of the store outlives close")
        assertEquals(0, fs.openHandles, "every file is closed")
        TestEngines.open(busyOptions, fs).use { assertEquals(expected, it.documents()) }
    }

    @Test
    fun `a close whose MANIFEST write fails still stops every thread, closes every file and releases the lock`() {
        val fs = SimulatedFileSystemIo()
        val before = delvaThreads()
        val store = busyStore(fs)
        val expected = store.documents()
        fs.failNextForce { it.fileName.toString().startsWith("MANIFEST") }
        assertFailsWith<IOException> { store.close() }
        assertEquals(emptySet(), delvaThreads() - before)
        assertEquals(0, fs.openHandles)
        TestEngines.open(busyOptions, fs).use { assertEquals(expected, it.documents()) }
    }

    @Test
    fun `a sync that fails while the store closes is thrown by close, since acknowledged writes were lost`() {
        val fs = SimulatedFileSystemIo()
        val before = delvaThreads()
        val periodic = EngineOptions(
            CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024, durability = Durability.Periodic(1.hours)),
            log = TestEngines.LOG, memtableBytes = 16L shl 10,
        )
        val store = TestEngines.open(periodic, fs)
        repeat(5) { store.insert(doc(it)) }
        // The writer's last sync, when it stops: the control log loses the acknowledged COMMITs.
        fs.failNextForce { it.parent?.fileName?.toString() == LogFiles.CONTROL_DIR }
        val e = assertFailsWith<EngineFailedException> { store.close() }
        assertTrue(e.cause is IOException, "the cause is the failed sync: ${e.cause}")
        assertEquals(emptySet(), delvaThreads() - before)
        assertEquals(0, fs.openHandles)
        TestEngines.open(periodic, fs).use { assertEquals(emptyMap(), it.documents()) }
    }

    @Test
    fun `a concurrent close returns once the store is closed`() {
        val fs = SimulatedFileSystemIo()
        val store = busyStore(fs)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fs.beforeForce { path ->
            if (path.parent.fileName.toString() == "control" && entered.count > 0) {
                entered.countDown()
                uninterruptibly { release.await() }
            }
        }
        val inserter = thread { runCatching { store.insert(doc(1000)) } }
        val closers = mutableListOf<Thread>()
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the writer syncs")
            closers += thread { store.close() }
            while (store.checkUsableOrNull() == null) Thread.onSpinWait()
            closers += thread { store.close() }
            closers.last().join(200)
            assertTrue(closers.last().isAlive, "the second close waits for the first")
        } finally {
            release.countDown()
            inserter.join()
            closers.forEach { it.join() }
        }
        TestEngines.open(busyOptions, fs).close()
    }

    /** Null while the store is usable, else what [Engine.checkUsable] throws. */
    private fun Engine.checkUsableOrNull(): Throwable? = runCatching { checkUsable() }.exceptionOrNull()
}
