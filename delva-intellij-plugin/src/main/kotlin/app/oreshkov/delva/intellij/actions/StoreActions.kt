package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.intellij.DelvaBundle
import app.oreshkov.delva.intellij.store.DelvaStoreList
import app.oreshkov.delva.intellij.store.DelvaStoresService
import app.oreshkov.delva.intellij.store.StoreAccessException
import app.oreshkov.delva.intellij.store.StoreProbe
import app.oreshkov.delva.intellij.ui.StoreExplorerPanel
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.toNioPathOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
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

/**
 * Asks for a store directory, then adds it as [addChosenStore] does and hands it to [added] once it's listed. Called on
 * the EDT.
 */
internal fun addStore(project: Project, panel: StoreExplorerPanel?, added: (Path) -> Unit = {}) {
    val dir = chooseStore(project) ?: return
    addChosenStore(project, dir, panel, added)
}

/**
 * Reads what [dir] holds in the background, then on the EDT adds it to the project's list, selects it in [panel] and
 * hands it to [added]. A directory without a store, or with one this plugin can't read, is added only when the person
 * says so in a warning, as the application may create the store or the plugin may be updated later; a damaged store is
 * added, with a notification that offers to verify it. Returns the job that does it.
 */
internal fun addChosenStore(project: Project, dir: Path, panel: StoreExplorerPanel?, added: (Path) -> Unit = {}): Job {
    val stores = DelvaStoresService.getInstance(project)
    // Launched rather than run under a modal progress: reading a store's metadata usually takes a millisecond.
    return stores.launch {
        val (probe, failure) = try {
            stores.backend.probe(dir) to null
        } catch (e: StoreAccessException) {
            null to e.message.orEmpty()
        }
        withContext(Dispatchers.EDT) {
            val warning = when (probe) {
                null -> DelvaBundle.message("store.add.unreadable", failure.orEmpty())
                StoreProbe.NoStore -> DelvaBundle.message("store.add.noStore", dir)
                is StoreProbe.OtherFormat -> DelvaBundle.message("store.add.unreadable", probe.message)
                is StoreProbe.Readable, is StoreProbe.Damaged -> null
            }
            if (warning != null && !confirmAdd(project, warning)) return@withContext
            val listed = DelvaStoreList.getInstance(project).add(dir)
            panel?.select(listed)
            if (probe is StoreProbe.Damaged) notifyDamaged(project, listed, probe)
            added(listed)
        }
    }
}

private fun confirmAdd(project: Project, warning: String): Boolean = Messages.showOkCancelDialog(
    project,
    warning,
    DelvaBundle.message("store.add.title"),
    DelvaBundle.message("store.add.anyway"),
    Messages.getCancelButton(),
    Messages.getWarningIcon(),
) == Messages.OK

private fun notifyDamaged(project: Project, dir: Path, probe: StoreProbe.Damaged) {
    NotificationGroupManager.getInstance().getNotificationGroup("Delva")
        .createNotification(DelvaBundle.message("store.add.damaged", probe.message), NotificationType.WARNING)
        .addAction(NotificationAction.createSimpleExpiring(DelvaBundle.message("store.add.verify")) { verifyStore(project, dir) })
        .notify(project)
}

/** Asks for a store directory; null when the person cancels. */
internal fun chooseStore(project: Project): Path? {
    val descriptor = FileChooserDescriptorFactory.singleDir().withTitle(DelvaBundle.message("store.choose"))
    return FileChooser.chooseFile(descriptor, project, null)?.toNioPathOrNull()
}
