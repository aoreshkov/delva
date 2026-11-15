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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** init with a vector field, and search vector, on a store whose documents hold vectors of three numbers. */
class VectorCommandsTest {
    private val root: Path = Files.createTempDirectory("delva-cli-vectors")

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private val store: Path = root.resolve("store")

    private val program: Program = DelvaProgram.create(mapOf("DELVA_STORE" to store.toString())::get)

    private fun run(vararg args: String): RunResult = program.record(*args).assertPipeSafe()

    private fun file(name: String, text: String): String = root.resolve(name).also { it.writeText(text) }.toString()

    /**
     * A store of Euclidean vectors: two orders (docs 1 and 2), a note near the first order (doc 3), and a note without
     * a vector (doc 4). From `[1, 0, 0]` the documents are 1, 3 and 2, at 0, about 0.14 and about 1.41.
     */
    private fun vectorStore(vararg more: String) {
        val init = run(
            "init", store.toString(), "--vector-field", "$.embedding", "--vector-dimensions", "3", "--vector-metric", "euclidean",
            "--exclude-key", "embedding", *more,
        )
        assertEquals(ExitStatus.OK, init.status, init.stderr)
        val inserted = run(
            "insert",
            file("a.json", """{"id":"a","kind":"order","embedding":[1,0,0]}"""),
            file("b.json", """{"id":"b","kind":"order","embedding":[0,1,0]}"""),
            file("c.json", """{"id":"c","kind":"note","embedding":[0.9,0.1,0]}"""),
            file("d.json", """{"id":"d","kind":"note"}"""),
        )
        assertEquals(ExitStatus.OK, inserted.status, inserted.stderr)
        assertEquals("1\n2\n3\n4\n", inserted.stdout)
    }

    /** The `doc` of each JSON Lines record of [result], in order. */
    private fun docs(result: RunResult): List<Long> {
        assertEquals(ExitStatus.OK, result.status, result.stderr)
        return result.stdout.lines().filter { it.isNotEmpty() }.map { Regex("""^\{"doc":(\d+),""").find(it)!!.groupValues[1].toLong() }
    }

    @Test
    fun `init creates a store with a vector field, which info shows`() {
        vectorStore()
        assertContains(run("info").stdout, Regex("""vector field\s+\$\.embedding, 3 dimensions, euclidean\n"""))

        val required = root.resolve("required")
        val init = run(
            "init", required.toString(), "--json", "--vector-field", "$.e", "--vector-dimensions", "2", "--vector-metric", "dot-product",
            "--vector-required",
        )
        assertEquals(ExitStatus.OK, init.status, init.stderr)
        assertContains(init.stdout, """"vectorField":{"path":"$.e","dimensions":2,"metric":"dot_product","required":true}""")
        // Required: a document without a vector isn't inserted.
        val rejected = run("-s", required.toString(), "insert", file("none.json", """{"id":"x"}"""))
        assertEquals(ExitStatus.DATA_ERR, rejected.status, rejected.stderr)
        assertEquals(ExitStatus.OK, run("-s", required.toString(), "insert", file("some.json", """{"e":[1,2]}""")).status)
    }

    @Test
    fun `init takes a vector field's path, dimensions and metric together, or none of them`() {
        for ((args, message) in listOf(
            arrayOf("--vector-field", "$.e") to "--vector-field requires --vector-dimensions",
            arrayOf("--vector-field", "$.e", "--vector-dimensions", "3") to "--vector-field requires --vector-metric",
            arrayOf("--vector-dimensions", "3") to "--vector-dimensions requires --vector-field",
            arrayOf("--vector-metric", "cosine") to "--vector-metric requires --vector-field",
            arrayOf("--vector-required") to "--vector-required requires --vector-field",
            arrayOf("--vector-field", "$.e", "--vector-dimensions", "5000", "--vector-metric", "cosine") to "5000 is not in 1..4096",
            arrayOf("--vector-field", "$.e", "--vector-dimensions", "3", "--vector-metric", "dot") to "'dot' is not one of cosine|dot-product|euclidean",
            arrayOf("--vector-field", "$", "--vector-dimensions", "3", "--vector-metric", "cosine") to "the vector can't be the whole document",
        )) {
            val result = program.record("init", root.resolve("bad").toString(), *args)
            assertEquals(ExitStatus.USAGE, result.status, args.joinToString(" "))
            assertContains(result.stderr, message)
        }
        assertFalse(Files.exists(root.resolve("bad")))
    }

