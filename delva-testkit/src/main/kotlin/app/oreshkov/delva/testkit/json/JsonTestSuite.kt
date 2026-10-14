package app.oreshkov.delva.testkit.json

import app.oreshkov.delva.RejectReason
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** The vendored JSONTestSuite parsing corpus with Delva's expected outcome for every file. */
object JsonTestSuite {
    sealed interface Expected {
        data object Accept : Expected

        /** Rejected; with [reason] null, any reason is acceptable. */
        data class Reject(val reason: RejectReason?) : Expected
    }

    class Case(val name: String, val bytes: ByteArray, val expected: Expected) {
        override fun toString(): String = name
    }

    /** All 318 files, sorted by name. Works whether the test kit is a directory or a jar on the classpath. */
    val cases: List<Case> by lazy { load() }

    private fun load(): List<Case> {
        val loader = JsonTestSuite::class.java.classLoader
        val overrides = checkNotNull(loader.getResource("jsontestsuite/delva-expectations.txt")).readText()
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val f = line.split(' ')
                f[0] to when (f[1]) {
                    "accept" -> Expected.Accept
                    "reject" -> Expected.Reject(f.getOrNull(2)?.let { RejectReason.valueOf(it) })
                    else -> error("bad line: $line")
                }
            }
        val uri = checkNotNull(loader.getResource("jsontestsuite/test_parsing")).toURI()
        val root = if (uri.scheme == "jar") {
            val fs = try {
                FileSystems.getFileSystem(uri)
            } catch (_: FileSystemNotFoundException) {
                FileSystems.newFileSystem(uri, emptyMap<String, Any>())
            }
            fs.provider().getPath(uri)
        } else {
            Path.of(uri)
        }
        val files = Files.list(root).use { stream -> stream.toList() }.sortedBy { it.name }
        val cases = files.map { file ->
            val name = file.name
            val expected = overrides[name] ?: when {
                name.startsWith("y_") -> Expected.Accept
                name.startsWith("n_") -> Expected.Reject(null)
                else -> error("no decision recorded for $name")
            }
            Case(name, Files.readAllBytes(file), expected)
        }
        val missing = overrides.keys - cases.map { it.name }.toSet()
        check(missing.isEmpty()) { "expectations name missing files: $missing" }
        return cases
    }
}
