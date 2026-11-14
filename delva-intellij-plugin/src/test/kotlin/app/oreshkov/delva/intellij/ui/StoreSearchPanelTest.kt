package app.oreshkov.delva.intellij.ui

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.intellij.actions.StoredNode
import app.oreshkov.delva.intellij.actions.openNode
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class StoreSearchPanelTest : BasePlatformTestCase() {
    // The light project outlives the test and keeps the store open, so the directory goes when the JVM does.
    private val dir: Path = FileUtil.createTempDirectory("delva-search", null, true).toPath().resolve("orders")

    override fun tearDown() {
        try {
            val editors = FileEditorManager.getInstance(project)
            editors.openFiles.forEach(editors::closeFile)
        } finally {
            super.tearDown()
        }
    }

    fun `test lists the nodes a JSONPath query selects and opens one`() {
        val id = runBlocking {
            JsonStore.create(dir).use { writer ->
                writer.insert("""{"order": {"items": [{"sku": "A1", "qty": 0}, {"sku": "B2", "qty": 3}]}}""").raw
            }
        }
        val panel = searching(dir)

        panel.setPathQuery("$..[?@.qty < 1]", scan = true)
        panel.search()
        awaitRead(panel)
        assertEquals("1 hit.", panel.statusText)
        val hit = panel.listed.single()
        assertEquals("$['order']['items'][0]", hit.path)
        assertEquals("""{"sku":"A1","qty":0}""", hit.preview)

        openNode(project, dir, StoredNode(hit))
        val editors = FileEditorManager.getInstance(project)
        PlatformTestUtil.waitWithEventsDispatching("no editor in time", { editors.openFiles.isNotEmpty() }, 30)
        val file = editors.openFiles.single() as LightVirtualFile
        assertEquals("orders #$id \$['order']['items'][0].json", file.name)
        assertEquals("""{"sku": "A1", "qty": 0}""", file.content.toString())
    }

    fun `test lists hits a page at a time`() {
        runBlocking { JsonStore.create(dir).use { writer -> repeat(150) { writer.insert("""{"n": $it}""") } } }
        val panel = searching(dir)

        panel.setPathQuery("$.n", scan = true)
        panel.search()
        awaitRead(panel)
        assertEquals(100, panel.listed.size)
        assertTrue(panel.hasMore)
        assertEquals("The first 100 hits; more match the query.", panel.statusText)

        panel.loadMore()
        awaitRead(panel)
        assertEquals((0 until 150).map { "$it" }, panel.listed.map { it.preview })
        assertFalse(panel.hasMore)
        assertEquals("150 hits.", panel.statusText)

        panel.show(null)
        assertEmpty(panel.listed)
    }

    fun `test searches by key and value`() {
        runBlocking {
            JsonStore.create(dir).use { writer ->
                writer.insert("""{"sku": "B2"}""")
                writer.insert("""{"sku": "C3"}""")
            }
        }
        val panel = searching(dir)

        panel.setKeyValueQuery("sku", "B2", valueIsText = true)
        panel.search()
        awaitRead(panel)
        assertEquals("""{"sku":"B2"}""", panel.listed.single().preview)

        panel.setKeyValueQuery("sku", "B2")
        panel.search()
        awaitRead(panel)
        assertEmpty(panel.listed)
        assertEquals("The value B2 isn't JSON. Tick Text to search for it as a string.", panel.statusText)
    }

    fun `test reads nothing for a query with a mistake, and says why the store refuses one`() {
        runBlocking { JsonStore.create(dir).use { writer -> writer.insert("""{"sku": "B2"}""") } }
        val panel = searching(dir)

        panel.setPathQuery("$.")
        panel.search()
        assertFalse(panel.isReading)
        panel.setPathQuery("$", where = "@.kind ==")
        panel.search()
        assertFalse(panel.isReading)

        panel.setPathQuery("$..sku")
        panel.search()
        awaitRead(panel)
        assertTrue(panel.statusText, panel.statusText.startsWith("No index term narrows this query"))
    }

    fun `test explains how a search finds its documents`() {
        runBlocking { JsonStore.create(dir).close() }
        val panel = searching(dir)

        panel.setPathQuery("$..sku")
        panel.explain()
        awaitRead(panel)
        assertEquals(
            "Reads: Every document, as no index term narrows the query\nTerms: None\nWithin: Anywhere in the document",
            panel.statusText,
        )
    }

    @OptIn(ExperimentalDelvaApi::class)
    fun `test lists the documents nearest to a vector, and to a document's`() {
        val options = CreationOptions().withVectorField(VectorField("$.v", 2, VectorMetric.EUCLIDEAN))
        val (far, near) = runBlocking {
            JsonStore.create(dir, options).use { writer ->
                writer.insert("""{"n": 1, "v": [0, 4]}""").raw to writer.insert("""{"n": 2, "v": [0, 1]}""").raw
            }
        }
        val panel = searching(dir)

        panel.setVectorQuery("[0, 0]")
        panel.search()
        awaitRead(panel)
        assertEquals("2 hits.", panel.statusText)
        assertEquals(listOf(near, far), panel.listed.map { it.docId })
        assertEquals(listOf(1f, 4f), panel.listed.map { it.distance })
        assertFalse(panel.hasMore)

        panel.setVectorQuery("[0, 0]", nearest = "1")
        panel.search()
        awaitRead(panel)
        assertEquals(listOf(near), panel.listed.map { it.docId })

        panel.setVectorQuery("[0, 0]")
        panel.searchSimilar(far)
        PlatformTestUtil.waitWithEventsDispatching("no hits in time", { panel.listed.isNotEmpty() }, 30)
        awaitRead(panel)
        assertEquals("[0,4]", panel.vectorText.replace(" ", ""))
        assertEquals(listOf(far, near), panel.listed.map { it.docId })
        assertEquals(listOf(0f, 3f), panel.listed.map { it.distance })
    }

    fun `test reads nothing for a vector search with a mistake`() {
        runBlocking { JsonStore.create(dir).close() }
        val panel = searching(dir)

        for ((vector, nearest, maxDistance) in listOf(
            Triple("", "10", ""),
            Triple("[1, x]", "10", ""),
            Triple("[1]", "0", ""),
            Triple("[1]", "10001", ""),
            Triple("[1]", "10", "near"),
        )) {
            panel.setVectorQuery(vector, nearest, maxDistance)
            panel.search()
            assertFalse("$vector $nearest $maxDistance", panel.isReading)
        }

        panel.setVectorQuery("[1]", maxDistance = "0.5")
        panel.search()
        awaitRead(panel)
        assertEquals("The store has no vector field: it was created without one, so it can't be searched by vector.", panel.statusText)
    }

    fun `test describes a hit's distance`() {
        assertEquals("0.1235", formatDistance(0.123456f))
        assertEquals("0.000", formatDistance(0f))
        assertEquals("∞", formatDistance(Float.POSITIVE_INFINITY))
    }

    private fun searching(dir: Path) = StoreSearchPanel(project, testRootDisposable).apply { show(dir) }

    private fun awaitRead(panel: StoreSearchPanel) =
        PlatformTestUtil.waitWithEventsDispatching("the read didn't end in time", { !panel.isReading }, 30)
}
