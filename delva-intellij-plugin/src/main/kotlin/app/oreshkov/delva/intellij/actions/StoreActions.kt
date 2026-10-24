package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.intellij.DelvaBundle
import app.oreshkov.delva.intellij.store.DelvaStoreList
import app.oreshkov.delva.intellij.store.DelvaStoresService
import app.oreshkov.delva.intellij.ui.StoreExplorerPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.toNioPathOrNull
import java.nio.file.Path

/** Adds a store directory to the project's list. */
internal class AddStoreAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        addStore(e.project ?: return, e.getData(StoreExplorerPanel.PANEL))
    }
}

/** Removes the selected store from the project's list; the store itself is left as it is. */
internal class RemoveStoreAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && e.getData(StoreExplorerPanel.SELECTED_STORE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = e.getData(StoreExplorerPanel.SELECTED_STORE) ?: return
        DelvaStoreList.getInstance(project).remove(dir)
        val stores = DelvaStoresService.getInstance(project)
        // Closing a store reads nothing, but it can wait for a read in progress.
        stores.launch { stores.backend.release(dir) }
    }
}

/** Reads the selected store again, to show what its writer has done since. */
internal class RefreshStoreAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(StoreExplorerPanel.PANEL) != null &&
            e.getData(StoreExplorerPanel.SELECTED_STORE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.getData(StoreExplorerPanel.PANEL)?.refresh()
    }
}

/** Asks for a store directory, adds it to the project's list and selects it in [panel]; returns it, or null. */
internal fun addStore(project: Project, panel: StoreExplorerPanel?): Path? {
    val dir = chooseStore(project) ?: return null
    val listed = DelvaStoreList.getInstance(project).add(dir)
    panel?.select(listed)
    return listed
}

/** Asks for a store directory; null when the person cancels. */
internal fun chooseStore(project: Project): Path? {
    val descriptor = FileChooserDescriptorFactory.singleDir().withTitle(DelvaBundle.message("store.choose"))
    return FileChooser.chooseFile(descriptor, project, null)?.toNioPathOrNull()
}
