package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.intellij.DelvaBundle
import app.oreshkov.delva.intellij.store.DelvaStoresService
import app.oreshkov.delva.intellij.store.DocumentEntry
import app.oreshkov.delva.intellij.store.StoreAccessException
import app.oreshkov.delva.intellij.store.StoreHit
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.limits.FileSizeLimit
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.pom.Navigatable
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** A node of a stored document, as the tool window lists it: a document itself, or a search hit. */
internal data class StoredNode(
    val docId: Long,
    /** The node's number in its document, in pre-order: 0 for the document itself. */
    val nodePre: Long,
    val documentKey: String?,
    /** The node's normalized path in its document: `$` for the document itself. */
    val path: String,
    val encodedSize: Long,
) {
    constructor(document: DocumentEntry) : this(document.docId, 0, document.key, "$", document.encodedSize)

    constructor(hit: StoreHit) : this(hit.docId, hit.nodePre, hit.documentKey, hit.path, hit.encodedSize)
}

/**
 * Opens [node] of the store in [dir] in a read-only JSON editor, or the editor already showing it: a document never
 * changes once stored, as a replacement gets an id of its own, so one already open is still current.
 */
internal fun openNode(project: Project, dir: Path, node: StoredNode) {
    val shown = NodeEditors.Shown(dir, node.docId, node.nodePre)
    val editors = project.service<NodeEditors>()
    editors.find(shown)?.let {
        open(project, it)
        return
    }
    val stores = DelvaStoresService.getInstance(project)
    val name = nodeName(dir, node)
    stores.launch {
        // Past the IDE's limit for code insight a JSON editor loses folding and highlighting, which a person reading a
        // document needs most; a larger one is better read with the command line.
        val maxChars = FileSizeLimit.getIntellisenseLimit("json")
        val text = try {
            withBackgroundProgress(project, DelvaBundle.message("document.progress", name)) {
                stores.backend.readNode(dir, node.docId, node.nodePre, maxChars)
            }
        } catch (e: StoreAccessException) {
            notify(project, e.message.orEmpty(), NotificationType.ERROR)
            return@launch
        }
        withContext(Dispatchers.EDT) {
            if (text == null) {
                val sizes = arrayOf(StringUtil.formatFileSize(node.encodedSize), StringUtil.formatFileSize(maxChars.toLong()))
                notify(project, DelvaBundle.message("document.tooLarge", name, *sizes), NotificationType.WARNING)
            } else {
                open(project, editors.remember(shown, jsonFile(name, text)))
            }
        }
    }
}

/** Opens [node] of the store in [dir] when the platform navigates to it: on a double click, Enter or Jump to Source. */
internal class NodeNavigatable(
    private val project: Project,
    private val dir: Path,
    private val node: StoredNode,
) : Navigatable {
    override fun navigate(requestFocus: Boolean) = openNode(project, dir, node)

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = true
}

/** A read-only JSON file named [name] that holds [text], for an editor tab. */
internal fun jsonFile(name: String, text: String): LightVirtualFile {
    val json = FileTypeManager.getInstance().getFileTypeByExtension("json")
    // The name is the tab's title; a separator in it would turn it into a path.
    val fileName = name.replace('/', '∕').replace('\\', '∖')
    return LightVirtualFile("$fileName.json", json, text).apply { isWritable = false }
}

internal fun open(project: Project, file: VirtualFile) {
    // Every blocking openFile is deprecated from 2026.3 on, and its suspending successor isn't in 2026.2.
    OpenFileDescriptor(project, file).navigate(true)
}

internal fun notify(project: Project, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance().getNotificationGroup("Delva").createNotification(content, type).notify(project)
}

/**
 * The store's directory name, then the document's key, or its id in a store without document keys, then where the
 * node is in the document unless it's the document itself.
 */
private fun nodeName(dir: Path, node: StoredNode): String {
    val store = dir.fileName ?: dir
    val key = node.documentKey
    val document = if (key != null) DelvaBundle.message("document.name.key", store, StringUtil.shortenTextWithEllipsis(key, 60, 10))
    else DelvaBundle.message("document.name.id", store, node.docId.toString())
    return if (node.nodePre == 0L) document
    else DelvaBundle.message("node.name", document, StringUtil.shortenTextWithEllipsis(node.path, 60, 20))
}

/**
 * The files of the nodes open in editors, so a node opened again shows the editor it has. Kept by the project, not in
 * the files' user data, so nothing of the plugin stays referenced from the platform's objects. EDT only.
 */
@Service(Service.Level.PROJECT)
internal class NodeEditors(private val project: Project) {
    data class Shown(val dir: Path, val docId: Long, val nodePre: Long)

    private val files = HashMap<Shown, VirtualFile>()

    /** The file of [shown] while an editor still shows it, else null. */
    fun find(shown: Shown): VirtualFile? {
        val editors = FileEditorManager.getInstance(project)
        files.values.removeIf { !editors.isFileOpen(it) }
        return files[shown]
    }

    fun remember(shown: Shown, file: VirtualFile): VirtualFile = file.also { files[shown] = it }
}
