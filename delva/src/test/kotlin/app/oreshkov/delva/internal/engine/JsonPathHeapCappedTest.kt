package app.oreshkov.delva.internal.engine

import app.oreshkov.delva.JsonPath
import app.oreshkov.delva.JsonPathQuery
import app.oreshkov.delva.NodeRef
import app.oreshkov.delva.ResultMode
import app.oreshkov.delva.ScanPolicy
import app.oreshkov.delva.SearchOrder
import app.oreshkov.delva.internal.ChunkedInputStream
import app.oreshkov.delva.internal.LiveHeapSampler
import app.oreshkov.delva.internal.io.JdkFileSystemIo
import app.oreshkov.delva.internal.query.PathSearch
import app.oreshkov.delva.testkit.generate.BigDocProfile
import app.oreshkov.delva.testkit.generate.GeneratedJson
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JSONPath under `heapCappedTest` (256 MB heap): descendant segments, filters and functions over one huge generated
 * MIXED document (records, 900-level chains, multi-MiB strings) on a real disk, through `select` and search in both
 * orders. Live heap must stay bounded whatever the document's size. `-Ddelva.heapCapped.bytes=<n>` scales it
 * (default 2 GiB: 256 MiB).
 */
class JsonPathHeapCappedTest {
    private val bytes = (System.getProperty("delva.heapCapped.bytes")?.toLong() ?: (2L shl 30)) / 8

    private fun seconds(start: Long) = "%.1f s".format((System.nanoTime() - start) / 1e9)

