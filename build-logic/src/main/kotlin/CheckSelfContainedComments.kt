import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Fails when source files cite documents that aren't in the repository: requirement ids such as `G-26` or `N-04.4`,
 * `Phase 11`, or `§` sections of anything but SPEC.md or an RFC. Comments should explain themselves, or point at
 * SPEC.md.
 */
@CacheableTask
abstract class CheckSelfContainedComments : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:SkipWhenEmpty
    abstract val sources: ConfigurableFileCollection

    /** Empty when the check passes; lets Gradle skip the task while the sources are unchanged. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val problems = sources.files.sorted().flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                outsideCitation(line)?.let { "${file.path}:${index + 1}: '$it'" }
            }
        }
        report.get().asFile.writeText(problems.joinToString("\n"))
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Comments cite documents outside the repository; explain the point in place or cite SPEC.md:\n" +
                    problems.joinToString("\n"),
            )
        }
    }
}

/**
 * The first citation of a document outside the repository in [line], or null. Escape sequences such as `\n` are
 * dropped first, so `"\nG-26"` in a string literal is caught. An id must not follow a letter or digit, which `\b` can't
 * say: to it the `n` of `\n`, like `_`, is a word character.
 */
internal fun outsideCitation(line: String): String? {
    val text = line.replace(escape, " ")
    return (citation.find(text) ?: section.find(text)?.takeUnless { "SPEC" in line || "RFC" in line })?.value
}

private val escape = Regex("""\\(?:u[0-9A-Fa-f]{4}|[bnrt])""")
private val citation =
    Regex("""(?<![A-Za-z0-9])(?:(?:TI|FL|DEP|[ABCDFGILNOPST])-\d{1,2}(?:\.\d+)?[a-z]?|Phase \d+)\b""")
private val section = Regex("""§\s*\d""")

/** Registers [CheckSelfContainedComments] over the module's sources and build scripts as `checkSelfContainedComments`. */
fun Project.registerCheckSelfContainedComments(): TaskProvider<CheckSelfContainedComments> =
    tasks.register("checkSelfContainedComments", CheckSelfContainedComments::class.java) {
        description = "Fails on comments that cite documents outside the repository."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        sources.from(fileTree("src") {
            include("**/*.kt", "**/*.kts", "**/*.md", "**/*.txt")
            // Third-party test data, kept byte for byte.
            exclude("**/jsontestsuite/**")
        })
        sources.from(fileTree(".") { include("*.kts") })
        report.set(layout.buildDirectory.file("reports/self-contained-comments.txt"))
    }
