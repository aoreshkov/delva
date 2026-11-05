package app.oreshkov.delva.cli

import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.HelpRenderer
import app.oreshkov.clikit.pathTo
import app.oreshkov.clikit.testing.GoldenFiles
import app.oreshkov.clikit.testing.record
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The command line as a user meets it: every help page and completion script is a golden file, so a new command or
 * option shows up as a reviewed diff in the commit that adds it.
 *
 * Regenerate with `./gradlew :delva-cli:test -Ddelva.updateGolden=true`, then read the diff.
 */
class DelvaSurfaceTest {

    private val golden = GoldenFiles(Path.of("src", "test", "resources", "golden"), "delva.updateGolden")

    @Test
    fun `every help page and completion script, and no golden file nothing asserts`() {
        golden.assertSurface(DelvaCommands.root)
    }

    /**
     * The golden files are rendered from the command tree, which doesn't know that the program reads argument files,
     * so the program's page is the one on file plus the line that says how to give one.
     */
    @Test
    fun `the help the program prints is the help on file, and says how to give an argument file`() {
        val result = DelvaProgram.program.record("info", "--help").assertPipeSafe()
        assertEquals(ExitStatus.OK, result.status)
        val footer = "\n@file reads arguments from a file, one per line; @@ for a literal @.\n"
        assertContains(result.stdout, footer)
        assertEquals(HelpRenderer().render(DelvaCommands.root.pathTo("info")), result.stdout.replaceFirst(footer, ""))
    }

    @Test
    fun `version names the program, and says dev when run from classes`() {
        assertEquals("delva dev\n", DelvaProgram.program.record("--version").stdout)
    }
}
