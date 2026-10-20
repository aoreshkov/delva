package app.oreshkov.clikit

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The generated scripts run in a real shell, because a golden file only proves the text didn't change, not that
 * the shell reads it the way it was meant. PowerShell runs wherever `pwsh` is installed, bash off Windows (where
 * `bash` may be WSL's); both run on CI. zsh and fish have only their golden files.
 */
class CompletionBehaviourTest {

    /** A line typed so far, and exactly what completing at its end should offer. */
    private val cases = listOf(
        "demo ses" to listOf("session"),
        // The value of --data-dir is skipped, not read as a command.
        "demo --data-dir store se" to listOf("session"),
        "demo -v --data-dir=store se" to listOf("session"),
        "demo request raw --method P" to listOf("POST"),
        "demo session list --site x --li" to listOf("--limit"),
        "demo completion f" to listOf("fish"),
        "demo store prune --b" to listOf("--before"),
        // help's words name commands, level by level, wherever its options are.
        "demo help st" to listOf("store"),
        "demo help store q" to listOf("query"),
        "demo -v help --data-dir x request g" to listOf("get"),
        // An alias and a former name are read on the way, a former option name's value skipped, and nothing
        // deprecated offered: `purge` and `--pretty` would match too.
        "demo req g" to listOf("get"),
        "demo help req r" to listOf("raw"),
        "demo store search --d" to listOf("--data-dir"),
        "demo session list --max 5 --s" to listOf("--site"),
        "demo store p" to listOf("prune"),
        "demo request get --p" to listOf("--param"),
    )

    @Test
    fun `no script runs the program it completes for`() {
        for (shell in Shell.entries) {
            val script = CompletionGenerator().generate(shell, TestCommands.root)
            assertFalse(Regex("""[$(&|]\s*demo\b""").containsMatchIn(script), "${shell.id} runs demo")
        }
    }

    @Test
    fun `bash offers what the tree allows`() {
        assumeTrue(!isWindows, "bash on Windows may be WSL's")
        assumeTrue(available("bash", "--version"), "no bash")
        val script = scriptFile(Shell.Bash)
        try {
            for ((line, expected) in cases) {
                // COMP_WORDS as bash builds it: `=` is a word break, so it is a word of its own.
                val words = line.replace("=", " = ").split(' ').filter { it.isNotEmpty() } + if (line.endsWith(' ')) listOf("") else emptyList()
                val array = words.joinToString(" ") { "'$it'" }
                val command = "source '$script'; COMP_WORDS=($array); COMP_CWORD=${words.size - 1}; _demo_complete; " +
                    "printf '%s\\n' \"\${COMPREPLY[@]}\""
                assertEquals(expected, run("bash", "-c", command), line)
            }
            // After an option with no fixed values nothing is offered, so bash's `-o default` completes file names.
            assertEquals(emptyList(), run("bash", "-c", "source '$script'; COMP_WORDS=(demo --data-dir ''); COMP_CWORD=2; _demo_complete; printf '%s\\n' \"\${COMPREPLY[@]}\""))
        } finally {
            script.deleteIfExists()
        }
    }

    @Test
    fun `PowerShell offers what the tree allows`() {
        assumeTrue(available("pwsh", "-NoProfile", "-Command", "exit 0"), "no pwsh")
        val script = scriptFile(Shell.PowerShell)
        try {
            for ((line, expected) in cases) {
                val command = ". '$script'; \$line = '$line'; " +
                    "(TabExpansion2 -inputScript \$line -cursorColumn \$line.Length).CompletionMatches | ForEach-Object CompletionText"
                assertEquals(expected, run("pwsh", "-NoProfile", "-NonInteractive", "-Command", command), line)
            }
            // After an option with no fixed values the script offers nothing, and PowerShell completes paths instead.
            val files = run(
                "pwsh", "-NoProfile", "-NonInteractive", "-Command",
                ". '$script'; \$line = 'demo --data-dir '; (TabExpansion2 -inputScript \$line -cursorColumn \$line.Length).CompletionMatches | ForEach-Object CompletionText",
            )
            assertTrue("--help" !in files && "session" !in files, "offered $files")
        } finally {
            script.deleteIfExists()
        }
    }

    private val isWindows = System.getProperty("os.name").lowercase().startsWith("windows")

    private fun scriptFile(shell: Shell) = Files.createTempFile("completion", if (shell == Shell.PowerShell) ".ps1" else ".sh").also {
        it.writeText(CompletionGenerator().generate(shell, TestCommands.root))
    }

    private fun available(vararg command: String): Boolean = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (_: IOException) {
        false
    }

    /** The command's stdout, one entry per non-blank line. */
    private fun run(vararg command: String): List<String> {
        val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "${command.first()} did not finish" }
        return output.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
}
