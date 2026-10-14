package app.oreshkov.clikit

import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.system.exitProcess

/**
 * The whole of a program's `main`: the terminal built from the raw command line, the program run,
 * the process ended with its status.
 *
 * `--no-color` and `--width` are read before parsing so that a usage error is already rendered in
 * the right colours at the right width (see [StandardOptions.widthFrom]). This is the one place a
 * program built on this module calls `exitProcess`; everything below it returns a status.
 */
public fun Program.runAndExit(args: Array<String>): Nothing {
    val io = SystemTerminalIo.create(
        noColor = StandardOptions.noColor(args),
        widthOverride = StandardOptions.widthFrom(args),
    )
    // Ctrl-C ends the JVM through its shutdown hooks, not through `run`. The terminal's hook clears a half-drawn progress
    // line, runs what the command registered with `onInterrupt`, or here, with none, says "interrupted", and delivers
    // what stdout holds. The exit status is the platform's: 130 (128 + SIGINT) on a POSIX shell.
    io.installInterruptHook(announce = true)
    // `run` reports what a command throws, Errors included; this net is for a bug in the kit itself. Either way the
    // run has finished, and the hook must not call it an interrupt.
    val status = try {
        run(args, io)
    } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
        val verbose = StandardOptions.verbose
        reportBug(error, name, args.any { it == verbose.long || it == verbose.short }, io)
    } finally {
        io.markFinished()
    }
    exitProcess(status)
}

/**
 * The `Implementation-Version` of the jar that holds [anchor], for `--version`; [fallback] when the
 * class was not loaded from a jar with one — from an IDE, or a test's class directory.
 */
public fun implementationVersion(anchor: KClass<*>, fallback: String = "dev"): String =
    anchor.java.`package`?.implementationVersion ?: fallback

/**
 * A file system path, as given; whether it must exist is the command's to decide. `-` is a path
 * like any other here — a command that reads standard input for `-` checks for it itself.
 */
public val Converters.path: ValueConverter<Path>
    get() = PATH

private val PATH: ValueConverter<Path> = ValueConverter { raw ->
    try {
        ConversionResult.Ok(Path.of(raw))
    } catch (e: InvalidPathException) {
        ConversionResult.Invalid("'$raw' is not a valid path: ${e.reason}")
    }
}
