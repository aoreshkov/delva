package app.oreshkov.clikit

import app.oreshkov.clikit.testing.record
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Argument files: the format of one, where `@path` is expanded and where not, and what [Program] makes of them. */
class ArgumentFilesTest {

    private val parser = Parser(TestCommands.root)

    /** Files by name; a name that isn't here can't be read. */
    private val files = mutableMapOf<String, String>()

    private fun read(name: String): String = files[name] ?: throw ArgumentFileException("no such file")

    private fun expand(vararg args: String): List<String> =
        assertIs<Expansion.Expanded>(parser.expand(args.toList(), ::read)).args

    @Test
    fun `a file holds one argument per line, verbatim`() {
        assertEquals(listOf("a", "b c", " d ", "'e'", "\"f\"", "# g", "a\\b"), argumentLines("a\nb c\n d \n'e'\n\"f\"\n# g\na\\b\n"))
    }

    @Test
    fun `line ends, the final line break and a byte-order mark read the same in every editor's file`() {
        val expected = listOf("--where", "@.kind == 'a|b'")
        assertEquals(expected, argumentLines("--where\n@.kind == 'a|b'\n"))
        assertEquals(expected, argumentLines("--where\n@.kind == 'a|b'"))
        assertEquals(expected, argumentLines("--where\r\n@.kind == 'a|b'\r\n"))
        assertEquals(expected, argumentLines("﻿--where\r\n@.kind == 'a|b'\r\n"))
    }

    @Test
    fun `an empty line is an empty argument, except for the final line break`() {
        assertEquals(emptyList(), argumentLines(""))
        assertEquals(emptyList(), argumentLines("﻿"))
        assertEquals(listOf(""), argumentLines("\n"))
        assertEquals(listOf("a", "", "b"), argumentLines("a\n\nb\n"))
        assertEquals(listOf("a", ""), argumentLines("a\n\n"))
        // Only CRLF is a line end; a carriage return elsewhere is part of the argument.
        assertEquals(listOf("a\rb"), argumentLines("a\rb\n"))
    }

    @Test
    fun `a file is expanded where an operand or an option is read`() {
        files["key"] = "k1\n"
        files["command"] = "store\nget\nk1\n"
        files["options"] = "--data-dir\nd\n"
        assertEquals(listOf("store", "get", "k1"), expand("store", "get", "@key"))
        assertEquals(listOf("store", "get", "k1"), expand("@command"))
        assertEquals(listOf("--data-dir", "d", "store", "get", "k1"), expand("@options", "store", "get", "@key"))
        assertEquals(listOf("store", "get", "--data-dir", "d", "k1"), expand("store", "get", "@options", "k1"))
    }

    @Test
    fun `an option's value is never expanded`() {
        val values = listOf(
            listOf("--data-dir", "@x", "store", "get", "k"),
            listOf("--data-dir=@x", "store", "get", "k"),
            listOf("store", "get", "--data-dir", "@x", "k"),
            listOf("request", "get", "-a", "@x", "e"),
            listOf("request", "get", "-a@x", "e"),
            listOf("request", "get", "--param", "@x", "-a", "b", "e"),
            listOf("request", "get", "-p", "@x", "-a", "b", "e"),
            // A cluster whose last short option takes a value takes the next argument as it.
            listOf("request", "get", "-va", "@x", "e"),
            // A former name takes a value as the option does.
            listOf("session", "list", "--max", "@x"),
        )
        values.forEach { assertEquals(it, expand(*it.toTypedArray()), it.joinToString(" ")) }
    }

    @Test
    fun `after a value in a cluster, the next argument is a new one`() {
        files["endpoint"] = "e\n"
        assertEquals(listOf("request", "get", "-vab", "e"), expand("request", "get", "-vab", "@endpoint"))
        assertEquals(listOf("request", "get", "-v", "e"), expand("request", "get", "-v", "@endpoint"))
    }

    @Test
    fun `nothing after -- is expanded, whether -- was typed or read from a file`() {
        files["end"] = "--\n"
        assertEquals(listOf("store", "query", "--", "@x"), expand("store", "query", "--", "@x"))
        assertEquals(listOf("store", "query", "--", "@x"), expand("store", "query", "@end", "@x"))
    }

