package app.oreshkov.delva.cli

import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.Program
import app.oreshkov.clikit.testing.RunResult
import app.oreshkov.clikit.testing.record
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** init --document-key, insert and --upsert, get --key, search key and info on a store with document keys. */
class DocumentKeyCommandsTest {
    private val root: Path = Files.createTempDirectory("delva-cli-keys")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private val store: Path = root.resolve("store")

    private val program: Program = DelvaProgram.create(mapOf("DELVA_STORE" to store.toString())::get)

    private fun run(vararg args: String): RunResult = program.record(*args).assertPipeSafe()

    private fun file(name: String, json: String): String = root.resolve(name).also { it.writeText(json) }.toString()

    /** A keyed store with `order-1` (doc 1), `order-2` (doc 2) and `invoice-9` (doc 3). */
    private fun keyedStore() {
        assertEquals(ExitStatus.OK, run("init", store.toString(), "--document-key", "$.id").status)
        val result = run(
            "insert",
            file("a.json", """{"id":"order-1","v":1}"""),
            file("b.json", """{"id":"order-2","v":2}"""),
            file("c.json", """{"id":"invoice-9","v":3}"""),
        )
        assertEquals(ExitStatus.OK, result.status, result.stderr)
        assertEquals("1\n2\n3\n", result.stdout)
    }

    @Test
    fun `a taken key isn't inserted unless --upsert replaces its document`() {
        keyedStore()
        val taken = run("insert", file("a2.json", """{"id":"order-1","v":10}"""))
        assertEquals(ExitStatus.NO_INPUT, taken.status)
        assertContains(taken.stderr, "document 1 has its key; add --upsert to replace it")
        val upsert = run("insert", "--upsert", file("a3.json", """{"id":"order-1","v":11}"""), file("d.json", """{"id":"new","v":4}"""))
        assertEquals(ExitStatus.OK, upsert.status, upsert.stderr)
        assertEquals("5\n6\n", upsert.stdout)
        assertEquals("""{"id":"order-1","v":11}""" + "\n", run("get", "--key", "order-1").stdout)
        val missing = run("insert", file("e.json", """{"v":5}"""))
        assertEquals(ExitStatus.DATA_ERR, missing.status)
        assertContains(missing.stderr, "KEY_MISSING")
    }

    @Test
    fun `get finds a document by its key, and takes an id or a key, not both`() {
        keyedStore()
        assertEquals("""{"id":"order-2","v":2}""" + "\n", run("get", "-k", "order-2").stdout)
        assertEquals("\"order-2\"\n", run("get", "--key", "order-2", "--path", "$.id").stdout)
        val none = run("get", "--key", "nothing")
        assertEquals(ExitStatus.OK, none.status)
        assertContains(none.stderr, "no document has the key nothing")
        assertEquals(ExitStatus.FAILURE, run("get", "--key", "nothing", "-e").status)
        for ((args, message) in listOf(
            arrayOf("get") to "exactly one of <doc> and --key is required",
            arrayOf("get", "1", "--key", "order-1") to "<doc> cannot be combined with --key",
        )) {
            val result = run(*args)
            assertEquals(ExitStatus.USAGE, result.status, args.joinToString(" "))
            assertEquals("error: $message\nusage: delva get [options] [doc]\n", result.stderr)
        }
    }

    @Test
    fun `search key lists documents by key, with the key in every hit`() {
        keyedStore()
        val all = run("search", "key", "--json")
        assertEquals(ExitStatus.OK, all.status, all.stderr)
        val keys = all.stdout.lines().filter { it.isNotEmpty() }.map { Regex(""""key":"([^"]*)"""").find(it)!!.groupValues[1] }
        assertEquals(listOf("invoice-9", "order-1", "order-2"), keys)
        val orders = run("search", "key", "order-", "--order", "desc")
        assertContains(orders.stdout, "KEY")
        assertTrue(orders.stdout.indexOf("order-2") < orders.stdout.indexOf("order-1"), orders.stdout)
        assertEquals("2\n", run("search", "key", "order-", "--count").stdout)
        // A page, then the rest from its cursor.
        val first = run("search", "key", "-n", "1")
        val cursor = Regex("more: --after (\\S+)").find(first.stderr)!!.groupValues[1]
        assertContains(run("search", "key", "--after", cursor, "--json").stdout, "order-1")
        // Hits of the other searches carry the key too.
        assertContains(run("search", "kv", "v", "2", "--json").stdout, """"key":"order-2"""")
    }

    @Test
    fun `info shows the key path, and key commands need one`() {
        keyedStore()
        assertContains(run("info").stdout, "$.id")
        assertContains(run("info", "--json").stdout, """"documentKey":"$.id"""")
        val plain = root.resolve("plain")
        assertEquals(ExitStatus.OK, run("init", plain.toString()).status)
        assertEquals(ExitStatus.USAGE, run("-s", plain.toString(), "search", "key").status)
        assertEquals(ExitStatus.USAGE, run("-s", plain.toString(), "get", "--key", "a").status)
        assertEquals(ExitStatus.USAGE, run("-s", plain.toString(), "insert", "--upsert", file("x.json", """{"id":"a"}""")).status)
        assertEquals(ExitStatus.USAGE, run("init", root.resolve("bad").toString(), "--document-key", "$").status)
    }

    @Test
    fun `a key that holds terminal controls is shown escaped, not obeyed`() {
        assertEquals(ExitStatus.OK, run("init", store.toString(), "--document-key", "$.id").status)
        // The key is ESC ] 0 ; x BEL, which sets a terminal's title, then ESC [ 2 J, which clears its screen.
        val key = "${Char(0x1B)}]0;x${Char(0x07)}${Char(0x1B)}[2J"
        val json = "{\"id\":\"" + key.map { "\\" + "u%04x".format(it.code) }.joinToString("") + "\"}"
        assertEquals(ExitStatus.OK, run("insert", file("hostile.json", json)).status)

        val table = run("search", "key")
        assertContains(table.stdout, "\\" + "u001B]0;x" + "\\" + "u0007")
        assertTrue(Char(0x1B) !in table.stdout && Char(0x07) !in table.stdout, table.stdout)
        val missing = run("get", "--key", key + "!")
        assertContains(missing.stderr, "no document has the key " + "\\" + "u001B]0;x")
        assertTrue(Char(0x1B) !in missing.stderr, missing.stderr)
    }
}
