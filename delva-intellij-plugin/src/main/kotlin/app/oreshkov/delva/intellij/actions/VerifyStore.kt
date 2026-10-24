package app.oreshkov.delva.intellij.actions

import app.oreshkov.delva.intellij.DelvaBundle
import app.oreshkov.delva.intellij.store.DelvaStoresService
import app.oreshkov.delva.intellij.store.StoreAccessException
import app.oreshkov.delva.intellij.store.VerifyProblem
import app.oreshkov.delva.intellij.store.VerifyResult
import app.oreshkov.delva.intellij.store.VerifyStep
import app.oreshkov.delva.intellij.ui.StoreExplorerPanel
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap

/** Checks every byte of the store selected in the Delva tool window, in the background. */
internal class VerifyStoreAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val dir = e.getData(StoreExplorerPanel.SELECTED_STORE)
        e.presentation.isEnabled = project != null && dir != null && !project.service<StoreVerifications>().isRunning(dir)
    }

    override fun actionPerformed(e: AnActionEvent) {
        verifyStore(e.project ?: return, e.getData(StoreExplorerPanel.SELECTED_STORE) ?: return)
    }
}

/**
 * Verifies the store in [dir] under a background progress the person can cancel, then says what it found in a
 * notification. Returns the verification's job, or null when the store is being verified already.
 */
internal fun verifyStore(project: Project, dir: Path): Job? {
    val verifications = project.service<StoreVerifications>()
    if (!verifications.start(dir)) return null
    val stores = DelvaStoresService.getInstance(project)
    val name = (dir.fileName ?: dir).toString()
    return stores.launch {
        try {
            val result = withBackgroundProgress(project, DelvaBundle.message("verify.progress", name)) {
                reportSequentialProgress(VerifyStep.entries.size * VerifyProgressSteps.STEP_UNITS) { reporter ->
                    val steps = VerifyProgressSteps { workSize, text -> reporter.sizedStep(workSize, text) }
                    stores.backend.verify(dir, steps::report)
                }
            }
            withContext(Dispatchers.EDT) { notifyVerified(project, dir, result) }
        } catch (e: StoreAccessException) {
            notify(project, DelvaBundle.message("verify.failed", name, e.message.orEmpty()), NotificationType.ERROR)
        } finally {
            verifications.finish(dir)
        }
    }
}

/**
 * Turns what verifying reports, bytes checked of each [VerifyStep]'s own total, into [sizedStep]s of a progress
 * reporter: each [VerifyStep] takes an equal share of the bar, and the step's name and its bytes are the progress text.
 */
internal class VerifyProgressSteps(private val sizedStep: (workSize: Int, text: String) -> Unit) {
    private var reported = 0

    fun report(step: VerifyStep, bytesChecked: Long, bytesTotal: Long) {
        // A report always moves a step at least one unit in, so the first one of a step shows its name even when the
        // step before ended at its full share; a step only takes work sizes above zero.
        val within = if (bytesTotal <= 0) 1 else 1 + (bytesChecked.coerceIn(0, bytesTotal) * (STEP_UNITS - 1) / bytesTotal).toInt()
        val target = step.ordinal * STEP_UNITS + within
        if (target <= reported) return
        val bytes = arrayOf(StringUtil.formatFileSize(bytesChecked), StringUtil.formatFileSize(maxOf(bytesChecked, bytesTotal)))
        sizedStep(target - reported, DelvaBundle.message("verify.step.${step.name}", *bytes))
        reported = target
    }

    companion object {
        /** Work units of one step: fine enough for a smooth bar. */
        const val STEP_UNITS = 1000
    }
}

/** The stores being verified, so a store isn't verified twice at once. */
@Service(Service.Level.PROJECT)
internal class StoreVerifications {
    private val running = ConcurrentHashMap.newKeySet<Path>()

    /** Whether [dir] is being verified. */
    fun isRunning(dir: Path): Boolean = dir in running

    /** Records that [dir] is being verified; false when it already is. */
    fun start(dir: Path): Boolean = running.add(dir)

    fun finish(dir: Path) {
        running.remove(dir)
    }
}

/** What verifying [dir] found, as a person reads it: the summary, then each problem on a line of its own. */
internal fun verifyReport(dir: Path, result: VerifyResult): String = buildString {
    val numbers = NumberFormat.getIntegerInstance()
    appendLine(DelvaBundle.message("verify.report.checked", dir, result.filesChecked, StringUtil.formatFileSize(result.bytesChecked)))
    if (result.problems.isEmpty()) {
        appendLine(DelvaBundle.message("verify.report.intact"))
        return@buildString
    }
    appendLine(DelvaBundle.message("verify.report.problems", result.problems.size))
    if (result.damagedDocuments.isNotEmpty()) {
        appendLine(DelvaBundle.message("verify.report.documents", result.damagedDocuments.joinToString { "#${numbers.format(it)}" }))
    }
    appendLine()
    for (problem in result.problems) appendLine(problemLine(problem))
}

private fun problemLine(problem: VerifyProblem): String = buildString {
    append(DelvaBundle.message("verify.problem", DelvaBundle.message("verify.kind.${problem.kind.name}"), problem.location, problem.detail))
    if (problem.damagedBytes > 0) append(' ').append(DelvaBundle.message("verify.problem.bytes", StringUtil.formatFileSize(problem.damagedBytes)))
    problem.docId?.let { append(' ').append(DelvaBundle.message("verify.problem.document", it.toString())) }
}

private fun notifyVerified(project: Project, dir: Path, result: VerifyResult) {
    val name = (dir.fileName ?: dir).toString()
    val group = NotificationGroupManager.getInstance().getNotificationGroup("Delva")
    if (result.problems.isEmpty()) {
        val bytes = StringUtil.formatFileSize(result.bytesChecked)
        group.createNotification(DelvaBundle.message("verify.intact", name, result.filesChecked, bytes), NotificationType.INFORMATION)
            .notify(project)
        return
    }
    val content = DelvaBundle.message("verify.damaged", name, result.problems.size, result.damagedDocuments.size)
    group.createNotification(content, NotificationType.ERROR)
        .addAction(NotificationAction.createSimple(DelvaBundle.message("verify.showReport")) {
            val file = LightVirtualFile(DelvaBundle.message("verify.report.name", name), PlainTextFileType.INSTANCE, verifyReport(dir, result))
            file.isWritable = false
            open(project, file)
        })
        .notify(project)
}
