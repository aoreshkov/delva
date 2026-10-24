package app.oreshkov.delva.intellij.ui

import app.oreshkov.delva.intellij.DelvaBundle
import app.oreshkov.delva.intellij.actions.SelectedNodes
import app.oreshkov.delva.intellij.actions.addStore
import app.oreshkov.delva.intellij.store.DelvaStoreList
import app.oreshkov.delva.intellij.store.DelvaStoresService
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import javax.swing.JList
import javax.swing.ListSelectionModel

/** The Delva tool window's content: the project's stores, and what the selected one holds. */
internal class StoreExplorerPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val storeList = DelvaStoreList.getInstance(project)
    private val stores = DelvaStoresService.getInstance(project)
    private val model = CollectionListModel<Path>()
    private val list = JBList(model)
    private val details = StoreDetailsPanel(project)
    private val search = StoreSearchPanel(project, this)
    private val types = StoreTypesPanel(project)
    private val tabs = JBTabbedPane().apply {
        addTab(DelvaBundle.message("explorer.tab.documents"), details)
        addTab(DelvaBundle.message("explorer.tab.search"), search)
        addTab(DelvaBundle.message("explorer.tab.types"), types)
    }

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = StoreRenderer()
        list.emptyText.text = DelvaBundle.message("explorer.empty")
        list.emptyText.appendLine(DelvaBundle.message("explorer.empty.add"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
            addStore(project, this)
        }
        list.addListSelectionListener { if (!it.valueIsAdjusting) showSelected() }

        val actions = ActionManager.getInstance()
        val group = actions.getAction(TOOLBAR_GROUP) as ActionGroup
        toolbar = actions.createActionToolbar(PLACE, group, true).also { it.targetComponent = this }.component
        setContent(OnePixelSplitter(true, SPLITTER_KEY, 0.4f).apply {
            firstComponent = ScrollPaneFactory.createScrollPane(list, true)
            secondComponent = tabs
        })

        project.messageBus.connect(this).subscribe(DelvaStoreList.TOPIC, DelvaStoreList.Listener {
            // The actions change the list on the EDT, and expect to select a store they added right after.
            if (ApplicationManager.getApplication().isDispatchThread) reloadList()
            else stores.launch { withContext(Dispatchers.EDT) { reloadList() } }
        })
        reloadList()
    }

    /** The store selected in the list, or null when none is. */
    val selectedStore: Path? get() = list.selectedValue

    /** Selects [dir] when the list holds it. */
    fun select(dir: Path) {
        val index = model.getElementIndex(dir)
        if (index >= 0) {
            list.selectedIndex = index
            list.ensureIndexIsVisible(index)
        }
    }

    /** Reads the selected store again, to show what its writer has done since: its documents, hits and types. */
    fun refresh() {
        details.show(list.selectedValue)
        search.refresh()
        types.refresh()
    }

    /** Shows the search of the selected store, ready to type a query. */
    fun showSearch() {
        tabs.selectedComponent = search
        search.focusForm()
    }

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        sink[PANEL] = this
        sink[SELECTED_STORE] = selectedStore
    }

    override fun dispose() {
        details.cancel()
        search.cancel()
        types.cancel()
    }

    private fun reloadList() {
        val selected = list.selectedValue
        val current = storeList.stores
        model.replaceAll(current)
        when {
            selected != null && selected in current -> select(selected)
            current.isNotEmpty() -> list.selectedIndex = 0
            else -> showSelected()
        }
    }

    private fun showSelected() {
        details.show(list.selectedValue)
        search.show(list.selectedValue)
        types.show(list.selectedValue)
    }

    companion object {
        val PANEL: DataKey<StoreExplorerPanel> = DataKey.create("app.oreshkov.delva.StoreExplorerPanel")
        val SELECTED_STORE: DataKey<Path> = DataKey.create("app.oreshkov.delva.SelectedStore")

        /** The documents or nodes selected in one of the tool window's lists. */
        val SELECTED_NODES: DataKey<SelectedNodes> = DataKey.create("app.oreshkov.delva.SelectedNodes")

        const val PLACE = "DelvaToolWindow"
        private const val TOOLBAR_GROUP = "app.oreshkov.delva.ToolWindow"
        private const val SPLITTER_KEY = "app.oreshkov.delva.StoreExplorer.splitter"
    }
}

/** A store as its directory's name, then where that directory is. */
private class StoreRenderer : ColoredListCellRenderer<Path>() {
    override fun customizeCellRenderer(list: JList<out Path>, value: Path?, index: Int, selected: Boolean, hasFocus: Boolean) {
        if (value == null) return
        append(value.fileName?.toString() ?: value.toString())
        value.parent?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
    }
}
