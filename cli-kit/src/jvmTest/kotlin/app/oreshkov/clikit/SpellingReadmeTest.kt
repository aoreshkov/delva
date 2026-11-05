package app.oreshkov.clikit

import app.oreshkov.clikit.testing.record
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The README's examples of an option given positionally and of values that mean "not given", which need the JVM for
 * `Converters.path`.
 */
class SpellingReadmeTest {

    @Test
    fun `the README's example of an option given positionally`() {
        val formsDir = Option.Single(
            "--forms-dir", Converters.path,
            help = "Directory of the forms", valueName = "path", required = true, env = "FORMS_DIR",
        )
        val formsDirArgument = Argument.Single.spelling(formsDir)
        val serve = CommandSpec("serve", options = listOf(formsDir), arguments = listOf(formsDirArgument))

        var seen: Path? = null
        fun run(vararg args: String, environment: Map<String, String> = emptyMap()) =
            Program(serve, "serve 1.0", mapOf(serve to CommandHandler { context -> seen = context.values[formsDir]; 0 }), environment = environment::get)
                .record(*args)

        for ((args, environment) in listOf(
            listOf("/srv/forms") to emptyMap(),
            listOf("--forms-dir", "/srv/forms") to emptyMap(),
            emptyList<String>() to mapOf("FORMS_DIR" to "/srv/forms"),
            listOf("/srv/forms") to mapOf("FORMS_DIR" to "/a"),
        )) {
            seen = null
            assertEquals(ExitStatus.OK, run(*args.toTypedArray(), environment = environment).status, "$args $environment")
            assertEquals(Path.of("/srv/forms"), seen, "$args $environment")
        }
        assertEquals(
            "error: --forms-dir is given twice: as an option and as <forms-dir>\nusage: serve [options] [forms-dir]\n",
            run("--forms-dir", "/a", "/b").stderr,
        )
        assertEquals(
            "error: missing required option --forms-dir (or <forms-dir>, or FORMS_DIR)\nusage: serve [options] [forms-dir]\n",
            run().stderr,
        )
    }

    @Test
    fun `the README's example of values that mean not given`() {
        val unfilled = Regex("""\$\{[^}]*}""")
        fun <T> ValueConverter<T>.configured() = unsetWhen(
            { it.isBlank() || unfilled.matches(it.trim()) },
            notice = { if (it.isBlank()) null else "ignoring '$it': an unfilled template" },
        )

        val formsDir = Option.Single("--forms-dir", Converters.path.configured(), valueName = "path", required = true, env = "FORMS_DIR")
        val serve = CommandSpec("serve", options = listOf(formsDir), arguments = listOf(Argument.Single.spelling(formsDir)))

        var seen: Path? = null
        val program = Program(serve, "serve 1.0", mapOf(serve to CommandHandler { context -> seen = context.values[formsDir]; 0 }))
        val ran = program.record("--forms-dir", "\${user_config.forms_dir}", "/srv/forms")
        assertEquals(ExitStatus.OK, ran.status)
        assertEquals(Path.of("/srv/forms"), seen)
        assertEquals("warning: --forms-dir: ignoring '\${user_config.forms_dir}': an unfilled template\n", ran.stderr)
        assertEquals(
            "error: missing required option --forms-dir (or <forms-dir>, or FORMS_DIR; " +
                "given '\${user_config.forms_dir}', which counts as not set)\nusage: serve [options] [forms-dir]\n",
            program.record("--forms-dir", "\${user_config.forms_dir}").stderr,
        )
    }
}
