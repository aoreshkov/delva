package app.oreshkov.clikit

import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [runAndExit] with argument files, in a real child JVM running [ArgumentFileFixture]: the arguments come from the
 * process's own command line, and the terminal is built from them, so `--width` in a file has to reach it.
 */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ArgumentFilesProcessTest {

    @TempDir
    lateinit var directory: Path

    private class Finished(val status: Int, val stdout: String, val stderr: String)

    private fun fixture(vararg args: String): Finished {
        val java = ProcessHandle.current().info().command().orElseThrow()
        val process = ProcessBuilder(
            listOf(java, "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path")) +
                listOf("app.oreshkov.clikit.ArgumentFileFixtureKt") + args,
        ).start()
        try {
            // Each stream holds a line or two, far less than a pipe's buffer, so reading one and then the other can't
            // leave the child waiting to write.
            val stdout = process.inputStream.readAllBytes().decodeToString()
            val stderr = process.errorStream.readAllBytes().decodeToString()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the fixture did not stop")
            return Finished(process.exitValue(), stdout.replace("\r\n", "\n"), stderr.replace("\r\n", "\n"))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `the terminal is built from the arguments in a file, which reach the command as written`() {
        val file = directory.resolve("args.txt")
        file.writeText("--width\n42\nshow\n@.kind == 'a|b'\n^&\n")
        val finished = fixture("@$file", "last")
        assertEquals(ExitStatus.OK, finished.status, finished.stderr)
        assertEquals("width=42 [@.kind == 'a|b'] [^&] [last]\n", finished.stdout)
    }

    @Test
    fun `a file that can't be read ends the process with NO_INPUT`() {
        val missing = directory.resolve("missing.txt")
        val finished = fixture("@$missing", "show")
        assertEquals(ExitStatus.NO_INPUT, finished.status)
        assertEquals("", finished.stdout)
        assertEquals("error: can't read argument file '$missing': no such file\n", finished.stderr)
    }
}
