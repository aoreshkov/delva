package app.oreshkov.clikit.testing

import app.oreshkov.clikit.BinaryInput
import app.oreshkov.clikit.InterruptRegistry
import app.oreshkov.clikit.Program
import app.oreshkov.clikit.TerminalIo
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * This terminal with [stdin] as its standard input — what `-` reads — for a test that hands a [TerminalIo] to code of
 * its own rather than to a [Program]. What is written still lands in this recorder.
 *
 * An adapter rather than a constructor parameter because standard input is a JVM stream (see [BinaryInput]), and the
 * recorder itself is platform-free.
 */
public fun RecordingTerminalIo.withStdin(stdin: ByteArray): TerminalIo = object :
    TerminalIo by this,
    InterruptRegistry by this,
    BinaryInput {
    override val stdin: InputStream = ByteArrayInputStream(stdin)
}

/** Runs the program in-process with [stdin] as its standard input — what `-` reads — and records what it did. */
public fun Program.record(vararg args: String, stdin: ByteArray, io: RecordingTerminalIo = RecordingTerminalIo()): RunResult {
    val status = run(arrayOf(*args), io.withStdin(stdin))
    return RunResult(status, io.stdout, io.stderr)
}
