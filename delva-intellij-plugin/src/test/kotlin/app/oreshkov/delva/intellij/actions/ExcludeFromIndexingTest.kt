package app.oreshkov.delva.intellij.actions

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import java.nio.file.Files

/** Exclusion from indexing in a project on disk, whose module has a content root. */
class ExcludeFromIndexingTest : HeavyPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun `test excludes a store inside the project's content and leaves others alone`() = timeoutRunBlocking {
        val root = createTempDirectory().toPath()
        val store = Files.createDirectories(root.resolve("data").resolve("orders"))
        val outside = createTempDirectory().toPath()
        PsiTestUtil.addContentRoot(module, LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(store)!!

        assertTrue("a store in the content is indexed", isIndexed(project, store))
        assertFalse(isIndexed(project, outside))
        assertFalse("nothing to exclude outside the content", excludeFromIndexing(project, outside))

        assertTrue(excludeFromIndexing(project, store))
        assertFalse("an excluded store isn't indexed", isIndexed(project, store))
        assertTrue("the rest of the content still is", isIndexed(project, root.resolve("data")))
    }
}
