package moe.shizuku.manager.starter

import android.util.Log
import androidx.lifecycle.asFlow
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.utils.ShizukuStateMachine

private val app = ShizukuApplication.application

/**
 * How long a start waits for the delivered binder before giving up.
 *
 * The server is launched by a shell command that returns as soon as it spawned the process, so the
 * wait covers the JVM starting, the server reaching its own main, and the delivery. A minute is
 * generous for all three; a start that outlives it has failed rather than merely run long.
 */
internal const val BINDER_WAIT_TIMEOUT_MS = 60_000L

object Starter {

    private val starterFile = File(app.applicationInfo.nativeLibraryDir, "libshizuku.so")

    val userCommand: String = starterFile.absolutePath
    val adbCommand = "adb shell $userCommand"
    val internalCommand = "$userCommand --apk=${app.applicationInfo.sourceDir}"

    val serviceStartedMessage = "Service started, this window will be automatically closed in 3 seconds"

    suspend fun waitForBinder(log: ((String) -> Unit)? = null) {
        try {
            log?.invoke("\nWaiting for service. This may take up to 1 minute...")
            withTimeout(BINDER_WAIT_TIMEOUT_MS) {
                ShizukuStateMachine.asFlow()
                    .first { it == ShizukuStateMachine.State.RUNNING }
            }
            log?.invoke(serviceStartedMessage)
        } catch (e: TimeoutCancellationException) {
            // The command was accepted, so the usual causes of a silent wait (a locked screen, a
            // disabled wireless-debugging setting, an unauthorized network) are behind us. What is
            // left is a server that was launched and never delivered, which needs its own verdict:
            // reported as a plain timeout, a support report cannot tell it from a connection that
            // never happened.
            Log.w(
                "Starter",
                "no binder within ${BINDER_WAIT_TIMEOUT_MS}ms (state=${ShizukuStateMachine.get()})"
            )
            throw StarterBinderTimeoutException(BINDER_WAIT_TIMEOUT_MS)
        }
    }

}

/**
 * The start went through and the server did not deliver its binder in time.
 *
 * Deliberately not a [java.util.concurrent.TimeoutException]: that one is thrown by a socket read
 * or a discovery deadline and is treated as a retryable connection failure. This is the case where
 * the shell command ran and nothing followed.
 */
class StarterBinderTimeoutException(private val timeoutMs: Long) :
    IllegalStateException("The service was started but sent no binder within ${timeoutMs}ms")
