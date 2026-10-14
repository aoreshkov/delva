package app.oreshkov.delva.internal.io

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.JsonQueryValue
import app.oreshkov.delva.KeyValueQuery
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.StoreLockedException
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.engine.Engine
import app.oreshkov.delva.internal.engine.EngineOptions
import app.oreshkov.delva.internal.engine.documents
import app.oreshkov.delva.internal.log.LogOptions
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real file system: the operations the engine relies on, on the OS the tests run on (Linux and Windows). */
class JdkFileSystemIoTest {
    private fun <T> withTempDir(block: (Path) -> T): T {
        val dir = Files.createTempDirectory("delva-io")
        try {
            return block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `files, renames, deletes, listing and locks`() = withTempDir { dir ->
        val io = JdkFileSystemIo()
        io.createDirectories(dir.resolve("a/b"))
        val file = dir.resolve("a/b/x")
        io.open(file, OpenMode.CREATE_NEW).use { f ->
            f.write(0, "hello".toByteArray(), 0, 5)
            f.write(10, "!".toByteArray(), 0, 1)
            f.force(true)
            assertEquals(11, f.size())
            val bytes = ByteArray(20)
            assertEquals(11, f.read(0, bytes, 0, 20))
            assertContentEquals(byteArrayOf(104, 101, 108, 108, 111, 0, 0, 0, 0, 0, 33), bytes.copyOf(11))
            f.truncate(5)
            assertEquals(5, f.size())
        }
        assertFailsWith<FileAlreadyExistsException> { io.open(file, OpenMode.CREATE_NEW) }
        io.syncDirectory(file.parent)

        // A reader keeps its handle while the file is replaced by a rename and the old name is deleted.
        io.open(dir.resolve("a/b/y"), OpenMode.CREATE_NEW).use { it.write(0, "new".toByteArray(), 0, 3) }
        io.open(dir.resolve("a/b/z"), OpenMode.CREATE_NEW).close()
        io.open(file, OpenMode.READ).use { reader ->
            io.atomicRename(dir.resolve("a/b/y"), dir.resolve("a/b/w"))
            io.delete(dir.resolve("a/b/z"))
            val bytes = ByteArray(5)
            assertEquals(5, reader.read(0, bytes, 0, 5))
        }
        assertEquals(listOf("w", "x"), io.list(file.parent).map { it.fileName.toString() })
        assertEquals(emptyList(), io.list(dir.resolve("missing")))

        val lock = assertNotNull(io.tryLock(dir.resolve("LOCK")))
        assertNull(io.tryLock(dir.resolve("LOCK")))
        lock.close()
        io.tryLock(dir.resolve("LOCK"))!!.close()
    }

    @Test
    fun `a store on disk rolls files under an open search, reopens, and locks its directory`() = withTempDir { dir ->
        val options = EngineOptions(
            CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024),
            smallInsertThreshold = 2048,
            log = LogOptions(laneFileBytes = 8192, controlFileBytes = 4096, preallocateBytes = 2048, writeBufferBytes = 4096),
            memtableBytes = 16L * 1024,
        )
        val expected = HashMap<Long, String>()
        fun doc(i: Int) = """{"all":true,"i":$i,"pad":[${(0 until i % 30).joinToString(",") { "\"${"p".repeat(it)}\"" }}]}"""
        Engine.open(dir, options, JdkFileSystemIo()).use { store ->
            assertFailsWith<StoreLockedException> { Engine.open(dir, options, JdkFileSystemIo()) }
            repeat(50) { i -> doc(i).let { expected[store.insert(it).raw] = it } }
            store.search(KeyValueQuery("all", JsonQueryValue.Bool(true), ResultMode.WHOLE_DOCUMENT, limit = 5)).use { result ->
                val first = result.nextPage()!!
                repeat(150) { i -> doc(i + 50).let { expected[store.insert(it).raw] = it } }
                var read = 0
                for (hit in first.hits + result.iterator().asSequence()) {
                    assertEquals(expected[hit.node.docId.raw], hit.readJsonAsString())
                    read++
                }
                assertEquals(50, read, "the open result reads its snapshot while files roll")
            }
        }
        assertTrue(Files.list(dir.resolve("data")).use { it.count() } > 5)
        assertTrue(Files.list(dir.resolve("index")).use { it.count() } > 2, "segments were flushed")
        Engine.open(dir, options, JdkFileSystemIo()).use { store ->
            assertEquals(expected, store.documents())
        }
    }

    /** Runs [block] with this thread's interrupt flag set, checks the flag is still set afterwards, then clears it. */
    private fun interrupted(block: () -> Unit) {
        Thread.currentThread().interrupt()
        try {
            block()
            assertTrue(Thread.currentThread().isInterrupted, "the interrupt is kept")
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `an interrupted caller reads, writes and syncs, keeps its interrupt, and leaves the file open for others`() = withTempDir { dir ->
        for (mmapReads in listOf(false, true)) {
            val io = JdkFileSystemIo(mmapReads)
            val file = dir.resolve("f-$mmapReads")
            io.open(file, OpenMode.CREATE_NEW).use { f ->
                interrupted {
                    f.write(0, "hello".toByteArray(), 0, 5)
                    f.force(true)
                    f.truncate(4)
                    assertEquals(4, f.size())
                    val bytes = ByteArray(4)
                    assertEquals(4, f.read(0, bytes, 0, 4))
                    assertContentEquals("hell".toByteArray(), bytes)
                    io.syncDirectory(dir)
                }
                var read = -1
                thread { read = f.read(0, ByteArray(4), 0, 4) }.join()
                assertEquals(4, read, "another thread still reads through the handle")
            }
            interrupted {
                io.open(file, OpenMode.READ).use { assertEquals(4, it.size()) }
                io.tryLock(dir.resolve("LOCK"))!!.close()
            }
        }
    }

    @Test
    fun `interrupts of the threads that share a handle don't fail their reads`() = withTempDir { dir ->
        val opened = AtomicInteger()
        val io = JdkFileSystemIo(openChannel = { path, options -> opened.incrementAndGet(); FileChannel.open(path, *options) })
        val content = Random(1).nextBytes(1 shl 20)
        io.open(dir.resolve("f"), OpenMode.CREATE_NEW).use { it.write(0, content, 0, content.size) }
        io.open(dir.resolve("f"), OpenMode.READ).use { f ->
            val failure = AtomicReference<Throwable>()
            val stop = System.nanoTime() + 10_000_000_000L
            fun done() = failure.get() != null || opened.get() > 10 || System.nanoTime() > stop
            val readers = List(8) {
                thread {
                    val bytes = ByteArray(content.size)
                    try {
                        while (!done()) {
                            Thread.interrupted()
                            assertEquals(content.size, f.read(0, bytes, 0, bytes.size))
                            assertContentEquals(content, bytes)
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }
            while (!done()) for (reader in readers) reader.interrupt()
            readers.forEach { it.join() }
            failure.get()?.let { throw it }
            assertTrue(opened.get() > 2, "an interrupt closed the channel during a read, and it was reopened")
        }
    }

    @Test
    fun `a closed handle stays closed`() = withTempDir { dir ->
        val f = JdkFileSystemIo().open(dir.resolve("f"), OpenMode.CREATE_NEW)
        f.close()
        assertFailsWith<ClosedChannelException> { f.read(0, ByteArray(1), 0, 1) }
        assertFailsWith<ClosedChannelException> { f.force(false) }
    }

    @Test
    fun `a mapped open that fails closes its channel`() = withTempDir { dir ->
        Files.write(dir.resolve("f"), byteArrayOf(1, 2, 3))
        var channel: FileChannel? = null
        val io = JdkFileSystemIo(mmapReads = true, openChannel = { path, options ->
            FailingSize(FileChannel.open(path, *options)).also { channel = it }
        })
        assertEquals("size", assertFailsWith<IOException> { io.open(dir.resolve("f"), OpenMode.READ) }.message)
        assertTrue(!channel!!.isOpen)
    }

    /** A channel whose `size()` fails. */
    private class FailingSize(private val delegate: FileChannel) : FileChannel() {
        override fun read(dst: ByteBuffer): Int = delegate.read(dst)
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = delegate.read(dsts, offset, length)
        override fun write(src: ByteBuffer): Int = delegate.write(src)
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = delegate.write(srcs, offset, length)
        override fun position(): Long = delegate.position()
        override fun position(newPosition: Long): FileChannel = apply { delegate.position(newPosition) }
        override fun size(): Long = throw IOException("size")
        override fun truncate(size: Long): FileChannel = apply { delegate.truncate(size) }
        override fun force(metaData: Boolean) = delegate.force(metaData)
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = delegate.transferTo(position, count, target)
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = delegate.transferFrom(src, position, count)
        override fun read(dst: ByteBuffer, position: Long): Int = delegate.read(dst, position)
        override fun write(src: ByteBuffer, position: Long): Int = delegate.write(src, position)
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = delegate.map(mode, position, size)
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = delegate.lock(position, size, shared)
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = delegate.tryLock(position, size, shared)
        override fun implCloseChannel() = delegate.close()
    }
}