    @Test
    fun `descendants and filters over a huge document run in bounded memory`() {
        val dir = Files.createTempDirectory("delva-jsonpath-heap")
        try {
            LiveHeapSampler().use { sampler ->
                Engine.open(dir, EngineOptions(), JdkFileSystemIo()).use { store ->
                    var start = System.nanoTime()
                    val docId = store.insert(GeneratedJson(21, BigDocProfile.MIXED, bytes))
                    println("MIXED ${bytes shr 20} MiB inserted in ${seconds(start)}, peak live heap ${sampler.peak shr 20} MiB")
                    val root = NodeRef(docId, 0)
                    fun count(path: String): Long {
                        start = System.nanoTime()
                        val n = store.select(root, JsonPath.compile(path)).use { r -> r.iterator().asSequence().count().toLong() }
                        println("  select $path: $n nodes, ${seconds(start)}, peak live heap ${sampler.peak shr 20} MiB")
                        return n
                    }
                    val all = count("$..*")
                    assertTrue(all > 10_000, "$all nodes")
                    val leaves = count("$..[?@.leaf == true]")
                    assertTrue(leaves > 0)
                    assertTrue(count("$..[?length(@) > 1000000]") > 0, "multi-MiB strings")
                    assertTrue(count("$.items[?@.qty == 1 && count(@.tags[*]) > 2].sku") > 0)
                    assertEquals(0, count("$..[?match(@.body, 'no such text')]"))

                    start = System.nanoTime()
                    val hits = store.search(JsonPathQuery(JsonPath.compile("$..[?@.leaf == true]"), limit = 1000)).use { r -> r.iterator().asSequence().count().toLong() }
                    println("  search for the leaves: $hits hits, ${seconds(start)}, peak live heap ${sampler.peak shr 20} MiB")
                    assertEquals(leaves, hits)
                    start = System.nanoTime()
                    val scanned = store.search(JsonPathQuery(JsonPath.compile("$..*"), ResultMode.CONTAINING_ELEMENT, limit = 1000, scan = ScanPolicy.ALLOW_SCAN)).use { r ->
                        r.iterator().asSequence().count().toLong()
                    }
                    println("  scanning search $..*: $scanned hits, ${seconds(start)}, peak live heap ${sampler.peak shr 20} MiB")
                    assertEquals(all, scanned)

                    // Descending: the same hits last first, the document walked once per chunk of kept hits.
                    for ((path, expected) in listOf("$..[?@.leaf == true]" to leaves, "$..*" to all)) {
                        start = System.nanoTime()
                        var count = 0L
                        var previous = Long.MAX_VALUE
                        var passes = 0L
                        val query = JsonPathQuery(JsonPath.compile(path), limit = 1000, scan = ScanPolicy.ALLOW_SCAN, order = SearchOrder.DESCENDING)
                        store.search(query).use { r ->
                            for (hit in r.iterator()) {
                                check(hit.node.nodePre < previous) { "descending hit ${hit.node} after pre $previous" }
                                previous = hit.node.nodePre
                                count++
                            }
                            passes = ((r as EngineSearchResult).source as PathSearch).passesForOrder
                        }
                        println("  descending search $path: $count hits in ${passes + 1} walks, ${seconds(start)}, peak live heap ${sampler.peak shr 20} MiB")
                        assertEquals(expected, count)
                    }
                }
                assertTrue(sampler.peak < 200L shl 20, "live heap peaked at ${sampler.peak shr 20} MiB")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an anchored search over more anchored objects than the heap holds longs runs in bounded memory`() {
        // `$.items[?@.k == 1]` looks up (k, 1) and walks only to the objects of its postings; a list of their pres
        // would need 8 bytes each, more than the heap.
        val objects = bytes * 8 / 50
        val dir = Files.createTempDirectory("delva-jsonpath-heap")
        try {
            LiveHeapSampler().use { sampler ->
                Engine.open(dir, EngineOptions(), JdkFileSystemIo()).use { store ->
                    var start = System.nanoTime()
                    val chunks = sequence {
                        yield("{\"items\":[")
                        for (i in 0 until objects) yield(if (i == 0L) "{\"k\":1}" else ",{\"k\":1}")
                        yield("]}")
                    }
                    store.insert(ChunkedInputStream(chunks))
                    println("$objects anchored objects inserted in ${seconds(start)}, peak live heap ${sampler.peak shr 20} MiB")
                    start = System.nanoTime()
                    val query = JsonPathQuery(JsonPath.compile("$.items[?@.k == 1]"), limit = 1000)
                    assertEquals("within", store.explain(query).within?.let { "within" })
                    val hits = store.search(query).use { r -> r.iterator().asSequence().count().toLong() }
                    println("  search $.items[?@.k == 1]: $hits hits, ${seconds(start)}, peak live heap ${sampler.peak shr 20} MiB")
                    assertEquals(objects, hits)
                }
                assertTrue(sampler.peak < 200L shl 20, "live heap peaked at ${sampler.peak shr 20} MiB")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `negative-step slices over an array larger than the heap run in bounded memory`() {
        // Each selected element pins the PART it was read from, so holding them all would hold the whole array.
        val padding = "x".repeat(100)
        val elements = (bytes * 2) / (padding.length + 12)
        val dir = Files.createTempDirectory("delva-jsonpath-heap")
        try {
            LiveHeapSampler().use { sampler ->
                Engine.open(dir, EngineOptions(), JdkFileSystemIo()).use { store ->
                    var start = System.nanoTime()
                    val chunks = sequence {
                        yield("{\"items\":[")
                        for (i in 0 until elements) yield(if (i == 0L) "\"$padding$i\"" else ",\"$padding$i\"")
                        yield("]}")
                    }
                    val docId = store.insert(ChunkedInputStream(chunks))
                    println("array of $elements strings inserted in ${seconds(start)}, peak live heap ${sampler.peak shr 20} MiB")
                    for (stride in listOf(1L, 3L)) {
                        start = System.nanoTime()
                        var count = 0L
                        var previous = Long.MAX_VALUE
                        store.select(NodeRef(docId, 0), JsonPath.compile("$.items[::-$stride]")).use { r ->
                            for (node in r.iterator()) {
                                check(node.node.nodePre < previous) { "${node.node} after pre $previous" }
                                if (count == 0L) assertEquals("$['items'][${elements - 1}]", node.path)
                                previous = node.node.nodePre
                                count++
                            }
                        }
                        println("  select $.items[::-$stride]: $count nodes, ${seconds(start)}, peak live heap ${sampler.peak shr 20} MiB")
                        assertEquals((elements + stride - 1) / stride, count)
                    }
                }
                assertTrue(sampler.peak < 200L shl 20, "live heap peaked at ${sampler.peak shr 20} MiB")
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
