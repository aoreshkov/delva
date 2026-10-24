package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.intellij.DelvaBundle
import app.oreshkov.delva.intellij.store.DelvaStoresService
import app.oreshkov.delva.intellij.store.SearchOutcome
import app.oreshkov.delva.intellij.store.StoreAccessException
import app.oreshkov.delva.intellij.ui.StoreExplorerPanel
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Asks for a JSONPath query, and for a store directory unless one is selected in the tool window, and opens the hits
 * in a read-only JSON editor.
 */
internal class SearchStoreAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stores = DelvaStoresService.getInstance(project)
        // The store selected in the tool window, or, from the Tools menu, one the person picks.
        val dir = e.getData(StoreExplorerPanel.SELECTED_STORE) ?: chooseStore(project) ?: return
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
        open(project, jsonFile(name, searchResultsText(outcome.hits)))
        if (outcome.more) notify(project, DelvaBundle.message("search.results.more", LIMIT), NotificationType.INFORMATION)
    }

    private companion object {
        const val LIMIT = 100
        const val MAX_CHARS = 100_000
    }
}
