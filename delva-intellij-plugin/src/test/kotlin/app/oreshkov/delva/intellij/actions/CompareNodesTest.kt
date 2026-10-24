package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.intellij.store.StoreAccessException
import app.oreshkov.delva.intellij.ui.StoreExplorerPanel
import com.intellij.diff.contents.DocumentContent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class CompareNodesTest : BasePlatformTestCase() {
    // The light project outlives the test and keeps the store open, so the directory goes when the JVM does.
    private val dir: Path = FileUtil.createTempDirectory("delva-compare", null, true).toPath().resolve("orders")

    fun `test compares two documents as indented JSON`() {
        val (a, b) = runBlocking {
            JsonStore.create(dir).use { writer -> listOf(writer.insert("""{"n": 1}""").raw, writer.insert("""{"n": 2}""").raw) }
        }

        val request = runBlocking { compareRequest(project, dir, document(a), document(b)) }
        assertEquals("orders #$a and orders #$b", request.title)
        assertEquals(listOf("orders #$a", "orders #$b"), request.contentTitles)
        val texts = request.contents.map { (it as DocumentContent).document.text }
        assertEquals(listOf("""{"n": 1}""", """{"n": 2}"""), texts)
        assertEquals("JSON", (request.contents[0] as DocumentContent).contentType?.name)
    }

    fun `test reports a document that is no longer in the store`() {
        val id = runBlocking { JsonStore.create(dir).use { writer -> writer.insert("""{"n": 1}""").raw } }

        val failure = runCatching { runBlocking { compareRequest(project, dir, document(id), document(id + 1)) } }.exceptionOrNull()
        assertInstanceOf(failure, StoreAccessException::class.java)
    }

    fun `test compares exactly two selected nodes`() {
        val action = CompareNodesAction()
        for (count in 0..3) {
            val nodes = SelectedNodes(dir, (1L..count).map(::document))
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(StoreExplorerPanel.SELECTED_NODES, nodes)
                .build()
            val event = TestActionEvent.createTestEvent(action, context)
            action.update(event)
            assertEquals("$count selected", count == 2, event.presentation.isEnabled)
        }
    }

    private fun document(docId: Long) = StoredNode(docId, 0, null, "$", null)
}
