package app.oreshkov.delva.intellij.ui

import app.oreshkov.delva.intellij.DelvaBundle
import app.oreshkov.delva.intellij.actions.NodeNavigatable
import app.oreshkov.delva.intellij.actions.StoredNode
import app.oreshkov.delva.intellij.store.DelvaStoresService
import app.oreshkov.delva.intellij.store.HitPage
import app.oreshkov.delva.intellij.store.SearchPlan
import app.oreshkov.delva.intellij.store.StoreAccessException
import app.oreshkov.delva.intellij.store.StoreHit
import app.oreshkov.delva.intellij.store.StoreQuery
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.observable.util.equalsTo
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.EditSourceOnEnterKeyHandler
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * Searches the selected store, by JSONPath or by a key and value, and lists the hits a page at a time, each opened in
 * an editor on a double click or Enter; for JSONPath it also explains how the store would find the documents. Touched
 * on the EDT only; reads run on the project's coroutine scope.
 */
internal class StoreSearchPanel(private val project: Project, parent: Disposable) : BorderLayoutPanel(), UiDataProvider {
    private val stores = DelvaStoresService.getInstance(project)

    private val graph = PropertyGraph()
    private val kind = graph.property(Kind.PATH)
    private val path = graph.property("")
    private val where = graph.property("")
    private val pathHits = graph.property(HitScope.MATCHES)
    private val scan = graph.property(false)
    private val key = graph.property("")
    private val value = graph.property("")
    private val valueIsText = graph.property(false)
    private val keyValueHits = graph.property(HitScope.DOCUMENTS)

    private lateinit var pathField: JComponent
    private lateinit var keyField: JComponent
    private val form: DialogPanel = form()

    private val hits = CollectionListModel<StoreHit>()
    private val list = JBList(hits)
    private val status = BorderLayoutPanel().apply { border = JBUI.Borders.empty(0, 8, 4, 8) }
    private val more = ActionLink(DelvaBundle.message("search.more")) { loadMore() }.apply {
        border = JBUI.Borders.empty(4, 8)
        isVisible = false
    }

    private var shown: Path? = null

    /** The search whose hits are listed, or null when none are. */
    private var searched: StoreQuery? = null
    private var next: String? = null
    private var running: Job? = null

    /** Counts the reads started, so the outcome of one that was replaced or stopped is dropped. */
    private var generation = 0

    init {
        form.registerValidators(parent)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = HitRenderer()
        // Both open what the data context's navigatable points at: the selected hit.
        EditSourceOnDoubleClickHandler.install(list)
        EditSourceOnEnterKeyHandler.install(list)

        addToTop(BorderLayoutPanel().addToTop(form).addToBottom(status))
        addToCenter(ScrollPaneFactory.createScrollPane(list, true))
        addToBottom(more)
    }

    /** The hits listed so far. */
    val listed: List<StoreHit> get() = hits.items

    /** Whether the search has hits past those listed. */
    val hasMore: Boolean get() = next != null

    /** Whether a read is under way. */
    val isReading: Boolean get() = running != null

    /** Searches the store in [dir] from now on, or none for null; the hits of another store are cleared. */
    fun show(dir: Path?) {
        if (dir == shown) return
        cancel()
        shown = dir
        clear()
        showStatus("")
    }

    /** Runs the listed search again, to show what the store's writer has done since. */
    fun refresh() {
        run(searched ?: return)
    }

    /** Puts the focus in the form's first field. */
    fun focusForm() {
        IdeFocusManager.getInstance(project).requestFocus(if (kind.get() == Kind.PATH) pathField else keyField, true)
    }

    /** Fills in a JSONPath search, as a person would. */
    @TestOnly
    fun setPathQuery(query: String, where: String = "", wholeDocuments: Boolean = false, scan: Boolean = false) {
        kind.set(Kind.PATH)
        path.set(query)
        this.where.set(where)
        pathHits.set(if (wholeDocuments) HitScope.DOCUMENTS else HitScope.MATCHES)
        this.scan.set(scan)
    }

    /** Fills in a key–value search, as a person would. */
    @TestOnly
    fun setKeyValueQuery(key: String, value: String, valueIsText: Boolean = false, wholeDocuments: Boolean = true) {
        kind.set(Kind.KEY_VALUE)
        this.key.set(key)
        this.value.set(value)
        this.valueIsText.set(valueIsText)
        keyValueHits.set(if (wholeDocuments) HitScope.DOCUMENTS else HitScope.MATCHES)
    }

    /** What the line under the form says, as plain text. */
    var statusText: String = ""
        private set