    @Test
    fun `search vector finds the nearest documents, nearest first, each with its distance`() {
        vectorStore()
        val json = run("search", "vector", "[1, 0, 0]", "--json")
        assertEquals(listOf(1L, 3L, 2L), docs(json))
        val records = json.stdout.lines().filter { it.isNotEmpty() }
        assertTrue(records[0].startsWith("""{"doc":1,"distance":0.0,"type":null,"size":"""), records[0])
        assertContains(records[0], """"value":{"id":"a","kind":"order","embedding":[1,0,0]}}""")
        val distances = records.map { Regex(""""distance":([^,]+),""").find(it)!!.groupValues[1].toFloat() }
        assertEquals(0.1414f, distances[1], 0.001f)
        assertEquals(1.4142f, distances[2], 0.001f)
        assertFalse("cursor" in json.stdout)

        val table = run("search", "vector", "[1, 0, 0]")
        assertEquals(ExitStatus.OK, table.status, table.stderr)
        val lines = table.stdout.lines().filter { it.isNotBlank() }
        assertEquals(4, lines.size, table.stdout)
        assertEquals(listOf("DOC", "DISTANCE", "TYPE", "SIZE", "VALUE"), lines[0].trim().split(Regex("\\s+")))
        assertContains(lines[1], Regex("""^\s*1\s+0\.0\s"""))
        assertContains(lines[1], """{"id": "a", "kind": "order", "embedding": [...]}""")
        assertContains(lines[2], Regex("""^\s*3\s+0\.141"""))
    }

    @Test
    fun `search vector takes how many, how far and a filter`() {
        vectorStore()
        assertEquals(listOf(1L, 3L), docs(run("search", "vector", "[1,0,0]", "-n", "2", "--json")))
        assertEquals(listOf(1L, 3L), docs(run("search", "vector", "[1,0,0]", "--max-distance", "0.5", "--json")))
        assertEquals(listOf(3L), docs(run("search", "vector", "[1,0,0]", "--where", "@.kind == 'note'", "--json")))

        // A filter no index term narrows compares every vector, which has to be asked for.
        val refused = program.record("search", "vector", "[1,0,0]", "--where", "@.id != 'a'")
        assertEquals(ExitStatus.USAGE, refused.status, refused.stderr)
        assertContains(refused.stderr, "narrows the vector search, so it would compare every vector; add --scan to allow that")
        assertEquals(listOf(3L, 2L), docs(run("search", "vector", "[1,0,0]", "--where", "@.id != 'a'", "--scan", "--json")))

        // Nothing near enough: exit 0, or 1 with --exit-status.
        val none = run("search", "vector", "[0,0,9]", "--max-distance", "0.5")
        assertEquals(ExitStatus.OK, none.status)
        assertEquals("", none.stdout)
        assertEquals("no hits\n", none.stderr)
        assertEquals(ExitStatus.FAILURE, run("search", "vector", "[0,0,9]", "--max-distance", "0.5", "-e").status)

        for ((args, message) in listOf(
            arrayOf("-n", "0") to "0 is not in 1..10000",
            arrayOf("-n", "10001") to "10001 is not in 1..10000",
            arrayOf("--max-distance", "NaN") to "'NaN' is not a distance",
            arrayOf("--where", "@.kind ==") to "invalid JSONPath query",
        )) {
            val result = program.record("search", "vector", "[1,0,0]", *args)
            assertEquals(ExitStatus.USAGE, result.status, args.joinToString(" "))
            assertContains(result.stderr, message)
        }
    }

