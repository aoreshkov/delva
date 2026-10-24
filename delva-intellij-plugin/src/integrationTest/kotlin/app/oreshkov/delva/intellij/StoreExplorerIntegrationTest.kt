package app.oreshkov.delva.intellij

import app.oreshkov.delva.CreationOptions
import app.oreshkov.delva.ExperimentalDelvaApi
import app.oreshkov.delva.JsonStore
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.isPluginLoaded
import com.intellij.driver.sdk.ui.components.common.editorTabs
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.toolWindow
import com.intellij.driver.sdk.ui.components.elements.JListUiComponent
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.report.ErrorReporterToCI
import com.intellij.ide.starter.runner.Starter
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The built plugin ZIP installed in the IntelliJ IDEA it builds against, as a user installs it from disk: the IDE loads
 * it with the Delva it bundles, shows a store the project lists, and opens one of its documents.
 */
class StoreExplorerIntegrationTest {
    private val workDir = Path(requiredProperty("delva.integrationTestDir"))

    @Test
    fun `lists a store and opens one of its documents`() {
        val projectDir = projectWithStore()
        val context = Starter.newContext(
            "storeExplorer",
            TestCase(IdeInfo.IdeaUltimate, LocalProjectInfo(projectDir)).useRelease(requiredProperty("delva.ideVersion")),
        ).apply {
            PluginConfigurator(this).installPluginFromPath(Path(requiredProperty("path.to.build.plugin")))
            applyVMOptionsPatch {
                // Nothing may cover the IDE's frame: no tips, no consent or privacy policy dialogs.
                addSystemProperty("ide.show.tips.on.startup.default.value", false)
                addSystemProperty("jb.consents.confirmation.enabled", false)
                addSystemProperty("jb.privacy.policy.text", "<!--999.999-->")
            }
            // A new configuration starts a trial of the paid features, whose page covers the editor; the plugin needs
            // none of them, and the IDE skips the trial when this marker is in its configuration directory.
            paths.configDir.createDirectories().resolve(".ce_migration_attempted").createFile()
        }.addProjectToTrustedLocations()

        val result = context.runIdeWithDriver().useDriverAndCloseIde {
            assertTrue(isPluginLoaded(PLUGIN_ID), "$PLUGIN_ID isn't loaded")
            waitForIndicators(5.minutes)
            invokeAction("ActivateDelvaToolWindow")
            ideFrame {
                toolWindow("Delva") {
                    val lists = { xx("//div[@class='JBList']", JListUiComponent::class.java).list() }
                    // The explorer selects its first store by itself.
                    waitFor("the listed store", 30.seconds, getter = { lists().firstOrNull() }) {
                        it != null && it.items.any { item -> item.startsWith(STORE_NAME) }
                    }

                    // Its documents come in key order, under the store's summary.
                    val documents = waitFor("the store's documents", 30.seconds, getter = { lists().getOrNull(1) }) {
                        it != null && it.items.size == KEYS.size
                    }!!
                    // A row reads as its key, id and size, separated by spaces.
                    val keys = documents.items.map { it.substringBefore(' ') }
                    assertEquals(KEYS.sorted(), keys)

                    // Selected and opened as Jump to Source does, the path a double click and Enter take too, without
                    // the mouse, which a locked screen doesn't move.
                    val list = driver.cast(documents.component, SwingList::class)
                    driver.withContext(OnDispatcher.EDT) { list.setSelectedIndex(keys.indexOf(KEYS.min())) }
                    driver.invokeAction("EditSource", component = documents.component)
                }
                waitFor("the document's editor", 30.seconds) {
                    editorTabs().getTabs().any { tab -> tab.text == "$STORE_NAME ${KEYS.min()}.json" }
                }
            }
        }

        // An exception the IDE caught is only logged, so the run passes without this.
        val errors = ErrorReporterToCI.collectErrors(result.runContext.logsDir)
            .filter { PLUGIN_PACKAGE in it.stackTraceContent }
        assertTrue(errors.isEmpty()) { errors.joinToString("\n\n") { it.messageText + "\n" + it.stackTraceContent } }
    }

    /** A project whose `.idea/delva.xml` lists a keyed store inside it, as the tool window saves it. */
    @OptIn(ExperimentalDelvaApi::class, kotlin.io.path.ExperimentalPathApi::class)
    private fun projectWithStore(): Path {
        val projectDir = workDir.resolve("projects/storeExplorer")
        projectDir.deleteRecursively()
        runBlocking {
            JsonStore.create(projectDir.resolve("data/$STORE_NAME"), CreationOptions().withDocumentKey("$.id")).use { store ->
                for (key in KEYS) store.insert("""{"id": "$key", "items": [{"sku": "A1", "qty": 2}]}""")
            }
        }
        projectDir.resolve(".idea").createDirectories().resolve("delva.xml").writeText(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<project version="4">
            |  <component name="DelvaStores">
            |    <option name="dirs">
            |      <list>
            |        <option value="${'$'}PROJECT_DIR${'$'}/data/$STORE_NAME" />
            |      </list>
            |    </option>
            |  </component>
            |</project>
            |""".trimMargin(),
        )
        return projectDir
    }

    private fun requiredProperty(name: String): String =
        checkNotNull(System.getProperty(name)?.takeIf { it.isNotBlank() }) {
            "System property $name isn't set; run ./gradlew :delva-intellij-plugin:integrationTest"
        }

    /** The IDE's list component, of which the test only selects. */
    @Remote("javax.swing.JList")
    interface SwingList {
        fun setSelectedIndex(index: Int)
    }

    private companion object {
        const val PLUGIN_ID = "app.oreshkov.delva"
        const val PLUGIN_PACKAGE = "app.oreshkov.delva.intellij"
        const val STORE_NAME = "orders"
        val KEYS = listOf("carol", "bob", "alice")
    }
}
