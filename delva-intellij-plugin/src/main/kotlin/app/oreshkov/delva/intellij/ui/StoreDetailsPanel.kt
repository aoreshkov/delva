package app.oreshkov.delva.intellij.ui

import app.oreshkov.delva.intellij.DelvaBundle
import app.oreshkov.delva.intellij.actions.NodeNavigatable
import app.oreshkov.delva.intellij.actions.StoredNode
import app.oreshkov.delva.intellij.actions.excludeFromIndexing
import app.oreshkov.delva.intellij.actions.isIndexed
import app.oreshkov.delva.intellij.actions.notify
import app.oreshkov.delva.intellij.store.DelvaStoresService
import app.oreshkov.delva.intellij.store.DocumentEntry
import app.oreshkov.delva.intellij.store.DocumentPage
import app.oreshkov.delva.intellij.store.StoreAccessException
import app.oreshkov.delva.intellij.store.StoreSummary
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InlineBanner
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.EditSourceOnEnterKeyHandler
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.text.NumberFormat
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * What the selected store holds: its summary, then its documents a page at a time, each opened in an editor on a
 * double click or Enter. Touched on the EDT only; reads run on the project's coroutine scope.
 */
internal class StoreDetailsPanel(private val project: Project) : BorderLayoutPanel(), UiDataProvider {
    private val stores = DelvaStoresService.getInstance(project)
    private val documents = CollectionListModel<DocumentEntry>()
    private val list = JBList(documents)
    private val documentsPane = ScrollPaneFactory.createScrollPane(list, true)
    private val more = ActionLink(DelvaBundle.message("explorer.documents.more")) { loadMore() }.apply {
        border = JBUI.Borders.empty(4, 8)
    }
    private var shown: Path? = null
    private var next: String? = null
    private var loading: Job? = null

    init {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = DocumentRenderer()
        list.emptyText.text = DelvaBundle.message("explorer.documents.empty")
        // Both open what the data context's navigatable points at: the selected document.
        EditSourceOnDoubleClickHandler.install(list)
        EditSourceOnEnterKeyHandler.install(list)
    }

    /** Shows what the store in [dir] holds now, or nothing for null. */
    fun show(dir: Path?) {
        cancel()
        shown = dir
        documents.removeAll()
        next = null
        if (dir == null) {
            display(null, null)
            return
        }
        display(panel { row { label(DelvaBundle.message("explorer.loading")) } }, null)
        loading = stores.launch {
            val content = try {
                val summary = stores.backend.info(dir)
                val page = stores.backend.documents(dir, null, PAGE_SIZE)
                Loaded(summary, page, isIndexed(project, dir))
            } catch (e: StoreAccessException) {
                Failed(e.message.orEmpty())
            }
            withContext(Dispatchers.EDT) {
                if (shown != dir) return@withContext
                when (content) {
                    is Loaded -> {
                        documents.replaceAll(content.page.documents)
                        next = content.page.next
                        display(header(dir, content), documentsPane)
                    }
                    is Failed -> display(failure(content.reason), null)
                }
            }
        }
    }

    /** The documents listed so far. */
    val listed: List<DocumentEntry> get() = documents.items

    /** Whether the store holds documents past those listed. */
    val hasMore: Boolean get() = next != null

    /** Lists the next page of documents. */
    fun loadMore() {
        val dir = shown ?: return
        val after = next ?: return
        if (loading?.isActive == true) return
        more.isEnabled = false
        loading = stores.launch {
            val page = try {
                stores.backend.documents(dir, after, PAGE_SIZE)
            } catch (e: StoreAccessException) {
                withContext(Dispatchers.EDT) {
                    if (shown == dir) display(failure(e.message.orEmpty()), null)
                }
                return@launch
            }
            withContext(Dispatchers.EDT) {
                if (shown != dir || next != after) return@withContext
                documents.add(page.documents)
                next = page.next
                more.isEnabled = true
                more.isVisible = next != null
            }
        }
    }

    /** Stops a read in progress. */
    fun cancel() {
        loading?.cancel()
        loading = null
    }

    override fun uiDataSnapshot(sink: DataSink) {
        val dir = shown ?: return
        val document = list.selectedValue ?: return
        val navigatable = NodeNavigatable(project, dir, StoredNode(document))
        sink[CommonDataKeys.NAVIGATABLE] = navigatable
        sink[CommonDataKeys.NAVIGATABLE_ARRAY] = arrayOf(navigatable)
    }

    private fun exclude(dir: Path, banner: InlineBanner) {
        stores.launch {
            val excluded = excludeFromIndexing(project, dir)
            withContext(Dispatchers.EDT) {
                if (excluded) banner.close()
                else notify(project, DelvaBundle.message("explorer.indexed.notExcluded", dir), NotificationType.WARNING)
            }
        }
    }

    private fun display(north: JComponent?, center: JComponent?) {
        removeAll()
        if (north != null) addToTop(north)
        if (center != null) {
            addToCenter(center)
            more.isEnabled = true
            more.isVisible = next != null
            addToBottom(more)
        }
        revalidate()
        repaint()
    }

    private fun header(dir: Path, loaded: Loaded): JComponent {
        val summary = loaded.summary
        val numbers = NumberFormat.getIntegerInstance()
        return panel {
            if (loaded.indexed) {
                val banner = InlineBanner(DelvaBundle.message("explorer.indexed"), EditorNotificationPanel.Status.Info)
                banner.addAction(DelvaBundle.message("explorer.indexed.exclude")) { exclude(dir, banner) }
                row { cell(banner).align(AlignX.FILL) }
            }
            row(DelvaBundle.message("explorer.details.path")) { text(dir.toString()) }
            row(DelvaBundle.message("explorer.details.documents")) { text(numbers.format(summary.documents)) }
            row(DelvaBundle.message("explorer.details.deleted")) { text(numbers.format(summary.deletedDocuments)) }
            row(DelvaBundle.message("explorer.details.writer")) {
                text(summary.writer ?: DelvaBundle.message("explorer.details.writer.none"))
            }
            summary.documentKey?.let { key -> row(DelvaBundle.message("explorer.details.documentKey")) { text(key) } }
            row {
                val order = if (summary.documentKey != null) "explorer.documents.byKey" else "explorer.documents.byId"
                label(DelvaBundle.message(order)).bold()
            }
        }.apply { border = JBUI.Borders.empty(0, 8) }
    }

    private sealed interface Content
    private class Loaded(val summary: StoreSummary, val page: DocumentPage, val indexed: Boolean) : Content
    private class Failed(val reason: String) : Content

    private companion object {
        const val PAGE_SIZE = 100
    }
}

/** [reason] beside an error icon; as plain text, since the label reads HTML and a query has `<` and `&` in it. */
internal fun failure(reason: String): JComponent = panel {
    row {
        icon(AllIcons.General.Error).align(AlignY.TOP)
        text(StringUtil.escapeXmlEntities(reason))
    }
}.apply { border = JBUI.Borders.empty(0, 8) }

/** A document as its key, or its id in a store without keys, then its id and roughly how large it is. */
private class DocumentRenderer : ColoredListCellRenderer<DocumentEntry>() {
    override fun customizeCellRenderer(
        list: JList<out DocumentEntry>,
        value: DocumentEntry?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value == null) return
        val id = DelvaBundle.message("explorer.documents.id", value.docId.toString())
        if (value.key != null) {
            append(value.key)
            append("  $id", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        } else {
            append(id)
        }
        append("  ${StringUtil.formatFileSize(value.encodedSize)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}
