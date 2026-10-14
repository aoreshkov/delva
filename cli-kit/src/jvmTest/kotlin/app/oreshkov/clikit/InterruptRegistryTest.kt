package app.oreshkov.clikit

import app.oreshkov.clikit.testing.RecordingTerminalIo
import app.oreshkov.clikit.testing.RunResult
import app.oreshkov.clikit.testing.record
import app.oreshkov.clikit.testing.withStdin
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What `onInterrupt` registers, and in what order it runs, without a process to end. */
class InterruptRegistryTest {

    private val err = ByteArrayOutputStream()
    private val io = SystemTerminalIo(stdout = PrintStream(ByteArrayOutputStream()), stderr = PrintStream(err, true, Charsets.UTF_8))

    /** The first `onInterrupt` installed the terminal's shutdown hook: the test JVM ending is no interrupt. */
    @AfterTest
    fun finish() = io.markFinished()

    @Test
    fun `actions run newest first, and a closed one not at all`() {
        val ran = mutableListOf<String>()
        io.onInterrupt { ran += "first" }
        val closed = io.onInterrupt { ran += "closed" }
        io.onInterrupt { ran += "last" }
        closed.close()
        assertTrue(io.runInterruptActions())
        assertEquals(listOf("last", "first"), ran)
    }

    @Test
    fun `no action means the caller says interrupted itself`() {
        assertFalse(io.runInterruptActions())
        io.onInterrupt { }.close()
        assertFalse(io.runInterruptActions())
    }

    @Test
    fun `one action failing is reported, and the others still run`() {
        var ran = false
        io.onInterrupt { ran = true }
        io.onInterrupt { error("socket gone") }
        assertTrue(io.runInterruptActions())
        assertTrue(ran)
        assertEquals("error: while stopping: socket gone\n", err.toString(Charsets.UTF_8))
    }

    @Test
    fun `the same action registered twice is two registrations`() {
        var runs = 0
        val action: () -> Unit = { runs++ }
        io.onInterrupt(action)
        io.onInterrupt(action).close()
        io.runInterruptActions()
        assertEquals(1, runs)
    }

    @Test
    fun `a command stopped through the recorder stops cleanly, as it would under a signal`() {
        val serve = CommandSpec("serve")
        val stop = CountDownLatch(1)
        val registered = CountDownLatch(1)
        val program = Program(
            CommandSpec("demo", subcommands = listOf(serve)),
            "demo 1.0",
            mapOf(
                serve to CommandHandler { context ->
                    context.onInterrupt {
                        context.io.errLine("stopping cleanly")
                        stop.countDown()
                    }
                    registered.countDown()
                    if (stop.await(10, TimeUnit.SECONDS)) ExitStatus.OK else ExitStatus.FAILURE
                },
            ),
        )
        val recorder = RecordingTerminalIo()
        var result: RunResult? = null
        val running = thread { result = program.record("serve", io = recorder) }
        assertTrue(registered.await(10, TimeUnit.SECONDS))
        assertTrue(recorder.interrupt())
        running.join(20_000)
        assertFalse(running.isAlive, "the command didn't stop")
        assertEquals(ExitStatus.OK, result?.status)
        assertEquals("stopping cleanly\n", recorder.stderr)
    }

    @Test
    fun `the recorder, too, reports an action that fails and runs the others`() {
        val recorder = RecordingTerminalIo()
        var ran = false
        recorder.onInterrupt { ran = true }
        recorder.onInterrupt { error("socket gone") }
        assertTrue(recorder.interrupt())
        assertTrue(ran)
        assertEquals("error: while stopping: socket gone\n", recorder.stderr)
    }

    @Test
    fun `the recorder says interrupted when nothing is registered, and a stdin run still registers`() {
        val recorder = RecordingTerminalIo()
        assertFalse(recorder.interrupt())
        assertEquals("interrupted\n", recorder.stderr)

        val withInput = RecordingTerminalIo()
        var ran = false
        (withInput.withStdin(ByteArray(0)) as InterruptRegistry).onInterrupt { ran = true }
        assertTrue(withInput.interrupt())
        assertTrue(ran)
    }

    @Test
    fun `on a terminal that is no registry, onInterrupt registers nothing`() {
        val plain = object : TerminalIo by RecordingTerminalIo() {}
        val context = CommandContext(Parser(CommandSpec("demo")).parse(emptyList()) as ParseResult.Invocation, plain)
        context.onInterrupt { error("never") }.close()
    }
}
