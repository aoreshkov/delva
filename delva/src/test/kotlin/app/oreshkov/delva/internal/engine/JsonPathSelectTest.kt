package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.InvalidJsonPathException
import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathTemplate
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.QueryLimitException
import app.oreshkov.delva.StoreOptions
import app.oreshkov.delva.testkit.json.JsonGen
import app.oreshkov.delva.testkit.json.JsonProfile
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.ONum
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.json.OracleJson
import app.oreshkov.delva.testkit.json.canonicalEquals
import app.oreshkov.delva.testkit.oracle.JsonPathOracle
import app.oreshkov.delva.testkit.oracle.JsonPathQueryGen
import app.oreshkov.delva.testkit.property.Gen
import app.oreshkov.delva.testkit.property.forAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The whole JSONPath Compliance Test Suite (`src/test/resources/jsonpath-cts`, pinned in `SOURCE.txt`) against
 * [JsonPath.compile] and `select` (SPEC.md §10): every invalid selector is rejected at compile time, and every valid
 * one selects, from the stored document, one of the nodelists the suite allows — values and Normalized Paths, in
 * order. Documents are stored with tiny fragments too, so the evaluator reads through REF, SPLIT and BIGSTR stubs.
 */
class JsonPathSelectTest {
    private class Case(val name: String, val selector: String, val invalid: Boolean, val document: OValue?, val results: List<Pair<List<OValue>, List<String>>>)

    private val cases: List<Case> by lazy {
        val text = checkNotNull(javaClass.classLoader.getResource("jsonpath-cts/cts.json")).readBytes()
        val tests = ((OracleJson.parseOrThrow(text) as OObj)["tests"] as OArr).items.map { it as OObj }
        tests.map { t ->
            fun strings(v: OValue?) = (v as OArr).items.map { (it as OStr).value }
            val results = when {
                t["result"] != null -> listOf((t["result"] as OArr).items to strings(t["result_paths"]))
                t["results"] != null -> (t["results"] as OArr).items.zip((t["results_paths"] as OArr).items) { r, p -> (r as OArr).items to strings(p) }
                else -> emptyList()
            }
            Case((t["name"] as OStr).value, (t["selector"] as OStr).value, (t["invalid_selector"] as? OBool)?.value == true, t["document"], results)
        }
    }

    private fun options(t: Int) = EngineOptions(
        CreationOptions(fragmentBytes = t), StoreOptions(encoderBudgetBytes = maxOf(2 * t, 1024), maxDepth = 512),
        sorterBytes = 4096, keyArenaBytes = 1024, keySeed = 7, log = TestEngines.LOG, backgroundFlush = false,
    )

    @Test
    fun `every invalid selector is rejected when it's compiled`() {
        val invalid = cases.filter { it.invalid }
        assertTrue(invalid.size > 200, "the suite has ${invalid.size} invalid selectors")
        val accepted = invalid.filter { runCatching { JsonPath.compile(it.selector) }.isSuccess }
        assertTrue(accepted.isEmpty(), "accepted invalid selectors:\n" + accepted.joinToString("\n") { "${it.selector}   (${it.name})" })
    }

    @Test
    fun `a template without placeholders accepts and refuses each selector as compile does`() {
        for (case in cases) {
            val compiled = runCatching { JsonPath.compile(case.selector) }.getOrNull()
            val bound = runCatching { JsonPathTemplate.compile(case.selector).bind() }.getOrNull()
            assertEquals(compiled, bound, "'${case.selector}' (${case.name})")
        }
    }

    @Test
    fun `every valid selector selects a nodelist the suite allows`() {
        val valid = cases.filter { !it.invalid }
        assertTrue(valid.size > 400, "the suite has ${valid.size} valid selectors")
        for (t in listOf(32, 256, 64 * 1024)) {
            TestEngines.open(options(t)).use { store ->
                val failures = ArrayList<String>()
                for (case in valid) {
                    val path = try {
                        JsonPath.compile(case.selector)
                    } catch (e: IllegalArgumentException) {
                        failures += "rejects '${case.selector}' (${case.name}): ${e.message}"
                        continue
                    }
                    val docId = store.insert(JsonText.minified(checkNotNull(case.document)))
                    val (values, paths) = store.select(NodeRef(docId, 0), path).use { result ->
                        result.iterator().asSequence().map { OracleJson.parseOrThrow(it.readJsonAsString(Int.MAX_VALUE).toByteArray()) to it.path }.toList().unzip()
                    }
                    val match = case.results.any { (expected, expectedPaths) ->
                        expected.size == values.size && expected.zip(values).all { (e, a) -> canonicalEquals(e, a) } && expectedPaths == paths
                    }
                    if (!match) failures += "'${case.selector}' (${case.name}) selects ${values.map { JsonText.minified(it) }} at $paths"
                }
                if (failures.isNotEmpty()) fail("T=$t: ${failures.size} failures:\n" + failures.joinToString("\n"))
            }
        }
    }

