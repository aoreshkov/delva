package app.oreshkov.delva.intellij.store

import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence

/** The list as the platform saves it, in a project on disk. */
class DelvaStoreListStorageTest : HeavyPlatformTestCase() {
    fun `test saves a directory inside the project relative to it`() {
        val base = Path.of(project.basePath!!)
        DelvaStoreList.getInstance(project).add(base.resolve("data").resolve("orders"))

        PlatformTestUtil.saveProject(project, true)

        val saved = Files.walk(base).use { files ->
            files.asSequence().filter { it.isRegularFile() }.map { it.readText() }.single { "DelvaStores" in it }
        }
        assertTrue(saved, "\$PROJECT_DIR\$/data/orders" in saved)
    }
}
