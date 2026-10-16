package app.oreshkov.clikit

import java.io.BufferedOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The real streams, in `PrintStream`s whose charset is decided rather than inherited.
 *
 * The charset is the console's when there is one and UTF-8 when there is not, and that split is the
 * whole point. Since JDK 19 a bare `System.out` encodes with `stdout.encoding`, which is the console
 * charset at a terminal and `native.encoding` otherwise — on Windows the ANSI code page in *both*
 * cases. `PrintStream` substitutes `?` for anything the charset cannot encode and reports nothing,
 * so `demo store get --json > out.json` would silently destroy Cyrillic, CJK or emoji in the
 * payload. The payload has to arrive intact, so the redirected case is UTF-8. Forcing
 * UTF-8 unconditionally is the opposite failure — mojibake at a legacy code page — which is why the
 * terminal case is probed instead.
 *
 * Buffering follows the reader. At a terminal every write is flushed, so output appears as it is
 * produced. Down a pipe stdout is block-buffered — a million JSON lines are a few hundred writes, not
 * a million — and [flush] empties it; [Program.run] flushes when it returns, whatever the outcome,
 * so a command that exits through a failing path still delivers what it wrote. stdout is its own
 * buffered stream on file descriptor 1 rather than `System.out`, which flushes on every write.
 *
 * [payload] takes the payload as UTF-8 bytes, for values too large to hold as a string. Down a pipe
 * and at a UTF-8 terminal they go through as they are; at a terminal with another charset they are
 * decoded and written as text, so the bytes and the text of one command reach the screen alike.
 *
 * [Tty.charset] is the same probe [Tty.borderStyle] comes from, so the stream and the box-drawing
 * decision cannot disagree about what the console can render.
 */
