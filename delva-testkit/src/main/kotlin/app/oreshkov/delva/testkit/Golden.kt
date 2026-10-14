package app.oreshkov.delva.testkit

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.fail

/**
 * Golden files freeze persisted byte layouts (hash inputs, keys, record formats). A golden file is a text resource
 * under `golden/` on the test classpath: `#` comment lines, then one expected line per case.
 *
 * Run with `-Ddelva.updateGolden=true` to rewrite the file in the module's `src/test/resources/golden` directory
 * (only when a format change is intended).
 */
object Golden {
    fun verify(name: String, header: String, actual: List<String>) {
        val resource = Golden::class.java.classLoader.getResource("golden/$name")
        val expected = resource?.readText()?.lines()?.filter { it.isNotEmpty() && !it.startsWith("#") }

        if (System.getProperty("delva.updateGolden") == "true") {
            if (expected != actual) write(name, header, actual)
            return
        }
        if (expected == null) {
            fail("Golden file golden/$name is missing. Create it with -Ddelva.updateGolden=true")
        }
        if (expected.size != actual.size) {
            fail("golden/$name: expected ${expected.size} lines, got ${actual.size}")
        }
        val mismatches = expected.indices.filter { expected[it] != actual[it] }
        if (mismatches.isNotEmpty()) {
            val details = mismatches.take(5).joinToString("\n") {
                "  entry ${it + 1}\n    expected: ${expected[it]}\n    actual:   ${actual[it]}"
            }
            fail("golden/$name: ${mismatches.size} lines differ\n$details")
        }
    }

    private fun write(name: String, header: String, lines: List<String>) {
        val dir = System.getProperty("delva.goldenDir") ?: fail("delva.goldenDir is not set for this test task")
        val file = Path.of(dir, name)
        Files.createDirectories(file.parent)
        val text = header.lines().joinToString("") { "# $it\n" } + lines.joinToString("") { "$it\n" }
        Files.writeString(file, text)
        println("Golden file rewritten: golden/$name")
    }
}

fun ByteArray.toHex(offset: Int = 0, length: Int = size - offset): String {
    val chars = CharArray(length * 2)
    for (i in 0 until length) {
        val b = this[offset + i].toInt() and 0xFF
        chars[2 * i] = HEX[b ushr 4]
        chars[2 * i + 1] = HEX[b and 0xF]
    }
    return String(chars)
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { ((Character.digit(this[2 * it], 16) shl 4) or Character.digit(this[2 * it + 1], 16)).toByte() }
}

private val HEX = "0123456789abcdef".toCharArray()
