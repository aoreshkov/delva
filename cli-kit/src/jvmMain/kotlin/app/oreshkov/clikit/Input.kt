package app.oreshkov.clikit

import java.io.FilterInputStream
import java.nio.charset.CharacterCodingException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** A [TerminalIo] whose standard input can be given, so a test can feed what a pipe would. */
public interface BinaryInput {
    /** What the program reads as standard input. */
    public val stdin: InputStream
}

/**
 * Where a command reads data from: a file, or standard input spelled `-`, as tools that read files have always
 * spelled it. Standard input can be read only once, so the parser refuses `-` given twice (see [ReadsStandardInput]).
 */
public sealed interface InputSource : ReadsStandardInput {
    /** The file's path as given, or `-`: what messages name it by. */
    public val name: String

    /** The data; the caller closes it, and closing standard input's leaves the process's standard input open. */
    public fun open(io: TerminalIo): InputStream

    /** Standard input, given as `-`. */
    public data object Stdin : InputSource {
        override val name: String get() = "-"

        override val readsStandardInput: Boolean get() = true

        override fun open(io: TerminalIo): InputStream =
            object : FilterInputStream((io as? BinaryInput)?.stdin ?: System.`in`) {
                override fun close() = Unit
            }
    }

    /** The file at [path]. */
    public class File(public val path: Path) : InputSource {
        override val name: String get() = path.toString()

        override val readsStandardInput: Boolean get() = false

        override fun open(io: TerminalIo): InputStream = Files.newInputStream(path)

        override fun toString(): String = "File($path)"
    }
}

/** A file to read, or `-` for standard input; whether the file exists is found when it is opened. */
public val Converters.inputSource: ValueConverter<InputSource>
    get() = INPUT_SOURCE

private val INPUT_SOURCE: ValueConverter<InputSource> = ValueConverter { raw ->
    if (raw == "-") {
        ConversionResult.Ok(InputSource.Stdin)
    } else {
        try {
            ConversionResult.Ok(InputSource.File(Path.of(raw)))
        } catch (e: InvalidPathException) {
            ConversionResult.Invalid("'$raw' is not a valid path: ${e.reason}")
        }
    }
}

/**
 * A value given on the command line itself, or read from a file or standard input: `--body '{"a":1}'`, `--body
 * @request.json`, `--body @-`, as curl and httpie spell it. `@@` starts a literal that begins with `@`.
 */
public sealed interface InlineOrFile : ReadsStandardInput {

    /** The text, read in full as UTF-8; malformed UTF-8 is a [CharacterCodingException]. The caller decides what it must parse as. */
    public fun readText(io: TerminalIo): String

    /** The [text] given on the command line itself. */
    public data class Inline(val text: String) : InlineOrFile {
        override val readsStandardInput: Boolean get() = false

        override fun readText(io: TerminalIo): String = text
    }

    /** Text read from [source], a file or standard input. */
    public data class FromInput(val source: InputSource) : InlineOrFile {
        override val readsStandardInput: Boolean get() = source.readsStandardInput

        override fun readText(io: TerminalIo): String =
            source.open(io).use { it.readBytes() }.decodeToString(throwOnInvalidSequence = true)
    }
}

/** `@path` for a file, `@-` for standard input, `@@text` for `@text`, and anything else for the text itself. */
public val Converters.inlineOrFile: ValueConverter<InlineOrFile>
    get() = INLINE_OR_FILE

private val INLINE_OR_FILE: ValueConverter<InlineOrFile> = ValueConverter { raw ->
    when {
        raw.startsWith("@@") -> ConversionResult.Ok(InlineOrFile.Inline(raw.substring(1)))
        raw == "@" -> ConversionResult.Invalid("'@' names no file; give @<path>, or @- for standard input")
        raw.startsWith("@") -> INPUT_SOURCE.convert(raw.substring(1)).map { InlineOrFile.FromInput(it) }
        else -> ConversionResult.Ok(InlineOrFile.Inline(raw))
    }
}
