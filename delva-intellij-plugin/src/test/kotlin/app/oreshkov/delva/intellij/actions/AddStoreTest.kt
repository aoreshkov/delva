package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.intellij.store.DelvaStoreList
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class AddStoreTest : BasePlatformTestCase() {
    // The light project outlives the test, so the directories go when the JVM does.
    private val root: Path = FileUtil.createTempDirectory("delva-add", null, true).toPath()

    /** The messages of the dialogs shown, each answered with [answer]. */
    private val asked = mutableListOf<String>()
    private var answer = Messages.CANCEL

    override fun setUp() {
        super.setUp()
        TestDialogManager.setTestDialog(TestDialog { message -> asked += message; answer }, testRootDisposable)
    }

    override fun tearDown() {
        try {
            // The light project's list outlives the test too.
            val list = DelvaStoreList.getInstance(project)
            for (dir in list.stores.filter { it.startsWith(root) }) list.remove(dir)
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    fun `test adds a store it reads without asking`() {
        val dir = root.resolve("orders")
        runBlocking { JsonStore.create(dir).use { writer -> writer.insert("""{"n": 1}""") } }

        val added = mutableListOf<Path>()
        await(addChosenStore(project, dir, panel = null) { added.add(it) })

        assertEquals(emptyList<String>(), asked)
        assertEquals(listOf(dir), listed())
        assertEquals(listOf(dir), added)
    }

    fun `test asks before adding a directory without a store, and adds nothing on Cancel`() {
        val dir = root.resolve("empty").also { it.toFile().mkdirs() }

        val added = mutableListOf<Path>()
        await(addChosenStore(project, dir, panel = null) { added.add(it) })

        assertTrue("$asked", asked.single().startsWith("There is no Delva store in $dir yet."))
        assertEquals(emptyList<Path>(), listed())
        assertEquals(emptyList<Path>(), added)

        answer = Messages.OK
        await(addChosenStore(project, dir, panel = null) { added.add(it) })
        assertEquals("Add Anyway adds it", listOf(dir), listed())
        assertEquals(listOf(dir), added)
    }

    private fun listed(): List<Path> = DelvaStoreList.getInstance(project).stores.filter { it.startsWith(root) }

    private fun await(job: Job) {
        PlatformTestUtil.waitWithEventsDispatching("adding the store didn't end in time", { job.isCompleted }, 30)
    }
}
