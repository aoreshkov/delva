package app.oreshkov.delva.intellij.store

import app.oreshkov.delva.JsonStore
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The service in a running IDE: the platform's coroutines, its service container and the plugin's descriptor. */
class DelvaStoresServiceTest : BasePlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun `test searches a store through the project service`() = timeoutRunBlocking {
        // The light project outlives the test and keeps the store open, so the directory goes when the JVM does.
        val dir = FileUtil.createTempDirectory("delva-store", null, true).toPath()
        JsonStore.create(dir).use { writer -> writer.insert("""{"order": {"items": [{"sku": "x1"}, {"sku": "x2"}]}}""") }

        val outcome = DelvaStoresService.getInstance(project).backend.searchPath(dir, "$..sku", limit = 10, maxChars = 1000)

        assertEquals(listOf("\"x1\"", "\"x2\""), outcome.hits.map { it.json })
        assertEquals(listOf("$['order']['items'][0]['sku']", "$['order']['items'][1]['sku']"), outcome.hits.map { it.path })
    }
}
