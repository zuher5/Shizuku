package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.service.WatchdogManager
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku

class ManualStopReceiver : AuthenticatedReceiver() {
    override fun onAuthenticated(context: Context, intent: Intent) {
        val applicationId = BuildConfig.APPLICATION_ID
        if (intent.action != "${applicationId}.STOP") return
        if (!ShizukuStateMachine.isRunning()) return

        // A manual stop must not be undone by the watchdog.
        WatchdogManager.setUserStopRequested(true)
        ShizukuStateMachine.onStopRequested()
        runCatching { Shizuku.exit() }
    }
}