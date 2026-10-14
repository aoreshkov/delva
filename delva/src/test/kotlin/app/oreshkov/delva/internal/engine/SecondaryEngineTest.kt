package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CompareOperand
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.DocId
import app.oreshkov.delva.DocumentKeyQuery
import app.oreshkov.delva.Durability
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreClosedException
import app.oreshkov.delva.StoreNotFoundException
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.FileIo
import app.oreshkov.delva.internal.io.FileSystemIo
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo.Platform
import app.oreshkov.delva.internal.log.LogFiles
import app.oreshkov.delva.internal.log.LogOptions
import app.oreshkov.delva.internal.log.ManifestStore
import java.io.InputStream
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A secondary beside a writer process on one simulated disk: it reads what the writer acknowledged, catches up on
 * refresh across flushes, a reset and a writer that moves on mid-refresh, keeps every result on the state it
 * started on, verifies clean, and never changes the store.
 */
class SecondaryEngineTest {
    private val dir = TestEngines.DIR

    private fun options(
        memtableBytes: Long? = null,
        log: LogOptions = TestEngines.LOG,
        compression: Int = 0,
        smallInsertThreshold: Long = 1024,
    ) = SecondaryFixtures.options(memtableBytes = memtableBytes, log = log, compression = compression, smallInsertThreshold = smallInsertThreshold)

    private fun secondary(fs: SimulatedFileSystemIo, options: EngineOptions): SecondaryEngine = SecondaryFixtures.secondary(fs, options)

    private fun doc(random: Random, i: Int, big: Boolean = false): String = SecondaryFixtures.doc(random, i, big)

    @Test
    fun `a secondary reads what the writer acknowledged and catches up on refresh`() {
        for (platform in Platform.entries) {
            val fs = SimulatedFileSystemIo(platform)
            val options = options()
            Engine.open(dir, options, fs).use { writer ->
                val random = Random(1)
                val first = (0 until 3).map { writer.insert(doc(random, it), mapOf("$" to "Root")) }
                secondary(fs, options).use { secondary ->
                    assertEquals(writer.documents(), secondary.documents(), "$platform")
                    assertEquals(first.map { NodeRef(it, 0) }, secondary.typed("Root"))
                    assertTrue(secondary.info().secondary)
                    assertFalse(writer.info().secondary)

                    writer.insert(doc(random, 3))
                    writer.delete(first[0])
                    writer.assignType(NodeRef(first[1], 0), "Other")
                    assertEquals(3, secondary.documents().size, "$platform: unchanged until refreshed")
                    assertTrue(secondary.refresh())
                    assertEquals(writer.documents(), secondary.documents(), "$platform")
                    assertEquals(listOf(NodeRef(first[2], 0)), secondary.typed("Root"))
                    assertEquals("Other", secondary.typeOf(NodeRef(first[1], 0)))
                    assertEquals(writer.info().approximateDocumentCount, secondary.info().approximateDocumentCount)
                    assertFalse(secondary.refresh(), "$platform: nothing new")
                    assertEquals(StoreLock.currentProcess(), secondary.writer(), "$platform: the writer's record")
                    assertEquals(0, secondary.refusedCalls)
                }
            }
            secondary(fs, options()).use { assertEquals(null, it.writer(), "$platform: no writer") }
        }
    }