    /** Lists the first hits of the search the form describes, unless the form has a mistake, which it then shows. */
    fun search() {
        if (shown == null || form.validateAll().isNotEmpty()) return
        run(query())
    }

    /** Shows how the store would find the documents of the JSONPath search the form describes. */
    fun explain() {
        val dir = shown ?: return
        if (kind.get() != Kind.PATH || form.validateAll().isNotEmpty()) return
        val query = query() as StoreQuery.ByPath
        clear()
        read(dir, { stores.backend.explain(dir, query) }) { showStatus(planText(it), planView(it)) }
    }

    /** Lists the next page of hits. */
    fun loadMore() {
        val dir = shown ?: return
        val query = searched ?: return
        val after = next ?: return
        if (running != null) return
        read(dir, { stores.backend.search(dir, query, after, PAGE_SIZE) }) { if (next == after) list(it) }
    }

    /** Stops a read in progress. */
    fun cancel() {
        running?.cancel()
        running = null
        generation++
    }

    override fun uiDataSnapshot(sink: DataSink) {
        val dir = shown ?: return
        val hit = list.selectedValue ?: return
        val navigatable = NodeNavigatable(project, dir, StoredNode(hit))
        sink[CommonDataKeys.NAVIGATABLE] = navigatable
        sink[CommonDataKeys.NAVIGATABLE_ARRAY] = arrayOf(navigatable)
    }

    private fun run(query: StoreQuery) {
        val dir = shown ?: return
        clear()
        searched = query
        read(dir, { stores.backend.search(dir, query, null, PAGE_SIZE) }, ::list)
    }

    /**
     * Runs [read] on the project's scope, showing that it's under way with a way to stop it, then [show]s what it
     * returns, on the EDT, unless another read or another store has taken its place meanwhile. A failure shows its
     * reason instead.
     */
    private fun <T> read(dir: Path, read: suspend () -> T, show: (T) -> Unit) {
        cancel()
        val started = generation
        more.isEnabled = false
        val reading = DelvaBundle.message("search.running")
        showStatus(reading, panel {
            row {
                label(reading)
                link(DelvaBundle.message("search.stop")) { stop() }
            }
        })
        running = stores.launch {
            val outcome = try {
                Result.success(read())
            } catch (e: StoreAccessException) {
                Result.failure(e)
            }
            withContext(Dispatchers.EDT) {
                if (generation != started || shown != dir) return@withContext
                running = null
                more.isEnabled = true
                outcome.fold(show) { e -> e.message.orEmpty().let { showStatus(it, failure(it)) } }
            }
        }
    }

    private fun stop() {
        cancel()
        more.isEnabled = true
        showStatus(DelvaBundle.message("search.stopped"))
    }

    private fun list(page: HitPage) {
        hits.add(page.hits)
        next = page.next
        more.isVisible = next != null
        val count = hits.size
        showStatus(DelvaBundle.message(if (next != null) "search.hits.more" else "search.hits", count))
    }

    private fun clear() {
        hits.removeAll()
        searched = null
        next = null
        more.isVisible = false
    }

    /** Shows [text] under the form, laid out as [content]. */
    private fun showStatus(text: String, content: JComponent? = if (text.isEmpty()) null else message(text)) {
        statusText = text
        status.removeAll()
        if (content != null) status.addToCenter(content)
        status.revalidate()
        status.repaint()
    }

    private fun query(): StoreQuery = when (kind.get()) {
        Kind.PATH -> StoreQuery.ByPath(path.get(), where.get().ifBlank { null }, pathHits.get() == HitScope.DOCUMENTS, scan.get())
        Kind.KEY_VALUE -> StoreQuery.ByKeyValue(key.get(), value.get(), valueIsText.get(), keyValueHits.get() == HitScope.DOCUMENTS)
    }

