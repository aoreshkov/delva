package app.oreshkov.clikit.testing

import app.oreshkov.clikit.Program
import app.oreshkov.clikit.Styler
import kotlin.test.fail

/** What one run of a program left behind: its exit status and both streams, apart. */
public class RunResult(
    /** The exit status. */
    public val status: Int,
    /** Everything written to standard output. */
    public val stdout: String,
    /** Everything written to standard error. */
    public val stderr: String,
) {
    /**
     * Fails when an escape sequence reached stdout. With a non-interactive terminal — the default of
     * [RecordingTerminalIo], and what a pipe is — stdout is the payload and must stay plain.
     */
    public fun assertPipeSafe(): RunResult {
        if (Styler.hasEscapes(stdout)) fail("an escape sequence reached stdout:\n$stdout")
        return this
    }

    override fun toString(): String = "RunResult(status=$status, stdout=$stdout, stderr=$stderr)"
}

/** Runs the program in-process against [io] — no terminal, no subprocess — and records what it did. */
public fun Program.record(vararg args: String, io: RecordingTerminalIo = RecordingTerminalIo()): RunResult {
    val status = run(arrayOf(*args), io)
    return RunResult(status, io.stdout, io.stderr)
}
