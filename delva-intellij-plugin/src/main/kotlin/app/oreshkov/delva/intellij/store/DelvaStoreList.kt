package app.oreshkov.delva.intellij.store

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.messages.Topic
import java.nio.file.Path

/**
 * The store directories a project lists in the Delva tool window. They're kept in the project's `delva.xml`, where the
 * platform writes a directory inside the project relative to it, so the list can be shared like run configurations.
 */
@Service(Service.Level.PROJECT)
@State(name = "DelvaStores", storages = [Storage("delva.xml")])
internal class DelvaStoreList(private val project: Project) :
    SimplePersistentStateComponent<DelvaStoreList.Paths>(Paths()) {

    class Paths : BaseState() {
        /** System-independent absolute paths, in the order they were added. */
        var dirs by list<String>()

        // A list property doesn't count changes made in place, and an unchanged count isn't saved.
        internal fun add(dir: String): Boolean = dirs.add(dir).also { incrementModificationCount() }

        internal fun remove(dir: String): Boolean = dirs.remove(dir).also { if (it) incrementModificationCount() }
    }

    val stores: List<Path> get() = state.dirs.map(::pathOf)

    /** Adds [dir] at the end unless it's listed already, and returns it as listed. */
    fun add(dir: Path): Path {
        val key = keyOf(dir)
        if (key !in state.dirs) {
            state.add(key)
            changed()
        }
        return pathOf(key)
    }

    /** Removes [dir], and returns false when it isn't listed. */
    fun remove(dir: Path): Boolean {
        if (!state.remove(keyOf(dir))) return false
        changed()
        return true
    }

    override fun loadState(state: Paths) {
        super.loadState(state)
        project.messageBus.syncPublisher(TOPIC).storesChanged()
    }

    private fun changed() {
        project.messageBus.syncPublisher(TOPIC).storesChanged()
    }

    private fun keyOf(dir: Path): String = FileUtil.toSystemIndependentName(dir.toAbsolutePath().normalize().toString())

    private fun pathOf(key: String): Path = Path.of(FileUtil.toSystemDependentName(key))

    /** Told whenever stores are added, removed or loaded, on the thread that did it, which needn't be the EDT. */
    fun interface Listener {
        fun storesChanged()
    }

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(project: Project): DelvaStoreList = project.service()
    }
}
