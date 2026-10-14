package app.oreshkov.delva.cli

import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.testing.record
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.Normalization
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfoTest {
    private val root: Path = Files.createTempDirectory("delva-cli-info")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private val program = DelvaProgram.program

    /** A store of five orders, one deleted, with a type on each order and on its customer. */
    private fun store(): Path {
        val dir = root.resolve("store")
        val creation = CreationOptions(fragmentBytes = 4096, normalization = Normalization.NFC, excludedKeys = setOf("note"))
        JsonStore.create(dir, creation).use { store ->
            val blocking = store.blocking()
            val outcomes = blocking.insertAll(
                (1..5).map { i ->
                    InsertSource(
                        """{"order":$i,"customer":{"name":"c$i"},"note":"n"}""".byteInputStream(),
                        mapOf("$" to "Order", "$.customer" to "Customer"),
                    )
                },
            )
            val first = (outcomes.first() as app.oreshkov.delva.InsertOutcome.Inserted).docId
            blocking.delete(first)
        }
        return dir
    }

    @Test
    fun `info shows what the store holds and how it was created`() {
        val dir = store()
        val result = program.record("--store", dir.toString(), "info").assertPipeSafe()
        assertEquals(ExitStatus.OK, result.status, result.stderr)
        for (line in listOf("store", dir.toString(), "documents", "4", "deleted documents", "types", "4.0 KiB", "nfc", "note")) {
            assertContains(result.stdout, line)
        }
        assertContains(result.stdout, "Customer")
        assertContains(result.stdout, "Order")
    }

    @Test
    fun `info --json is one object with the same facts`() {
        val dir = store()
        val result = program.record("info", "--json", "-s", dir.toString()).assertPipeSafe()
        assertEquals(ExitStatus.OK, result.status, result.stderr)
        val json = result.stdout
        assertEquals(1, json.lines().filter { it.isNotEmpty() }.size, json)
        assertContains(json, """"approximateDocumentCount":4,"deletedDocumentCount":1,"diskBytes":""")
        assertContains(json, """{"name":"Order","approximateCount":""")
        assertContains(
            json,
            """"creationOptions":{"fragmentBytes":4096,"hashTerms":true,"normalization":"nfc","indexedKeys":[],""" +
                """"excludedKeys":["note"],"minIndexedStringBytes":0,"maxIndexedStringBytes":2147483647,"documentKey":null}""",
        )
    }

    @Test
    fun `without --store it is a usage error`() {
        val result = program.record("info")
        assertEquals(ExitStatus.USAGE, result.status)
        assertEquals("error: no store given: pass --store <dir>\nusage: delva info [options]\n", result.stderr)
    }

    @Test
    fun `a path that holds no store is exit 66, and nothing is written there`() {
        val missing = root.resolve("missing")
        val result = program.record("-s", missing.toString(), "info")
        assertEquals(ExitStatus.NO_INPUT, result.status)
        assertEquals("error: no store at $missing\n", result.stderr)
        assertFalse(missing.exists())

        val documents = root.resolve("documents").createDirectories()
        documents.resolve("notes.txt").writeText("not a store")
        assertEquals(ExitStatus.NO_INPUT, program.record("-s", documents.toString(), "info").status)
        assertEquals(listOf("notes.txt"), documents.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun `info works while the store is open elsewhere and names its writer, which a write command waits for`() {
        val dir = store()
        assertContains(program.record("-s", dir.toString(), "info").stdout, Regex("""writer\s+none"""))
        assertContains(program.record("-s", dir.toString(), "info", "--json").stdout, """"writer":null""")
        JsonStore.open(dir).use {
            val pid = ProcessHandle.current().pid()
            val result = program.record("-s", dir.toString(), "info")
            assertEquals(ExitStatus.OK, result.status, result.stderr)
            assertContains(result.stdout, Regex("""writer\s+process $pid"""))
            assertContains(program.record("-s", dir.toString(), "info", "--json").stdout, """"writer":{"pid":$pid,""")

            val write = program.record("-s", dir.toString(), "types", "assign", "1:0", "Order")
            assertEquals(ExitStatus.TEMP_FAIL, write.status)
            val holder = Regex("""error: \Q$dir\E is open in another process \(process $pid( on [^,)]+)?(, started [^)]+)?\)\n""")
            assertTrue(holder.matches(write.stderr), write.stderr)
        }
    }
}
