package app.oreshkov.delva.cli

import app.oreshkov.clikit.ExitStatus
import app.oreshkov.clikit.Program
import app.oreshkov.clikit.testing.RecordingTerminalIo
import app.oreshkov.clikit.testing.RunResult
import app.oreshkov.clikit.testing.record
import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.InsertSource
import app.oreshkov.delva.JsonStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** get, search, explain, path and types against a small store, as a user and as a script would call them. */
class ReadCommandsTest {
    private val root: Path = Files.createTempDirectory("delva-cli-read")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    /** Three orders (docs 1–3) with typed customers, and a document with a non-ASCII name (doc 4). */
    private val store: Path = root.resolve("store").also { dir ->
        JsonStore.create(dir, CreationOptions(fragmentBytes = 4096)).use { store ->
            store.blocking().insertAll(
                listOf(
                    """{"order":1,"status":"open","customer":{"name":"ada"},"items":[{"sku":"A1","qty":2},{"sku":"B2","qty":0}]}""",
                    """{"order":2,"status":"lost","customer":{"name":"bob"},"items":[{"sku":"A1","qty":1}]}""",
                    """{"order":3,"status":"open","customer":{"name":"ada"},"items":[]}""",
                ).map { InsertSource(it.byteInputStream(), mapOf("$.customer" to "Customer")) },
            )
            store.blocking().insert("""{"name":"Жана","city":"София"}""")
        }
    }

    private val program: Program = DelvaProgram.create(mapOf("DELVA_STORE" to store.toString())::get)

    private fun run(vararg args: String): RunResult = program.record(*args).assertPipeSafe()

    // ---------------------------------------------------------------------------------------------------- get

    @Test
    fun `get prints the document compact down a pipe, and indented when asked`() {
        val result = run("get", "2")
        assertEquals(ExitStatus.OK, result.status, result.stderr)
        assertEquals("""{"order":2,"status":"lost","customer":{"name":"bob"},"items":[{"sku":"A1","qty":1}]}""" + "\n", result.stdout)

        val pretty = run("get", "2", "--pretty", "--line-width", "40")
        assertContains(pretty.stdout, "{\n  \"order\": 2,")
        assertContains(pretty.stdout, "  \"customer\": {\"name\": \"bob\"},")
    }

    @Test
    fun `get keeps non-ASCII text intact down a pipe`() {
        assertEquals("""{"name":"Жана","city":"София"}""" + "\n", run("get", "4").stdout)
    }

    @Test
    fun `get --path prints each selected node on its line, and --json gives each its path`() {
        assertEquals("\"A1\"\n\"B2\"\n", run("get", "1", "--path", "$.items[*].sku").stdout)
        assertEquals(
            """{"path":"${'$'}['items'][0]['sku']","value":"A1"}""" + "\n",
            run("get", "1", "-p", "$.items[0].sku", "--json").stdout,
        )
    }

    @Test
    fun `--line-width indents down a pipe, as --pretty does`() {
        val result = run("get", "2", "--line-width", "0")
        assertEquals(ExitStatus.OK, result.status, result.stderr)
        assertContains(result.stdout, "{\n  \"order\": 2,")
        assertContains(result.stdout, "  \"customer\": {\n    \"name\": \"bob\"\n  },")
    }

    @Test
    fun `--max-levels outlines a document`() {
        assertEquals("""{"order": 1, "status": "open", "customer": {...}, "items": [...]}""" + "\n", run("get", "1", "--max-levels", "0", "--line-width", "200").stdout)
    }

    @Test
    fun `get of a missing document is 66, and a selection of nothing is 0, or 1 with -e`() {
        assertEquals(ExitStatus.NO_INPUT, program.record("get", "99").status)
        val nothing = run("get", "1", "--path", "$.nope")
        assertEquals(ExitStatus.OK, nothing.status)
        assertEquals("", nothing.stdout)
        assertContains(nothing.stderr, "selects nothing")
        assertEquals(ExitStatus.FAILURE, run("get", "1", "--path", "$.nope", "-e").status)
    }