    @Test
    fun `the oracle agrees with the suite`() {
        for (case in cases) {
            val query = runCatching { JsonPathOracle.parse(case.selector) }
            if (case.invalid) {
                if (query.isSuccess) fail("oracle accepts invalid '${case.selector}' (${case.name})")
                continue
            }
            val selected = query.getOrElse { fail("oracle rejects '${case.selector}' (${case.name}): ${it.message}") }.select(checkNotNull(case.document))
            val match = case.results.any { (expected, paths) ->
                expected.size == selected.size && expected.zip(selected).all { (e, a) -> canonicalEquals(e, a.value) } && paths == selected.map { it.path }
            }
            assertTrue(match, "oracle: '${case.selector}' (${case.name}) selects ${selected.map { JsonText.minified(it.value) to it.path }}")
        }
    }

    @Test
    fun `random queries select what the oracle selects`() {
        val gen = Gen { random, size ->
            val profile = JsonProfile.entries[random.nextInt(JsonProfile.entries.size)]
            val document = JsonGen(profile, fragmentSize = 64).value().generate(random, size)
            val queries = JsonPathQueryGen(listOf(document))
            document to List(8) { queries.query(random) }
        }
        var queries = 0
        var selecting = 0
        for (t in listOf(32, 64 * 1024)) {
            TestEngines.open(options(t)).use { store ->
                forAll("select T=$t", gen, iterations = 150) { (document, texts) ->
                    val docId = store.insert(JsonText.minified(document))
                    for (text in texts) {
                        val expected = runCatching { JsonPathOracle.parse(text) }.getOrNull()
                        val path = runCatching { JsonPath.compile(text) }.getOrNull()
                        assertEquals(expected != null, path != null, "engine and oracle disagree on whether '$text' is valid")
                        if (path == null) continue
                        queries++
                        val actual = store.select(NodeRef(docId, 0), path).use { r ->
                            r.iterator().asSequence().map { Triple(OracleJson.parseOrThrow(it.readJsonAsString(Int.MAX_VALUE).toByteArray()), it.path, it.node.nodePre) }.toList()
                        }
                        val oracle = expected!!.select(document)
                        assertEquals(oracle.map { it.path to it.pre }, actual.map { it.second to it.third }, "'$text' over ${JsonText.minified(document)}")
                        for ((e, a) in oracle.zip(actual)) assertTrue(canonicalEquals(e.value, a.first), "'$text' value at ${e.path}")
                        if (actual.isNotEmpty()) selecting++
                    }
                }
            }
        }
        // Queries that select nothing prove little; the generator must keep hitting.
        assertTrue(queries > 1000 && selecting > queries / 4, "$selecting of $queries valid queries selected something")
    }

    @Test
    fun `random slices select what the oracle selects, through PARTs and directory records`() {
        // T=32 puts one PART in each directory record, T=256 a few, and 64 KiB leaves the arrays unsplit.
        val gen = Gen { random, size ->
            val length = random.nextInt(size * 4 + 1)
            val items = List(length) { i -> if (random.nextInt(5) == 0) OArr(listOf(ONum("$i"), OStr("x".repeat(random.nextInt(40))))) else ONum("$i") }
            val document = OObj(listOf("a" to OArr(items), "b" to OArr(List(random.nextInt(4)) { OArr(items.take(random.nextInt(length + 1))) })))
            fun bound() = if (random.nextInt(4) == 0) "" else (random.nextInt(2 * length + 7) - length - 3).toString()
            fun step() = when (random.nextInt(8)) {
                0 -> ""
                1 -> ":"
                else -> ":" + (random.nextInt(11) - 5).let { if (random.nextInt(3) == 0) it * 7 else it }
            }
            document to List(8) { if (it % 4 == 3) "$.b[*][${bound()}:${bound()}${step()}]" else "$.a[${bound()}:${bound()}${step()}]" }
        }
        var negative = 0
        for (t in listOf(32, 256, 64 * 1024)) {
            TestEngines.open(options(t)).use { store ->
                forAll("slices T=$t", gen, iterations = 100, maxSize = 60) { (document, texts) ->
                    val docId = store.insert(JsonText.minified(document))
                    for (text in texts) {
                        val actual = store.select(NodeRef(docId, 0), JsonPath.compile(text)).use { r -> r.iterator().asSequence().map { it.path to it.node.nodePre }.toList() }
                        val oracle = JsonPathOracle.parse(text).select(document)
                        assertEquals(oracle.map { it.path to it.pre }, actual, "'$text' over ${JsonText.minified(document)}")
                        if (text.contains(":-") && actual.size > 1) negative++
                    }
                }
            }
        }
        assertTrue(negative > 300, "$negative negative-step slices selected more than one element")
    }