    @Test
    fun `the vector comes from the command line, a file, standard input or a stored document`() {
        vectorStore()
        val expected = listOf(1L, 3L, 2L)
        assertEquals(expected, docs(run("search", "vector", file("query.json", "[1.0,\n 0e0,\n -0.0]\n"), "--json")))
        assertEquals(expected, docs(program.record("search", "vector", "-", "--json", stdin = "[1, 0, 0]".toByteArray())))
        // A document's own vector finds the document first.
        assertEquals(expected, docs(run("search", "vector", "1", "--json")))
        assertEquals(listOf(3L, 1L, 2L), docs(run("search", "vector", "3", "--json")))

        val noVector = program.record("search", "vector", "4")
        assertEquals(ExitStatus.NO_INPUT, noVector.status)
        assertContains(noVector.stderr, "document 4 has no vector at $.embedding")
        assertEquals(ExitStatus.NO_INPUT, program.record("search", "vector", "99").status)
        val noFile = program.record("search", "vector", root.resolve("nothing.json").toString())
        assertEquals(ExitStatus.NO_INPUT, noFile.status)
        assertContains(noFile.stderr, "no such file")
    }

    @Test
    fun `text that isn't a vector is refused, with the element that is wrong`() {
        vectorStore()
        for ((vector, message) in listOf(
            "[]" to "the vector is empty",
            "[1, x, 0]" to "element 1 of the vector, 'x', is not a number",
            "[1,,0]" to "element 1 of the vector, '', is not a number",
            "[1, NaN, 0]" to "element 1 of the vector, 'NaN', is not a number",
            "[1, 0x10, 0]" to "element 1 of the vector, '0x10', is not a number",
            "[1, 0, 1e99]" to "element 2 of the vector, 1e99, is too large",
            "[1, 0, 0" to "a vector is a JSON array of numbers",
        )) {
            val inline = program.record("search", "vector", vector)
            assertEquals(ExitStatus.USAGE, inline.status, vector)
            assertContains(inline.stderr, message)
            val name = file("bad.json", vector)
            val fromFile = program.record("search", "vector", name)
            assertEquals(ExitStatus.DATA_ERR, fromFile.status, vector)
            assertContains(fromFile.stderr, "$name: $message")
        }
        val object_ = program.record("search", "vector", file("object.json", """{"embedding":[1,0,0]}"""))
        assertEquals(ExitStatus.DATA_ERR, object_.status)
        assertContains(object_.stderr, "a vector is a JSON array of numbers")

        val dimensions = program.record("search", "vector", "[1, 0]")
        assertEquals(ExitStatus.DATA_ERR, dimensions.status)
        assertContains(dimensions.stderr, "the query vector has 2 numbers, the store's vectors 3")
    }

    @Test
    fun `search vector needs a store with a vector field, and a vector with a direction under cosine`() {
        val plain = root.resolve("plain")
        assertEquals(ExitStatus.OK, run("init", plain.toString()).status)
        val none = program.record("-s", plain.toString(), "search", "vector", "[1,0,0]")
        assertEquals(ExitStatus.USAGE, none.status)
        assertContains(none.stderr, "the store has no vector field: it wasn't created with --vector-field")

        val cosine = root.resolve("cosine")
        assertEquals(
            ExitStatus.OK,
            run("init", cosine.toString(), "--vector-field", "$.e", "--vector-dimensions", "2", "--vector-metric", "cosine").status,
        )
        val zero = program.record("-s", cosine.toString(), "search", "vector", "[0, 0]")
        assertEquals(ExitStatus.DATA_ERR, zero.status)
        assertContains(zero.stderr, "the query vector is zero")
    }

    @Test
    fun `hits of a store with document keys carry their keys`() {
        vectorStore("--document-key", "$.id")
        val json = run("search", "vector", "[1,0,0]", "-n", "1", "--json")
        assertTrue(json.stdout.startsWith("""{"doc":1,"key":"a","distance":0.0,"""), json.stdout)
        val table = run("search", "vector", "[1,0,0]", "-n", "2")
        val lines = table.stdout.lines().filter { it.isNotBlank() }
        assertEquals(listOf("KEY", "DOC", "DISTANCE", "TYPE", "SIZE", "VALUE"), lines[0].trim().split(Regex("\\s+")))
        assertContains(lines[2], Regex("""^c\s+3\s"""))
    }
}
