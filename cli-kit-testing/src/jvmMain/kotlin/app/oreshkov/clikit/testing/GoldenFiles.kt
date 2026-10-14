package app.oreshkov.clikit.testing

import app.oreshkov.clikit.CommandSpec
import app.oreshkov.clikit.CompletionGenerator
import app.oreshkov.clikit.HelpRenderer
import app.oreshkov.clikit.Shell
import app.oreshkov.clikit.Styler
import app.oreshkov.clikit.paths
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Golden files: the mechanism that keeps generated output honest as a command tree grows.
 *
 * Adding an option changes a committed file, visibly, in the same commit as the option — which is
 * the whole point. With the system property [updateProperty] set to `true`, [assertMatches] writes
 * the files instead of comparing them; then *read the diff*: a golden test whose output is
 * rewritten without being looked at is a test that asserts whatever the code currently does.
 *
 * LF is written and compared on every platform, so a checkout on Windows — where `.gitattributes`
 * should check text files out as LF — cannot turn a passing suite into a failing one.
 *
 * @param directory where the files live, relative to the test's working directory (the module's
 *   directory under Gradle), such as `src/test/resources/golden`.
 * @param updateProperty the system property that switches to writing; the build forwards it to the
 *   test JVM.
 */
public class GoldenFiles(
    private val directory: Path,
    private val updateProperty: String,
) {
    private val write: Boolean get() = System.getProperty(updateProperty)?.toBoolean() == true

    /**
     * Fails unless [actual], with its line endings made LF, equals the golden file [name], also read as LF; or writes
     * [actual] there when the update property is set.
     */
    public fun assertMatches(name: String, actual: String) {
        val normalised = actual.replace("\r\n", "\n")
        val file = directory.resolve(name)

        if (write) {
            file.parent.createDirectories()
            file.writeText(normalised)
            return
        }

        if (!file.exists()) {
            fail("missing golden file '$name'. Generate it with -D$updateProperty=true and read the diff.")
        }

        assertEquals(
            file.readText().replace("\r\n", "\n"),
            normalised,
            "golden file '$name' differs. If the change is intended, regenerate with -D$updateProperty=true " +
                "and review the diff.",
        )
    }

    /** Every golden file currently on disk, so a test can notice one that nothing asserts. */
    public fun existing(): Set<String> =
        if (directory.exists()) {
            Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() }
        } else {
            emptySet()
        }

    /**
     * Fails for a file on disk that is not in [expected]: a test renamed, its golden file orphaned,
     * and nothing failing. [expected] is listed by the caller rather than collected from the
     * assertions that ran, because test order is not defined and a filtered run asserts only some.
     */
    public fun assertNoOrphans(expected: Set<String>) {
        assertEquals(emptySet(), existing() - expected, "orphaned golden files in $directory")
    }

    /**
     * Pins a program's whole surface: one golden file per help page ([helpFileName]) and one per completion script
     * ([completionFileName]), then fails for any file on disk it did not assert, as [assertNoOrphans] does. [extra]
     * names further files in the same directory that the caller asserts itself, so they aren't taken for orphans.
     *
     * Every file is compared before it fails, so one run lists every page a change touched: the first difference is
     * the error, and the others are attached to it as suppressed.
     */
    public fun assertSurface(root: CommandSpec, styler: Styler = Styler.Plain, extra: Set<String> = emptySet()) {
        val help = HelpRenderer(styler)
        val completions = CompletionGenerator()
        val expected = root.paths().associate { helpFileName(it) to { help.render(it) } } +
            Shell.entries.associate { completionFileName(it) to { completions.generate(it, root) } }

        val failures = expected.mapNotNull { (name, render) ->
            try {
                assertMatches(name, render())
                null
            } catch (failure: AssertionError) {
                failure
            }
        }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
        assertNoOrphans(expected.keys + extra)
    }

    public companion object {
        /** `help-root.txt` for the root's page; `help-store-index-create.txt` for `demo store index create`. */
        public fun helpFileName(path: List<CommandSpec>): String =
            (listOf("help") + path.drop(1).map { it.name }.ifEmpty { listOf("root") }).joinToString("-") + ".txt"

        /** `completion-bash.sh`, and so on; `completion-powershell.ps1` for PowerShell. */
        public fun completionFileName(shell: Shell): String =
            "completion-${shell.id}." + if (shell == Shell.PowerShell) "ps1" else "sh"
    }
}
