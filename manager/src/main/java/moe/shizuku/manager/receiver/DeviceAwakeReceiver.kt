package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ktx.logi
import moe.shizuku.manager.service.WatchdogManager
import moe.shizuku.manager.service.WatchdogService

/**
 * Gives the watchdog another chance when the device comes back to life.
 *
 * A phone left alone over a long doze comes back with wireless debugging off
 * and Wi-Fi dropped, which is why recovery runs out of attempts while nobody is
 * looking. Once the user is present again - which is the moment the device can
 * actually start anything - the attempt ladder starts over. Without this, an
 * exhausted watchdog stays silent until the app is opened by hand.
 *
 * Registered for `USER_PRESENT` and `BOOT_COMPLETED`-adjacent wake actions only;
 * it starts no work of its own beyond asking the watchdog to re-check.
 */
class DeviceAwakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_USER_PRESENT -> "device unlocked"
            Intent.ACTION_SCREEN_ON -> "screen on"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            else -> return
        }

        val appContext = context.applicationContext

        // The foreground service can be taken down by the platform while the
        // device dozes even with START_STICKY; bring it back before relying on
        // it for anything else.
        if (ShizukuSettings.getWatchdog() && !WatchdogService.isRunning()) {
            logi("DeviceAwakeReceiver: %s, restarting the watchdog service", reason)
            WatchdogService.start(appContext)
        }

        WatchdogManager.onDeviceAwake(appContext, reason)
    }
}