    @Test
    fun `results keep the state they started on across refreshes and new epochs`() {
        val fs = SimulatedFileSystemIo(Platform.WINDOWS_LEGACY)
        val options = options()
        Engine.open(dir, options, fs).use { writer ->
            val random = Random(2)
            val before = (0 until 4).map { writer.insert(doc(random, it)) }
            secondary(fs, options).use { secondary ->
                val query = KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT, limit = 1)
                val pinned = secondary.search(query)
                val firstPage = pinned.nextPage()!!
                writer.insert(doc(random, 4))
                writer.delete(before[3])
                assertTrue(secondary.refresh())
                // A flush: the next refresh opens a new epoch from the new MANIFEST.
                writer.flush()
                writer.insert(doc(random, 5))
                assertTrue(secondary.refresh())
                val epochs = secondary.epochsOpened
                assertTrue(epochs >= 2, "a flush opens a new epoch: $epochs")
                val rest = generateSequence { pinned.nextPage() }.flatMap { it.hits }.map { it.node.docId }.toList()
                assertEquals(before, firstPage.hits.map { it.node.docId } + rest, "the pinned result reads the state it started on")
                pinned.close()
                assertEquals(writer.documents(), secondary.documents())
                assertEquals(emptyList(), secondary.verify().issues)
            }
        }
    }

    @Test
    fun `the secondary never writes, and never opens CURRENT or the lock files`() {
        val fs = SimulatedFileSystemIo(Platform.WINDOWS_POSIX)
        val options = options()
        val opened = Collections.synchronizedList(ArrayList<Path>())
        Engine.open(dir, options, fs).use { writer ->
            val random = Random(3)
            repeat(20) { writer.insert(doc(random, it, big = it % 5 == 0), mapOf("$" to "Root")) }
            writer.flush()
            val recording = Recording(fs.process(), opened)
            SecondaryEngine.open(dir, recording, SimulatedFileSystemIo()) { EngineOptions(it, options.store) }.use { secondary ->
                repeat(5) { writer.insert(doc(random, 20 + it)) }
                secondary.refresh()
                // A comparison with a JSON operand spills to the secondary's scratch disk, never to the store.
                assertTrue(secondary.isEqual(CompareOperand.Json("[1]"), CompareOperand.Json("[1]")))
                assertEquals(emptyList(), secondary.verify().issues)
                assertEquals(0, secondary.refusedCalls)
            }
        }
        val forbidden = setOf(ManifestStore.CURRENT, LogFiles.LOCK_FILE, StoreLock.OWNER_FILE)
        assertEquals(emptyList(), opened.filter { it.fileName.toString() in forbidden })
        assertTrue(opened.none { it.startsWith(dir.resolve(LogFiles.TMP_DIR)) })
    }

    @Test
    fun `a store without a writer, and a directory without a store`() {
        val fs = SimulatedFileSystemIo()
        assertFailsWith<StoreNotFoundException> { secondary(fs, options()) }
        fs.createDirectories(dir)
        assertFailsWith<StoreNotFoundException> { secondary(fs, options()) }
        val options = options()
        val random = Random(4)
        Engine.open(dir, options, fs).use { writer -> repeat(5) { writer.insert(doc(random, it)) } }
        val files = fs.files().map { it to fs.bytes(it).contentHashCode() }
        val expected = Engine.open(dir, options, fs.process()).use { it.documents() }
        val after = fs.files().map { it to fs.bytes(it).contentHashCode() }
        secondary(fs, options).use { secondary ->
            assertEquals(expected, secondary.documents())
            assertFalse(secondary.refresh())
        }
        assertEquals(after, fs.files().map { it to fs.bytes(it).contentHashCode() }, "a secondary changes nothing")
        assertTrue(files.isNotEmpty())
    }

    @Test
    fun `a reset while a secondary is open shows the empty store after the next refresh`() {
        for (platform in Platform.entries) {
            val fs = SimulatedFileSystemIo(platform)
            val options = options()
            val random = Random(5)
            Engine.open(dir, options, fs).use { writer -> repeat(6) { writer.insert(doc(random, it), mapOf("$" to "Root")) } }
            secondary(fs, options).use { secondary ->
                val old = secondary.documents()
                val pinned = secondary.search(KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT, limit = 100))
                JsonStore.reset(dir, fs)
                assertTrue(secondary.refresh(), "$platform")
                assertEquals(emptyMap(), secondary.documents(), "$platform")
                assertEquals(emptyList(), secondary.typed("Root"), "$platform")
                assertEquals(old.keys, pinned.iterator().asSequence().map { it.node.docId.raw }.toSet(), "$platform: the result keeps its state")
                pinned.close()
                Engine.open(dir, options, fs).use { writer ->
                    assertEquals(DocId(1), writer.insert(doc(random, 100)))
                    assertTrue(secondary.refresh())
                    assertEquals(writer.documents(), secondary.documents(), "$platform")
                }
                assertEquals(emptyList(), secondary.verify().issues, "$platform")
            }
        }
    }

    /**
     * A secondary can't know which inserts the writer has in flight: one that started first and commits after a later
     * one has a lower docId than documents the secondary already shows. Results opened before its COMMIT is replayed
     * never see it.
     */
    @Test
    fun `a document committed after a refresh stays out of results opened before, whatever its docId`() {
        val fs = SimulatedFileSystemIo()
        val options = options()
        Engine.open(dir, options, fs).use { writer ->
            writer.insert("""{"all":true,"n":0}""")
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            // Blocks halfway through its document, after it took its docId.
            val slow = object : InputStream() {
                private val bytes = """{"all":true,"n":1}""".toByteArray()
                private var at = 0

                override fun read(): Int {
                    if (at == 4) {
                        started.countDown()
                        release.await()
                    }
                    return if (at < bytes.size) bytes[at++].toInt() else -1
                }
            }
            val early = thread { writer.insert(slow) }
            started.await()
            val later = writer.insert("""{"all":true,"n":2}""")
            secondary(fs, options).use { secondary ->
                val query = KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT, limit = 100)
                val pinned = secondary.search(query)
                val count = secondary.count(query)
                release.countDown()
                early.join()
                assertTrue(secondary.refresh())
                val earlyDocId = writer.documents().keys.single { it != 1L && it != later.raw }
                assertTrue(earlyDocId < later.raw, "the slow insert took the lower docId")
                assertEquals(listOf(1L, later.raw), pinned.iterator().asSequence().map { it.node.docId.raw }.toList(), "pinned before its COMMIT")
                assertEquals(2, count)
                assertEquals(writer.documents(), secondary.documents())
                pinned.close()
            }
        }
    }

    /**
     * The writer moves on between the secondary's look at the directory and its replay: it adopts a compression
     * dictionary the secondary hasn't loaded and uses it, or relocates fragments into a GC-lane file a MANIFEST the
     * secondary hasn't read lists. The replay stops at the first record that needs what it doesn't know, rather than
     * taking the record for damage, and the refresh reads on once it knows.
     */
    @Test
    fun `a refresh that falls behind the writer's dictionaries and lane files reads on once it knows them`() {
        for (platform in Platform.entries) {
            val fs = SimulatedFileSystemIo(platform)
            // No flush and no new lane file in the first part: its replay must stop at the dictionary, and nothing indexes again.
            val options = options(memtableBytes = 1L shl 20, compression = 2, smallInsertThreshold = 1L shl 20, log = LogOptions(laneFileBytes = 64L shl 10, controlFileBytes = 64L shl 10, preallocateBytes = 0, writeBufferBytes = 4096))
            Engine.open(dir, options, fs).use { writer ->
                val random = Random(8)
                var i = 0
                repeat(10) { writer.insert(doc(random, i++)) }
                var hook: (() -> Unit)? = null
                val hooked = object : FileSystemIo by fs.process() {}.let { base ->
                    object : FileSystemIo by base {
                        override fun list(dir: Path): List<Path> = base.list(dir).also {
                            if (dir.fileName.toString() == LogFiles.DICTIONARY_DIR) hook?.also { hook = null }?.invoke()
                        }
                    }
                }
                SecondaryEngine.open(dir, hooked, SimulatedFileSystemIo()) { EngineOptions(it, options.store) }.use { secondary ->
                    val dictionaries = writer.codecs.dictionaryCount
                    hook = {
                        // Enough payload to train and adopt a dictionary, then documents that use it.
                        while (writer.codecs.dictionaryCount == dictionaries) writer.insert(doc(random, i++))
                        repeat(5) { writer.insert(doc(random, i++)) }
                    }
                    secondary.refresh()
                    assertTrue(writer.codecs.dictionaryCount > dictionaries, "$platform: a dictionary was adopted")
                    assertEquals(writer.documents(), secondary.documents(), "$platform")
                    assertEquals(writer.itemHits(), secondary.itemHits(), "$platform: every fragment indexed")
                    assertEquals(0, secondary.stats.damagedRelocatedRecords, "$platform")

                    repeat(300) { writer.insert(doc(random, i++)) }
                    val live = writer.documents().keys.toList()
                    writer.deleteAll(live.drop(3).map(::DocId))
                    writer.flush()
                    secondary.refresh()
                    var retired = 0
                    hook = { retired = writer.reclaim() }
                    val epochs = secondary.epochsOpened
                    secondary.refresh()
                    assertTrue(retired > 0, "$platform: lane files were reclaimed")
                    assertEquals(epochs, secondary.epochsOpened, "$platform: the replay waited for the GC-lane file, it didn't start over")
                    assertEquals(writer.documents(), secondary.documents(), "$platform")
                    assertEquals(writer.itemHits(), secondary.itemHits(), "$platform: every fragment indexed")
                    assertEquals(emptyList(), secondary.verify().issues, "$platform")
                    assertEquals(0, secondary.refusedCalls)
                }
            }
        }
    }

    @Test
    fun `closing the secondary ends its results`() {
        val fs = SimulatedFileSystemIo()
        val options = options()
        Engine.open(dir, options, fs).use { writer -> writer.insert(doc(Random(6), 0)) }
        val secondary = secondary(fs, options)
        val result = secondary.search(KeyValueQuery("all", JsonQueryValue.Bool(true)))
        secondary.close()
        assertFailsWith<StoreClosedException> { result.nextPage() }
        assertFailsWith<StoreClosedException> { secondary.refresh() }
        result.close()
    }

    /** Records the files opened through [fs]. */
    private class Recording(private val fs: FileSystemIo, private val opened: MutableList<Path>) : FileSystemIo by fs {
        override fun open(path: Path, mode: OpenMode): FileIo {
            opened.add(path.normalize())
            return fs.open(path, mode)
        }
    }
}
