package app.oreshkov.delva.internal

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every golden file is checked by some test: a golden file nothing verifies pins nothing, and a change to the format it
 * describes would pass unnoticed.
 */
class GoldenFilesTest {
    private val golden: Path = Path.of("src/test/resources/golden")
    private val sources: Path = Path.of("src/test/kotlin")

    @Test
    fun `every golden file is named by a Golden verify call`() {
        val files = golden.listDirectoryEntries().map { it.name }.toSortedSet()
        assertTrue(files.size > 5, "found the golden files: $files")
        val call = Regex("""Golden\.verify\(\s*"([^"]+)"""")
        val verified = Files.walk(sources).use { paths ->
            paths.filter { it.name.endsWith(".kt") }.toList()
        }.flatMap { source -> call.findAll(source.readText()).map { it.groupValues[1] } }.toSet()
        assertEquals(emptySet(), files - verified, "golden files no test verifies")
    }
}
