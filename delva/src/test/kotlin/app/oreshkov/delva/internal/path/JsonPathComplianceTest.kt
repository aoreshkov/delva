package app.oreshkov.delva.internal.path

import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OArr
import app.oreshkov.delva.testkit.json.OBool
import app.oreshkov.delva.testkit.json.OObj
import app.oreshkov.delva.testkit.json.OStr
import app.oreshkov.delva.testkit.json.OValue
import app.oreshkov.delva.testkit.json.OracleJson
import app.oreshkov.delva.testkit.oracle.JsonPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The JSONPath Compliance Test Suite (`src/test/resources/jsonpath-cts`, pinned in `SOURCE.txt`) against the paths of
 * SPEC.md §6.1. They are a subset of RFC 9535, so two properties hold for every case:
 *
 * - a selector the suite calls invalid is rejected, by the engine's parser and by the oracle's;
 * - a selector that is accepted selects what the suite expects, at the Normalized Path it expects, and both parsers
 *   agree on its steps.
 *
 * Every Normalized Path in the suite's results must parse, and print back unchanged.
 */
class JsonPathComplianceTest {
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

    @Test
    fun `invalid selectors are rejected`() {
        val invalid = cases.filter { it.invalid }
        assertTrue(invalid.size > 200, "the suite has ${invalid.size} invalid selectors")
        for (case in invalid) {
            if (runCatching { SingularPath.parse(case.selector) }.isSuccess) fail("engine accepts invalid '${case.selector}' (${case.name})")
            if (runCatching { JsonPath.parse(case.selector) }.isSuccess) fail("oracle accepts invalid '${case.selector}' (${case.name})")
        }
    }

    @Test
    fun `accepted selectors select what the suite expects`() {
        var accepted = 0
        for (case in cases.filter { !it.invalid }) {
            val steps = runCatching { SingularPath.parse(case.selector) }.getOrNull()
            val oracle = runCatching { JsonPath.parse(case.selector) }.getOrNull()
            assertEquals(steps != null, oracle != null, "engine and oracle disagree on '${case.selector}' (${case.name})")
            if (steps == null) continue
            accepted++
            assertEquals(steps, oracle!!.steps.map { if (it is JsonPath.Step.Member) PathStep.Name(it.key) else PathStep.Index((it as JsonPath.Step.Index).index) })
            val found = resolve(checkNotNull(case.document), steps)
            val actual = if (found == null) emptyList<String>() else listOf(JsonText.minified(found))
            val actualPaths = if (found == null) emptyList() else listOf(normalized(steps))
            val match = case.results.any { (values, paths) -> values.map { JsonText.minified(it) } == actual && paths == actualPaths }
            assertTrue(match, "'${case.selector}' (${case.name}) selects $actual at $actualPaths")
        }
        // The share of the suite inside the subset; a drop means the grammar or the suite changed.
        println("JSONPath CTS: $accepted of ${cases.count { !it.invalid }} valid selectors are delva paths")
        assertTrue(accepted >= 50, "only $accepted valid selectors accepted")
    }

    @Test
    fun `the suite's Normalized Paths parse and print back unchanged`() {
        val paths = cases.flatMap { c -> c.results.flatMap { it.second } }.toSet()
        assertTrue(paths.size > 50, "the suite has ${paths.size} distinct result paths")
        for (path in paths) {
            val steps = SingularPath.parse(path)
            assertEquals(path, normalized(steps))
            assertEquals(path, JsonPath.parse(path).normalized)
        }
    }

    private fun normalized(steps: List<PathStep>): String = buildString {
        append('$')
        for (s in steps) {
            when (s) {
                is PathStep.Name -> NormalizedPath.appendName(this, s.key)
                is PathStep.Index -> NormalizedPath.appendIndex(this, s.index)
            }
        }
    }

    private fun resolve(root: OValue, steps: List<PathStep>): OValue? {
        var current = root
        for (step in steps) {
            current = when (step) {
                is PathStep.Name -> (current as? OObj)?.get(step.key)
                is PathStep.Index -> (current as? OArr)?.items?.let { if (step.index < it.size) it[step.index.toInt()] else null }
            } ?: return null
        }
        return current
    }
}
