package app.oreshkov.clikit

/**
 * Argument files: `@path` on the command line stands for the arguments the file holds, one per line, which reach the
 * program without passing through a shell or a launcher script. That is how an argument holding `|`, `&` or `^` gets
 * through a Windows `.bat` start script, which hands its arguments to `cmd`, and `cmd` reads `|` as a pipe however
 * the argument was quoted.
 */

/** What [Parser.expand] made of a command line: the arguments with every argument file in place, or why one can't be read. */
internal sealed interface Expansion {
    /** The arguments, each argument file replaced by its lines; these are never expanded again. */
    class Expanded(val args: List<String>) : Expansion

    /** An argument file that can't be read: `can't read argument file 'x': <reason>`. */
    class Unreadable(val message: String) : Expansion
}

/** An argument file that can't be read; the message is the reason, such as `no such file`. */
internal class ArgumentFileException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The arguments in [text], an argument file's content: one per line, each taken verbatim, with no quoting, escapes or
 * comments, so that a filter full of quotes, `|` and `$` means what it says. Lines end with LF or CRLF, and the final
 * line break adds no argument, so every editor's file reads the same; an empty line anywhere else is an empty
 * argument. A leading byte-order mark is left out, as Notepad writes one.
 */
internal fun argumentLines(text: String): List<String> {
    val body = text.removePrefix(BYTE_ORDER_MARK)
    if (body.isEmpty()) return emptyList()
    val lines = body.split('\n').map { it.removeSuffix("\r") }
    return if (body.endsWith('\n')) lines.dropLast(1) else lines
}
