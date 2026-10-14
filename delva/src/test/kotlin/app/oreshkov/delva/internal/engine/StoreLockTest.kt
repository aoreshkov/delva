package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.LockHolder
import app.oreshkov.delva.StoreLockedException
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.internal.io.OpenMode
import app.oreshkov.delva.internal.io.SimulatedFileSystemIo
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The record of who holds a store's lock. The other process's view is in [ChildJvmCrashTest]. */
class StoreLockTest {
    private val dir = TestEngines.DIR
    private val options = EngineOptions(CreationOptions(fragmentBytes = 256), StoreOptions(encoderBudgetBytes = 1024), log = TestEngines.LOG)
    private val owner = dir.resolve(StoreLock.OWNER_FILE)

    private fun SimulatedFileSystemIo.writeOwner(text: String) {
        delete(owner)
        open(owner, OpenMode.CREATE_NEW).use { it.write(0, text.toByteArray(), 0, text.toByteArray().size) }
    }

    @Test
    fun `a second open names the holder, and a close removes the record`() {
        val fs = SimulatedFileSystemIo()
        TestEngines.open(options, fs).use {
            assertTrue(fs.exists(owner))
            val e = assertFailsWith<StoreLockedException> { TestEngines.open(options, fs) }
            assertEquals(StoreLock.currentProcess(), e.holder)
            assertEquals(ProcessHandle.current().pid(), e.holder!!.pid)
            assertContains(e.message!!, "by process ${e.holder.pid}")
            assertEquals(StoreLock.currentProcess(), assertFailsWith<StoreLockedException> { StoreReset.run(dir, fs) }.holder)
        }
        assertFalse(fs.exists(owner), "closing deletes the record")
        assertFalse(fs.exists(dir.resolve("${StoreLock.OWNER_FILE}.tmp")))
        StoreReset.run(dir, fs)
        assertFalse(fs.exists(owner), "a reset leaves no record either")
    }

    @Test
    fun `a record left by a crash is replaced by the next holder`() {
        val fs = SimulatedFileSystemIo()
        TestEngines.open(options, fs).close()
        fs.writeOwner("delva-lock 1\npid=7\nhost=elsewhere\n")
        fs.restart()
        TestEngines.open(options, fs).use {
            assertEquals(StoreLock.currentProcess(), StoreLock.holder(dir, fs))
        }
    }

    @Test
    fun `a missing, damaged or foreign record gives no holder`() {
        val fs = SimulatedFileSystemIo()
        fs.createDirectories(dir)
        assertNull(StoreLock.holder(dir, fs), "missing")
        for (text in listOf(
            "", "pid=7\n", "delva-lock 2\npid=7\n", "delva-lock 1\n", "delva-lock 1\npid=-3\n", "delva-lock 1\npid=x\n",
            "delva-lock 1\npid=7\nstarted=yesterday\n", "delva-lock 1\npid=7\nhost=\n", "delva-lock 1\npid=7\nhost=a\u0007b\n",
            "delva-lock 1\npid=7\n" + "x".repeat(2000),
        )) {
            fs.writeOwner(text)
            assertNull(StoreLock.holder(dir, fs, thisHost = "here"), text.take(40))
        }
        fs.writeOwner("delva-lock 1\npid=7\nstarted=2026-09-30T08:15:02.123Z\nhost=elsewhere\nlater=field\n")
        assertEquals(LockHolder(7, Instant.parse("2026-09-30T08:15:02.123Z"), "elsewhere"), StoreLock.holder(dir, fs, thisHost = "here"), "a later field is ignored")
        assertEquals("process 7 on elsewhere, started 2026-09-30T08:15:02.123Z", StoreLock.holder(dir, fs, thisHost = "here").toString())
    }

    @Test
    fun `a record of a process on this host that is gone gives no holder`() {
        val fs = SimulatedFileSystemIo()
        fs.createDirectories(dir)
        val self = StoreLock.currentProcess()
        fs.writeOwner("delva-lock 1\npid=${Long.MAX_VALUE}\nhost=here\n")
        assertNull(StoreLock.holder(dir, fs, thisHost = "here"), "no such process")
        assertEquals(Long.MAX_VALUE, StoreLock.holder(dir, fs, thisHost = "there")?.pid, "another host's processes can't be checked")
        fs.writeOwner("delva-lock 1\npid=${self.pid}\nstarted=2000-01-01T00:00:00Z\nhost=here\n")
        val started = ProcessHandle.current().info().startInstant()
        if (started.isPresent) assertNull(StoreLock.holder(dir, fs, thisHost = "here"), "the pid is another process now")
        fs.writeOwner("delva-lock 1\npid=${self.pid}\nhost=here\n")
        assertEquals(self.pid, StoreLock.holder(dir, fs, thisHost = "here")?.pid, "a running process without a start time")
    }

    @Test
    fun `a record that can't be written doesn't fail the open`() {
        val fs = SimulatedFileSystemIo(isWindows = true)
        fs.createDirectories(dir)
        // On Windows a file can't be renamed over one that's open: the record stays the old one.
        fs.writeOwner("delva-lock 1\npid=7\nhost=elsewhere\n")
        fs.open(owner, OpenMode.READ).use {
            TestEngines.open(options, fs).use { store -> store.insert("""{"all":true}""") }
        }
        assertTrue(fs.exists(owner), "the record that couldn't be replaced isn't deleted either")
    }

    @Test
    fun `the host name comes from the environment, else from the first host-name file that names one`() {
        val tmp = Files.createTempDirectory("delva-host")
        try {
            val missing = tmp.resolve("missing")
            val blank = tmp.resolve("blank").apply { writeText("\n  \n") }
            val control = tmp.resolve("control").apply { writeText("a" + 7.toChar() + "b\n") }
            val commented = tmp.resolve("commented").apply { writeText("# set by the installer\n  build-7  \nignored\n") }
            val files = listOf(missing, blank, control, commented)
            assertEquals("win-3", StoreLock.hostName(mapOf("COMPUTERNAME" to " win-3 ", "HOSTNAME" to "box")::get, files))
            assertEquals("box", StoreLock.hostName(mapOf("COMPUTERNAME" to " ", "HOSTNAME" to "box")::get, files))
            assertEquals("build-7", StoreLock.hostName(mapOf<String, String>()::get, files))
            assertNull(StoreLock.hostName(mapOf<String, String>()::get, listOf(missing, blank, control)))
            assertNull(StoreLock.hostName(mapOf("HOSTNAME" to "x".repeat(256))::get, listOf()), "too long for a record")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    fun `on Linux the host is known without HOSTNAME, so a stale record on this host is dropped`() {
        val host = assertNotNull(StoreLock.hostName(env = { null }), "shells don't export HOSTNAME")
        assertEquals(Path.of("/proc/sys/kernel/hostname").readText().trim(), host)
        assertNotNull(StoreLock.currentProcess().host, "the record names the host")
        val fs = SimulatedFileSystemIo()
        fs.createDirectories(dir)
        fs.writeOwner("delva-lock 1\npid=${Long.MAX_VALUE}\nhost=${StoreLock.hostName()}\n")
        assertNull(StoreLock.holder(dir, fs), "the process is gone")
    }
}
