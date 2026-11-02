package app.oreshkov.clikit

import app.oreshkov.clikit.testing.record
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Argument files on the JVM: reading a real file, and a property over generated command lines. The property's cases
 * each have their own seed, printed when one fails; `-Ddelva.seed=<seed>` runs just that case again.
 */
class ArgumentFilesJvmTest {

    @TempDir
    lateinit var directory: Path

    private fun reason(name: String): String = assertFailsWith<ArgumentFileException> { readArgumentFile(name) }.message!!

    @Test
    fun `a file is read as UTF-8, whole`() {
        val file = directory.resolve("args.txt")
        file.writeBytes("\uFEFF--where\r\n@.kind == 'a|b' – ünïcode\r\n".toByteArray())
        assertEquals(listOf("--where", "@.kind == 'a|b' – ünïcode"), argumentLines(readArgumentFile(file.toString())))
    }

    @Test
    fun `a file that can't be read says why`() {
        assertEquals("no such file", reason(directory.resolve("missing.txt").toString()))
        assertEquals("it is a directory", reason(directory.resolve("dir").createDirectory().toString()))
        val latin1 = directory.resolve("latin1.txt").also { it.writeBytes(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())) }
        assertEquals("not UTF-8 text", reason(latin1.toString()))
        val huge = directory.resolve("huge.txt").also { it.writeText("a\n".repeat((1 shl 20) / 2 + 1)) }
        assertEquals("larger than 1 MiB", reason(huge.toString()))
        assertTrue(reason("bad\u0000name").startsWith("not a valid path: "))
    }

    @Test
    fun `a file of exactly 1 MiB is read`() {
        val file = directory.resolve("full.txt").also { it.writeText("a\n".repeat((1 shl 20) / 2)) }
        assertEquals((1 shl 20) / 2, argumentLines(readArgumentFile(file.toString())).size)
    }

    @Test
    fun `withArgumentFiles reads files relative to the working directory, and names the missing one`() {
        val file = Files.createTempFile(Path.of(""), "args", ".txt")
        try {
            file.writeText("store\nget\nk1\n")
            val program = Program(TestCommands.root, "demo 1.0", TestCommands.handlers(ExitStatus.FAILURE)).withArgumentFiles()
            assertEquals(ExitStatus.FAILURE, program.record("@${file.fileName}").status)
            val missing = program.record("@${directory.resolve("missing.txt")}")
            assertEquals(ExitStatus.NO_INPUT, missing.status)
            assertEquals("error: can't read argument file '${directory.resolve("missing.txt")}': no such file\n", missing.stderr)
        } finally {
            Files.delete(file)
        }
    }

    @Test
    fun `the README's example`() {
        val where = Option.Single("--where", Converters.string, valueName = "filter")
        val path = Argument.Single("path", Converters.string, "Where to look")
        val query = CommandSpec("query", "Find entries", options = listOf(where), arguments = listOf(path))
        val root = CommandSpec("tool", subcommands = listOf(query))

        var seen: Pair<String?, String?>? = null
        val program = Program(
            root,
            "tool 1.0",
            mapOf(query to CommandHandler { context -> seen = context.values[where] to context.values[path]; ExitStatus.OK }),
        ).withArgumentFiles()

        val file = directory.resolve("args.txt").also { it.writeText("query\n--where\n@.kind == 'a|b'\n\$.orders\n") }
        assertEquals(ExitStatus.OK, program.record("@$file").status)
        assertEquals("@.kind == 'a|b'" to "\$.orders", seen)

        file.writeText("--where\n@.kind == 'a|b'\n\$.orders\n")
        seen = null
        assertEquals(ExitStatus.OK, program.record("query", "@$file").status)
        assertEquals("@.kind == 'a|b'" to "\$.orders", seen)

        // A value is never a file, and an operand starting with @ is written @@.
        assertEquals(ExitStatus.OK, program.record("query", "--where", "@.kind", "@@x").status)
        assertEquals("@.kind" to "@x", seen)

        val missing = program.record("@args.txt")
        assertEquals(ExitStatus.NO_INPUT, missing.status)
        assertEquals("error: can't read argument file 'args.txt': no such file\n", missing.stderr)
        assertContains(program.record("--help").stdout, "@file reads arguments from a file, one per line; @@ for a literal @.")
    }

    @Test
    fun `a command line written to a file reads as the same command line typed`() = forEachCase { random ->
        val args = List(random.nextInt(0, 9)) { WORDS.random(random) }
        val content = write(args, random)
        assertEquals(args, argumentLines(content), "the file was ${content.escaped()}")

        val read = { name: String -> if (name == "f") content else throw ArgumentFileException("no such file") }
        val parser = Parser(TestCommands.root)
        // A whole command line from a file: its lines are literal, `@` ones too.
        assertEquals(args, assertIs<Expansion.Expanded>(parser.expand(listOf("@f"), read)).args)
        val program = Program(TestCommands.root, "demo 1.0", TestCommands.handlers())
        // The handlers write nothing, so stdout is help or a completion script, which say argument files are on.
        val typed = program.record(*args.toTypedArray())
        val fromFile = program.withArgumentFiles(read).record("@f")
        assertEquals(typed.status to typed.stderr, fromFile.status to fromFile.stderr, args.toString())

        // Part of one, after arguments typed: either in place of `@f`, or `@f` left as a value or an operand after `--`.
        val split = random.nextInt(0, args.size + 1)
        val typedPart = args.subList(0, split).map { if (it.startsWith("@")) "@$it" else it }
        val before = assertIs<Expansion.Expanded>(parser.expand(typedPart, read)).args
        val expanded = assertIs<Expansion.Expanded>(parser.expand(typedPart + "@f", read)).args
        assertTrue(expanded == before + args || expanded == before + "@f", "$typedPart @f gave $expanded")
    }

    /** [args] as an argument file, with LF or CRLF, a byte-order mark or not, and a final line break or not. */
    private fun write(args: List<String>, random: Random): String {
        val separator = if (random.nextBoolean()) "\n" else "\r\n"
        val bom = if (random.nextInt(4) == 0) "\uFEFF" else ""
        // Without the final line break, a last empty argument would be lost; so would a file of one empty argument.
        val final = if (args.isEmpty() || (args.last().isNotEmpty() && random.nextBoolean())) "" else separator
        return bom + args.joinToString(separator) + final
    }

    private fun String.escaped(): String = replace("\r", "\\r").replace("\n", "\\n").replace("\uFEFF", "\\uFEFF")

    private fun forEachCase(cases: Int = 300, property: (Random) -> Unit) {
        val seeds = System.getProperty("delva.seed")?.toLong()?.let(::listOf)
            ?: Random.nextLong().let { run -> List(cases) { run + it } }
        for (seed in seeds) {
            try {
                property(Random(seed))
            } catch (failure: AssertionError) {
                throw AssertionError("case failed (seed=$seed)\n  reproduce with: -Ddelva.seed=$seed", failure)
            }
        }
    }

    private companion object {
        /** Words the fixture tree reads in every role, and some it doesn't. */
        val WORDS = listOf(
            "store", "get", "query", "prune", "request", "raw", "session", "list", "help", "req",
            "--data-dir", "--data-dir=d", "-v", "--json", "--", "-a", "-va", "-vab", "--account", "-p", "--param", "k=v",
            "-n", "5", "--max", "--before", "--confirm", "--method", "GET", "--url", "--help",
            "x", "e", "", " ", "a b", "'q'", "a|b", "^&", "@y", "@@z", "@", "@.kind == 'a|b'", "-",
        )
    }
}
