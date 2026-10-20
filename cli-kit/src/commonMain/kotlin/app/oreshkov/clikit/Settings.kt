package app.oreshkov.clikit

/**
 * Settings: values for options from somewhere other than the command line and the environment, such as configuration
 * files ([ConfigurationFiles] on the JVM).
 *
 * They come last in the order of precedence: the command line wins, then an option's [Option.Single.env] variable,
 * then the settings, highest layer first, then the handler's default. Only an option that declares a key, its
 * [Option.Single.config], can be set this way.
 */

/** One value a [Settings] source holds, `key = value`, and where it was written, for messages to name. */
@ExperimentalCliKitApi
public class Setting(
    /** The key, as an option's [Option.Single.config] names it. */
    public val key: String,
    /** The value as written; the option's converter reads it as it reads a value typed on the command line. */
    public val value: String,
    /** Where it was written, as messages name it, such as `/home/ana/.config/tool/config:3`. */
    public val origin: String,
) {
    override fun equals(other: Any?): Boolean =
        other is Setting && other.key == key && other.value == value && other.origin == origin

    override fun hashCode(): Int = (31 * key.hashCode() + value.hashCode()) * 31 + origin.hashCode()

    override fun toString(): String = "Setting($origin: $key = $value)"
}

/**
 * Where settings come from, for [Program] and [Parser] to read the [Option.Single.config] keys from.
 *
 * Implement it to read another format, or another place, than [ConfigurationFiles] does.
 */
@ExperimentalCliKitApi
public interface Settings {
    /**
     * Where the settings come from, highest precedence first, as help's Configuration section lists them. Written the
     * same on every machine, such as `$XDG_CONFIG_HOME/tool/config`, so the help pages that golden files pin don't
     * depend on who runs the tests. Empty for no source at all, and help then shows no configuration.
     */
    public val locations: List<String>

    /**
     * Every setting, highest precedence first, and in the order written within one source. For a key, the parser takes
     * the first, and the next only when the option's converter calls the first [ConversionResult.Unset].
     *
     * The parser calls it at most once a parse, and only when it needs a value that neither the command line nor the
     * environment gave, so a broken source never stops `--help`, `help`, `--version` or `completion`, or a command
     * with no configurable option.
     *
     * @throws SettingsException for a source that can't be read or that is malformed, with a message that says where.
     */
    public fun read(): List<Setting>

    public companion object {
        /** No settings, the default of [Program] and [Parser]: nothing is read, and help shows no configuration. */
        public val None: Settings = object : Settings {
            override val locations: List<String> get() = emptyList()

            override fun read(): List<Setting> = emptyList()

            override fun toString(): String = "Settings.None"
        }
    }
}

/**
 * A [Settings] source can't be read, or is malformed. [Program] reports [message], which names the file and line, as
 * an error and exits with [ExitStatus.CONFIG], with no usage line: the command line isn't at fault.
 */
@ExperimentalCliKitApi
public class SettingsException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The settings in [text], the content of the configuration file [file], in the kit's own format: `key = value` lines,
 * each trimmed, the value taken verbatim, with no quotes or escapes, so that `C:\work` means what it says. Blank lines,
 * and those whose first non-blank character is `#` or `;`, are left out; a leading byte-order mark is too, as Notepad
 * writes one. A line that starts with `[` is refused, kept for sections of a later version, and so is a key given
 * twice: the reader couldn't tell which one wins.
 */
internal fun parseSettings(text: String, file: String): List<Setting> {
    val lines = mutableMapOf<String, Int>()
    val settings = mutableListOf<Setting>()
    text.removePrefix(BYTE_ORDER_MARK).split('\n').forEachIndexed { index, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line[0] == '#' || line[0] == ';') return@forEachIndexed
        val number = index + 1
        val at = "$file:$number"
        if (line[0] == '[') throw SettingsException("$at: sections aren't supported; write 'key = value' lines")
        val equals = line.indexOf('=')
        val key = if (equals < 0) "" else line.substring(0, equals).trim()
        if (key.isEmpty()) throw SettingsException("$at: expected 'key = value'")
        if (!isSettingKey(key)) throw SettingsException("$at: '$key' is not a key; keys are lower case, digits, '-' and '.'")
        lines.put(key, number)?.let { throw SettingsException("$at: '$key' is set twice, here and on line $it") }
        settings += Setting(key, line.substring(equals + 1).trim(), at)
    }
    return settings
}

private const val BYTE_ORDER_MARK = "\uFEFF"
