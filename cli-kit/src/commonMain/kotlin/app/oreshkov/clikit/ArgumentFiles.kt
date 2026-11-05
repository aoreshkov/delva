package app.oreshkov.clikit

/**
 * Argument files: `@path` as the first argument stands for the arguments the file holds, one per line, which reach the
 * program without passing through a shell or a launcher script. That is how an argument holding `|`, `&` or `^` gets
 * through a Windows `.bat` start script, which hands its arguments to `cmd`, and `cmd` reads `|` as a pipe however
 * the argument was quoted.
 */

/** What [expandArgumentFile] made of a command line: the arguments with the argument file in place, or why it can't be read. */
internal sealed interface Expansion {
    /** The arguments, with an argument file replaced by its lines; these are never expanded again. */
    class Expanded(val args: List<String>) : Expansion

    /** An argument file that can't be read: `can't read argument file 'x': <reason>`. */
    class Unreadable(val message: String) : Expansion
}

/** An argument file that can't be read; the message is the reason, such as `no such file`. */
internal class ArgumentFileException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * [args] with an argument file, read by [read], in place of their first word, for a program that has turned argument
 * files on.
 *
 * Only the first word can name a file, since it is the one word that can't be data: anywhere later, `@id` may be a key
 * or a file name, and reading it as a file would break the command, or if a file of that name is there, silently run
 * another one. There, `@@x` is the literal `@x`, and `@` alone is left as it is, since it names no file. The file's
 * lines are followed by the rest of [args] as they are, and a line that starts with `@` is literal: files don't nest.
 */
internal fun expandArgumentFile(args: List<String>, read: (String) -> String): Expansion {
    val first = args.firstOrNull()
    if (first == null || first.length < 2 || first[0] != '@') return Expansion.Expanded(args)
    val rest = args.drop(1)
    if (first[1] == '@') return Expansion.Expanded(listOf(first.substring(1)) + rest)
    val file = first.substring(1)
    return try {
        Expansion.Expanded(argumentLines(read(file)) + rest)
    } catch (e: ArgumentFileException) {
        Expansion.Unreadable("can't read argument file '$file': ${e.message}")
    }
}

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
