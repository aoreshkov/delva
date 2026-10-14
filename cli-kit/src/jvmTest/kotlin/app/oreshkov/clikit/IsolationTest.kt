package app.oreshkov.clikit

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * cli-kit depends on the Kotlin standard library and nothing else — **not even delva**, which it
 * shares a repository with — and the build file says so in a comment, which is worth exactly
 * nothing on its own. This is the part that fails when somebody adds a dependency.
 *
 * The rule matters for two reasons. The module knows no command of any program's — a program
 * declares its own tree — so it is generic machinery another program can take as it is; and a
 * module with no dependencies is the cheapest possible thing to test.
 */
class IsolationTest {

    @Test
    fun `no delva type is on the classpath`() {
        listOf(
            "app.oreshkov.delva.JsonStore",
            "app.oreshkov.delva.JsonPath",
        ).forEach { name ->
            assertFailsWith<ClassNotFoundException>("$name must not be reachable from cli-kit") {
                Class.forName(name)
            }
        }
    }

    @Test
    fun `no third-party library is on the classpath`() {
        // The specific ones a CLI layer drifts towards. Clikt and Mordant were considered and
        // dropped; this is what keeps "we implemented it ourselves" true rather than aspirational.
        listOf(
            "com.github.ajalt.clikt.core.CliktCommand",
            "com.github.ajalt.mordant.terminal.Terminal",
            "com.sun.jna.Native",
            "kotlinx.serialization.json.Json",
            "kotlinx.coroutines.CoroutineScope",
            "io.ktor.client.HttpClient",
        ).forEach { name ->
            assertFailsWith<ClassNotFoundException>("$name must not be reachable from cli-kit") {
                Class.forName(name)
            }
        }
    }

    @Test
    fun `the standard library is, obviously, reachable`() {
        // Guards the test itself: if `Class.forName` failed for an unrelated reason, every
        // assertion above would pass while proving nothing.
        assertTrue(Class.forName("kotlin.text.StringsKt") != null)
    }
}
