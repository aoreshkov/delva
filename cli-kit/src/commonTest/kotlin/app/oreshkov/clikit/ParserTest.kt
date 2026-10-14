package app.oreshkov.clikit

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parser is pure functions over `Array<String>`, which is the whole reason it was written this
 * way: no terminal, no temp files, no mocking, and every GNU/POSIX form asserted directly.
 */
class ParserTest {

    private val parser = Parser(TestCommands.root)

    private fun invocation(vararg args: String): ParseResult.Invocation {
        val result = parser.parse(arrayOf(*args))
        assertIs<ParseResult.Invocation>(result, "expected an invocation, got $result")
        return result
    }

    private fun usageError(vararg args: String): ParseResult.UsageError {
        val result = parser.parse(arrayOf(*args))
        assertIs<ParseResult.UsageError>(result, "expected a usage error, got $result")
        return result
    }

    // -------------------------------------------------------------------------- subcommand tree

    @Test
    fun `a three-level command resolves, with its options`() {
        val parsed = invocation("request", "raw", "--method", "GET", "--url", "https://x.invalid/")
        assertEquals("demo request raw", parsed.commandLine())
        assertEquals("GET", parsed.values[TestCommands.method])
        assertEquals("https://x.invalid/", parsed.values[TestCommands.url])
    }

    @Test
    fun `every leaf in the fixture tree parses`() {
        // Walks the tree and asserts each runnable leaf accepts its own required options, which
        // keeps the fixture honest: a case below that fails should fail for the rule it names.
        fun walk(path: List<CommandSpec>) {
            val command = path.last()
            if (command.runnable && path.size > 1) {
                val args = path.drop(1).map { it.name } +
                    optionsFor(path).filter { it.required }.flatMap { listOf(it.long, sampleFor(it)) } +
                    command.arguments.filter { it.required }.map { it.choices.firstOrNull() ?: "x" }
                val result = parser.parse(args)
                assertIs<ParseResult.Invocation>(result, "${args.joinToString(" ")} -> $result")
            }
            command.subcommands.forEach { walk(path + it) }
        }
        walk(listOf(TestCommands.root))
    }

    private fun sampleFor(option: Option): String = option.choices.firstOrNull() ?: "value"

    // -------------------------------------------------------------------------------- option forms

    @Test
    fun `long option with a separate value`() {
        assertEquals("alice", invocation("session", "login", "--account", "alice").values[TestCommands.account])
    }

    @Test
    fun `long option with an inline value`() {
        assertEquals("alice", invocation("session", "login", "--account=alice").values[TestCommands.account])
    }

    @Test
    fun `short option with a separate value`() {
        assertEquals("alice", invocation("session", "login", "-a", "alice").values[TestCommands.account])
    }

    @Test
    fun `short option with an attached value`() {
        assertEquals("alice", invocation("session", "login", "-aalice").values[TestCommands.account])
    }

    @Test
    fun `bundled short flags`() {
        val parsed = invocation("-v", "session", "login", "-a", "alice")
        assertTrue(parsed.values[StandardOptions.verbose])
    }

    @Test
    fun `an inline value containing an equals sign keeps everything after the first one`() {
        val parsed = invocation("request", "get", "-a", "alice", "--param=filter=a=b", "game.startup")
        assertEquals(listOf("filter" to "a=b"), parsed.values[TestCommands.param])
    }

    @Test
    fun `a repeated option accumulates rather than overwriting`() {
        val parsed = invocation(
            "request", "get", "-a", "alice",
            "-p", "page=2", "-p", "limit=50", "--param", "sort=asc",
            "game.startup",
        )
        assertEquals(
            listOf("page" to "2", "limit" to "50", "sort" to "asc"),
            parsed.values[TestCommands.param],
        )
    }

    @Test
    fun `values arrive converted, read by the handle that declared them`() {
        val parsed = invocation("session", "list", "-n", "5")
        val limit: Int? = parsed.values[TestCommands.limit]
        assertEquals(5, limit)
        assertNull(invocation("session", "list").values[TestCommands.limit])
        assertTrue(invocation("session", "login", "--force").values[TestCommands.force])
        assertEquals(listOf("\$.a", "\$.b"), invocation("store", "query", "\$.a", "\$.b").values[TestCommands.paths])
    }

