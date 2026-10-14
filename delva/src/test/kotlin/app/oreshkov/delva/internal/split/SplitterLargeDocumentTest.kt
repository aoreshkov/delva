package app.oreshkov.delva.internal.split

import app.oreshkov.delva.testkit.generate.BigDocProfile
import app.oreshkov.delva.testkit.generate.GeneratedJson
import app.oreshkov.delva.testkit.json.JsonLimits
import app.oreshkov.delva.testkit.json.JsonText
import app.oreshkov.delva.testkit.json.OracleJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Generated multi-megabyte documents at realistic fragment sizes, checked against the oracle like the property test. */
class SplitterLargeDocumentTest {
    @Test
    fun `generated documents split, reassemble and index like the oracle`() {
        for (profile in BigDocProfile.entries) {
            val target = if (profile == BigDocProfile.LONG_STRINGS) 12L shl 20 else 3L shl 20
            val json = GeneratedJson(seed = profile.ordinal + 11L, profile = profile, targetBytes = target).readBytes()
            val value = OracleJson.parseOrThrow(json, JsonLimits(maxDepth = 10_000))
            for ((fragmentBytes, budget) in listOf(4096 to 16 * 4096, 64 * 1024 to 8 * 1024 * 1024, 64 * 1024 to 2 * 64 * 1024)) {
                val options = SplitOptions(fragmentBytes, budget, sorterBytes = 1 shl 20, keyArenaBytes = 64 * 1024)
                val run = split(json, options, window = 4096, chunk = 7000)
                val label = "$profile T=$fragmentBytes B=$budget"
                assertEquals(JsonText.minified(value), reassemble(run.fragments), label)
                checkFragments(run.fragments, value, options)
                val live = run.runs.runs.flatten()
                assertEquals(expectedPostings(value, true), collapse(live).mapValues { (_, p) -> p.memberPre to p.inArray }, label)
                checkLocators(live, run.fragments, value)
                assertTrue(run.splitter.peakPendingBytes <= budget + 2 * fragmentBytes + 64 * 1024 || profile == BigDocProfile.DEEP, "$label peak ${run.splitter.peakPendingBytes}")
                println("$label: ${json.size} B → ${run.splitter.fragmentCount} fragments, ${run.splitter.payloadBytes} payload B, ${run.splitter.runCount} runs, peak pending ${run.splitter.peakPendingBytes}, peak record ${run.splitter.peakRecordBytes}")
            }
        }
    }
}
