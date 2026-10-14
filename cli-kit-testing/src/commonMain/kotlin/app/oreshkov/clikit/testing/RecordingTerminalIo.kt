package app.oreshkov.clikit.testing

import app.oreshkov.clikit.BorderStyle
import app.oreshkov.clikit.InterruptRegistry
import app.oreshkov.clikit.Styler
import app.oreshkov.clikit.TableRenderer
import app.oreshkov.clikit.TerminalIo
import app.oreshkov.clikit.TerminalText

/**
 * Captures both streams, for tests.
 *
 * The reason it records them separately rather than interleaved: the assertions worth making are
 * "the payload was exactly this" and "no escape sequence reached the pipe", and both need the
 * streams apart.
 */
public class RecordingTerminalIo(
    override val interactive: Boolean = false,
    override val width: Int = TableRenderer.DEFAULT_WIDTH,
    override val styler: Styler = Styler.Plain,
    override val borderStyle: BorderStyle = BorderStyle.Ascii,
    private val password: CharArray? = null,
    /** What the user types at each [readLine], in turn; `null` once they run out, as without a terminal. */
    answers: List<String> = emptyList(),
) : TerminalIo, InterruptRegistry {
    private val answers = ArrayDeque(answers)
    private val outBuffer = StringBuilder()
    private val errBuffer = StringBuilder()

    /** Everything written to standard output so far. */
    public val stdout: String get() = outBuffer.toString()

    /** Everything written to standard error so far. */
    public val stderr: String get() = errBuffer.toString()

    override fun out(text: String) {
        outBuffer.append(text)
    }

    override fun err(text: String) {
        errBuffer.append(text)
    }

    override fun readPassword(prompt: String): CharArray? {
        errLine(prompt)
        return password?.copyOf()
    }

    override fun readLine(prompt: String): String? {
        val answer = answers.removeFirstOrNull() ?: return null
        errLine(prompt + answer)
        return answer
    }

    private class Registration(val action: () -> Unit)

    private val interruptActions = ArrayDeque<Registration>()

    override fun onInterrupt(action: () -> Unit): AutoCloseable {
        val registration = Registration(action)
        interruptActions.addFirst(registration)
        return AutoCloseable { interruptActions.remove(registration) }
    }

    /**
     * Does what the real terminal does when the process is interrupted: runs the actions registered with
     * `onInterrupt`, newest first, or writes `interrupted` when there are none; answers whether any action ran. An
     * action that throws is reported as `error: while stopping: <message>`, and the rest still run. The recorder is not
     * thread-safe, so call it while the command is waiting rather than writing, as a test that stops a command blocked
     * until it is stopped does.
     */
    public fun interrupt(): Boolean {
        val registrations = interruptActions.toList()
        if (registrations.isEmpty()) errLine("interrupted")
        for (registration in registrations) {
            try {
                registration.action()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                errLine(styler.red("error: ") + "while stopping: " + TerminalText.escape(e.message ?: e::class.simpleName.orEmpty()))
            }
        }
        return registrations.isNotEmpty()
    }
}