    private fun form(): DialogPanel = panel {
        row {
            segmentedButton(Kind.entries) { text = DelvaBundle.message(it.key) }.bind(kind)
        }
        rowsRange {
            row(DelvaBundle.message("search.path")) {
                pathField = expandableTextField()
                    .bindText(path)
                    .align(AlignX.FILL)
                    .validationOnInput { field -> checkQuery(field.text)?.let { error(it) } }
                    .validationOnApply { field ->
                        when {
                            kind.get() != Kind.PATH -> null
                            field.text.isBlank() -> error(DelvaBundle.message("search.path.empty"))
                            else -> checkQuery(field.text)?.let { error(it) }
                        }
                    }
                    .applyToComponent { addActionListener { search() } }
                    .component
            }
            row(DelvaBundle.message("search.where")) {
                expandableTextField()
                    .bindText(where)
                    .align(AlignX.FILL)
                    .comment(DelvaBundle.message("search.where.comment"))
                    .validationOnInput { field -> checkFilter(field.text)?.let { error(it) } }
                    .validationOnApply { field -> if (kind.get() != Kind.PATH) null else checkFilter(field.text)?.let { error(it) } }
                    .applyToComponent { addActionListener { search() } }
            }
            row(DelvaBundle.message("search.hits.label")) {
                comboBox(HitScope.entries, scopeRenderer(HitScope::pathKey)).bindItem(pathHits)
            }
            row {
                checkBox(DelvaBundle.message("search.scan")).bindSelected(scan)
            }
        }.visibleIf(kind.equalsTo(Kind.PATH))
        rowsRange {
            row(DelvaBundle.message("search.key")) {
                keyField = textField()
                    .bindText(key)
                    .align(AlignX.FILL)
                    .validationOnApply { field ->
                        if (kind.get() == Kind.KEY_VALUE && field.text.isEmpty()) error(DelvaBundle.message("search.key.empty")) else null
                    }
                    .applyToComponent { addActionListener { search() } }
                    .component
            }
            row(DelvaBundle.message("search.value")) {
                textField()
                    .bindText(value)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .applyToComponent { addActionListener { search() } }
                checkBox(DelvaBundle.message("search.value.text")).bindSelected(valueIsText)
            }
            row(DelvaBundle.message("search.hits.label")) {
                comboBox(HitScope.entries, scopeRenderer(HitScope::keyValueKey)).bindItem(keyValueHits)
            }
        }.visibleIf(kind.equalsTo(Kind.KEY_VALUE))
        row {
            button(DelvaBundle.message("search.run")) { search() }
            button(DelvaBundle.message("search.explain")) { explain() }.visibleIf(kind.equalsTo(Kind.PATH))
        }
    }.apply { border = JBUI.Borders.empty(0, 8) }

    /** Why [query] can't be run, or null when it can, or is empty and so not yet typed. */
    private fun checkQuery(query: String): String? = if (query.isEmpty()) null else stores.backend.checkQuery(query)

    /** Why [filter] can't be applied, or null when it can or is blank, which applies none. */
    private fun checkFilter(filter: String): String? = if (filter.isBlank()) null else stores.backend.checkFilter(filter)

    /** Shows each [HitScope] as the message its [key] names. */
    private fun scopeRenderer(key: (HitScope) -> String) =
        textListCellRenderer<HitScope?> { it?.let { scope -> DelvaBundle.message(key(scope)) } }

    private enum class Kind(val key: String) {
        PATH("search.kind.path"),
        KEY_VALUE("search.kind.keyValue"),
    }

    /** What each hit is, as each kind of search words it. */
    private enum class HitScope(val pathKey: String, val keyValueKey: String) {
        MATCHES("search.hits.path.matches", "search.hits.keyValue.matches"),
        DOCUMENTS("search.hits.path.documents", "search.hits.keyValue.documents"),
    }

    private companion object {
        const val PAGE_SIZE = 100
    }
}

private fun message(text: String): JComponent = panel { row { text(StringUtil.escapeXmlEntities(text)) } }

/** How the store would find a JSONPath search's documents, as `delva explain` puts it: what each line names and says. */
private fun planLines(plan: SearchPlan): List<Pair<String, String>> = listOf(
    DelvaBundle.message("search.plan.reads") to
        DelvaBundle.message(if (plan.fullScan) "search.plan.reads.scan" else "search.plan.reads.terms"),
    DelvaBundle.message("search.plan.terms") to (plan.terms ?: DelvaBundle.message("search.plan.terms.none")),
    DelvaBundle.message("search.plan.within") to (plan.within ?: DelvaBundle.message("search.plan.within.anywhere")),
)

private fun planText(plan: SearchPlan): String = planLines(plan).joinToString("\n") { (name, says) -> "$name $says" }

private fun planView(plan: SearchPlan): JComponent = panel {
    for ((name, says) in planLines(plan)) row(name) { text(StringUtil.escapeXmlEntities(says)) }
}

/** A hit as its document's key, or its id in a store without keys, then where it is in the document and how it starts. */
private class HitRenderer : ColoredListCellRenderer<StoreHit>() {
    override fun customizeCellRenderer(list: JList<out StoreHit>, value: StoreHit?, index: Int, selected: Boolean, hasFocus: Boolean) {
        if (value == null) return
        append(value.documentKey ?: DelvaBundle.message("explorer.documents.id", value.docId.toString()))
        if (value.nodePre != 0L) append("  ${value.path}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append("  ${value.preview}${if (value.previewCut) "…" else ""}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
}