    @Test
    fun `a handle the command does not declare reads as absent`() {
        val parsed = invocation("session", "list", "-n", "5")
        assertNull(parsed.values[TestCommands.account])
        assertEquals(emptyList(), parsed.values[TestCommands.param])
        assertFalse(parsed.values[TestCommands.force])
    }

    @Test
    fun `required reads a required value, and fails loudly for one that is not`() {
        val parsed = invocation("request", "raw", "--method", "GET", "--url", "u")
        assertEquals("GET", parsed.values.required(TestCommands.method))
        assertFailsWith<IllegalStateException> { parsed.values.required(TestCommands.account) }
    }

    @Test
    fun `a double dash ends option parsing`() {
        val parsed = invocation("store", "get", "--", "--this-is-a-key")
        assertEquals("--this-is-a-key", parsed.values[TestCommands.key])
    }

    @Test
    fun `a global option is legal before or after the subcommand`() {
        // The bug this guards against: options declared on the root becoming "unknown" once a
        // subcommand has been named.
        assertTrue(invocation("--json", "store", "query", "\$.a").values[StandardOptions.json])
        assertTrue(invocation("store", "query", "\$.a", "--json").values[StandardOptions.json])
    }

    @Test
    fun `an option value that looks like a subcommand is not read as one`() {
        val parsed = invocation("--data-dir", "store", "session", "list")
        assertEquals("demo session list", parsed.commandLine())
        assertEquals("store", parsed.values[TestCommands.dataDir])
    }

    // ------------------------------------------------------------------------------ help, version

    @Test
    fun `help is handled at every level`() {
        assertIs<ParseResult.Help>(parser.parse(arrayOf("--help")))
        assertIs<ParseResult.Help>(parser.parse(arrayOf("session", "--help")))
        assertIs<ParseResult.Help>(parser.parse(arrayOf("store", "prune", "-h")))
    }

    @Test
    fun `a group with no subcommand shows its help rather than failing`() {
        val result = parser.parse(arrayOf("session"))
        assertIs<ParseResult.Help>(result)
        assertEquals("session", result.path.last().name)
    }

    @Test
    fun `version is handled at every level`() {
        assertEquals(ParseResult.Version, parser.parse(arrayOf("--version")))
        assertEquals(ParseResult.Version, parser.parse(arrayOf("session", "login", "--version")))
    }

    // -------------------------------------------------------------------------------- usage errors

    @Test
    fun `an unknown option suggests the nearest match`() {
        val error = usageError("session", "login", "--acount", "alice")
        assertContains(error.message, "--acount")
        assertEquals("--account", error.suggestion)
    }

    @Test
    fun `an unknown subcommand suggests the nearest match`() {
        val error = usageError("sesion", "list")
        assertEquals("session", error.suggestion)
    }

    @Test
    fun `a wildly wrong word gets no suggestion rather than a confident wrong one`() {
        assertNull(usageError("--quixotic").suggestion)
    }

    @Test
    fun `a missing required option is named`() {
        assertContains(usageError("request", "raw", "--url", "https://example.test").message, "--method")
    }

    @Test
    fun `a missing required argument names what was expected`() {
        assertContains(usageError("store", "get").message, "<key>")
    }

    @Test
    fun `an unexpected extra argument is refused rather than ignored`() {
        // Silently dropping it is how a typo'd flag becomes a command that ran against the wrong
        // thing and reported success.
        assertContains(usageError("store", "get", "key-one", "key-two").message, "key-two")
    }

    @Test
    fun `a value outside a fixed enumeration lists the alternatives`() {
        val error = usageError("request", "raw", "--method", "bogus", "--url", "https://x.invalid/")
        assertContains(error.message, "GET|POST")
    }

    @Test
    fun `an argument outside a fixed enumeration is refused`() {
        usageError("completion", "elvish")
        assertIs<ParseResult.Invocation>(parser.parse(arrayOf("completion", "zsh")))
    }

    @Test
    fun `a long option that needs a value and has none is refused`() {
        assertContains(usageError("session", "login", "--account").message, "requires a value")
    }

    @Test
    fun `a flag given a value is refused rather than silently accepted`() {
        assertContains(usageError("session", "login", "-a", "x", "--force=yes").message, "does not take a value")
    }

    // ------------------------------------------------------------------------------- conversion

    @Test
    fun `a bad integer produces a message, not a stack trace`() {
        val result = Converters.int.convert("foo")
        assertIs<ConversionResult.Invalid>(result)
        assertContains(result.reason, "not an integer")
    }

