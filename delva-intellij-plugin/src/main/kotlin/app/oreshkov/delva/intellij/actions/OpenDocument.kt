package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.intellij.DelvaBundle
import app.oreshkov.delva.intellij.store.DelvaStoresService
import app.oreshkov.delva.intellij.store.DocumentEntry
import app.oreshkov.delva.intellij.store.StoreAccessException
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

/**
 * Opens [document] of the store in [dir] in a read-only JSON editor, or the editor already showing it: a document never
 * changes once stored, as a replacement gets an id of its own, so one already open is still current.
 */
internal fun openDocument(project: Project, dir: Path, document: DocumentEntry) {
    val shown = DocumentEditors.Shown(dir, document.docId)
    val editors = project.service<DocumentEditors>()
    editors.find(shown)?.let {
        open(project, it)
        return
    }
    val stores = DelvaStoresService.getInstance(project)
    val name = documentName(dir, document)
    stores.launch {
        // Past the IDE's limit for code insight a JSON editor loses folding and highlighting, which a person reading a
        // document needs most; a larger one is better read with the command line.
        val maxChars = FileSizeLimit.getIntellisenseLimit("json")
        val text = try {
            withBackgroundProgress(project, DelvaBundle.message("document.progress", name)) {
                stores.backend.readDocument(dir, document.docId, maxChars)
            }
        } catch (e: StoreAccessException) {
            notify(project, e.message.orEmpty(), NotificationType.ERROR)
            return@launch
        }
        withContext(Dispatchers.EDT) {
            if (text == null) {
                val sizes = arrayOf(StringUtil.formatFileSize(document.encodedSize), StringUtil.formatFileSize(maxChars.toLong()))
                notify(project, DelvaBundle.message("document.tooLarge", name, *sizes), NotificationType.WARNING)
            } else {
                open(project, editors.remember(shown, jsonFile(name, text)))
            }
        }
    }
}

/** Opens [document] of the store in [dir] when the platform navigates to it: on a double click, Enter or Jump to Source. */
internal class DocumentNavigatable(
    private val project: Project,
    private val dir: Path,
    private val document: DocumentEntry,
) : Navigatable {
    override fun navigate(requestFocus: Boolean) = openDocument(project, dir, document)

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

/** The store's directory name, then the document's key, or its id in a store without document keys. */
private fun documentName(dir: Path, document: DocumentEntry): String {
    val store = dir.fileName ?: dir
    val key = document.key
    return if (key != null) DelvaBundle.message("document.name.key", store, StringUtil.shortenTextWithEllipsis(key, 60, 10))
    else DelvaBundle.message("document.name.id", store, document.docId.toString())
}

/**
 * The files of the documents open in editors, so a document opened again shows the editor it has. Kept by the project,
 * not in the files' user data, so nothing of the plugin stays referenced from the platform's objects. EDT only.
 */
@Service(Service.Level.PROJECT)
internal class DocumentEditors(private val project: Project) {
    data class Shown(val dir: Path, val docId: Long)

    private val files = HashMap<Shown, VirtualFile>()

    /** The file of [shown] while an editor still shows it, else null. */
    fun find(shown: Shown): VirtualFile? {
        val editors = FileEditorManager.getInstance(project)
        files.values.removeIf { !editors.isFileOpen(it) }
        return files[shown]
    }

    fun remember(shown: Shown, file: VirtualFile): VirtualFile = file.also { files[shown] = it }
}