    @Test
    fun `contradictory format flags are usage errors, with the usage line`() {
        for ((args, message) in listOf(
            arrayOf("get", "1", "--pretty", "--compact") to "--pretty cannot be combined with --compact",
            arrayOf("get", "1", "--json", "--pretty") to "--json cannot be combined with --pretty",
            arrayOf("--json", "get", "1", "--max-levels", "2") to "--json cannot be combined with --max-levels",
            arrayOf("get", "1", "--compact", "--line-width", "60") to "--compact cannot be combined with --line-width",
            arrayOf("get", "1", "--line-width", "60", "--json") to "--json cannot be combined with --line-width",
        )) {
            val result = program.record(*args)
            assertEquals(ExitStatus.USAGE, result.status, args.joinToString(" "))
            assertEquals("error: $message\nusage: delva get [options] [doc]\n", result.stderr)
        }
        assertEquals(ExitStatus.USAGE, program.record("get", "x").status)
    }

    // ------------------------------------------------------------------------------------------------- search

    @Test
    fun `search kv takes JSON, or text with --string, and says which it needs`() {
        assertEquals(run("search", "kv", "status", "\"open\"").stdout, run("search", "kv", "status", "open", "--string").stdout)
        val notJson = program.record("search", "kv", "status", "open")
        assertEquals(ExitStatus.USAGE, notJson.status)
        assertContains(notJson.stderr, "add --string")
        assertEquals(ExitStatus.OK, run("search", "kv", "order", "2").status)
    }

    @Test
    fun `search hits down a pipe are a plain table with a preview of each`() {
        val result = run("search", "kv", "name", "ada", "-S", "--mode", "element")
        assertEquals(ExitStatus.OK, result.status, result.stderr)
        val lines = result.stdout.lines().filter { it.isNotBlank() }
        assertEquals(3, lines.size, result.stdout)
        assertContains(lines[0], "NODE")
        assertContains(lines[1], "\$['customer']")
        assertContains(lines[1], "Customer")
        assertContains(lines[1], """{"name": "ada"}""")
    }

