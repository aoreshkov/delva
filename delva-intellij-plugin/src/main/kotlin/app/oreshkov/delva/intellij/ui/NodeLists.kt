package app.oreshkov.delva.intellij.ui

import app.oreshkov.delva.intellij.actions.NodeNavigatable
import app.oreshkov.delva.intellij.actions.SelectedNodes
import app.oreshkov.delva.intellij.actions.StoredNode
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.project.Project
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBList
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.EditSourceOnEnterKeyHandler
import java.nio.file.Path
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/**
 * Sets up [list], of a store's documents or nodes, as every such list in the tool window works: several can be selected,
 * a double click or Enter opens what the data context's navigatable points at, and the context menu offers to open or
 * compare them.
 */
internal fun <T> configureNodeList(list: JBList<T>, renderer: ListCellRenderer<in T>) {
    list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    list.cellRenderer = renderer
    EditSourceOnDoubleClickHandler.install(list)
    EditSourceOnEnterKeyHandler.install(list)
    PopupHandler.installPopupMenu(list, NODE_POPUP_GROUP, NODE_POPUP_PLACE)
}

/** Gives the platform's and the plugin's actions [nodes], selected in a list of the store in [dir], when there are any. */
internal fun DataSink.selectedNodes(project: Project, dir: Path, nodes: List<StoredNode>) {
    if (nodes.isEmpty()) return
    val navigatables = nodes.map { NodeNavigatable(project, dir, it) }
    this[CommonDataKeys.NAVIGATABLE] = navigatables.first()
    this[CommonDataKeys.NAVIGATABLE_ARRAY] = navigatables.toTypedArray()
    this[StoreExplorerPanel.SELECTED_NODES] = SelectedNodes(dir, nodes)
}

private const val NODE_POPUP_GROUP = "app.oreshkov.delva.NodePopup"
private const val NODE_POPUP_PLACE = "DelvaToolWindowPopup"
