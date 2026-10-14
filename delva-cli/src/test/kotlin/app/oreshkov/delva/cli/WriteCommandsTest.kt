package app.oreshkov.delva.cli

import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.Program
import app.oreshkov.clikit.testing.RecordingTerminalIo
import app.oreshkov.clikit.testing.RunResult
import app.oreshkov.clikit.testing.record
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.Normalization
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** init, insert, delete, reset, types assign and clear, verify and compare. */
class WriteCommandsTest {
    private val root: Path = Files.createTempDirectory("delva-cli-write")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private val store: Path = root.resolve("store")
    private val program: Program = DelvaProgram.create(mapOf("DELVA_STORE" to store.toString())::get)

    private fun run(vararg args: String): RunResult = program.record(*args).assertPipeSafe()

    private fun file(name: String, json: String): Path = root.resolve(name).also { it.writeText(json) }

    private fun initialized(): Path {
        assertEquals(ExitStatus.OK, run("init", store.toString()).status)
        return store
    }

    private fun ids(result: RunResult): List<Long> = result.stdout.lines().filter { it.isNotBlank() }.map { it.trim().toLong() }

    // ---------------------------------------------------------------------------------------------------- init

    @Test
    fun `init creates a store with the options given, and refuses to create one twice`() {
        val created = run("init", store.toString(), "--normalization", "nfc", "--fragment-size", "4K", "--exclude-key", "note", "--json")
        assertEquals(ExitStatus.OK, created.status, created.stderr)
        assertContains(created.stdout, """"fragmentBytes":4096""")
        JsonStore.open(store).use { opened ->
            assertEquals(Normalization.NFC, opened.creationOptions.normalization)
            assertEquals(setOf("note"), opened.creationOptions.excludedKeys)
        }
        val again = program.record("init", store.toString())
        assertEquals(ExitStatus.CONFIG, again.status)
        assertContains(again.stderr, "there is already a store")
    }

    @Test
    fun `init with options delva refuses is a usage error, and creates nothing`() {
        val result = program.record("init", store.toString(), "--fragment-size", "1B")
        assertEquals(ExitStatus.USAGE, result.status)
        assertContains(result.stderr, "fragmentBytes")
        assertFalse(store.exists())
    }

    // -------------------------------------------------------------------------------------------------- insert

    @Test
    fun `insert prints the ids in the order of the files, and the documents are there`() {
        initialized()
        val a = file("a.json", """{"n":1}""")
        val b = file("b.json", """{"n":2,"name":"Жана"}""")
        val inserted = run("insert", a.toString(), b.toString())
        assertEquals(ExitStatus.OK, inserted.status, inserted.stderr)
        val ids = ids(inserted)
        assertEquals(2, ids.size)
        assertEquals("""{"n":2,"name":"Жана"}""" + "\n", run("get", ids[1].toString()).stdout)
    }

    @Test
    fun `insert reads standard input for -, and gives types by path`() {
        initialized()
        val result = program.record(
            "insert", "-", "-t", "$=Order", "-t", "$.customer=Customer",
            stdin = """{"order":7,"customer":{"name":"ada"}}""".toByteArray(),
        )
        assertEquals(ExitStatus.OK, result.status, result.stderr)
        assertEquals("1\n", run("types", "count", "Order").stdout)
        assertEquals("1\n", run("types", "count", "Customer").stdout)
    }

