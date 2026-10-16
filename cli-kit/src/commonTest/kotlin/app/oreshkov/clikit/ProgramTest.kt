package app.oreshkov.clikit

import app.oreshkov.clikit.testing.RecordingTerminalIo
import app.oreshkov.clikit.testing.RunResult
import app.oreshkov.clikit.testing.record
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgramTest {

    private val go = CommandSpec("go", "Do the thing", options = listOf(StandardOptions.json))

    private val root = CommandSpec(
        name = "demo",
        options = listOf(StandardOptions.verbose, StandardOptions.width),
        subcommands = listOf(go, StandardOptions.completion),
    )

    private fun run(vararg args: String, handler: CommandHandler = CommandHandler { 3 }): RunResult =
        Program(root, "demo 1.0", mapOf(go to handler)).record(*args)

    @Test
    fun `an invocation is handed to its command's handler, and its exit status is returned`() {
        var seen: String? = null
        val result = run("go") { context -> seen = context.invocation.commandLine(); 3 }
        assertEquals(3, result.status)
        assertEquals("demo go", seen)
    }

    @Test
    fun `the handler's output is machine-readable exactly when --json was given`() {
        var machineReadable: Boolean? = null
        val handler = CommandHandler { context -> machineReadable = context.output.machineReadable; 0 }
        run("go", handler = handler)
        assertEquals(false, machineReadable)
        run("go", "--json", handler = handler)
        assertEquals(true, machineReadable)
    }

    @Test
    fun `--version and --help are payload, and exit zero`() {
        val version = run("--version")
        assertEquals(ExitStatus.OK, version.status)
        assertEquals("demo 1.0\n", version.stdout)

        val help = run("--help")
        assertEquals(ExitStatus.OK, help.status)
        assertContains(help.stdout, "go")
        assertEquals("", help.stderr)
    }

    @Test
    fun `a usage error goes to stderr, never reaches a handler, and exits USAGE, which is 2`() {
        val result = run("goo") { error("dispatched") }
        assertEquals(ExitStatus.USAGE, result.status)
        assertEquals(2, ExitStatus.USAGE)
        assertEquals("", result.stdout)
        assertContains(result.stderr, "did you mean go?")
    }

    @Test
    fun `help is wrapped to the terminal's width, but never past 100 columns`() {
        val wordy = CommandSpec("wordy", List(60) { "word" }.joinToString(" "))
        val program = Program(wordy, "wordy 1.0", mapOf(wordy to CommandHandler { 0 }))
        fun widest(width: Int) = program.record("--help", io = RecordingTerminalIo(width = width)).stdout.lines().maxOf { it.length }
        assertEquals(59, widest(60))
        assertEquals(99, widest(100))
        assertEquals(99, widest(200))
    }

    @Test
    fun `completion is answered without a handler`() {
        val result = run("completion", "bash") { error("dispatched") }
        assertEquals(ExitStatus.OK, result.status)
        assertContains(result.stdout, "demo")
        assertEquals("", result.stderr)
        result.assertPipeSafe()
    }

    @Test
    fun `an escaping exception is a bug, reported on one line with EX_SOFTWARE`() {
        val result = run("go") { throw IllegalStateException("boom") }
        assertEquals(ExitStatus.SOFTWARE, result.status)
        assertEquals("", result.stdout)
        assertContains(result.stderr, "internal error: boom")
        assertContains(result.stderr, "This is a bug in demo. Re-run with --verbose")

        val verbose = run("--verbose", "go") { throw IllegalStateException("boom") }
        assertContains(verbose.stderr, "IllegalStateException")
        assertFalse('\r' in verbose.stderr, "stderr is LF on every platform, the stack trace too")
    }

    @Test
    fun `an expected failure is one error line and its own status, not a bug`() {
        val result = run("go") { throw CommandFailure(ExitStatus.NO_INPUT, "no such thing") }
        assertEquals(ExitStatus.NO_INPUT, result.status)
        assertEquals("error: no such thing\n", result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun `a usage failure from a handler adds the usage line, as a parse error does`() {
        val result = run("go") { throw CommandFailure(ExitStatus.USAGE, "--width needs a store") }
        assertEquals(ExitStatus.USAGE, result.status)
        assertEquals("error: --width needs a store\nusage: demo go [options]\n", result.stderr)
    }

    @Test
    fun `a failure with status 2 can leave the usage line out, when 2 means something else to the program`() {
        val result = run("go") { throw CommandFailure(ExitStatus.USAGE, "no job configured", showUsage = false) }
        assertEquals(ExitStatus.USAGE, result.status)
        assertEquals("error: no job configured\n", result.stderr)

        val translated = Program(
            root,
            "demo 1.0",
            mapOf(go to CommandHandler { error("missing") }),
            translate = { CommandFailure(ExitStatus.USAGE, "no stored response at 'k'", showUsage = false) },
        ).record("go")
        assertEquals("error: no stored response at 'k'\n", translated.stderr)
    }

    @Test
    fun `a failure with another status can ask for the usage line`() {
        val result = run("go") { throw CommandFailure(ExitStatus.CONFIG, "--width contradicts the store", showUsage = true) }
        assertEquals(ExitStatus.CONFIG, result.status)
        assertEquals("error: --width contradicts the store\nusage: demo go [options]\n", result.stderr)
    }

    @Test
    fun `a handler reads the environment the program was given`() {
        var seen: String? = "unset"
        val program = Program(
            root,
            "demo 1.0",
            mapOf(go to CommandHandler { context -> seen = context.environment("DEMO_TOKEN"); 0 }),
            environment = mapOf("DEMO_TOKEN" to "t0k3n")::get,
        )
        program.record("go")
        assertEquals("t0k3n", seen)

        run("go") { context -> seen = context.environment("DEMO_TOKEN"); 0 }
        assertNull(seen, "no environment unless the program is given one")
    }

    @Test
    fun `translate turns a library's exception into its failure, and leaves the rest to the bug net`() {
        val program = Program(
            root,
            "demo 1.0",
            mapOf(go to CommandHandler { throw IllegalStateException("locked") }),
            translate = { e -> if (e.message == "locked") CommandFailure(ExitStatus.TEMP_FAIL, "it is locked") else null },
        )
        val translated = program.record("go")
        assertEquals(ExitStatus.TEMP_FAIL, translated.status)
        assertEquals("error: it is locked\n", translated.stderr)

        val untranslated = Program(root, "demo 1.0", mapOf(go to CommandHandler { error("other") }), translate = { null })
        assertEquals(ExitStatus.SOFTWARE, untranslated.record("go").status)
    }

    @Test
    fun `an Error, or a translate that throws, is a bug too`() {
        val overflow = run("go") { throw NotImplementedError("deep") }
        assertEquals(ExitStatus.SOFTWARE, overflow.status)
        assertContains(overflow.stderr, "internal error: deep")

        val program = Program(
            root,
            "demo 1.0",
            mapOf(go to CommandHandler { error("first") }),
            translate = { error("second") },
        )
        val result = program.record("--verbose", "go")
        assertEquals(ExitStatus.SOFTWARE, result.status)
        assertContains(result.stderr, "internal error: first")
        assertContains(result.stderr, "Suppressed: java.lang.IllegalStateException: second")
    }

    @Test
    fun `stdout is flushed when run returns, however the command ended`() {
        class Flushing : TerminalIo {
            var flushes = 0
            override fun out(text: String) = Unit
            override fun err(text: String) = Unit
            override val interactive = false
            override val width = 80
            override val styler = Styler.Plain
            override val borderStyle = BorderStyle.Ascii
            override fun readPassword(prompt: String): CharArray? = null
            override fun flush() {
                flushes++
            }
        }
        for (handler in listOf(CommandHandler { 0 }, CommandHandler { error("boom") }, CommandHandler { throw CommandFailure(4, "no") })) {
            val io = Flushing()
            Program(root, "demo 1.0", mapOf(go to handler)).run(arrayOf("go"), io)
            assertEquals(1, io.flushes)
        }
        val io = Flushing()
        Program(root, "demo 1.0", mapOf(go to CommandHandler { 0 })).run(arrayOf("--bogus"), io)
        assertEquals(1, io.flushes)
    }

    @Test
    fun `a stdout that didn't take the output is a failure, reported unless its reader went away`() {
        fun run(state: StdoutState, status: Int): Pair<Int, String> {
            val recording = RecordingTerminalIo()
            val io = object : TerminalIo by recording {
                override fun stdoutState() = state
            }
            return Program(root, "demo 1.0", mapOf(go to CommandHandler { status })).run(arrayOf("go"), io) to recording.stderr
        }
        assertEquals(ExitStatus.IO_ERR to "error: could not write to standard output\n", run(StdoutState.Failed, ExitStatus.OK))
        assertEquals(ExitStatus.IO_ERR to "", run(StdoutState.ReaderGone, ExitStatus.OK))
        assertEquals(ExitStatus.DATA_ERR to "", run(StdoutState.ReaderGone, ExitStatus.DATA_ERR), "a failed command keeps its status")
        assertEquals(ExitStatus.OK to "", run(StdoutState.Written, ExitStatus.OK))
    }

    private val drop = CommandSpec(
        "drop",
        options = listOf(StandardOptions.force, StandardOptions.noInput, StandardOptions.quiet),
    )

    private fun confirming(vararg args: String, answers: List<String> = emptyList()): RunResult {
        val root = CommandSpec("demo", subcommands = listOf(drop))
        val program = Program(root, "demo 1.0", mapOf(drop to CommandHandler { if (it.confirm("drop it?")) 0 else 5 }))
        return program.record(*args, io = RecordingTerminalIo(answers = answers))
    }

    @Test
    fun `confirm goes ahead on yes, stops on anything else, and never asks with --force`() {
        assertEquals(0, confirming("drop", answers = listOf("y")).status)
        assertEquals(0, confirming("drop", answers = listOf(" YES ")).status)
        assertEquals(5, confirming("drop", answers = listOf("")).status)
        assertEquals(5, confirming("drop", answers = listOf("nope")).status)
        val forced = confirming("drop", "--force")
        assertEquals(0, forced.status)
        assertEquals("", forced.stderr, "--force asks nothing")
        assertContains(confirming("drop", answers = listOf("y")).stderr, "drop it? [y/N] y")
    }

    @Test
    fun `without a terminal, or with --no-input, confirm refuses and says to pass --force`() {
        for (result in listOf(confirming("drop"), confirming("drop", "--no-input", answers = listOf("y")))) {
            assertEquals(ExitStatus.USAGE, result.status)
            assertContains(result.stderr, "pass --force")
        }
    }

    private val login = CommandSpec("login", options = listOf(StandardOptions.noInput))

    private fun secret(
        vararg args: String,
        environment: Map<String, String> = emptyMap(),
        password: CharArray? = null,
    ): Pair<RunResult, String?> {
        var read: String? = null
        val program = Program(
            CommandSpec("demo", subcommands = listOf(login)),
            "demo 1.0",
            mapOf(login to CommandHandler { read = it.readSecret("Password: ", env = "DEMO_PASSWORD").concatToString(); 0 }),
            environment = environment::get,
        )
        return program.record(*args, io = RecordingTerminalIo(password = password)) to read
    }

    @Test
    fun `a secret comes from its variable first, then from the terminal`() {
        assertEquals("from-env", secret("login", environment = mapOf("DEMO_PASSWORD" to "from-env"), password = "typed".toCharArray()).second)
        val (typed, value) = secret("login", environment = mapOf("DEMO_PASSWORD" to ""), password = "typed".toCharArray())
        assertEquals("typed", value)
        assertContains(typed.stderr, "Password: ")
    }

    @Test
    fun `without a terminal, under --no-input, or with nothing typed, a secret is a usage error naming the variable`() {
        for ((result, value) in listOf(secret("login"), secret("login", "--no-input", password = "typed".toCharArray()))) {
            assertNull(value)
            assertEquals(ExitStatus.USAGE, result.status)
            assertContains(result.stderr, "error: Password — not asking without a terminal; set DEMO_PASSWORD\n")
        }
        val (empty, _) = secret("login", password = CharArray(0))
        assertEquals(ExitStatus.USAGE, empty.status)
        assertContains(empty.stderr, "error: Password — nothing was entered; set DEMO_PASSWORD\n")
    }

    @Test
    fun `a failure cannot claim success`() {
        assertFailsWith<IllegalArgumentException> { CommandFailure(ExitStatus.OK, "fine") }
    }

    @Test
    fun `a runnable command without a handler is refused when the program is built`() {
        val error = assertFailsWith<IllegalArgumentException> { Program(root, "demo 1.0", emptyMap()) }
        assertContains(error.message.orEmpty(), "go")
        // The fixture tree is complete, which is what keeps the other suites honest.
        Program(TestCommands.root, "demo 1.0", TestCommands.handlers())
    }

    @Test
    fun `a handler for a command the tree does not run is refused too`() {
        val stray = CommandSpec("stray")
        val error = assertFailsWith<IllegalArgumentException> {
            Program(root, "demo 1.0", mapOf(go to CommandHandler { 0 }, stray to CommandHandler { 0 }))
        }
        assertContains(error.message.orEmpty(), "stray")
    }

    @Test
    fun `--width and --no-color are read off the raw command line, before anything is parsed`() {
        assertEquals(120, StandardOptions.widthFrom(arrayOf("--width", "120", "session", "list")))
        assertEquals(120, StandardOptions.widthFrom(arrayOf("--width=120")))
        assertNull(StandardOptions.widthFrom(arrayOf("session", "list")))
        // Not rejected here: the parser sees the same token a moment later and reports it properly,
        // with the command's usage line attached.
        assertNull(StandardOptions.widthFrom(arrayOf("--width", "wide")))
        assertNull(StandardOptions.widthFrom(arrayOf("--width", "0")))
        assertEquals(ExitStatus.USAGE, run("--width", "0", "go").status)

        assertTrue(StandardOptions.noColor(arrayOf("go", "--no-color")))
        assertFalse(StandardOptions.noColor(arrayOf("go")))
    }
}
