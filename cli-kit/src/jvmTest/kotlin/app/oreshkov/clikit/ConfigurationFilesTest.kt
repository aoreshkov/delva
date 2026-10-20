package app.oreshkov.clikit

import app.oreshkov.clikit.testing.record
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Where configuration files are looked for, on each platform, and how they're read. Nothing here touches the real home. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ConfigurationFilesTest {

    private val temporary: Path = createTempDirectory("cli-kit-configuration").toRealPath()
    private val home: Path = temporary.resolve("home").createDirectories()
    private val work: Path = temporary.resolve("work").resolve("project").resolve("src").createDirectories()

    @AfterTest
    fun removeTemporary() {
        temporary.deleteRecursively()
    }

    private fun files(
        env: Map<String, String> = emptyMap(),
        variable: String? = null,
        project: String? = null,
        system: Boolean = true,
        windows: Boolean = false,
    ) = ConfigurationFiles("demo", variable, project, system, env::get, home, work, windows)

    private fun write(file: Path, text: String): Path {
        file.parent.createDirectories()
        file.writeText(text)
        return file
    }

    private fun config(directory: Path): Path = directory.resolve("demo").resolve("config")

    // ------------------------------------------------------------------------------------ locations

    @Test
    fun `help describes the locations the same way on every machine`() {
        assertEquals(
            listOf(
                ".demo/config in the working directory or the nearest directory above it",
                "\$DEMO_CONFIG, or if it's not set, \$XDG_CONFIG_HOME/demo/config (by default ~/.config/demo/config, or " +
                    "%APPDATA%\\demo\\config on Windows)",
                "demo/config in each of \$XDG_CONFIG_DIRS (by default /etc/xdg; %ProgramData% on Windows)",
            ),
            files(variable = "DEMO_CONFIG", project = ".demo").locations,
        )
        assertEquals(
            listOf("\$XDG_CONFIG_HOME/demo/config (by default ~/.config/demo/config, or %APPDATA%\\demo\\config on Windows)"),
            files(system = false).locations,
        )
    }

    @Test
    fun `by default, the user file is under ~ config and the system file under etc xdg`() {
        assertEquals(listOf(config(home.resolve(".config")) to false, config(Path.of("/etc/xdg")) to false), files().files())
        assertEquals(listOf(config(home.resolve(".config")) to false), files(system = false).files())
    }

    @Test
    fun `the XDG variables move the files, and an empty or relative one is ignored`() {
        val xdgHome = temporary.resolve("xdg-home")
        val first = temporary.resolve("xdg-first")
        val second = temporary.resolve("xdg-second")
        // ':' where XDG_CONFIG_DIRS is read for real; the platform's separator, so this runs on Windows too.
        val dirs = listOf("$first", "relative", "", "$second").joinToString(File.pathSeparator)
        assertEquals(
            listOf(config(xdgHome) to false, config(first) to false, config(second) to false),
            files(mapOf("XDG_CONFIG_HOME" to "$xdgHome", "XDG_CONFIG_DIRS" to dirs)).files(),
        )
        val ignored = mapOf("XDG_CONFIG_HOME" to "relative/dir", "XDG_CONFIG_DIRS" to "")
        assertEquals(listOf(config(home.resolve(".config")) to false, config(Path.of("/etc/xdg")) to false), files(ignored).files())
        assertEquals(listOf(config(home.resolve(".config")) to false), files(mapOf("XDG_CONFIG_DIRS" to "relative")).files())
    }

    @Test
    fun `the program's variable names a file read in place of the user's, relative to the working directory`() {
        val named = temporary.resolve("elsewhere.conf")
        assertEquals(
            listOf(named to true, config(Path.of("/etc/xdg")) to false),
            files(mapOf("DEMO_CONFIG" to "$named"), variable = "DEMO_CONFIG").files(),
        )
        assertEquals(work.resolve("local.conf") to true, files(mapOf("DEMO_CONFIG" to "local.conf"), variable = "DEMO_CONFIG").files().first())
        // Empty is unset.
        assertEquals(config(home.resolve(".config")) to false, files(mapOf("DEMO_CONFIG" to ""), variable = "DEMO_CONFIG").files().first())
    }

    @Test
    fun `on Windows, the user file is under APPDATA and the system file under ProgramData`() {
        val appData = temporary.resolve("AppData")
        val programData = temporary.resolve("ProgramData")
        assertEquals(
            listOf(config(appData) to false, config(programData) to false),
            files(mapOf("APPDATA" to "$appData", "ProgramData" to "$programData"), windows = true).files(),
        )
        // XDG_CONFIG_HOME, set by someone who wants it, still wins; XDG_CONFIG_DIRS isn't read.
        val xdgHome = temporary.resolve("xdg-home")
        assertEquals(
            listOf(config(xdgHome) to false),
            files(mapOf("XDG_CONFIG_HOME" to "$xdgHome", "XDG_CONFIG_DIRS" to "$programData"), windows = true).files(),
        )
        assertEquals(listOf(config(home.resolve("AppData").resolve("Roaming")) to false), files(windows = true).files())
    }

    @Test
    fun `the project file is the nearest one from the working directory up, and only when asked for`() {
        assertEquals(config(home.resolve(".config")) to false, files(project = ".demo").files().first())
        val outer = write(temporary.resolve("work").resolve(".demo").resolve("config"), "")
        assertEquals(outer to false, files(project = ".demo").files().first())
        val inner = write(temporary.resolve("work").resolve("project").resolve(".demo").resolve("config"), "")
        assertEquals(inner to false, files(project = ".demo").files().first())
        assertEquals(config(home.resolve(".config")) to false, files().files().first())
    }

    // ------------------------------------------------------------------------------------ reading

    @Test
    fun `settings come highest first, each naming its file and line`() {
        val project = write(temporary.resolve("work").resolve(".demo").resolve("config"), "store = /project\n")
        val user = write(config(home.resolve(".config")), "# mine\nstore = /user\nlimit = 3\n")
        val system = temporary.resolve("etc")
        write(config(system), "limit = 9\n")
        val read = files(mapOf("XDG_CONFIG_DIRS" to "$system"), project = ".demo").read()
        assertEquals(
            listOf(
                Setting("store", "/project", "$project:1"),
                Setting("store", "/user", "$user:2"),
                Setting("limit", "3", "$user:3"),
                Setting("limit", "9", "${config(system)}:1"),
            ),
            read,
        )
    }

    @Test
    fun `a missing file is skipped, but not the one the variable names`() {
        assertEquals(emptyList(), files(mapOf("XDG_CONFIG_DIRS" to "${temporary.resolve("none")}")).read())
        val missing = temporary.resolve("missing.conf")
        val error = assertFailsWith<SettingsException> { files(mapOf("DEMO_CONFIG" to "$missing"), variable = "DEMO_CONFIG").read() }
        assertEquals("DEMO_CONFIG names $missing, which doesn't exist", error.message)
    }

    @Test
    fun `a file that is too large, not UTF-8, or a directory is an error that names it`() {
        val user = config(home.resolve(".config"))
        val quiet = mapOf("XDG_CONFIG_DIRS" to "${temporary.resolve("none")}")
        user.parent.createDirectories()

        user.writeBytes(ByteArray(1 shl 20) { '#'.code.toByte() })
        assertEquals(emptyList(), files(quiet).read())
        user.writeBytes(ByteArray((1 shl 20) + 1) { '#'.code.toByte() })
        assertEquals("$user is larger than 1 MiB", assertFailsWith<SettingsException> { files(quiet).read() }.message)

        user.writeBytes(byteArrayOf('a'.code.toByte(), '='.code.toByte(), 0xC3.toByte()))
        assertEquals("$user is not UTF-8 text", assertFailsWith<SettingsException> { files(quiet).read() }.message)

        user.toFile().delete()
        user.createDirectories()
        assertEquals("$user is a directory, not a configuration file", assertFailsWith<SettingsException> { files(quiet).read() }.message)
    }

    @Test
    fun `the README's example, with the home directory a temporary one`() {
        val store = Option.Single("--store", Converters.path, valueName = "dir", env = "TOOL_STORE", config = "store")
        val list = CommandSpec("list", "List entries", options = listOf(store))
        val root = CommandSpec("tool", subcommands = listOf(list))
        val env = mapOf("XDG_CONFIG_DIRS" to "${temporary.resolve("none")}")

        val program = Program(
            root,
            "tool 1.0",
            mapOf(list to CommandHandler { context -> context.output.text("${context.values[store]}"); ExitStatus.OK }),
            environment = env::get,
            settings = ConfigurationFiles("tool", "TOOL_CONFIG", null, true, env::get, home, work, windows = false),
        )

        val file = write(home.resolve(".config").resolve("tool").resolve("config"), "store = /srv/tool\n")
        assertEquals("${Path.of("/srv/tool")}\n", program.record("list").stdout)

        file.writeText("store = /srv/tool\nstore = /srv/other\n")
        val twice = program.record("list")
        assertEquals(ExitStatus.CONFIG, twice.status)
        assertEquals("error: $file:2: 'store' is set twice, here and on line 1\n", twice.stderr)
        assertContains(program.record("list", "--help").stdout, "[env: TOOL_STORE] [config: store]")
    }

    @Test
    fun `a program reads its option from the user file`() {
        val store = Option.Single("--store", Converters.path, valueName = "dir", config = "store")
        val seen = mutableListOf<Path?>()
        val show = CommandSpec("show", options = listOf(store))
        val root = CommandSpec("demo", subcommands = listOf(show))
        val settings = files(mapOf("XDG_CONFIG_DIRS" to "${temporary.resolve("none")}"))
        val program = Program(root, "demo 1.0", mapOf(show to CommandHandler { seen += it.values[store]; 0 }), settings = settings)

        write(config(home.resolve(".config")), "\uFEFFstore = C:\\work\\store\r\n")
        assertEquals(0, program.record("show").status)
        assertEquals(listOf<Path?>(Path.of("C:\\work\\store")), seen)

        write(config(home.resolve(".config")), "store = /a\n[show]\n")
        val broken = program.record("show")
        assertEquals(ExitStatus.CONFIG, broken.status)
        assertContains(broken.stderr, "config:2: sections aren't supported")
    }
}