    @Test
    fun `search --json is one record per hit, with its cursor and its whole value`() {
        val result = run("search", "kv", "sku", "\"A1\"", "--mode", "element", "--json")
        val records = result.stdout.lines().filter { it.isNotEmpty() }
        assertEquals(2, records.size)
        assertTrue(records[0].startsWith("""{"doc":1,"node":"""), records[0])
        assertContains(records[0], """"path":"${'$'}['items'][0]","type":null,""")
        assertContains(records[0], """"value":{"sku":"A1","qty":2}}""")
        assertContains(records[0], """"cursor":"""")
    }

    @Test
    fun `--nested needs --mode element, which has hits inside hits`() {
        for ((args, message) in listOf(
            arrayOf("search", "kv", "sku", "\"A1\"", "--nested", "outermost") to "--nested requires --mode element",
            arrayOf("search", "kv", "sku", "\"A1\"", "--nested", "all", "--mode", "document") to
                "--nested requires --mode element, but --mode is 'document'",
        )) {
            val result = program.record(*args)
            assertEquals(ExitStatus.USAGE, result.status, args.joinToString(" "))
            assertEquals("error: $message\nusage: delva search kv [options] <key> <value>\n", result.stderr)
        }
        assertEquals(ExitStatus.USAGE, program.record("search", "path", "$..sku", "--nested", "outermost").status)
        val element = run("search", "kv", "sku", "\"A1\"", "--mode", "element", "--nested", "outermost", "--json")
        assertEquals(ExitStatus.OK, element.status, element.stderr)
        assertEquals(2, element.stdout.lines().count { it.isNotEmpty() }, element.stdout)
    }

    @Test
    fun `a limit prints one page and says how to get the next, and the cursor resumes it`() {
        val first = run("search", "kv", "status", "open", "-S", "-n", "1", "--json")
        assertEquals(1, first.stdout.lines().count { it.isNotEmpty() })
        val cursor = Regex("more: --after (\\S+)").find(first.stderr)?.groupValues?.get(1) ?: error(first.stderr)
        val rest = run("search", "kv", "status", "open", "-S", "--after", cursor, "--json")
        assertEquals(1, rest.stdout.lines().count { it.isNotEmpty() })
        assertContains(rest.stdout, """"doc":3""")
        assertFalse("more:" in rest.stderr)
    }

    @Test
    fun `newest first is one hit with the latest document`() {
        val result = run("search", "kv", "status", "open", "-S", "--order", "desc", "-n", "1", "--json")
        assertTrue(result.stdout.startsWith("""{"doc":3,"""), result.stdout)
    }

    @Test
    fun `no hits is exit 0 and a notice, or 1 with --exit-status`() {
        val none = run("search", "kv", "status", "gone", "-S")
        assertEquals(ExitStatus.OK, none.status)
        assertEquals("", none.stdout)
        assertContains(none.stderr, "no hits")
        assertEquals(ExitStatus.FAILURE, run("search", "kv", "status", "gone", "-S", "-e").status)
    }

    @Test
    fun `--count prints how many hits there are, at most --limit, and refuses --max-bytes`() {
        assertEquals("2\n", run("search", "kv", "status", "open", "-S", "--count").stdout)
        assertEquals("1\n", run("search", "kv", "status", "open", "-S", "-c", "-n", "1").stdout)
        assertEquals("""{"count":2}""" + "\n", run("search", "path", "$.items[?@.sku == 'A1']", "--mode", "element", "-c", "--json").stdout)
        val none = run("search", "kv", "status", "gone", "-S", "-c", "-e")
        assertEquals(ExitStatus.FAILURE, none.status)
        assertEquals("0\n", none.stdout)
        assertEquals(ExitStatus.USAGE, program.record("search", "kv", "status", "open", "-S", "-c", "--max-bytes", "1K").status)
        assertEquals(ExitStatus.USAGE, program.record("search", "path", "$..sku", "-c").status, "a full scan is still refused")
    }

    @Test
    fun `search path finds what a JSONPath query selects, and refuses a full scan unless asked`() {
        val outOfStock = run("search", "path", "$.items[?@.qty < 1].sku", "--json")
        assertEquals(1, outOfStock.stdout.lines().count { it.isNotEmpty() }, outOfStock.stdout)
        assertContains(outOfStock.stdout, """"doc":1""")
        assertContains(outOfStock.stdout, """"value":{"order":1""")

        val scan = program.record("search", "path", "$..sku")
        assertEquals(ExitStatus.USAGE, scan.status)
        assertContains(scan.stderr, "error: no index term narrows the search for $..sku, so it would read every document; add --scan")
        assertFalse("ScanPolicy" in scan.stderr || "Query rejected" in scan.stderr, scan.stderr)
        assertEquals(ExitStatus.OK, run("search", "path", "$..sku", "--scan").status)
        assertEquals(ExitStatus.USAGE, program.record("search", "path", "$[").status)
    }

    @Test
    fun `a search the store refuses is worded in delva's options, with the status its remedy calls for`() {
        // Too long to search, with or without --scan; get --path takes it on one document.
        val long = "$" + "[0]".repeat(31) + "[?@.a == 1]"
        val tooLong = program.record("search", "path", long, "--scan")
        assertEquals(ExitStatus.USAGE, tooLong.status)
        assertContains(tooLong.stderr, "error: $long has 32 segments; a search takes at most 31; delva get <doc> --path")
        assertFalse("Query rejected" in tooLong.stderr, tooLong.stderr)

        // Only a store created with other options answers a key it doesn't index.
        val excluding = root.resolve("excluding")
        JsonStore.create(excluding, CreationOptions(fragmentBytes = 4096, excludedKeys = setOf("status"))).use { it.blocking().insert("""{"status":"open"}""") }
        val notIndexed = DelvaProgram.create(mapOf("DELVA_STORE" to excluding.toString())::get).record("search", "kv", "status", "open", "-S")
        assertEquals(ExitStatus.DATA_ERR, notIndexed.status)
        assertContains(notIndexed.stderr, "error: the key \"status\" isn't indexed by this store; what a store indexes is fixed when it is created, and delva info shows it")
        assertFalse("CreationOptions" in notIndexed.stderr, notIndexed.stderr)
    }

    @Test
    fun `search path --where searches only the documents whose root passes the filter`() {
        // The filter's term narrows a query that alone would need --scan.
        val lost = run("search", "path", "$..sku", "--where", "@.status == 'lost'", "--mode", "element", "--json")
        assertEquals(1, lost.stdout.lines().count { it.isNotEmpty() }, lost.stdout)
        assertContains(lost.stdout, """"doc":2""")
        assertEquals("2\n", run("search", "path", "$..sku", "--where", "\$.customer.name == 'ada'", "--mode", "element", "-c").stdout)
        assertEquals(
            """{"fullScan":false,"terms":"'status' == 'lost'","within":null}""" + "\n",
            run("explain", "$..sku", "--where", "@.status == 'lost'", "--json").stdout,
        )
        val bad = program.record("search", "path", "$..sku", "--where", "@.status ==")
        assertEquals(ExitStatus.USAGE, bad.status)
        assertContains(bad.stderr, "at 11")
    }

    // ------------------------------------------------------------------------------------ explain, path, types

    @Test
    fun `explain shows the plan, as fields or JSON`() {
        val plan = run("explain", "$..[?@.qty < 1].sku")
        assertContains(plan.stdout, "terms")
        assertContains(plan.stdout, "'qty' < 1")
        assertEquals("""{"fullScan":true,"terms":null,"within":null}""" + "\n", run("explain", "$..sku", "--json").stdout)
    }

    @Test
    fun `path names a node, and a missing one is a failure`() {
        val hit = run("search", "kv", "sku", "\"B2\"", "--mode", "element", "--json").stdout
        val node = Regex(""""node":(\d+)""").find(hit)!!.groupValues[1]
        assertEquals("\$['items'][1]\n", run("path", "1:$node").stdout)
        assertEquals(ExitStatus.DATA_ERR, program.record("path", "1:999").status)
        assertEquals(ExitStatus.USAGE, program.record("path", "1").status)
    }

    @Test
    fun `types list, count and objects`() {
        assertContains(run("types", "list").stdout, "Customer")
        assertEquals("3\n", run("types", "count", "Customer").stdout)

        val objects = run("types", "objects", "Customer").stdout.lines().filter { it.isNotEmpty() }
        assertEquals(3, objects.size)
        assertTrue(objects.all { Regex("\\d+:\\d+").matches(it) }, objects.toString())

        val page = run("types", "objects", "Customer", "-n", "2")
        assertEquals(objects.take(2), page.stdout.lines().filter { it.isNotEmpty() })
        assertContains(page.stderr, "more: --after ${objects[1]}")
        assertEquals(objects.drop(2), run("types", "objects", "Customer", "--after", objects[1]).stdout.lines().filter { it.isNotEmpty() })

        assertEquals(ExitStatus.FAILURE, run("types", "objects", "Nobody", "-e").status)
        assertEquals(ExitStatus.DATA_ERR, program.record("types", "count", "").status)
    }

    // ----------------------------------------------------------------------------------- beside a writer

    @Test
    fun `every read command works while another store has the store open, and sees what it wrote`() {
        JsonStore.open(store).use { writer ->
            writer.blocking().insert("""{"order":5,"status":"open","customer":{"name":"cy"},"items":[]}""")
            val commands = listOf(
                listOf("info"),
                listOf("get", "5"),
                listOf("get", "1", "--path", "$.items[*].sku"),
                listOf("search", "kv", "status", "\"open\"", "--count"),
                listOf("search", "path", "$.items[?@.qty > 0].sku"),
                listOf("explain", "$.items[?@.qty > 0].sku"),
                listOf("path", "1:4"),
                listOf("types", "list"),
                listOf("types", "count", "Customer"),
                listOf("types", "objects", "Customer"),
                listOf("compare", "1", "3"),
                listOf("verify"),
            )
            for (command in commands) {
                val result = program.record(*command.toTypedArray())
                assertTrue(result.status == ExitStatus.OK || (command[0] == "compare" && result.status == ExitStatus.FAILURE), "$command: ${result.status} ${result.stderr}")
            }
            assertEquals("3\n", run("search", "kv", "status", "\"open\"", "--count").stdout, "the writer's insert, acknowledged before the command")
            // Writes still need the store to themselves.
            assertEquals(ExitStatus.TEMP_FAIL, program.record("delete", "2", "--force").status)
        }
    }

    // ------------------------------------------------------------------------------------------- environment

    @Test
    fun `DELVA_STORE names the store, and --store overrides it`() {
        assertEquals(ExitStatus.OK, run("info").status)
        val elsewhere = root.resolve("elsewhere")
        assertEquals(ExitStatus.NO_INPUT, program.record("-s", elsewhere.toString(), "info").status)
        assertEquals(ExitStatus.USAGE, DelvaProgram.create { null }.record("info").status)
    }

    @Test
    fun `at a terminal the JSON is indented to the terminal's width`() {
        val io = RecordingTerminalIo(interactive = true, width = 60)
        val result = program.record("get", "1", io = io)
        assertEquals(ExitStatus.OK, result.status)
        assertTrue(result.stdout.startsWith("{\n  \"order\": 1,"), result.stdout)
    }
}