    @Test
    fun `a value its declared converter rejects is a usage error, not a dropped value`() {
        // The bug this guards against: `-p page` converted to nothing, so the request went out
        // without the parameter and reported success.
        listOf(
            arrayOf("request", "get", "-a", "alice", "-p", "page", "game.startup"),
            arrayOf("request", "get", "-a", "alice", "-ppage", "game.startup"),
            arrayOf("request", "get", "-a", "alice", "--param=page", "game.startup"),
        ).forEach { args ->
            val error = usageError(*args)
            assertContains(error.message, "--param")
            assertContains(error.message, "key=value")
        }
    }

    @Test
    fun `an environment variable fills in an option the command line left out, and never overrides it`() {
        val environment = mapOf("DEMO_DATA_DIR" to "/from/env")
        val parser = Parser(TestCommands.root, environment::get)
        fun dataDir(vararg args: String) =
            (parser.parse(arrayOf(*args)) as ParseResult.Invocation).values[TestCommands.dataDir]
        assertEquals("/from/env", dataDir("session", "list"))
        assertEquals("/typed", dataDir("--data-dir", "/typed", "session", "list"))
        assertEquals("/typed", dataDir("session", "list", "--data-dir=/typed"))
        // Empty counts as unset, and without an environment nothing is read at all.
        assertNull((Parser(TestCommands.root) { "" }.parse(arrayOf("session", "list")) as ParseResult.Invocation).values[TestCommands.dataDir])
        assertNull(invocation("session", "list").values[TestCommands.dataDir])
    }

    @Test
    fun `a variable's value is checked like a typed one, and satisfies a required option`() {
        val limit = Option.Single("--limit", Converters.positiveInt, env = "DEMO_LIMIT", required = true)
        val root = CommandSpec("demo", options = listOf(limit))
        val bad = Parser(root) { if (it == "DEMO_LIMIT") "zero" else null }.parse(emptyArray())
        assertIs<ParseResult.UsageError>(bad)
        assertContains(bad.message, "DEMO_LIMIT")
        val good = Parser(root) { if (it == "DEMO_LIMIT") "7" else null }.parse(emptyArray())
        assertEquals(7, (good as ParseResult.Invocation).values[limit])
        assertIs<ParseResult.UsageError>(Parser(root).parse(emptyArray()))
    }

    @Test
    fun `an environment variable name is checked when the option is declared`() {
        assertFailsWith<IllegalArgumentException> { Option.Single("--x", Converters.string, env = "lower-case") }
    }

    @Test
    fun `a value outside an option's range is a usage error naming the range`() {
        assertContains(usageError("session", "list", "-n", "0").message, "1 or more")
        assertContains(usageError("session", "list", "--limit=x").message, "not an integer")
    }

    @Test
    fun `an argument its converter rejects is a usage error naming the argument`() {
        val error = usageError("completion", "elvish")
        assertContains(error.message, "<shell>")
        assertContains(error.message, "bash|zsh|fish|powershell")
    }

    @Test
    fun `a negative number where an option is expected says how to pass it`() {
        assertContains(usageError("store", "get", "-5").message, "put '--' before")
        assertEquals("-5", invocation("store", "get", "--", "-5").values[TestCommands.key])
        // A real unknown short option still gets a suggestion, not the hint.
        assertFalse("put '--'" in usageError("session", "login", "-x").message)
    }

    @Test
    fun `a malformed key=value pair is rejected`() {
        assertIs<ConversionResult.Invalid>(Converters.keyValue.convert("nope"))
        assertIs<ConversionResult.Invalid>(Converters.keyValue.convert("=novalue"))
        assertIs<ConversionResult.Ok<Pair<String, String>>>(Converters.keyValue.convert("k="))
    }

    @Test
    fun `edit distance is symmetric and zero for equal strings`() {
        assertEquals(0, editDistance("account", "account"))
        assertEquals(editDistance("acount", "account"), editDistance("account", "acount"))
        assertEquals(1, editDistance("acount", "account"))
    }

    @Test
    fun `swapped neighbouring letters are one edit, so they get a suggestion`() {
        assertEquals(1, editDistance("inof", "info"))
        assertEquals(1, editDistance("sesison", "session"))
        assertEquals(2, editDistance("abcd", "badc"))
        assertEquals(3, editDistance("kitten", "sitting"))
        assertEquals("store", usageError("stoer").suggestion)
    }
}
