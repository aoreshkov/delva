package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.JsonStore
import app.oreshkov.delva.intellij.store.VerifyProblem
import app.oreshkov.delva.intellij.store.VerifyResult
import app.oreshkov.delva.intellij.store.VerifyStep
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

class VerifyStoreTest : BasePlatformTestCase() {
    // The light project outlives the test and keeps the store open, so the directory goes when the JVM does.
    private val dir: Path = FileUtil.createTempDirectory("delva-verify", null, true).toPath().resolve("orders")

    fun `test verifies a store in the background and says it is intact`() {
        runBlocking { JsonStore.create(dir).use { writer -> repeat(10) { writer.insert("""{"n": $it}""") } } }
        val notified = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notified += notification
            }
        })

        val job = verifyStore(project, dir)!!
        assertNull("a store being verified isn't verified again", verifyStore(project, dir))
        PlatformTestUtil.waitWithEventsDispatching("the verification didn't end in time", { job.isCompleted && notified.isNotEmpty() }, 30)

        val notification = notified.single()
        assertTrue(notification.content, notification.content.startsWith("orders is intact: "))
        assertNotNull("it can be verified again", verifyStore(project, dir)?.also { it.cancel() })
    }

    fun `test maps each step's bytes to an equal share of the progress`() {
        val reported = mutableListOf<Pair<Int, String>>()
        val steps = VerifyProgressSteps { workSize, text -> reported += workSize to text }
        steps.report(VerifyStep.LOG, 0, 2048)
        steps.report(VerifyStep.LOG, 1024, 2048)
        steps.report(VerifyStep.LOG, 1024, 2048)
        steps.report(VerifyStep.LOG, 2048, 2048)
        steps.report(VerifyStep.INDEX, 0, 0)
        steps.report(VerifyStep.FRAGMENTS, 10, 10)

        val units = VerifyProgressSteps.STEP_UNITS
        assertEquals("a report that doesn't move the bar is left out", 5, reported.size)
        assertEquals("the steps add up to the whole bar", VerifyStep.entries.size * units, reported.sumOf { it.first })
        assertEquals("Checking the log: 0 B of ${StringUtil.formatFileSize(2048)}", reported[0].second)
        assertEquals("Checking the indexes: 0 B of 0 B", reported[3].second)
        assertEquals("the log ends at its full share", units + 1, reported.take(4).sumOf { it.first })
    }

    fun `test reports each problem and the documents it makes unreadable`() {
        val problems = listOf(
            VerifyProblem(VerifyProblem.Kind.LOG_RECORD, "data/lane-S-000001.frag @ 4096", "checksum mismatch", 128, 7),
            VerifyProblem(VerifyProblem.Kind.FRAGMENT_INDEX, "index/seg-000002.sst @ 0", "locates no fragment", 0, 7),
            VerifyProblem(VerifyProblem.Kind.SEGMENT, "index/seg-000003.sst @ 512", "bad block", 0, null),
        )
        val report = verifyReport(Path.of("orders"), VerifyResult(4, 8192, problems))
        assertEquals(
            """
            Verified orders: 4 files, ${StringUtil.formatFileSize(8192)}.
            3 problems; reads of the data they name fail, everything else is still served.
            Documents that can't be read: #7

            Log record at data/lane-S-000001.frag @ 4096: checksum mismatch (${StringUtil.formatFileSize(128)} damaged) [document #7]
            Fragment index at index/seg-000002.sst @ 0: locates no fragment [document #7]
            Index segment at index/seg-000003.sst @ 512: bad block
            """.trimIndent() + "\n",
            report.replace("\r\n", "\n"),
        )
    }
}
