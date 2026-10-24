package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import moe.shizuku.manager.service.WatchdogManager
import moe.shizuku.manager.worker.AdbStartWorker

class NotifAttemptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The user explicitly asked for another attempt, so a pending worker is
        // replaced instead of being kept and any prior manual stop is cleared.
        WatchdogManager.clearUserStopRequest(context.applicationContext)
        AdbStartWorker.enqueue(context, ExistingWorkPolicy.REPLACE)
    }
}
