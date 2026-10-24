package app.oreshkov.delva.intellij.actions

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.readActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFileManager
import java.nio.file.Path

// A store's files are binary and change with every write, so indexing them as part of the project only costs time.

/** Whether the IDE indexes the store in [dir] as part of [project]'s content. */
internal suspend fun isIndexed(project: Project, dir: Path): Boolean = readAction {
    // Only a directory the VFS knows already can be in the project's content; looking it up must not add it.
    val file = VirtualFileManager.getInstance().findFileByNioPath(dir) ?: return@readAction false
    ProjectFileIndex.getInstance(project).isInContent(file)
}

/**
 * Excludes the store in [dir] from [project]'s content, as Mark Directory as | Excluded does, so its files aren't
 * indexed; Cancel Exclusion takes it back. Returns false when the store isn't in the content of a module.
 */
internal suspend fun excludeFromIndexing(project: Project, dir: Path): Boolean {
    val (module, root, file) = readAction {
        val file = VirtualFileManager.getInstance().findFileByNioPath(dir) ?: return@readAction null
        val index = ProjectFileIndex.getInstance(project)
        val module = index.getModuleForFile(file) ?: return@readAction null
        val root = index.getContentRootForFile(file) ?: return@readAction null
        Triple(module, root, file)
    } ?: return false
    // Not in a read action that a write can restart: a model it took before the restart would never be disposed.
    val model = readActionBlocking { ModuleRootManager.getInstance(module).modifiableModel }
    try {
        val entry = model.contentEntries.firstOrNull { it.file == root } ?: return false
        entry.addExcludeFolder(file.url)
        edtWriteAction { model.commit() }
        return true
    } finally {
        if (!model.isDisposed) model.dispose()
    }
}
