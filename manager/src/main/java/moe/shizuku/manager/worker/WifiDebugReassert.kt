package moe.shizuku.manager.worker

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

object WifiDebugReassert {

    private const val TAG = "WifiDebugReassert"

    fun reassert(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return

            val cr = context.contentResolver
            val flag = Settings.Global.getInt(cr, "adb_wifi_enabled", 0)
            if (flag == 1) return

            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            Log.d(TAG, "re-armed adb_wifi_enabled 0 -> 1")
        } catch (e: Throwable) {
            Log.d(TAG, "re-arm failed: ${e.message}")
        }
    }
}