    @Test
    fun `random templates bound to values select what the oracle selects with the literals in place`() {
        val gen = Gen { random, size ->
            val profile = JsonProfile.entries[random.nextInt(JsonProfile.entries.size)]
            val document = JsonGen(profile, fragmentSize = 64).value().generate(random, size)
            val queries = JsonPathQueryGen(listOf(document))
            document to List(8) { queries.template(random) { r -> if (r.nextInt(4) != 0) queries.filteredQuery(r) else queries.query(r) } }
        }
        var bound = 0
        var parameterized = 0
        var selecting = 0
        TestEngines.open(options(32)).use { store ->
            forAll("templates", gen, iterations = 150) { (document, templates) ->
                val docId = store.insert(JsonText.minified(document))
                for (template in templates) {
                    val expected = runCatching { JsonPathOracle.parse(template.filled) }.getOrNull()
                    val path = runCatching { JsonPathTemplate.compile(template.text).bind(template.values) }
                    path.exceptionOrNull()?.let { assertIs<InvalidJsonPathException>(it, "$template") }
                    assertEquals(expected != null, path.isSuccess, "engine and oracle disagree on whether $template is valid")
                    val query = path.getOrNull() ?: continue
                    bound++
                    if (template.values.isNotEmpty()) parameterized++
                    fun select(path: JsonPath) = store.select(NodeRef(docId, 0), path).use { r ->
                        r.iterator().asSequence().map { Triple(OracleJson.parseOrThrow(it.readJsonAsString(Int.MAX_VALUE).toByteArray()), it.path, it.node.nodePre) }.toList()
                    }
                    val actual = select(query)
                    // The bound text is a query of its own, the same one: it selects the same nodes.
                    val recompiled = JsonPath.compile(query.toString())
                    assertEquals(query, recompiled, "$template")
                    assertEquals(actual.map { it.second to it.third }, select(recompiled).map { it.second to it.third }, "$template recompiled")
                    val oracle = expected!!.select(document)
                    assertEquals(oracle.map { it.path to it.pre }, actual.map { it.second to it.third }, "$template over ${JsonText.minified(document)}")
                    for ((e, a) in oracle.zip(actual)) assertTrue(canonicalEquals(e.value, a.first), "$template value at ${e.path}")
                    if (actual.isNotEmpty()) selecting++
                }
            }
        }
        assertTrue(bound > 800 && selecting > bound / 5, "$selecting of $bound bound templates selected something")
        assertTrue(parameterized > bound / 3, "only $parameterized of $bound templates have a placeholder")
    }

    @Test
    fun `select starts at any node and reports paths from it`() {
        TestEngines.open(options(32)).use { store ->
            val docId = store.insert("""{"a":{"b":[1,{"c":2},3]},"d":4}""")
            fun select(pre: Long, path: String) = store.select(NodeRef(docId, pre), JsonPath.compile(path)).use { r ->
                r.iterator().asSequence().map { it.path + "=" + it.readJsonAsString() + "@" + it.node.nodePre }.toList()
            }
            assertEquals(listOf("$['b'][1]={\"c\":2}@4"), select(1, "$.b[?@.c]"))
            assertEquals(listOf("$['c']=2@5"), select(4, "$.c"))
            assertEquals(listOf("$[0]=1@3", "$[2]=3@6"), select(2, "$[?@ != \$[1]]"))
            assertEquals(listOf("$=4@7"), select(7, "$"))
        }
    }

    @Test
    fun `match on a long stored string that runs the regex matcher out of stack fails with QueryLimitException`() {
        TestEngines.open(options(64 * 1024)).use { store ->
            val docId = store.insert("""{"s":"${"ab".repeat(500_000)}"}""")
            for (function in listOf("match", "search")) {
                store.select(NodeRef(docId, 0), JsonPath.compile("$[?$function(@, '(a|b)*c')]")).use { result ->
                    val e = assertFailsWith<QueryLimitException>(function) { result.iterator().hasNext() }
                    assertTrue(e.message!!.contains("stack"), "$function: ${e.message}")
                }
            }
        }
    }

}
