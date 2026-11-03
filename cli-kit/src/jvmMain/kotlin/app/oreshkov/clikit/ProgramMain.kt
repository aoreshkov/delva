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
 *
 * With [argument files][withArgumentFiles] on, they are read first, once, so `--no-color` and `--width` work from a
 * file too.
 */
public fun Program.runAndExit(args: Array<String>): Nothing {
    val expansion = expand(args)
    // A file that can't be read is reported on a terminal built from what the command line itself says.
    val tokens = (expansion as? Expansion.Expanded)?.args?.toTypedArray() ?: args
    val io = SystemTerminalIo.create(
        noColor = StandardOptions.noColor(tokens),
        widthOverride = StandardOptions.widthFrom(tokens),
    )
    // Ctrl-C ends the JVM through its shutdown hooks, not through `run`. The terminal's hook clears a half-drawn progress
    // line, runs what the command registered with `onInterrupt`, or here, with none, says "interrupted", and delivers
    // what stdout holds. The exit status is the platform's: 130 (128 + SIGINT) on a POSIX shell.
    io.installInterruptHook(announce = true)
    // `run` reports what a command throws, Errors included; this net is for a bug in the kit itself. Either way the
    // run has finished, and the hook must not call it an interrupt.
    val status = try {
        run(expansion, io)
    } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
        val verbose = StandardOptions.verbose
        reportBug(error, name, tokens.any { it == verbose.long || it == verbose.short }, io)
    } finally {
        io.markFinished()
    }
    exitProcess(status)
}

/**
 * A copy of this program with argument files on: on the command line, `@path` stands for the arguments in the file at
 * `path`, one per line, which reach the program without passing through a shell or a launcher script. That is the way
 * to give an argument that holds `|`, `&` or `^` through a Windows `.bat` start script, which hands it to `cmd`.
 *
 * - **Where:** only where the parser reads a new option or operand. A value an option takes, such as `--where @.a`,
 *   `--where=@.a` or `-w @.a`, is left as it is, and so is everything after `--`, so a JSONPath filter, or a value
 *   [Converters.inlineOrFile] reads as a file, keeps working. An operand that starts with `@` is written `@@`, and
 *   `@` alone is left as it is.
 * - **The file:** UTF-8, at most 1 MiB, with LF or CRLF line ends and an optional byte-order mark. Each line is one
 *   argument, verbatim: no quotes, escapes or comments. The final line break adds no argument; an empty line anywhere
 *   else is an empty argument. A relative path is relative to the working directory.
 * - **As if typed:** the lines are read where `@path` was, options, values and `--` included; a line that starts with
 *   `@` is literal, since files don't nest. `--no-color` and `--width` work from a file too.
 * - **Errors:** a file that can't be read is `error: can't read argument file 'x': <reason>`, exit
 *   [ExitStatus.NO_INPUT], with no usage line.
 *
 * Help pages end with a line saying so, and the completion scripts step over a word that starts with `@`.
 */
@ExperimentalCliKitApi
public fun Program.withArgumentFiles(): Program = withArgumentFiles(::readArgumentFile)

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
