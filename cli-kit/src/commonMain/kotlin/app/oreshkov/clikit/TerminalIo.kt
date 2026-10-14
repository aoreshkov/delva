package app.oreshkov.clikit

/**
 * Where output goes, and the reason a program built on this module needs no `println`.
 *
 * **The split is a contract, not a preference**: the payload goes to stdout and everything
 * decorative goes to stderr, so `demo request get --json | jq` works with no filtering. Two sinks
 * on one object is what makes that impossible to get wrong by accident — there is no ambient
 * stream to reach for.
 */
public interface TerminalIo {
    /** The payload. Only ever the thing the command was asked to produce. */
    public fun out(text: String)

    /** Progress, tables, warnings, errors — everything a pipe should not receive. */
    public fun err(text: String)

    /** [text], then a line break, on stdout. */
    public fun outLine(text: String = "") {
        out(text)
        out("\n")
    }

    /** [text], then a line break, on stderr. */
    public fun errLine(text: String = "") {
        err(text)
        err("\n")
    }

    /** Whether stdout is an interactive terminal — the one question colour and progress turn on. */
    public val interactive: Boolean

    /** Columns to render tables at. */
    public val width: Int

    /** Applies the colours and bold the output uses; [Styler.Plain] when colour is off. */
    public val styler: Styler

    /** The table borders the terminal can show. */
    public val borderStyle: BorderStyle

    /**
     * Whether the terminal acts on escape sequences, colour or not: `false` on a legacy Windows console that refused
     * VT processing, where `ESC [ K` would show as text. [Progress] erases its line with spaces there.
     */
    public val escapesInterpreted: Boolean get() = true

    /**
     * Read a password without echo.
     *
     * `null` means there is no terminal to read from — stdin or stdout is redirected — so a caller
     * can refuse with a usable message instead of hanging or, far worse, echoing the password into
     * a log.
     *
     * A `CharArray` precisely so the value can be erased afterwards; a `String` cannot be.
     */
    public fun readPassword(prompt: String): CharArray?

    /**
     * Read a line the user types after [prompt], such as an answer to a confirmation; `null` when there is no terminal
     * to read from, so a caller refuses with a usable message rather than read its answer from a pipe.
     */
    public fun readLine(prompt: String): String? = null

    /**
     * Delivers whatever stdout still holds. A terminal may buffer the payload when it is a pipe; [Program.run]
     * calls this when it returns, so nothing a command wrote is lost however it ends.
     */
    public fun flush() {}

    /**
     * Whether stdout took everything written to it, asked once [flush] has run. A stream that failed — a full disk, a
     * closed pipe — must not end in exit status 0 over a truncated payload, so [Program.run] turns anything but
     * [StdoutState.Written] into [ExitStatus.IO_ERR].
     */
    public fun stdoutState(): StdoutState = StdoutState.Written
}

/** What became of the payload written to stdout; see [TerminalIo.stdoutState]. */
public enum class StdoutState {
    /** All of it was written. */
    Written,

    /**
     * stdout is a pipe whose reader went away, as `head` does once it has its lines. Not reported: the reader asked
     * for no more.
     */
    ReaderGone,

    /** Writing failed, and the program says so on stderr. */
    Failed,
}

/**
 * A [TerminalIo] that runs a command's own actions when the process is interrupted, in place of the `interrupted`
 * notice; see [onInterrupt]. The real terminal is one, and so is the test recorder, which interrupts on request.
 */
public interface InterruptRegistry {
    /** Registers [action]; closing the handle unregisters it. */
    public fun onInterrupt(action: () -> Unit): AutoCloseable
}

