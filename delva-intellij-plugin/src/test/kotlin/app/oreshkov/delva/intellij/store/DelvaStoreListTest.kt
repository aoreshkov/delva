package app.oreshkov.delva.intellij.store

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class DelvaStoreListTest : BasePlatformTestCase() {
    // The paths needn't exist: the list only names directories.
    private val root = FileUtil.createTempDirectory("delva-list", null, true).toPath()
    private lateinit var list: DelvaStoreList

    override fun setUp() {
        super.setUp()
        list = DelvaStoreList.getInstance(project)
        list.loadState(DelvaStoreList.Paths())
    }

    fun `test lists a directory once, in its normal form, and tells listeners`() {
        var changes = 0
        project.messageBus.connect(testRootDisposable).subscribe(DelvaStoreList.TOPIC, DelvaStoreList.Listener { changes++ })
        val dir = root.resolve("stores").resolve("orders")

        val listed = list.add(root.resolve("stores").resolve("other").resolve("..").resolve("orders"))
        assertEquals(dir.toAbsolutePath().normalize(), listed)
        assertEquals(listed, list.add(dir))
        assertEquals(listOf(listed), list.stores)
        assertEquals("the second add changed nothing", 1, changes)

        assertTrue(list.remove(dir))
        assertFalse(list.remove(dir))
        assertEquals(emptyList<Path>(), list.stores)
        assertEquals(2, changes)
    }

    fun `test saves the list in order, as system-independent paths`() {
        val before = list.state.modificationCount
        val a = list.add(root.resolve("a"))
        val b = list.add(root.resolve("b"))
        assertTrue("a change is saved", list.state.modificationCount > before)
        assertTrue(list.state.dirs.none { '\\' in it })

        val saved = DelvaStoreList.Paths().apply { copyFrom(list.state) }
        list.loadState(DelvaStoreList.Paths())
        list.loadState(saved)
        assertEquals(listOf(a, b), list.stores)
    }
}