    @Test
    fun `a rejected or missing file is reported, the rest go in, and the status says which`() {
        initialized()
        val good = file("good.json", """{"ok":true}""")
        val bad = file("bad.json", """{"ok":""")
        val rejected = run("insert", good.toString(), bad.toString(), "--json")
        assertEquals(ExitStatus.DATA_ERR, rejected.status)
        assertContains(rejected.stderr, "bad.json")
        assertContains(rejected.stdout, """{"source":"$good","doc":1}""".replace("\\", "\\\\"))
        assertContains(rejected.stdout, """"rejected":"MALFORMED_JSON"""")

        val missing = run("insert", root.resolve("nope.json").toString(), good.toString())
        assertEquals(ExitStatus.NO_INPUT, missing.status)
        assertContains(missing.stderr, "no such file")
        assertEquals(1, ids(missing).size)
        assertEquals(ExitStatus.USAGE, program.record("insert", "-", "-").status)
    }

    @Test
    fun `insert --replace puts in a new version and deletes the old one, and refuses one that is gone`() {
        initialized()
        val (v1, other) = ids(run("insert", file("v1.json", """{"v":1}""").toString(), file("other.json", """{"v":0}""").toString()))
        val replaced = run("insert", file("v2.json", """{"v":2}""").toString(), "--replace", v1.toString(), "-t", "$=Order")
        assertEquals(ExitStatus.OK, replaced.status, replaced.stderr)
        val v2 = ids(replaced).single()
        assertEquals("""{"v":2}""" + "\n", run("get", v2.toString()).stdout)
        assertEquals(ExitStatus.NO_INPUT, program.record("get", v1.toString()).status)
        assertEquals("1\n", run("types", "count", "Order").stdout)

        // v1 is gone: nothing changes, and the status says an input was missing.
        val conflict = run("insert", file("v3.json", """{"v":3}""").toString(), "--replace", v1.toString(), "--replace", other.toString(), "--json")
        assertEquals(ExitStatus.NO_INPUT, conflict.status)
        assertContains(conflict.stderr, "no document to replace: $v1")
        assertContains(conflict.stdout, """"conflict":"$v1"""")
        assertEquals("""{"v":0}""" + "\n", run("get", other.toString()).stdout)

        val twoFiles = program.record("insert", file("a.json", "{}").toString(), file("b.json", "{}").toString(), "--replace", v2.toString())
        assertEquals(ExitStatus.USAGE, twoFiles.status)
        assertContains(twoFiles.stderr, "--replace takes one file, not 2")
        assertEquals(ExitStatus.USAGE, program.record("insert", "-", "--replace", "x").status)
    }

    // -------------------------------------------------------------------------------------------------- delete

    private fun threeDocuments(): List<Long> {
        initialized()
        return ids(run("insert", file("1.json", "{}").toString(), file("2.json", "[]").toString(), file("3.json", "7").toString()))
    }

    @Test
    fun `delete without a terminal needs --force, and a dry run deletes nothing`() {
        val ids = threeDocuments()
        val refused = program.record("delete", ids[0].toString())
        assertEquals(ExitStatus.USAGE, refused.status)
        assertContains(refused.stderr, "pass --force")

        val dry = run("delete", ids[0].toString(), "99", "--dry-run", "--json")
        assertEquals(ExitStatus.OK, dry.status)
        assertEquals("""{"wouldDelete":[${ids[0]}],"missing":[99]}""" + "\n", dry.stdout)
        assertEquals(ExitStatus.OK, run("get", ids[0].toString()).status)

        val forced = run("delete", ids[0].toString(), "--force")
        assertEquals(ExitStatus.OK, forced.status)
        assertContains(forced.stderr, "deleted 1 document")
        assertEquals(ExitStatus.NO_INPUT, program.record("get", ids[0].toString()).status)
    }

    @Test
    fun `delete asks at a terminal, and no leaves everything as it was`() {
        val ids = threeDocuments()
        val no = program.record("delete", ids[1].toString(), io = RecordingTerminalIo(answers = listOf("n")))
        assertEquals(ExitStatus.FAILURE, no.status)
        assertContains(no.stderr, "delete 1 document? [y/N] n")
        assertEquals(ExitStatus.OK, run("get", ids[1].toString()).status)

        val yes = program.record("delete", ids[1].toString(), io = RecordingTerminalIo(answers = listOf("y")))
        assertEquals(ExitStatus.OK, yes.status)
        assertEquals(ExitStatus.NO_INPUT, program.record("get", ids[1].toString()).status)
    }

    @Test
    fun `delete reads ids from standard input, and says which were missing`() {
        val ids = threeDocuments()
        val result = program.record("delete", "-", "--force", "--json", stdin = "${ids[0]}\n${ids[2]} 42\n".toByteArray())
        assertEquals(ExitStatus.OK, result.status, result.stderr)
        assertEquals("""{"deleted":[${ids[0]},${ids[2]}],"missing":[42]}""" + "\n", result.stdout)
        assertEquals(ExitStatus.NO_INPUT, program.record("delete", "42", "--force").status)
        assertEquals(ExitStatus.USAGE, program.record("delete", "-", "--force", stdin = "x".toByteArray()).status)
    }

    // --------------------------------------------------------------------------------------------------- reset

    private fun documentCount(): Long = JsonStore.open(store).use { it.info().approximateDocumentCount }

    @Test
    fun `reset without a terminal needs --force, and leaves the store as init created it`() {
        assertEquals(ExitStatus.OK, run("init", store.toString(), "--normalization", "nfc").status)
        run("insert", file("1.json", "{}").toString(), file("2.json", "[]").toString())
        val refused = program.record("reset")
        assertEquals(ExitStatus.USAGE, refused.status)
        assertContains(refused.stderr, "pass --force")
        assertEquals(2, documentCount())

        val reset = run("reset", "--force", "--json")
        assertEquals(ExitStatus.OK, reset.status, reset.stderr)
        assertContains(reset.stdout, """"creationOptions":{""")
        assertContains(reset.stdout, """"normalization":"nfc"""")
        assertEquals(0, documentCount())
        JsonStore.open(store).use { assertEquals(Normalization.NFC, it.creationOptions.normalization) }
        assertEquals(listOf(1L), ids(run("insert", file("3.json", "{}").toString())))
    }

    @Test
    fun `reset asks at a terminal with the number of documents, and no changes nothing`() {
        val ids = threeDocuments()
        val no = program.record("reset", io = RecordingTerminalIo(answers = listOf("n")))
        assertEquals(ExitStatus.FAILURE, no.status)
        assertContains(no.stderr, "reset $store, deleting 3 documents? [y/N] n")
        assertEquals(ExitStatus.OK, run("get", ids[0].toString()).status)

        val yes = program.record("reset", io = RecordingTerminalIo(answers = listOf("y")))
        assertEquals(ExitStatus.OK, yes.status, yes.stderr)
        assertContains(yes.stderr, "it is empty")
        assertEquals(ExitStatus.NO_INPUT, program.record("get", ids[0].toString()).status)
    }

    @Test
    fun `reset of a missing store fails before asking, and one that doesn't open is still reset`() {
        val missing = program.record("reset")
        assertEquals(ExitStatus.NO_INPUT, missing.status)
        assertContains(missing.stderr, "no store at")
        assertFalse(store.exists())

        threeDocuments()
        Files.walk(store).use { paths -> paths.filter { it.isRegularFile() && it.name.startsWith("ctl-") }.toList() }.forEach(Files::delete)
        assertEquals(ExitStatus.IO_ERR, program.record("info").status)
        val reset = run("reset", "--force")
        assertEquals(ExitStatus.OK, reset.status, reset.stderr)
        assertContains(reset.stderr, "the store doesn't open")
        assertEquals(0, documentCount())
    }

    // --------------------------------------------------------------------------------------------------- types

    @Test
    fun `types assign and clear`() {
        initialized()
        val id = ids(run("insert", file("c.json", """{"customer":{"name":"ada"}}""").toString())).single()
        val node = Regex(""""node":(\d+)""").find(run("search", "kv", "name", "ada", "-S", "--mode", "element", "--json").stdout)!!.groupValues[1]
        assertEquals(ExitStatus.OK, run("types", "assign", "$id:$node", "Customer").status)
        assertEquals("$id:$node\n", run("types", "objects", "Customer").stdout)
        assertEquals(ExitStatus.OK, run("types", "clear", "$id:$node").status)
        assertEquals("0\n", run("types", "count", "Customer").stdout)
    }

    // -------------------------------------------------------------------------------------------------- verify

    @Test
    fun `verify reports an intact store, and damage with exit 1`() {
        initialized()
        val docs = (1..300).map { file("d$it.json", """{"id":$it,"tags":["a","b"],"note":"${"x".repeat(it % 50)}"}""").toString() }
        assertEquals(ExitStatus.OK, run("insert", *docs.toTypedArray()).status)
        val intact = run("verify")
        assertEquals(ExitStatus.OK, intact.status, intact.stderr)
        assertContains(intact.stdout, "intact")

        // A flipped bit in the middle of the data lane's records (verify changes nothing, so the file keeps its
        // preallocated zeros after them): the store still opens, and verify finds the damage.
        val segment = Files.walk(store).use { paths ->
            paths.filter { it.isRegularFile() && it.name.startsWith("lane-") }.toList().maxBy { it.fileSize() }
        }
        val bytes = Files.readAllBytes(segment)
        val at = bytes.indexOfLast { it != 0.toByte() } / 2
        bytes[at] = (bytes[at].toInt() xor 0x10).toByte()
        Files.write(segment, bytes)

        val damaged = run("verify", "--json")
        assertEquals(ExitStatus.FAILURE, damaged.status, damaged.stderr)
        assertContains(damaged.stdout, """"intact":false""")
        assertContains(damaged.stdout, """"issues":[{""")
    }

    // ------------------------------------------------------------------------------------------------- compare

    @Test
    fun `compare says equal or different by exit status, and lists differences with --diff`() {
        initialized()
        val (v1, v2) = ids(run("insert", file("v1.json", """{"a":1,"b":[1,2],"at":"t1"}""").toString(), file("v2.json", """{"b":[1,2],"a":2,"at":"t2"}""").toString()))
        assertEquals(ExitStatus.FAILURE, run("compare", "$v1", "$v2").status)
        assertEquals(ExitStatus.OK, run("compare", "$v1", "$v1").status)

        val same = file("same.json", """{"at":"t1", "b":[1, 2], "a":1.0}""")
        assertEquals(ExitStatus.OK, run("compare", "$v1", same.toString()).status)
        assertEquals(ExitStatus.OK, program.record("compare", "-", "$v1", stdin = """{"a":1,"b":[1,2],"at":"t1"}""".toByteArray()).status)

        val diff = run("compare", "$v1", "$v2", "--diff", "--json")
        assertEquals(ExitStatus.FAILURE, diff.status)
        val records = diff.stdout.lines().filter { it.isNotEmpty() }
        assertEquals(2, records.size, diff.stdout)
        assertTrue(records.any { """"path":"${'$'}['a']"""" in it && """"kind":"changed"""" in it }, diff.stdout)

        assertEquals(1, run("compare", "$v1", "$v2", "--diff", "--ignore-path", "\$.at", "--json").stdout.lines().count { it.isNotEmpty() })
        assertEquals(ExitStatus.USAGE, program.record("compare", "-", "-").status)
    }

    @Test
    fun `a path given a type twice is a usage error`() {
        val result = program.record("insert", "-", "-t", "\$=Order", "-t", "\$=Invoice", stdin = "{}".toByteArray())
        assertEquals(ExitStatus.USAGE, result.status)
        assertContains(result.stderr, "error: --type gives '\$' twice\nusage: delva insert ")
    }

    @Test
    fun `standard input is taken once, and refused twice before any store is opened`() {
        for (args in listOf(arrayOf("insert", "-", "-"), arrayOf("delete", "-", "-", "--force"), arrayOf("compare", "-", "-"))) {
            val result = program.record(*args, stdin = "{}".toByteArray())
            assertEquals(ExitStatus.USAGE, result.status, args.joinToString(" "))
            assertContains(result.stderr, "error: standard input (-) can be read only once\nusage: delva ${args[0]} ")
        }
    }
}
