package app.oreshkov.delva.intellij.ui

import app.oreshkov.delva.intellij.store.DelvaStoreList
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class StoreExplorerPanelTest : BasePlatformTestCase() {
    private val root = FileUtil.createTempDirectory("delva-panel", null, true).toPath()

    fun `test follows the project's list and keeps a listed store selected`() {
        val stores = DelvaStoreList.getInstance(project)
        stores.loadState(DelvaStoreList.Paths())
        val first = stores.add(root.resolve("first"))

        val panel = StoreExplorerPanel(project)
        Disposer.register(testRootDisposable, panel)
        assertEquals("the first store is selected", first, selected(panel))

        val second = stores.add(root.resolve("second"))
        panel.select(second)
        assertEquals(second, selected(panel))

        stores.remove(second)
        assertEquals("the selection moves to a store still listed", first, selected(panel))
        stores.remove(first)
        assertNull(selected(panel))
    }

    // What the panel puts in its data context; the headless test data manager ignores components.
    private fun selected(panel: StoreExplorerPanel): Path? = panel.selectedStore
}
