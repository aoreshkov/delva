package app.oreshkov.clikit

import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * A program for [InterruptProcessTest] to run in a child JVM: `serve` runs until the process is stopped, as a daemon
 * does, and with `--handle` stops cleanly through [onInterrupt]; `crash` throws an Error.
 */
private object InterruptFixture {
    val handle = Option.Flag("--handle", help = "Register an interrupt action")

    val serve = CommandSpec("serve", "Run until stopped", options = listOf(handle))

    val crash = CommandSpec("crash", "Throw an Error")

    val program = Program(
        CommandSpec("fixture", subcommands = listOf(serve, crash)),
        "fixture 1",
        mapOf(
            crash to CommandHandler { throw StackOverflowError("too deep") },
            serve to CommandHandler { context ->
                if (context.values[handle]) context.onInterrupt { context.io.errLine("stopping cleanly") }
                // Ends the JVM from inside once standard input closes: a shutdown `run` never returns from, as a
                // signal's is, but one a test can cause on every OS.
                Thread {
                    System.`in`.readAllBytes()
                    exitProcess(EXIT_ON_EOF)
                }.apply { isDaemon = true }.start()
                context.io.outLine("ready")
                context.io.flush()
                CountDownLatch(1).await()
                ExitStatus.OK
            },
        ),
    )

    const val EXIT_ON_EOF = 3
}

/**
 * The fixture's entry point: `java -cp <test classpath> app.oreshkov.clikit.InterruptFixtureKt serve [--handle] | crash`.
 * With `-Dfixture.direct`, it calls `Program.run` on a terminal of its own, as a program that doesn't use `runAndExit`
 * does.
 */
fun main(args: Array<String>) {
    if (System.getProperty("fixture.direct") != null) {
        exitProcess(InterruptFixture.program.run(args, SystemTerminalIo.create(noColor = true)))
    }
    InterruptFixture.program.runAndExit(args)
}
