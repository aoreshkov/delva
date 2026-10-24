package app.oreshkov.delva.intellij.ui

import app.oreshkov.delva.intellij.DelvaBundle
import app.oreshkov.delva.intellij.actions.addStore
import app.oreshkov.delva.intellij.store.DelvaStoreList
import app.oreshkov.delva.intellij.store.DelvaStoresService
import app.oreshkov.delva.intellij.store.StoreAccessException
import app.oreshkov.delva.intellij.store.StoreSummary
import com.intellij.icons.AllIcons
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
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.components.BorderLayoutPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.text.NumberFormat
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

/** The Delva tool window's content: the project's stores, and what the selected one holds. */
internal class StoreExplorerPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val storeList = DelvaStoreList.getInstance(project)
    private val stores = DelvaStoresService.getInstance(project)
    private val model = CollectionListModel<Path>()
    private val list = JBList(model)
    private val details = BorderLayoutPanel()
    private var loading: Job? = null

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
            secondComponent = ScrollPaneFactory.createScrollPane(details, true)
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

    /** Reads the selected store again, to show what its writer has done since. */
    fun refresh() = showSelected()

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        sink[PANEL] = this
        sink[SELECTED_STORE] = selectedStore
    }

    override fun dispose() {
        loading?.cancel()
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
        loading?.cancel()
        val dir = list.selectedValue
        if (dir == null) {
            show(null)
            return
        }
        show(panel { row { label(DelvaBundle.message("explorer.loading")) } })
        loading = stores.launch {
            val shown = try {
                summaryOf(dir, stores.backend.info(dir))
            } catch (e: StoreAccessException) {
                failure(e.message.orEmpty())
            }
            withContext(Dispatchers.EDT) {
                if (list.selectedValue == dir) show(shown())
            }
        }
    }

    private fun show(component: JComponent?) {
        details.removeAll()
        if (component != null) details.addToCenter(component)
        details.revalidate()
        details.repaint()
    }

    companion object {
        val PANEL: DataKey<StoreExplorerPanel> = DataKey.create("app.oreshkov.delva.StoreExplorerPanel")
        val SELECTED_STORE: DataKey<Path> = DataKey.create("app.oreshkov.delva.SelectedStore")

        const val PLACE = "DelvaToolWindow"
        private const val TOOLBAR_GROUP = "app.oreshkov.delva.ToolWindow"
        private const val SPLITTER_KEY = "app.oreshkov.delva.StoreExplorer.splitter"
    }
}

// Built off the EDT, as plain values, and turned into Swing on it.

private fun summaryOf(dir: Path, summary: StoreSummary): () -> JComponent = {
    val numbers = NumberFormat.getIntegerInstance()
    panel {
        row(DelvaBundle.message("explorer.details.path")) { text(dir.toString()) }
        row(DelvaBundle.message("explorer.details.documents")) { text(numbers.format(summary.documents)) }
        row(DelvaBundle.message("explorer.details.deleted")) { text(numbers.format(summary.deletedDocuments)) }
        row(DelvaBundle.message("explorer.details.writer")) {
            text(summary.writer ?: DelvaBundle.message("explorer.details.writer.none"))
        }
    }
}

private fun failure(reason: String): () -> JComponent = {
    panel {
        row {
            icon(AllIcons.General.Error).align(AlignY.TOP)
            text(reason)
        }
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
