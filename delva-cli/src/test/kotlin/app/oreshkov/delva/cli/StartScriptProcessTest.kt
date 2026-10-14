package app.oreshkov.delva.cli

import app.oreshkov.clikit.ExitStatus
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The installed `delva` start script, run as a user runs it: in a child process, through real pipes. Run by the
 * `processTest` task, which installs the distribution first; `check` includes it, on every OS CI builds on.
 */
class StartScriptProcessTest {
    private val home: Path = Path.of(System.getProperty("delva.cli.home") ?: error("run this through the processTest task"))
    private val windows = System.getProperty("os.name").lowercase().startsWith("windows")
    private val script: Path = home.resolve("bin").resolve(if (windows) "delva.bat" else "delva")
    private val work: Path = Files.createTempDirectory("delva-process")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = work.deleteRecursively()

    private class Run(val status: Int, val stdout: ByteArray, val stderr: String) {
        val text: String get() = stdout.toString(Charsets.UTF_8)
    }

    private fun start(args: Array<out String>, stdin: ByteArray, env: Map<String, String>): Process {
        val builder = ProcessBuilder(listOf(script.toString()) + args)
        builder.environment().apply {
            put("JAVA_HOME", System.getProperty("java.home"))
            remove("DELVA_OPTS")
            remove("JAVA_OPTS")
            remove("DELVA_STORE")
            putAll(env)
        }
        val process = builder.start()
        process.outputStream.use { it.write(stdin) }
        return process
    }

    /**
     * Reads [stream] to its end on a thread of its own, so a full pipe can't stall the child and a hung child can't
     * stall the test before [awaitExit] times out; join, then read.
     */
    private class Drain(stream: InputStream) {
        @Volatile private var bytes = ByteArray(0)
        private val reader = Thread { bytes = stream.readAllBytes() }.apply { isDaemon = true; start() }

        fun read(): ByteArray {
            reader.join()
            return bytes
        }
    }

    /** Waits for the child, and kills it and what it started (the `.bat` runs java as a grandchild) if it hangs. */
    private fun Process.awaitExit() {
        if (waitFor(120, TimeUnit.SECONDS)) return
        descendants().forEach { it.destroyForcibly() }
        destroyForcibly()
        error("delva did not finish in 120 seconds")
    }

    private fun delva(vararg args: String, stdin: ByteArray = ByteArray(0), env: Map<String, String> = emptyMap()): Run {
        val process = start(args, stdin, env)
        val stderr = Drain(process.errorStream)
        val stdout = Drain(process.inputStream)
        process.awaitExit()
        return Run(process.exitValue(), stdout.read(), stderr.read().toString(Charsets.UTF_8))
    }

    @Test
    fun `the version comes from the jar's manifest`() {
        val run = delva("--version")
        assertEquals(0, run.status, run.stderr)
        assertEquals("delva ${System.getProperty("delva.cli.version")}", run.text.trim())
    }

    @Test
    fun `a store round-trips through the script, UTF-8 and exit statuses intact`() {
        val store = work.resolve("store")
        assertEquals(0, delva("init", store.toString()).status)
        val env = mapOf("DELVA_STORE" to store.toString())
        val document = """{"name":"Жана","city":"東京","key":"🔑"}"""

        val inserted = delva("insert", "-", stdin = document.toByteArray(), env = env)
        assertEquals(0, inserted.status, inserted.stderr)
        assertEquals("1", inserted.text.trim())

        val read = delva("get", "1", env = env)
        assertEquals(0, read.status, read.stderr)
        assertEquals(document + "\n", read.text.replace("\r\n", "\n"))

        val missing = delva("-s", work.resolve("nope").toString(), "info")
        assertEquals(ExitStatus.NO_INPUT, missing.status)
        assertContains(missing.stderr, "no store at")
        assertEquals(ExitStatus.USAGE, delva("inof").status)
        assertEquals(ExitStatus.FAILURE, delva("search", "kv", "name", "nobody", "-S", "-e", env = env).status)
    }

    @Test
    fun `a reader that stops early ends the command with IO_ERR, and nothing on stderr`() {
        val store = work.resolve("store")
        assertEquals(0, delva("init", store.toString()).status)
        val env = mapOf("DELVA_STORE" to store.toString())
        // Over a megabyte: more than stdout's buffer and the pipe's hold, so delva is still writing when the reader goes.
        val document = """{"values":[""" + (1..100_000).joinToString(",") { "\"value $it\"" } + "]}"
        assertEquals(0, delva("insert", "-", stdin = document.toByteArray(), env = env).status)

        // What `delva get 1 | head -c1` does.
        val process = start(arrayOf("get", "1"), ByteArray(0), env)
        val stderr = Drain(process.errorStream)
        assertEquals('{'.code, process.inputStream.read())
        process.inputStream.close()
        process.awaitExit()
        val errors = stderr.read().toString(Charsets.UTF_8)
        assertEquals(ExitStatus.IO_ERR, process.exitValue(), errors)
        assertEquals("", errors)
    }

    @Test
    fun `DELVA_OPTS reaches the JVM`() {
        val run = delva("--version", env = mapOf("DELVA_OPTS" to "-Xmx1k"))
        assertNotEquals(0, run.status)
        assertContains(run.stderr.lowercase() + run.text.lowercase(), "heap")
    }

    @Test
    fun `the scripts opt in to native access, and the distribution carries its licences`() {
        for (name in listOf("delva", "delva.bat")) {
            assertContains(home.resolve("bin").resolve(name).readText(), "--enable-native-access=ALL-UNNAMED")
        }
        assertTrue(home.resolve("LICENSE").exists() && home.resolve("NOTICE").exists())
    }

    @Test
    fun `a file argument is read, and a missing one is exit 66`() {
        val store = work.resolve("store")
        delva("init", store.toString())
        val file = work.resolve("doc.json").also { it.writeText("""{"a":1}""") }
        val env = mapOf("DELVA_STORE" to store.toString())
        assertEquals(0, delva("insert", file.toString(), env = env).status)
        assertEquals(ExitStatus.NO_INPUT, delva("insert", work.resolve("nope.json").toString(), env = env).status)
    }
}
