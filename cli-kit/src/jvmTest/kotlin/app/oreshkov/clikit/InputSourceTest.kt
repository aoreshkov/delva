package app.oreshkov.clikit

import app.oreshkov.clikit.testing.RecordingTerminalIo
import app.oreshkov.clikit.testing.record
import app.oreshkov.clikit.testing.withStdin
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InputSourceTest {

    private val file = Argument.Variadic("file", Converters.inputSource, "Files, or - for standard input")
    private val cat = CommandSpec("cat", arguments = listOf(file))
    private val program = Program(
        CommandSpec("demo", subcommands = listOf(cat)),
        "demo 1.0",
        mapOf(
            cat to CommandHandler { context ->
                for (source in context.values[file]) {
                    source.open(context.io).use { context.io.out(it.readAllBytes().toString(Charsets.UTF_8)) }
                }
                0
            },
        ),
    )

    @Test
    fun `dash is standard input, anything else a file`() {
        assertIs<InputSource.Stdin>((Converters.inputSource.convert("-") as ConversionResult.Ok).value)
        val parsed = (Converters.inputSource.convert("a.json") as ConversionResult.Ok).value
        assertEquals(Path.of("a.json"), (parsed as InputSource.File).path)
    }

    @Test
    fun `files and standard input read in the order given`() {
        val a: Path = Files.createTempFile("input", ".txt")
        try {
            a.writeText("from a file;")
            val result = program.record("cat", a.toString(), "-", stdin = "from stdin".toByteArray())
            assertEquals("from a file;from stdin", result.stdout)
        } finally {
            a.deleteIfExists()
        }
    }

    @Test
    fun `dash given twice is refused before the handler runs`() {
        val result = program.record("cat", "-", "-", stdin = "once".toByteArray())
        assertEquals(ExitStatus.USAGE, result.status)
        assertEquals("", result.stdout)
        assertContains(result.stderr, "standard input (-) can be read only once")
    }

    private fun inline(raw: String): InlineOrFile = (Converters.inlineOrFile.convert(raw) as ConversionResult.Ok).value

    @Test
    fun `inline or file - @path is a file, @- standard input, @@ a literal at sign, anything else the text`() {
        assertEquals(InlineOrFile.Inline("""{"a":1}"""), inline("""{"a":1}"""))
        assertEquals(InlineOrFile.Inline("@home"), inline("@@home"))
        assertEquals(InlineOrFile.Inline(""), inline(""))
        assertEquals(InlineOrFile.FromInput(InputSource.Stdin), inline("@-"))
        assertEquals(Path.of("body.json"), ((inline("@body.json") as InlineOrFile.FromInput).source as InputSource.File).path)
        val refused = Converters.inlineOrFile.convert("@")
        assertIs<ConversionResult.Invalid>(refused)
        assertContains(refused.reason, "names no file")
        assertTrue(inline("@-").readsStandardInput)
        assertFalse(inline("@body.json").readsStandardInput)
        assertFalse(inline("-").readsStandardInput)
    }

    @Test
    fun `inline or file reads its text, and refuses malformed UTF-8`() {
        val io = RecordingTerminalIo().withStdin("é from stdin".toByteArray())
        assertEquals("é from stdin", inline("@-").readText(io))
        assertEquals("literal", inline("literal").readText(io))
        val a: Path = Files.createTempFile("input", ".bin")
        try {
            Files.write(a, byteArrayOf(0x7b, 0xff.toByte(), 0x7d))
            assertFailsWith<CharacterCodingException> { inline("@$a").readText(io) }
        } finally {
            a.deleteIfExists()
        }
    }

    @Test
    fun `an inline value and a file argument cannot both take standard input`() {
        val body = Option.Single("--body", Converters.inlineOrFile)
        val put = CommandSpec("put", options = listOf(body), arguments = listOf(file))
        val parsed = Parser(put).parse(listOf("--body", "@-", "-"))
        assertIs<ParseResult.UsageError>(parsed)
        assertEquals("standard input (-) can be read only once", parsed.message)
        assertIs<ParseResult.Invocation>(Parser(put).parse(listOf("--body", "@-", "a.json")))
    }
}
