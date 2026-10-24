package app.oreshkov.delva.intellij.ui

import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.intellij.actions.openDocument
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class StoreDetailsPanelTest : BasePlatformTestCase() {
    // The light project outlives the test and keeps the store open, so the directory goes when the JVM does.
    private val dir: Path = FileUtil.createTempDirectory("delva-details", null, true).toPath().resolve("orders")

    override fun tearDown() {
        try {
            val editors = FileEditorManager.getInstance(project)
            editors.openFiles.forEach(editors::closeFile)
        } finally {
            super.tearDown()
        }
    }

    fun `test lists a store's documents a page at a time`() {
        runBlocking { JsonStore.create(dir).use { writer -> repeat(150) { writer.insert("""{"n": $it}""") } } }
        val panel = StoreDetailsPanel(project)

        panel.show(dir)
        waitUntil("the first page") { panel.listed.size == 100 }
        assertTrue(panel.hasMore)

        panel.loadMore()
        waitUntil("the second page") { panel.listed.size == 150 }
        assertFalse(panel.hasMore)
        assertEquals("each document once, in id order", panel.listed.map { it.docId }.sorted(), panel.listed.map { it.docId })

        panel.show(null)
        assertEmpty(panel.listed)
    }

    fun `test opens a document read-only, and the same editor again`() {
        // Too long for one line of 80 columns, but its inner object fits one.
        val sku = "x".repeat(55)
        runBlocking { JsonStore.create(dir).use { writer -> writer.insert("""{"order": {"sku": "$sku"}, "n": 1}""") } }
        val panel = StoreDetailsPanel(project)
        panel.show(dir)
        waitUntil("the documents") { panel.listed.size == 1 }
        val document = panel.listed.single()
        val editors = FileEditorManager.getInstance(project)

        openDocument(project, dir, document)
        waitUntil("the editor") { editors.openFiles.isNotEmpty() }
        val file = editors.openFiles.single() as LightVirtualFile
        assertEquals("orders #${document.docId}.json", file.name)
        assertFalse(file.isWritable)
        assertEquals("{\n  \"order\": {\"sku\": \"$sku\"},\n  \"n\": 1\n}", file.content.toString())

        openDocument(project, dir, document)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertSame(file, editors.openFiles.single())
    }

    private fun waitUntil(what: String, condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching("no $what in time", condition, 30)
}
