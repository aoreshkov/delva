package app.oreshkov.delva.intellij.ui

import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.intellij.store.TypeSummary
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class StoreTypesPanelTest : BasePlatformTestCase() {
    // The light project outlives the test and keeps the store open, so the directory goes when the JVM does.
    private val dir: Path = FileUtil.createTempDirectory("delva-types", null, true).toPath().resolve("orders")

    fun `test lists a store's types and the objects of the one chosen`() {
        runBlocking {
            JsonStore.create(dir).use { writer ->
                writer.insert("""{"customer": {"name": "Ann"}, "order": {"total": 3}}""", mapOf("$.customer" to "Customer", "$.order" to "Order"))
                writer.insert("""{"order": {"total": 5}}""", mapOf("$.order" to "Order"))
            }
        }
        val panel = StoreTypesPanel(project)

        panel.show(dir)
        awaitRead(panel)
        assertEquals(listOf(TypeSummary("Customer", 1), TypeSummary("Order", 2)), panel.listedTypes)
        assertEquals("the first type is chosen", listOf("""{"name":"Ann"}"""), panel.listed.map { it.preview })
        assertEquals("1 object.", panel.statusText)

        panel.choose("Order")
        awaitRead(panel)
        assertEquals(listOf("""{"total":3}""", """{"total":5}"""), panel.listed.map { it.preview })
        assertEquals(listOf("$['order']", "$['order']"), panel.listed.map { it.path })

        runBlocking { JsonStore.open(dir).use { writer -> writer.insert("""{"order": {"total": 8}}""", mapOf("$.order" to "Order")) } }
        panel.refresh()
        awaitRead(panel)
        assertEquals("the chosen type stays chosen", 3, panel.listed.size)

        panel.show(null)
        assertEmpty(panel.listedTypes)
        assertEmpty(panel.listed)
    }

    fun `test lists objects a page at a time`() {
        runBlocking { JsonStore.create(dir).use { writer -> repeat(150) { writer.insert("""{"n": $it}""", mapOf("$" to "Row")) } } }
        val panel = StoreTypesPanel(project)

        panel.show(dir)
        awaitRead(panel)
        assertEquals(100, panel.listed.size)
        assertTrue(panel.hasMore)
        assertEquals("The first 100 objects; more have this type.", panel.statusText)

        panel.loadMore()
        awaitRead(panel)
        assertEquals((0 until 150).map { """{"n":$it}""" }, panel.listed.map { it.preview })
        assertFalse(panel.hasMore)
    }

    fun `test says when a store has no types`() {
        runBlocking { JsonStore.create(dir).use { writer -> writer.insert("""{"n": 1}""") } }
        val panel = StoreTypesPanel(project)

        panel.show(dir)
        awaitRead(panel)
        assertEmpty(panel.listedTypes)
        assertEquals("No types are assigned in this store.", panel.statusText)
    }

    private fun awaitRead(panel: StoreTypesPanel) =
        PlatformTestUtil.waitWithEventsDispatching("the read didn't end in time", { !panel.isReading }, 30)
}
