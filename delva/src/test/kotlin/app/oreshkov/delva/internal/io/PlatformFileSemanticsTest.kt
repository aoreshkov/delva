package app.oreshkov.delva.internal.io

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * What the engine relies on about files another process has open, checked against the JDK and file system the tests
 * run on, so that a platform that behaves otherwise fails here and not in the field. The JDK opens files allowing
 * others to delete and rename them, so an open handle never stops the writer from deleting or renaming a file; on
 * Windows it does stop it from **replacing** one, which [replace] waits out.
 */
class PlatformFileSemanticsTest {
    private val io = JdkFileSystemIo()

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private val dir: Path = createTempDirectory("delva-platform")

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = dir.deleteRecursively()

    private fun FileChannel.text(): String {
        val buffer = ByteBuffer.allocate(64)
        val n = read(buffer, 0)
        return String(buffer.array(), 0, maxOf(n, 0))
    }

    @Test
    fun `a file another handle has open can be deleted, and the handle still reads it`() {
        val file = dir.resolve("a.dat").apply { writeText("a") }
        FileChannel.open(file, StandardOpenOption.READ).use { reader ->
            io.delete(file)
            assertFalse(file.exists())
            assertEquals("a", reader.text())
        }
    }

    @Test
    fun `a file another handle has open can be renamed, and the handle still reads it`() {
        val file = dir.resolve("b.dat").apply { writeText("b") }
        val moved = dir.resolve("moved.dat")
        FileChannel.open(file, StandardOpenOption.READ).use { reader ->
            io.atomicRename(file, moved)
            io.delete(moved)
            assertEquals("b", reader.text())
        }
    }

    @Test
    fun `replacing a name another handle has open fails on Windows only`() {
        val target = dir.resolve("CURRENT").apply { writeText("old") }
        val tmp = dir.resolve("CURRENT.tmp").apply { writeText("new") }
        FileChannel.open(target, StandardOpenOption.READ).use {
            if (io.isWindows) {
                assertFailsWith<AccessDeniedException> { io.atomicRename(tmp, target) }
                assertEquals("old", target.readText(), "a failed replace changes nothing")
            } else {
                io.atomicRename(tmp, target)
                assertEquals("new", target.readText())
            }
        }
    }

    @Test
    fun `replace waits until the other handle on the target is closed`() {
        val target = dir.resolve("CURRENT").apply { writeText("old") }
        val tmp = dir.resolve("CURRENT.tmp").apply { writeText("new") }
        val reader = FileChannel.open(target, StandardOpenOption.READ)
        val closer = thread { Thread.sleep(50); reader.close() }
        try {
            io.replace(tmp, target)
        } finally {
            closer.join()
            reader.close()
        }
        assertEquals("new", target.readText())
    }

    @Test
    fun `replace gives up while the other handle stays open`() {
        if (!io.isWindows) return
        val target = dir.resolve("CURRENT").apply { writeText("old") }
        val tmp = dir.resolve("CURRENT.tmp").apply { writeText("new") }
        var slept = 0L
        FileChannel.open(target, StandardOpenOption.READ).use {
            assertFailsWith<FileSystemException> { io.replace(tmp, target) { slept += it } }
        }
        assertEquals(1023L, slept, "about a second of backoff in all")
        assertEquals("old", Files.readString(target))
    }
}
