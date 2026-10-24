package app.oreshkov.delva.intellij.ui

import app.oreshkov.delva.intellij.DelvaBundle
import app.oreshkov.delva.intellij.actions.StoredNode
import app.oreshkov.delva.intellij.store.DelvaStoresService
import app.oreshkov.delva.intellij.store.HitPage
import app.oreshkov.delva.intellij.store.StoreAccessException
import app.oreshkov.delva.intellij.store.StoreHit
import app.oreshkov.delva.intellij.store.TypeSummary
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.awt.event.ItemEvent
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JList

/**
 * The types assigned in the selected store, and the objects of the chosen one a page at a time, each opened in an
 * editor on a double click or Enter. Touched on the EDT only; reads run on the project's coroutine scope.
 */
internal class StoreTypesPanel(private val project: Project) : BorderLayoutPanel(), UiDataProvider {
    private val stores = DelvaStoresService.getInstance(project)
    private val types = CollectionComboBoxModel<TypeSummary>()
    private val typeBox = ComboBox(types).apply { renderer = TypeRenderer() }
    private val objects = CollectionListModel<StoreHit>()
    private val list = JBList(objects)
    private val status = BorderLayoutPanel().apply { border = JBUI.Borders.empty(0, 8, 4, 8) }
    private val more = ActionLink(DelvaBundle.message("types.more")) { loadMore() }.apply {
        border = JBUI.Borders.empty(4, 8)
        isVisible = false
    }

    private var shown: Path? = null
    private var next: String? = null
    private var loading: Job? = null

    /** Counts the reads started, so the outcome of one that was replaced is dropped. */
    private var generation = 0

    /** Whether the type box is being filled, which isn't a person choosing a type. */
    private var filling = false

    init {
        configureNodeList(list, HitRenderer())
        typeBox.addItemListener { if (it.stateChange == ItemEvent.SELECTED && !filling) listObjects() }
        val form = panel {
            row(DelvaBundle.message("types.type")) { cell(typeBox).align(AlignX.FILL) }
        }.apply { border = JBUI.Borders.empty(0, 8) }
        addToTop(BorderLayoutPanel().addToTop(form).addToBottom(status))
        addToCenter(ScrollPaneFactory.createScrollPane(list, true))
        addToBottom(more)
    }

    /** The types listed. */
    val listedTypes: List<TypeSummary> get() = types.items

    /** The objects of the chosen type listed so far. */
    val listed: List<StoreHit> get() = objects.items

    /** Whether the chosen type has objects past those listed. */
    val hasMore: Boolean get() = next != null

    /** Whether a read is under way. */
    val isReading: Boolean get() = loading != null

    /** What the line under the type box says, as plain text. */
    var statusText: String = ""
        private set

    /** Shows the types of the store in [dir], or nothing for null; the first type's objects are listed. */
    fun show(dir: Path?) {
        if (dir == shown) return
        shown = dir
        reload(keep = null)
    }

    /** Reads the store's types again, and the chosen type's objects, to show what its writer has done since. */
    fun refresh() {
        reload(keep = (typeBox.selectedItem as TypeSummary?)?.name)
    }

    /** Lists the objects of the type named [name], as a person choosing it would. */
    fun choose(name: String) {
        types.items.firstOrNull { it.name == name }?.let { typeBox.selectedItem = it }
    }

    /** Lists the next page of objects of the chosen type. */
    fun loadMore() {
        val dir = shown ?: return
        val type = (typeBox.selectedItem as TypeSummary?)?.name ?: return
        val after = next ?: return
        if (loading != null) return
        read(dir, { stores.backend.objectsOfType(dir, type, after, PAGE_SIZE) }) { if (next == after) list(it) }
    }

    /** Stops a read in progress. */
    fun cancel() {
        loading?.cancel()
        loading = null
        generation++
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink.selectedNodes(project, shown ?: return, list.selectedValuesList.map { StoredNode(it) })
    }

    /** Lists the store's types again, choosing the one named [keep] when the store still has it, else the first. */
    private fun reload(keep: String?) {
        val dir = shown
        clearObjects()
        fill(emptyList(), null)
        if (dir == null) {
            showStatus("")
            return
        }
        read(dir, { stores.backend.types(dir) }) { listed ->
            fill(listed, listed.firstOrNull { it.name == keep } ?: listed.firstOrNull())
            if (listed.isEmpty()) showStatus(DelvaBundle.message("types.none")) else listObjects()
        }
    }

    private fun fill(listed: List<TypeSummary>, chosen: TypeSummary?) {
        filling = true
        try {
            types.replaceAll(listed)
            typeBox.selectedItem = chosen
            typeBox.isEnabled = listed.isNotEmpty()
        } finally {
            filling = false
        }
    }

    private fun listObjects() {
        val dir = shown ?: return
        val type = (typeBox.selectedItem as TypeSummary?)?.name ?: return
        clearObjects()
        read(dir, { stores.backend.objectsOfType(dir, type, null, PAGE_SIZE) }, ::list)
    }

    /**
     * Runs [read] on the project's scope, then [show]s what it returns, on the EDT, unless another read or another store
     * has taken its place meanwhile. A failure shows its reason instead.
     */
    private fun <T> read(dir: Path, read: suspend () -> T, show: (T) -> Unit) {
        cancel()
        val started = generation
        more.isEnabled = false
        showStatus(DelvaBundle.message("explorer.loading"))
        loading = stores.launch {
            val outcome = try {
                Result.success(read())
            } catch (e: StoreAccessException) {
                Result.failure(e)
            }
            withContext(Dispatchers.EDT) {
                if (generation != started || shown != dir) return@withContext
                loading = null
                more.isEnabled = true
                outcome.fold(show) { e -> e.message.orEmpty().let { showStatus(it, failure(it)) } }
            }
        }
    }

    private fun list(page: HitPage) {
        objects.add(page.hits)
        next = page.next
        more.isVisible = next != null
        val count = objects.size
        showStatus(DelvaBundle.message(if (next != null) "types.objects.more" else "types.objects", count))
    }

    private fun clearObjects() {
        objects.removeAll()
        next = null
        more.isVisible = false
    }

    /** Shows [text] under the type box, laid out as [content]. */
    private fun showStatus(text: String, content: JComponent? = if (text.isEmpty()) null else statusMessage(text)) {
        statusText = text
        status.removeAll()
        if (content != null) status.addToCenter(content)
        status.revalidate()
        status.repaint()
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}

/** A type as its name, then how many objects have it. */
private class TypeRenderer : ColoredListCellRenderer<TypeSummary>() {
    override fun customizeCellRenderer(
        list: JList<out TypeSummary>,
        value: TypeSummary?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value == null) return
        append(value.name)
        append("  ${DelvaBundle.message("types.count", value.approximateCount)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}
