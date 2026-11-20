package app.oreshkov.delva.cli

import app.oreshkov.clikit.testing.GoldenFiles
import app.oreshkov.clikit.testing.record
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The command line as a user meets it: every help page and completion script the program prints is a golden file, so
 * a new command or option shows up as a reviewed diff in the commit that adds it.
 *
 * Regenerate with `./gradlew :delva-cli:test -Ddelva.updateGolden=true`, then read the diff.
 */
class DelvaSurfaceTest {

    private val golden = GoldenFiles(Path.of("src", "test", "resources", "golden"), "delva.updateGolden")

    @Test
    fun `every help page and completion script, and no golden file nothing asserts`() {
        golden.assertSurface(DelvaProgram.create { null })
    }

    @Test
    fun `version names the program, and says dev when run from classes`() {
        assertEquals("delva dev\n", DelvaProgram.program.record("--version").stdout)
    }
}
