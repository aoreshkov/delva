package app.oreshkov.delva.internal.io

import app.oreshkov.delva.internal.io.SimulatedFileSystemIo.Platform
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Processes sharing a simulated disk, and what each platform model lets a writer do to a file another process holds
 * open: the facts [PlatformFileSemanticsTest] checks against the real JDK, plus legacy delete semantics, which the
 * machines the tests run on don't have.
 */
class SimulatedProcessesTest {
    private val dir = Path.of("d")
    private val file = dir.resolve("f")
    private val other = dir.resolve("g")

    private fun disk(platform: Platform) = SimulatedFileSystemIo(platform).apply {
        createDirectories(dir)
        for (path in listOf(file, other)) open(path, OpenMode.CREATE_NEW).use { it.write(0, byteArrayOf(1, 2, 3), 0, 3) }
    }

    private fun FileIo.readAll(): ByteArray = ByteArray(size().toInt()).also { readFully(0, it, 0, it.size) }

    @Test
    fun `deleting a file another process has open`() {
        for (platform in Platform.entries) {
            val writer = disk(platform)
            val reader = writer.process()
            val handle = reader.open(file, OpenMode.READ)
            if (platform == Platform.WINDOWS_STRICT) {
                assertFailsWith<AccessDeniedException>(platform.name) { writer.delete(file) }
                assertTrue(writer.exists(file), platform.name)
                continue
            }
            writer.delete(file)
            assertContentEquals(byteArrayOf(1, 2, 3), handle.readAll(), "$platform: the open handle still reads")
            if (platform == Platform.WINDOWS_LEGACY) {
                // Delete-pending until the last handle closes: listed, but every use of the name fails.
                assertEquals(listOf(file), writer.deletePending(), platform.name)
                assertTrue(writer.exists(file) && file in writer.list(dir), platform.name)
                assertFailsWith<AccessDeniedException>(platform.name) { writer.open(file, OpenMode.READ) }
                assertFailsWith<AccessDeniedException>(platform.name) { writer.open(file, OpenMode.CREATE_NEW) }
                assertFailsWith<AccessDeniedException>(platform.name) { writer.delete(file) }
                assertFailsWith<AccessDeniedException>(platform.name) { writer.atomicRename(file, dir.resolve("h")) }
                assertFailsWith<AccessDeniedException>(platform.name) { writer.atomicRename(other, file) }
                handle.close()
                assertEquals(emptyList(), writer.deletePending(), platform.name)
            }
            assertFalse(writer.exists(file), platform.name)
            writer.open(file, OpenMode.CREATE_NEW).close()
        }
    }

    @Test
    fun `renaming a file another process has open, and replacing one`() {
        for (platform in Platform.entries) {
            val writer = disk(platform)
            val handle = writer.process().open(file, OpenMode.READ)
            if (platform.isWindows) {
                assertFailsWith<AccessDeniedException>(platform.name) { writer.atomicRename(other, file) }
                assertContentEquals(byteArrayOf(1, 2, 3), writer.bytes(other), "$platform: a failed replace changes nothing")
            } else {
                writer.atomicRename(other, file)
            }
            if (platform == Platform.WINDOWS_STRICT) {
                assertFailsWith<AccessDeniedException>(platform.name) { writer.atomicRename(file, dir.resolve("h")) }
            } else {
                writer.atomicRename(file, dir.resolve("h"))
                assertContentEquals(byteArrayOf(1, 2, 3), handle.readAll(), "$platform: the open handle still reads")
            }
        }
    }

    @Test
    fun `a lock belongs to one process, and an exit releases it and closes the process's handles`() {
        val writer = disk(Platform.WINDOWS_LEGACY)
        val reader = writer.process()
        val lock = assertNotNull(writer.tryLock(dir.resolve("LOCK")))
        assertNull(reader.tryLock(dir.resolve("LOCK")))
        reader.open(file, OpenMode.READ)
        writer.delete(file)
        assertEquals(listOf(file), writer.deletePending())
        reader.exit()
        assertEquals(emptyList(), writer.deletePending(), "the exit closed the handle")
        assertFailsWith<java.io.IOException> { reader.exists(file) }
        val next = writer.process()
        assertNull(next.tryLock(dir.resolve("LOCK")))
        writer.exit()
        assertNotNull(next.tryLock(dir.resolve("LOCK")), "the writer's exit released its lock")
        lock.close()
    }

    @Test
    fun `reads of what another process wrote last can be page-torn, the writer's own never`() {
        val writer = disk(Platform.LINUX)
        val reader = writer.process()
        val path = dir.resolve("log")
        val out = writer.open(path, OpenMode.CREATE_NEW)
        val old = ByteArray(5 * 4096) { 1 }
        out.write(0, old, 0, old.size)
        val random = Random(7)
        writer.tearReads(random)
        // Past the old end too, where the page before the write reads as zeros.
        val start = 1000
        val new = ByteArray(5 * 4096) { 2 }
        out.write(start.toLong(), new, 0, new.size)
        val after = old.copyOf(start + new.size).also { new.copyInto(it, start) }
        val before = old.copyOf(after.size)
        val input = reader.open(path, OpenMode.READ)
        val landed = HashSet<Int>()
        var torn = 0
        repeat(50) {
            val bytes = input.readAll()
            assertEquals(after.size, bytes.size)
            for (page in bytes.indices step 4096) {
                // The part of a page the write covers comes whole from before or after it; the rest is as written.
                val from = maxOf(page, start)
                val to = minOf(page + 4096, bytes.size)
                val slice = bytes.copyOfRange(from, to)
                val new = slice.contentEquals(after.copyOfRange(from, to))
                assertTrue(new || slice.contentEquals(before.copyOfRange(from, to)), "page at $page")
                assertTrue(new || page !in landed, "page at $page went back to before the write")
                if (new) landed += page
                assertContentEquals(after.copyOfRange(page, from), bytes.copyOfRange(page, from), "page at $page")
            }
            if (!bytes.contentEquals(after)) torn++
            assertContentEquals(after, writer.open(path, OpenMode.READ).use { it.readAll() }, "the writer's own reads")
        }
        assertTrue(torn > 0, "some reads were torn")
        assertContentEquals(after, input.readAll(), "every page has landed")
    }
}
