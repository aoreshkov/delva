package app.oreshkov.clikit

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [runAndExit]'s shutdown hook, in a real child JVM running [InterruptFixture]: the one place a JVM shutdown during a
 * command, with its hooks and its threads, is real.
 */
// A child process that never stops must fail the build, not hang it; a read from its pipe ignores an interrupt, so
// the test runs on a thread of its own that the timeout can abandon.
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class InterruptProcessTest {

    private val windows = System.getProperty("os.name").lowercase().startsWith("windows")

    private class Stopped(val status: Int, val stderr: String)

    private fun fixture(vararg args: String, direct: Boolean = false): Process {
        val java = ProcessHandle.current().info().command().orElseThrow()
        val properties = if (direct) listOf("-Dfixture.direct") else emptyList()
        return ProcessBuilder(
            listOf(java, "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path")) +
                properties + listOf("app.oreshkov.clikit.InterruptFixtureKt") + args,
        ).start()
    }

    /**
     * Starts `fixture serve [args]`, through `Program.run` itself when [direct], waits for it to say it is running, lets
     * [stop] end it, and collects the rest.
     */
    private fun serve(vararg args: String, direct: Boolean = false, stop: (Process) -> Unit): Stopped {
        val process = fixture("serve", *args, direct = direct)
        try {
            // stderr is read only when the fixture failed to start, and so has already exited: read while it runs, it
            // would wait for an end that never comes.
            if (process.inputStream.bufferedReader().readLine() != "ready") {
                fail("the fixture did not start: " + process.errorStream.readAllBytes().decodeToString())
            }
            stop(process)
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the fixture did not stop")
            return Stopped(process.exitValue(), process.errorStream.readAllBytes().decodeToString())
        } finally {
            process.destroyForcibly()
        }
    }

    /** The fixture exits from inside once its standard input closes. */
    private val closeStdin: (Process) -> Unit = { it.outputStream.close() }

    @Test
    fun `a registered action runs in place of the interrupted notice`() {
        val stopped = serve("--handle", stop = closeStdin)
        assertEquals(3, stopped.status, stopped.stderr)
        assertContains(stopped.stderr, "stopping cleanly")
        assertFalse("interrupted" in stopped.stderr, stopped.stderr)
    }

    @Test
    fun `without one, the process says it was interrupted`() {
        val stopped = serve(stop = closeStdin)
        assertEquals(3, stopped.status, stopped.stderr)
        assertContains(stopped.stderr, "interrupted")
    }

    @Test
    fun `without runAndExit, a registered action still runs`() {
        val stopped = serve("--handle", direct = true, stop = closeStdin)
        assertEquals(3, stopped.status, stopped.stderr)
        assertContains(stopped.stderr, "stopping cleanly")
        assertFalse("interrupted" in stopped.stderr, stopped.stderr)
    }

    @Test
    fun `an Error is a bug reported on one line, not an interrupt`() {
        val process = fixture("crash")
        try {
            val stderr = process.errorStream.readAllBytes().decodeToString()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the fixture did not stop")
            assertEquals(ExitStatus.SOFTWARE, process.exitValue(), stderr)
            assertContains(stderr, "internal error: too deep")
            assertFalse("interrupted" in stderr || "\tat " in stderr, stderr)
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `SIGTERM runs the action, and the exit status stays the platform's`() {
        // On Windows `destroy` terminates the process outright, and no shutdown hook runs.
        assumeFalse(windows, "SIGTERM is POSIX")
        // Through the handle: Process.destroy also closes the process's streams, and stderr is read after it.
        val stopped = serve("--handle") { it.toHandle().destroy() }
        assertEquals(128 + 15, stopped.status, stopped.stderr)
        assertContains(stopped.stderr, "stopping cleanly")
        assertFalse("interrupted" in stopped.stderr, stopped.stderr)
    }
}
