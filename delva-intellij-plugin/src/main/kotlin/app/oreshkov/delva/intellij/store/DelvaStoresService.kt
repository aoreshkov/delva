package app.oreshkov.delva.intellij.store

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The project's access to Delva stores. It owns the stores the project has open and closes them with the project, or
 * when the plugin is unloaded, so no file handle outlives either.
 */
@Service(Service.Level.PROJECT)
internal class DelvaStoresService(private val scope: CoroutineScope) : Disposable {
    private val embedded = EmbeddedStoreBackend()

    val backend: StoreBackend get() = embedded

    /** Runs [block] in the project's lifetime: it's cancelled when the project closes or the plugin is unloaded. */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    override fun dispose() {
        embedded.close()
    }

    companion object {
        fun getInstance(project: Project): DelvaStoresService = project.service()
    }
}
