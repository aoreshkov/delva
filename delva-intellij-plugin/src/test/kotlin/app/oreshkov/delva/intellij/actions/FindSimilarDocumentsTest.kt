package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.VectorField
import app.oreshkov.delva.VectorMetric
import app.oreshkov.delva.intellij.store.DelvaStoreList
import app.oreshkov.delva.intellij.ui.StoreExplorerPanel
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class FindSimilarDocumentsTest : BasePlatformTestCase() {
    // The light project outlives the test and keeps the stores open, so the directory goes when the JVM does.
    private val root: Path = FileUtil.createTempDirectory("delva-similar", null, true).toPath()

    @OptIn(ExperimentalDelvaApi::class)
    fun `test is offered for one document of a store with a vector field`() {
        val vectors = root.resolve("vectors")
        val plain = root.resolve("plain")
        runBlocking {
            JsonStore.create(vectors, CreationOptions().withVectorField(VectorField("$.v", 2, VectorMetric.COSINE))).close()
            JsonStore.create(plain).close()
        }
        val stores = DelvaStoreList.getInstance(project)
        stores.loadState(DelvaStoreList.Paths())
        stores.add(vectors)
        stores.add(plain)
        val panel = StoreExplorerPanel(project)
        Disposer.register(testRootDisposable, panel)
        assertEquals(vectors, panel.selectedStore)
        PlatformTestUtil.waitWithEventsDispatching("no summary in time", { panel.selectedStoreHasVectors }, 30)

        assertEquals("one selected", Shown(visible = true, enabled = true), shown(panel, vectors, selected = 1))
        assertEquals("two selected", Shown(visible = true, enabled = false), shown(panel, vectors, selected = 2))

        panel.select(plain)
        assertEquals("a store without a vector field", Shown(visible = false, enabled = false), shown(panel, plain, selected = 1))
    }

    private data class Shown(val visible: Boolean, val enabled: Boolean)

    /** How the action shows in the context menu of a list of [dir] in [panel] with [selected] documents selected. */
    private fun shown(panel: StoreExplorerPanel, dir: Path, selected: Int): Shown {
        val action = FindSimilarDocumentsAction()
        val nodes = SelectedNodes(dir, (1L..selected).map { StoredNode(it, 0, null, "$", null) })
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(StoreExplorerPanel.PANEL, panel)
            .add(StoreExplorerPanel.SELECTED_NODES, nodes)
            .build()
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        return Shown(event.presentation.isVisible, event.presentation.isEnabled)
    }
}
