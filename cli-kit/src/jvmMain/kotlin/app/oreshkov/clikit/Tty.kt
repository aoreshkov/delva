package app.oreshkov.clikit

import java.io.Console
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Terminal capability detection, and the part of this module that used to justify a library.
 *
 * JDK 22 made `System.console()` return a JLine-backed `Console`, even when a stream is redirected,
 * and added `isTerminal()` to tell the two apart; JDK 25 returns `null` unless stdin and stdout are
 * both an interactive terminal (JDK-8361613). On JDK 22 and later, TTY detection is therefore a
 * null check and `isTerminal()` rather than a native call.
 */
public object Tty {
    private val console: Console? = System.console()

    /**
     * True only for an interactive terminal.
     *
     * `isTerminal()` is checked as well as the null: before JDK 25 there is a console even when a
     * stream is redirected, and on JDK 25 someone can force the JLine provider with
     * `-Djdk.console=jdk.internal.le`.
     */
    public val interactive: Boolean = console != null && console.isTerminal()

    /**
     * The console's charset, probed rather than assumed.
     *
     * This decides whether box-drawing characters are safe. On a Windows console still running a
     * legacy code page, emitting `┌─┐` produces mojibake — replacing a real thing Mordant did,
     * with a fallback border style rather than with hope.
     */
    public val charset: Charset = console?.charset()
        ?: runCatching { System.getProperty("stdout.encoding")?.let(Charset::forName) }.getOrNull()
        ?: Charsets.UTF_8

    /** The border style this console can actually render. */
    public val borderStyle: BorderStyle =
        if (canEncode(BOX_DRAWING_PROBE)) BorderStyle.Unicode else BorderStyle.Ascii

    /**
     * Terminal width.
     *
     * `COLUMNS` when the shell exports it, otherwise 80. Exact detection is the one capability
     * deliberately not implemented (see [TableRenderer.DEFAULT_WIDTH]); `--width` is the override
     * and a truncated cell is the failure mode.
     */
    public val width: Int =
        System.getenv("COLUMNS")?.toIntOrNull()?.takeIf { it > MIN_SENSIBLE_WIDTH }
            ?: TableRenderer.DEFAULT_WIDTH

    /**
     * Read a password without echo, or `null` when there is no terminal.
     *
     * Note `readPassword("%s", prompt)`: the prompt goes through as an **argument**, never as the
     * format string. An account name containing a `%` must not become a
     * `MissingFormatArgumentException` at the moment someone is trying to log in.
     *
     * [interactive], not the console alone, decides "no terminal": before JDK 25 a redirected
     * process still has a console, and reading from it would take the password from the pipe.
     */
    public fun readPassword(prompt: String): CharArray? =
        console?.takeIf { interactive }?.readPassword("%s", prompt)

    /** Read a line after [prompt], or `null` when there is no terminal — for the same reasons as [readPassword]. */
    public fun readLine(prompt: String): String? =
        console?.takeIf { interactive }?.readLine("%s", prompt)

    private fun canEncode(probe: String): Boolean = runCatching {
        charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .canEncode(probe)
    }.recoverCatching { error ->
        if (error is CharacterCodingException) false else throw error
    }.getOrDefault(false)

    private const val BOX_DRAWING_PROBE = "─│┌┐└┘┬┴├┤┼…"
    private const val MIN_SENSIBLE_WIDTH = 20
}

/**
 * Whether to emit colour, decided once and in this order — which matters because users expect
 * exactly this order.
 *
 * `--no-color` beats everything, then `NO_COLOR` (no-color.org), then the force variables that
 * exist so a CI job can have colour anyway, then `TERM=dumb`, then whether there is a terminal at
 * all. Only if all of that says yes does the Windows opt-in decide. [SystemTerminalIo] asks for the opt-in
 * whatever the answer, since a progress line erases itself with an escape even without colour.
 */
public fun decideColour(
    noColorFlag: Boolean,
    interactive: Boolean = Tty.interactive,
    environment: (String) -> String? = System::getenv,
    /**
     * The last step: on Windows, whether the console accepted the VT opt-in.
     *
     * Injectable so that the *ordering* above can be tested without the answer depending on
     * whether the test JVM happens to have a console attached — which it does not under Gradle,
     * and which would make half these assertions pass for the wrong reason.
     */
    enableVirtualTerminal: () -> Boolean = VirtualTerminal::enable,
): Boolean = when {
    noColorFlag -> false
    environment("NO_COLOR")?.isNotEmpty() == true -> false
    environment("FORCE_COLOR")?.isNotEmpty() == true -> true
    environment("CLICOLOR_FORCE") == "1" -> true
    environment("TERM") == "dumb" -> false
    !interactive -> false
    else -> enableVirtualTerminal()
}
