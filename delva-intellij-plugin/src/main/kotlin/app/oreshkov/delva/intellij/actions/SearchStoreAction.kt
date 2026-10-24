package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.intellij.DelvaBundle
import app.oreshkov.delva.intellij.store.DelvaStoresService
import app.oreshkov.delva.intellij.store.SearchOutcome
import app.oreshkov.delva.intellij.store.StoreAccessException
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Asks for a store directory and a JSONPath query, and opens the hits in a read-only JSON editor. */
internal class SearchStoreAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stores = DelvaStoresService.getInstance(project)
        val descriptor = FileChooserDescriptorFactory.singleDir().withTitle(DelvaBundle.message("search.chooseStore"))
        val dir = FileChooser.chooseFile(descriptor, project, null)?.toNioPathOrNull() ?: return
        val validator = object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? = stores.backend.checkQuery(inputString)
        }
        val query = Messages.showInputDialog(
            project,
            DelvaBundle.message("search.query.prompt"),
            DelvaBundle.message("search.query.title", dir.fileName ?: dir),
            null,
            "$..*",
            validator,
        ) ?: return
        stores.launch { search(project, stores, dir, query) }
    }

    private suspend fun search(project: Project, stores: DelvaStoresService, dir: Path, query: String) {
        val outcome = try {
            withBackgroundProgress(project, DelvaBundle.message("search.progress", dir.fileName ?: dir)) {
                stores.backend.searchPath(dir, query, LIMIT, MAX_CHARS)
            }
        } catch (e: StoreAccessException) {
            notify(project, e.message.orEmpty(), NotificationType.ERROR)
            return
        }
        withContext(Dispatchers.EDT) { open(project, dir, query, outcome) }
    }

    private fun open(project: Project, dir: Path, query: String, outcome: SearchOutcome) {
        val name = DelvaBundle.message("search.results.name", dir.fileName ?: dir, query)
        val json = FileTypeManager.getInstance().getFileTypeByExtension("json")
        val file = LightVirtualFile("$name.json", json, searchResultsText(outcome.hits)).apply { isWritable = false }
        // Every blocking openFile is deprecated from 2026.3 on, and its suspending successor isn't in 2026.2.
        OpenFileDescriptor(project, file).navigate(true)
        if (outcome.more) notify(project, DelvaBundle.message("search.results.more", LIMIT), NotificationType.INFORMATION)
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("Delva").createNotification(content, type).notify(project)
    }

    private companion object {
        const val LIMIT = 100
        const val MAX_CHARS = 100_000
    }
}
