package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.intellij.ui.DelvaToolWindowFactory
import app.oreshkov.delva.intellij.ui.StoreExplorerPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Shows the search of the store selected in the Delva tool window. From elsewhere, such as the Tools menu, it opens the
 * tool window first, and asks for a store directory to add when the project lists none.
 */
internal class SearchStoreAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        e.getData(StoreExplorerPanel.PANEL)?.let {
            it.showSearch()
            return
        }
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(DelvaToolWindowFactory.ID) ?: return
        toolWindow.activate {
            val panel = toolWindow.contentManager.contents.firstNotNullOfOrNull { it.component as? StoreExplorerPanel }
            if (panel != null && (panel.selectedStore != null || addStore(project, panel) != null)) panel.showSearch()
        }
    }
}
