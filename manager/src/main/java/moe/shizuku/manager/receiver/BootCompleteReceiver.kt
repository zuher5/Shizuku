package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbArm
import moe.shizuku.manager.service.WatchdogManager
import moe.shizuku.manager.service.WatchdogService
import moe.shizuku.manager.utils.ShizukuStateMachine

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        if (ShizukuStateMachine.isRunning()) {
            // The server is already up: make sure the watchdog keeps running so
            // a later death is still noticed.
            if (ShizukuSettings.getWatchdog()) WatchdogService.start(context)
            return
        }

        if (!ShizukuSettings.getStartOnBoot(context)) return

        // The enabled boot-start preference is an explicit start request and
        // overrides a manual stop persisted from the previous session.
        WatchdogManager.clearUserStopRequest(context.applicationContext)

        // Arm adbd before the start attempt, so wireless debugging is already
        // available when the worker looks for it. Both writes are idempotent.
        if (ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ADB) {
            AdbArm.arm(context)
        }

        // ShizukuReceiverStarter owns the root/ADB branch and the permission
        // checks, so it stays the single entry point for a boot start.
        ShizukuReceiverStarter.start(context)

        if (ShizukuSettings.getWatchdog()) WatchdogService.start(context)

        Log.i(AppConstants.TAG, "Boot start requested")
    }
}
