package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.service.WatchdogManager

class ManualStartReceiver : AuthenticatedReceiver() {
    override fun onAuthenticated(context: Context, intent: Intent) {
        val applicationId = BuildConfig.APPLICATION_ID
        if (intent.action != "${applicationId}.START") return

        // An authenticated explicit start overrides the prior user-stop state,
        // matching the in-app starter. Otherwise a failed start leaves the
        // watchdog permanently suppressed until another binder connection.
        WatchdogManager.clearUserStopRequest(context.applicationContext)
        ShizukuReceiverStarter.start(context)
    }
}
