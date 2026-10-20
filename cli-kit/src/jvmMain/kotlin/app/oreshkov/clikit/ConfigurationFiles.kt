package app.oreshkov.clikit

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * Settings from configuration files, where the XDG Base Directory specification puts them, and on Windows where
 * Windows does. Highest first:
 *
 * - the project file, only when [project] names its directory, such as `.tool`: the nearest `.tool/config` from
 *   [workingDirectory] up to the root of the file system. Off by default, since a repository someone else wrote can
 *   carry one;
 * - the user file: the file the [variable] names, when it's set, such as `TOOL_CONFIG`; else
 *   `$XDG_CONFIG_HOME/<program>/config`; else `~/.config/<program>/config`, or `%APPDATA%\<program>\config` on Windows;
 * - when [system] is on, the system files: `<program>/config` in each directory of `$XDG_CONFIG_DIRS`, by default
 *   `/etc/xdg`; on Windows, `%ProgramData%\<program>\config`.
 *
 * A variable that is unset or empty takes its default, and a relative path in an XDG variable is ignored, as the
 * specification says. A file that doesn't exist is skipped, except the one [variable] names, which was asked for. Every
 * file is read, whole, each time [read] is called, and each is UTF-8, at most 1 MiB, of `key = value` lines,
 * with `#` and `;` starting comments; cli-kit's README specifies the format.
 *
 * Paths come from [environment], [home] and [workingDirectory], so a test can point them at a temporary directory.
 * A relative path in a value is the converter's to resolve, so it's relative to the working directory, as on the
 * command line, not to the file: write absolute paths in a user or system file.
 */
@ExperimentalCliKitApi
public class ConfigurationFiles internal constructor(
    private val program: String,
    private val variable: String?,
    private val project: String?,
    private val system: Boolean,
    private val environment: (String) -> String?,
    private val home: Path,
    private val workingDirectory: Path,
    private val windows: Boolean,
) : Settings {
    /**
     * The files of [program], the name of the directory they're in. [variable] names a file read in place of the
     * user's; [project] turns on the project file and names its directory; [system] turns the system files off when
     * `false`.
     */
    public constructor(
        program: String,
        variable: String? = null,
        project: String? = null,
        system: Boolean = true,
        /** Where the variables are read: `System::getenv` by default, a map in a test. */
        environment: (String) -> String? = System::getenv,
        /** The user's home directory, for the default user file. */
        home: Path = Path.of(System.getProperty("user.home")),
        /** Where the search for the project file starts, and what a relative path in [variable] is relative to. */
        workingDirectory: Path = Path.of("").toAbsolutePath(),
    ) : this(program, variable, project, system, environment, home, workingDirectory, ON_WINDOWS)

    init {
        require(isDirectoryName(program)) { "program '$program' must be a directory name" }
        require(project == null || isDirectoryName(project)) { "project directory '$project' must be a directory name" }
        require(variable == null || isEnvironmentName(variable)) {
            "environment variable '$variable' must be upper case, digits and '_'"
        }
    }

    override val locations: List<String> = buildList {
        if (project != null) add("$project/config in the working directory or the nearest directory above it")
        val user = "\$XDG_CONFIG_HOME/$program/config (by default ~/.config/$program/config, or %APPDATA%\\$program\\config on Windows)"
        add(variable?.let { "\$$it, or if it's not set, $user" } ?: user)
        if (system) {
            add("$program/config in each of \$XDG_CONFIG_DIRS (by default /etc/xdg; %ProgramData% on Windows)")
        }
    }

    /** The files to read, highest first, each with whether it must exist. */
    internal fun files(): List<Pair<Path, Boolean>> = buildList {
        if (project != null) nearestProjectFile(project)?.let { add(it to false) }
        val named = variable?.let(::value)
        if (named != null) {
            val file = try {
                workingDirectory.resolve(named)
            } catch (e: InvalidPathException) {
                throw SettingsException("$variable: '$named' is not a valid path: ${e.reason}", e)
            }
            add(file to true)
        } else {
            add(userDirectory().resolve(program).resolve(FILE) to false)
        }
        if (system) systemDirectories().forEach { add(it.resolve(program).resolve(FILE) to false) }
    }

    override fun read(): List<Setting> = files().flatMap { (file, required) -> read(file, required) }

    private fun read(file: Path, required: Boolean): List<Setting> {
        if (Files.isDirectory(file)) throw SettingsException("$file is a directory, not a configuration file")
        val bytes = try {
            Files.newInputStream(file).use { it.readNBytes(MAX_BYTES + 1) }
        } catch (_: NoSuchFileException) {
            if (required) throw SettingsException("$variable names $file, which doesn't exist")
            return emptyList()
        } catch (e: AccessDeniedException) {
            throw SettingsException("$file can't be read: permission denied", e)
        } catch (e: IOException) {
            throw SettingsException("$file can't be read: ${e.message ?: e::class.simpleName}", e)
        }
        if (bytes.size > MAX_BYTES) throw SettingsException("$file is larger than 1 MiB")
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw SettingsException("$file is not UTF-8 text", e)
        }
        return parseSettings(text, file.toString())
    }

    /** The nearest `<project>/config` from the working directory up, or `null`. */
    private fun nearestProjectFile(project: String): Path? =
        generateSequence(workingDirectory.toAbsolutePath().normalize()) { it.parent }
            .map { it.resolve(project).resolve(FILE) }
            .firstOrNull { Files.isRegularFile(it) }

    /** `$XDG_CONFIG_HOME`; else `%APPDATA%` on Windows, or `~/.config` elsewhere. */
    private fun userDirectory(): Path {
        absolute("XDG_CONFIG_HOME")?.let { return it }
        if (!windows) return home.resolve(".config")
        return absolute("APPDATA") ?: home.resolve("AppData").resolve("Roaming")
    }

    /** `$XDG_CONFIG_DIRS`, by default `/etc/xdg`; `%ProgramData%`, if set, on Windows. */
    private fun systemDirectories(): List<Path> {
        if (windows) return listOfNotNull(absolute("ProgramData"))
        val listed = value("XDG_CONFIG_DIRS") ?: return listOf(Path.of("/etc/xdg"))
        // The specification's ':', which is the path separator wherever this is read for real; a test on Windows
        // can then list directories whose paths hold a drive's colon.
        return listed.split(File.pathSeparatorChar).mapNotNull(::absolutePath)
    }

    /** The variable [name], or `null` when it's unset or empty. */
    private fun value(name: String): String? = environment(name)?.takeIf { it.isNotEmpty() }

    /** The variable [name] as an absolute path, or `null`: a relative or malformed one is ignored, as unset. */
    private fun absolute(name: String): Path? = value(name)?.let(::absolutePath)

    private fun absolutePath(text: String): Path? = try {
        Path.of(text).takeIf { text.isNotEmpty() && it.isAbsolute }
    } catch (_: InvalidPathException) {
        null
    }

    override fun toString(): String = "ConfigurationFiles($program)"
}

/** The name of the file in each directory. */
private const val FILE = "config"

/** The largest file read, so that memory stays bounded whatever a file holds. */
private const val MAX_BYTES = 1 shl 20

private val ON_WINDOWS: Boolean = System.getProperty("os.name")?.lowercase()?.startsWith("windows") == true

private fun isDirectoryName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' }
