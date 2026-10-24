package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.intellij.DelvaBundle
import app.oreshkov.delva.intellij.store.DelvaStoresService
import app.oreshkov.delva.intellij.store.StoreAccessException
import app.oreshkov.delva.intellij.ui.StoreExplorerPanel
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.limits.FileSizeLimit
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** The nodes selected in a list of the store in [dir], in the order the list shows them. */
internal data class SelectedNodes(val dir: Path, val nodes: List<StoredNode>)

/** Shows the two nodes selected in the Delva tool window side by side in the IDE's diff viewer. */
internal class CompareNodesAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null && e.getData(StoreExplorerPanel.SELECTED_NODES)?.nodes?.size == 2
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = e.getData(StoreExplorerPanel.SELECTED_NODES)?.takeIf { it.nodes.size == 2 } ?: return
        compareNodes(project, selected.dir, selected.nodes[0], selected.nodes[1])
    }
}

/** Reads [left] and [right] of the store in [dir] and shows them in the diff viewer; a failure is a notification. */
internal fun compareNodes(project: Project, dir: Path, left: StoredNode, right: StoredNode) {
    val stores = DelvaStoresService.getInstance(project)
    stores.launch {
        val request = try {
            withBackgroundProgress(project, DelvaBundle.message("compare.progress", nodeName(dir, left), nodeName(dir, right))) {
                compareRequest(project, dir, left, right)
            }
        } catch (e: StoreAccessException) {
            notify(project, e.message.orEmpty(), NotificationType.ERROR)
            return@launch
        }
        withContext(Dispatchers.EDT) { DiffManager.getInstance().showDiff(project, request) }
    }
}

/**
 * The diff of [left] and [right] of the store in [dir], each as indented JSON. Throws [StoreAccessException], also when
 * either is longer than the JSON editor works with.
 */
internal suspend fun compareRequest(project: Project, dir: Path, left: StoredNode, right: StoredNode): SimpleDiffRequest {
    val backend = DelvaStoresService.getInstance(project).backend
    // The diff viewer is two JSON editors, so the same limit applies as to opening a node.
    val maxChars = FileSizeLimit.getIntellisenseLimit("json")
    val names = listOf(nodeName(dir, left), nodeName(dir, right))
    val texts = coroutineScope {
        listOf(left, right).mapIndexed { i, node ->
            async {
                backend.readNode(dir, node.docId, node.nodePre, maxChars)
                    ?: throw StoreAccessException(tooLargeMessage(names[i], node, maxChars))
            }
        }.map { it.await() }
    }
    val json = FileTypeManager.getInstance().getFileTypeByExtension("json")
    val contents = DiffContentFactory.getInstance()
    return SimpleDiffRequest(
        DelvaBundle.message("compare.title", names[0], names[1]),
        contents.create(project, texts[0], json),
        contents.create(project, texts[1], json),
        names[0],
        names[1],
    )
}
