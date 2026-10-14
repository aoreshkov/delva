package app.oreshkov.delva.internal.io

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * Calls [wait] until it returns without an `InterruptedException`, then sets the thread's interrupt flag again if an
 * interrupt came in meanwhile. For waits whose work goes on regardless, such as a submitted write: leaving early would
 * report an outcome that isn't the true one, while the restored flag still tells the caller it was interrupted.
 */
internal inline fun <T> uninterruptibly(wait: () -> T): T {
    var interrupted = false
    try {
        while (true) {
            try {
                return wait()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

/**
 * Runs [block] on a new virtual thread and waits for it without being interruptible. Nothing else can reach that
 * thread, so no interrupt can close a channel while [block] forces it: an interrupted `force` loses its result, and
 * the bytes it covered would be in an unknown state.
 */
internal fun <T> onOwnThread(block: () -> T): T {
    val task = FutureTask(block)
    Thread.ofVirtual().name("delva-force").start(task)
    return uninterruptibly {
        try {
            task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