    @Test
    fun `a file's last option takes its value from the next argument, which is then not expanded`() {
        files["dir"] = "--data-dir\n"
        assertEquals(listOf("--data-dir", "@x", "store", "get", "k"), expand("@dir", "@x", "store", "get", "k"))
    }

    @Test
    fun `@@ is a literal @, and @ alone names no file`() {
        assertEquals(listOf("store", "get", "@k"), expand("store", "get", "@@k"))
        assertEquals(listOf("store", "get", "@@k"), expand("store", "get", "@@@k"))
        assertEquals(listOf("store", "get", "@"), expand("store", "get", "@"))
    }

    @Test
    fun `files don't nest, so a line that starts with @ is literal`() {
        files["outer"] = "store\nget\n@inner\n"
        files["escaped"] = "store\nget\n@@k\n"
        assertEquals(listOf("store", "get", "@inner"), expand("@outer"))
        assertEquals(listOf("store", "get", "@@k"), expand("@escaped"))
    }

    @Test
    fun `an expanded command line parses as the same arguments typed`() {
        files["filter"] = "--param\nwhere=@.kind == 'a|b'\n-a\nalice\n"
        val typed = assertIs<ParseResult.Invocation>(
            parser.parse(listOf("request", "get", "--param", "where=@.kind == 'a|b'", "-a", "alice", "e")),
        )
        val expanded = assertIs<ParseResult.Invocation>(parser.parse(expand("request", "get", "@filter", "e")))
        assertEquals(typed.commandLine(), expanded.commandLine())
        assertEquals(typed.values.toString(), expanded.values.toString())
    }

    @Test
    fun `a file that can't be read names itself and why`() {
        val expansion = assertIs<Expansion.Unreadable>(parser.expand(listOf("store", "get", "@missing.txt"), ::read))
        assertEquals("can't read argument file 'missing.txt': no such file", expansion.message)
    }

    private val program = Program(TestCommands.root, "demo 1.0", TestCommands.handlers())

    private fun withFiles(): Program = program.withArgumentFiles(::read)

    @Test
    fun `a program reads argument files only once they are turned on`() {
        files["key"] = "k1\n"
        var key: String? = null
        val handlers = TestCommands.handlers() + (TestCommands.storeGet to CommandHandler { context -> key = context.values[TestCommands.key]; 0 })
        val program = Program(TestCommands.root, "demo 1.0", handlers)
        program.record("store", "get", "@key")
        assertEquals("@key", key)
        program.withArgumentFiles(::read).record("store", "get", "@key")
        assertEquals("k1", key)
    }

    @Test
    fun `a file that can't be read exits NO_INPUT with no usage line`() {
        val result = withFiles().record("store", "get", "@missing.txt")
        assertEquals(ExitStatus.NO_INPUT, result.status)
        assertEquals("error: can't read argument file 'missing.txt': no such file\n", result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `a file's name is escaped in the error`() {
        val result = withFiles().record("@bad\u001B[2J")
        assertEquals("error: can't read argument file 'bad\\u001B[2J': no such file\n", result.stderr)
    }

    @Test
    fun `help ends with a line on argument files only when they are on`() {
        val footer = "@file reads arguments from a file, one per line; @@ for a literal @."
        assertFalse(footer in program.record("--help").stdout)
        val help = withFiles().record("store", "get", "--help").stdout
        assertTrue(help.endsWith("$footer\n"), help)
        // Before the program's own last word.
        val root = withFiles().record("--help").stdout
        assertTrue(root.indexOf(footer) in 0 until root.indexOf("Documentation:"), root)
    }

    @Test
    fun `the completion script steps over @ words only when argument files are on`() {
        Shell.entries.forEach { shell ->
            val plain = program.record("completion", shell.id).stdout
            val withFiles = withFiles().record("completion", shell.id).stdout
            assertEquals(CompletionGenerator().generate(shell, TestCommands.root), plain, shell.id)
            // One line changes: the walk's case for words it steps over.
            val changed = plain.lines().zip(withFiles.lines()).filter { (a, b) -> a != b }
            assertEquals(plain.lines().size, withFiles.lines().size, shell.id)
            assertEquals(1, changed.size, "${shell.id}: $changed")
            assertContains(changed.single().second, "@", message = shell.id)
        }
    }

    @Test
    fun `a copy with argument files on leaves the original as it was`() {
        files["key"] = "k1\n"
        withFiles()
        assertEquals(ExitStatus.USAGE, program.record("@key").status)
    }
}
