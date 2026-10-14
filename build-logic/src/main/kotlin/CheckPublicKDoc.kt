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
import java.io.File

/**
 * Fails when a public declaration of a published module has no KDoc.
 *
 * Explicit API mode makes every public declaration say `public`, so the check reads lines rather than parsing Kotlin.
 * A declaration is documented by a KDoc right above it, annotations aside. A public property is also documented when
 * its class's KDoc names it: as `[name]`, the way the options classes describe their properties in a list, or with a
 * `@property` or `@param` tag. Entries of a public enum need KDoc too, and so do the constructor properties of a data
 * class, though neither says `public`.
 *
 * Not checked, because their KDoc comes from elsewhere or adds nothing: overrides, which inherit the documentation of
 * what they override; `actual` declarations, documented on the `expect` one; companion objects, which only hold
 * documented members; and primary constructors, which the class's KDoc covers.
 */
@CacheableTask
abstract class CheckPublicKDoc : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:SkipWhenEmpty
    abstract val sources: ConfigurableFileCollection

    /** Empty when the check passes; lets Gradle skip the task while the sources are unchanged. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val problems = sources.files.sorted().flatMap { file -> undocumented(file).map { "${file.path}:$it" } }
        report.get().asFile.writeText(problems.joinToString("\n"))
        if (problems.isNotEmpty()) {
            throw GradleException("Public declarations without KDoc:\n" + problems.joinToString("\n"))
        }
    }

    private class Scope(val indent: Int, val public: Boolean, val enum: Boolean, val doc: String) {
        // From the brace that opens the body until the first member or the `;` after the entries.
        var inEntries = false
        var entriesDone = false

        // Within a primary constructor that spans lines.
        var inHeader = false
    }

    /** `line: name` for each undocumented declaration of [file]. */
    private fun undocumented(file: File): List<String> {
        val problems = mutableListOf<String>()
        val scopes = ArrayDeque<Scope>()
        var docLines: MutableList<String>? = null
        var doc: String? = null
        var annotationDepth = 0
        var inRawString = false
        for ((index, raw) in file.readLines().withIndex()) {
            val line = raw.trim()
            val indent = raw.length - raw.trimStart().length
            // A raw string's lines aren't code; the line that opens one is.
            val rawQuotes = if (docLines == null) line.split("\"\"\"").size - 1 else 0
            if (inRawString) {
                if (rawQuotes % 2 == 1) inRawString = false
                doc = null
                continue
            }
            if (rawQuotes % 2 == 1) inRawString = true
            if (docLines != null) {
                docLines += line
                if (line.endsWith("*/")) {
                    doc = docLines.joinToString("\n")
                    docLines = null
                }
                continue
            }
            if (line.startsWith("/**")) {
                if (line.endsWith("*/") && line.length > 4) doc = line else docLines = mutableListOf(line)
                continue
            }
            if (annotationDepth > 0) {
                annotationDepth += line.count { it == '(' } - line.count { it == ')' }
                continue
            }
            val code = stripped(line)
            val match = declaration.find(code)
            if (line.startsWith("@") && !line.startsWith("@file:") && match == null) {
                annotationDepth = line.count { it == '(' } - line.count { it == ')' }
                continue
            }
            if (line.isEmpty() || line.startsWith("//")) {
                doc = null
                continue
            }
            while (scopes.isNotEmpty() && indent <= scopes.last().indent && !line.startsWith(")")) scopes.removeLast()
            scopes.lastOrNull()?.let { if (indent == it.indent && line.startsWith(")")) it.inHeader = false }

            val enclosing = scopes.lastOrNull()
            val visible = scopes.all { it.public }
            if (enclosing != null && enclosing.enum && !enclosing.entriesDone) {
                if (indent == enclosing.indent && line.startsWith(")") && line.endsWith("{")) enclosing.inEntries = true
                if (enclosing.inEntries && indent > enclosing.indent) {
                    val entry = enumEntry.find(line)
                    when {
                        // An entry's arguments or body, on the lines after it.
                        indent > enclosing.indent + 4 -> {}
                        entry != null && match == null -> {
                            if (visible && doc == null) problems += "${index + 1}: ${entry.groupValues[1]}"
                            if (line.endsWith(";")) enclosing.entriesDone = true
                        }
                        else -> enclosing.entriesDone = true
                    }
                    if (!enclosing.entriesDone) {
                        doc = null
                        continue
                    }
                }
            }
            if (match != null) {
                val modifiers = match.groupValues[1].split(' ')
                val kind = match.groupValues[2]
                val name = match.groupValues[3].ifEmpty { kind }
                // A data class's constructor properties need no modifier to be public.
                val implicitlyPublic = enclosing != null && enclosing.inHeader && modifiers.none { it in visibilities }
                val isPublic = ("public" in modifiers || implicitlyPublic) && visible
                val exempt = "override" in modifiers || "actual" in modifiers || "companion" in modifiers
                val isProperty = kind == "val" || kind == "var"
                val documented = doc != null ||
                    isProperty && enclosing != null && names(enclosing.doc, name)
                if (isPublic && !exempt && !documented) problems += "${index + 1}: $name"
                if (kind in containers) {
                    val isEnum = "enum" in modifiers
                    val scope = Scope(indent, isPublic || "companion" in modifiers && visible, isEnum, doc ?: "")
                    if (isEnum && line.endsWith("{")) scope.inEntries = true
                    if (line.endsWith("(")) scope.inHeader = true
                    scopes += scope
                    // So do a one-line class's, which only the class's KDoc can document.
                    if (isPublic) {
                        oneLineProperty.findAll(line).map { it.groupValues[1] }
                            .filterNot { names(scope.doc, it) }
                            .forEach { problems += "${index + 1}: $it" }
                    }
                    // A one-line enum lists its entries between the braces.
                    if (isEnum && isPublic && line.endsWith("}")) {
                        line.substringAfter('{').substringBeforeLast('}').substringBefore(';').split(',')
                            .map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { problems += "${index + 1}: $it" }
                    }
                }
            }
            doc = null
        }
        return problems
    }

    private fun stripped(line: String): String = line.replace(leadingAnnotations, "")

    /** Whether [doc] describes the property [name]: as `[name]`, or with a `@property` or `@param` tag. */
    private fun names(doc: String, name: String): Boolean =
        Regex("""\[${Regex.escape(name)}]|@(?:property|param) ${Regex.escape(name)}\b""").containsMatchIn(doc)

    private companion object {
        val containers = setOf("class", "interface", "object")
        val visibilities = setOf("public", "internal", "private", "protected")
        val oneLineProperty = Regex("""[(,]\s*(?:public\s+)?va[lr]\s+(\w+)""")
        val leadingAnnotations = Regex("""^(?:@[\w.:]+(?:\([^)]*\))?\s+)+""")
        val declaration = Regex(
            """^((?:(?:public|internal|private|protected|override|open|abstract|sealed|data|value|enum|annotation|inline|""" +
                """const|lateinit|operator|infix|suspend|tailrec|external|expect|actual|fun|companion|inner|""" +
                """vararg|noinline|crossinline)\s+)*)(class|interface|object|fun|val|var|typealias|constructor)\b""" +
                """(?:\s*<[^>]*(?:<[^>]*>[^>]*)*>)?\s*(?:[\w.<>?*, ]+\.)?(`[^`]+`|\w+)?""",
        )
        val enumEntry = Regex("""^([A-Za-z_]\w*)\s*(?:\(.*)?(?:\{.*)?[,;]?$""")
    }
}

/** Registers [CheckPublicKDoc] over the module's main sources as `checkPublicKDoc`. */
fun Project.registerCheckPublicKDoc(): TaskProvider<CheckPublicKDoc> =
    tasks.register("checkPublicKDoc", CheckPublicKDoc::class.java) {
        description = "Fails on public declarations without KDoc."
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        sources.from(fileTree("src") {
            include("main/kotlin/**/*.kt", "*Main/kotlin/**/*.kt")
        })
        report.set(layout.buildDirectory.file("reports/public-kdoc.txt"))
    }
