package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.intellij.ui.StoreExplorerPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/**
 * Lists, on the Delva tool window's Search tab, the documents whose vectors are nearest to the vector of the document
 * selected in one of its lists, or of the document that holds the node selected.
 */
internal class FindSimilarDocumentsAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val panel = e.getData(StoreExplorerPanel.PANEL)
        // Only a store created with a vector field can answer it, so it's left out of the others' menus.
        e.presentation.isVisible = e.project != null && panel?.selectedStoreHasVectors == true
        e.presentation.isEnabled = e.presentation.isVisible && e.getData(StoreExplorerPanel.SELECTED_NODES)?.nodes?.size == 1
    }

    override fun actionPerformed(e: AnActionEvent) {
        val panel = e.getData(StoreExplorerPanel.PANEL) ?: return
        val selected = e.getData(StoreExplorerPanel.SELECTED_NODES)?.takeIf { it.nodes.size == 1 } ?: return
        panel.searchSimilar(selected.dir, selected.nodes.single().docId)
    }
}