public class SystemTerminalIo(
    override val interactive: Boolean = Tty.interactive,
    override val width: Int = Tty.width,
    override val styler: Styler = Styler.Plain,
    override val borderStyle: BorderStyle = Tty.borderStyle,
    private val stdout: PrintStream = FileDescriptorOne(),
    private val stderr: PrintStream = PrintStream(System.err, false, STREAM_CHARSET),
    /**
     * At a terminal, whether it acts on escapes: on Windows, whether the console accepted VT processing, which is asked
     * for whatever the colour decision; elsewhere, yes. Down a pipe, no: nothing is drawn there.
     */
    override val escapesInterpreted: Boolean = interactive && VirtualTerminal.enable(),
) : TerminalIo, BinaryPayload, InterruptRegistry {

    /**
     * Whether stdout is a pipe or a socket, asked once a write to it failed; only file descriptor 1 can be asked, so a
     * stream the caller gave counts as a file. Replaced by the tests.
     */
    internal var stdoutIsPipe: () -> Boolean = { stdout is FileDescriptorOne && StandardOutput.isPipe() }

    /** The real stdout: its own buffered stream on file descriptor 1, and recognisable as that. */
    private class FileDescriptorOne : PrintStream(
        BufferedOutputStream(FileOutputStream(FileDescriptor.out), STDOUT_BUFFER_BYTES),
        false,
        STREAM_CHARSET,
    )

    /** A registration; its identity, not its action's, is what closing removes, so one action can be registered twice. */
    private class Registration(val action: () -> Unit)

    /** Newest first. Registered from the handler's thread and read from the shutdown hook's. */
    private val interruptActions = ConcurrentLinkedDeque<Registration>()

    private val hookInstalled = AtomicBoolean(false)

    /** Set by [runAndExit]: with no action registered, the hook says `interrupted`. */
    @Volatile
    private var announceInterrupt = false

    /** Set by [runAndExit] when the program's run ends, after which the JVM shutting down is no interrupt. */
    @Volatile
    private var finished = false

    /** Whether this terminal wrote to stdout at all; see [stdoutState]. */
    @Volatile
    private var wroteStdout = false

    private fun markWritten() {
        if (!wroteStdout) wroteStdout = true
    }

    /**
     * Registers [action] to run if the JVM shuts down while it is registered: on Ctrl-C or SIGTERM, which end the JVM
     * through its shutdown hooks rather than through the command. The first call installs this instance's shutdown
     * hook, so the actions run whether the program goes through [runAndExit] or calls [Program.run] itself. A command
     * closes the handle once it has stopped, and so a JVM that exits after it doesn't run the action.
     */
    override fun onInterrupt(action: () -> Unit): AutoCloseable {
        installInterruptHook()
        val registration = Registration(action)
        interruptActions.addFirst(registration)
        return AutoCloseable { interruptActions.remove(registration) }
    }

    /**
     * Installs the shutdown hook, once per instance: it clears a half-drawn progress line, runs the registered actions,
     * and delivers what stdout holds. One hook for all of it, because the JVM runs hooks at once and in no order. With
     * [announce], as [runAndExit] asks, it also says `interrupted` when no action was registered.
     */
    internal fun installInterruptHook(announce: Boolean = false) {
        if (announce) announceInterrupt = true
        if (hookInstalled.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(Thread(::onShutdown, "clikit-interrupt"))
        }
    }

    /** The program's run has ended: from here on the hook does nothing. */
    internal fun markFinished() {
        finished = true
    }

    private fun onShutdown() {
        if (finished) return
        Progress.clearShown()
        if (!runInterruptActions() && announceInterrupt) errLine("interrupted")
        flush()
    }

    /**
     * Runs the registered actions, newest first; `false` when there are none. One that throws is reported and the rest
     * still run: they are each some part of the program stopping cleanly.
     */
    internal fun runInterruptActions(): Boolean {
        val registrations = interruptActions.toList()
        for (registration in registrations) {
            try {
                registration.action()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                errLine(styler.red("error: ") + "while stopping: " + TerminalText.escape(e.message ?: e::class.simpleName.orEmpty()))
            }
        }
        return registrations.isNotEmpty()
    }

    override fun out(text: String) {
        markWritten()
        stdout.print(text)
        if (interactive) stdout.flush()
    }

    override fun err(text: String) {
        stderr.print(text)
        stderr.flush()
    }

    override fun flush() {
        stdout.flush()
    }

    /**
     * `PrintStream` swallows a failed write, and only [PrintStream.checkError] tells of it. That flag belongs to the
     * stream, not to this terminal: on a stream the caller shares, such as `System.out` under a protocol's own writer,
     * another writer's failure sets it too. A terminal that wrote nothing has no payload to lose, so it ignores the flag.
     */
    override fun stdoutState(): StdoutState = when {
        !wroteStdout || !stdout.checkError() -> StdoutState.Written
        stdoutIsPipe() -> StdoutState.ReaderGone
        else -> StdoutState.Failed
    }

    override val payload: OutputStream =
        if (!interactive || stdout.charset() == Charsets.UTF_8) {
            object : OutputStream() {
                override fun write(b: Int) {
                    markWritten()
                    stdout.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    markWritten()
                    stdout.write(b, off, len)
                }

                /** At a terminal, now; down a pipe, when the program's run ends. */
                override fun flush() {
                    if (interactive) stdout.flush()
                }
            }
        } else {
            Utf8TextOutputStream(::out)
        }

    override fun readPassword(prompt: String): CharArray? = Tty.readPassword(prompt)

    override fun readLine(prompt: String): String? = Tty.readLine(prompt)

    public companion object {
        /**
         * The charset both streams encode with.
         *
         * The fallback is taken here rather than in [Tty.charset], which answers a different
         * question: that one is "what can this console render", and its own no-console fallback is
         * `stdout.encoding` — the very value a redirected payload must not be written with.
         */
        private val STREAM_CHARSET: Charset = if (Tty.interactive) Tty.charset else Charsets.UTF_8

        private const val STDOUT_BUFFER_BYTES = 64 * 1024

        /**
         * The instance a program actually uses, with colour decided once by [decideColour] and the
         * width flag applied.
         *
         * `--no-color` and `--width` arrive from the parsed globals, which is why this is a factory
         * rather than an object: the decision needs the command line, and making it twice is how
         * the two halves of a program disagree about whether colour is on.
         */
        public fun create(noColor: Boolean, widthOverride: Int? = null): SystemTerminalIo {
            val colour = decideColour(noColor)
            return SystemTerminalIo(
                width = widthOverride ?: Tty.width,
                styler = Styler(colour),
            )
        }
    }
}
