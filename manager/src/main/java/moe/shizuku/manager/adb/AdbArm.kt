package moe.shizuku.manager.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Single choke point for arming / disarming adbd.
 *
 * Writing `Settings.Global` requires `WRITE_SECURE_SETTINGS`, which the user
 * grants with `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`
 * or which the server grants back to the manager after the first successful
 * start. Every previously scattered `Settings.Global.putInt(ADB_*)` call site
 * routes through here so the permission check and the key set cannot drift
 * apart, and so a missing permission never crashes the caller.
 */
object AdbArm {

    private const val TAG = "AdbArm"

    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    fun hasWriteSecureSettings(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Whether adbd can be armed at all right now. */
    fun canArm(context: Context): Boolean = hasWriteSecureSettings(context)

    /**
     * Turn USB debugging + wireless debugging on.
     *
     * Idempotent: writing a value that is already set changes nothing.
     * `adb_allowed_connection_time = 0` keeps the wireless-debugging pairing
     * keys from expiring (the default lifetime is 7 days).
     *
     * @return true when the settings were written.
     */
    fun arm(context: Context): Boolean {
        val app = context.applicationContext
        if (!hasWriteSecureSettings(app)) {
            Log.d(TAG, "cannot arm adbd: WRITE_SECURE_SETTINGS not granted")
            return false
        }
        return try {
            val cr = app.contentResolver
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1)
            }
            Settings.Global.putLong(cr, ADB_ALLOWED_CONNECTION_TIME, 0L)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "arming adbd via WRITE_SECURE_SETTINGS failed", e)
            false
        }
    }

    /**
     * Re-arm only `adb_wifi_enabled` (0 -> 1 recovery path). Used by the unlock
     * receiver and the mDNS observer. Never throws, so it is safe to call from
     * a receiver.
     */
    fun armWifiEnabled(context: Context): Boolean {
        val app = context.applicationContext
        if (!hasWriteSecureSettings(app)) return false
        return putInt(app, ADB_WIFI_ENABLED, 1)
    }

    /** Turn wireless debugging off. */
    fun disarmWifiEnabled(context: Context): Boolean {
        val app = context.applicationContext
        if (!hasWriteSecureSettings(app)) return false
        return putInt(app, ADB_WIFI_ENABLED, 0)
    }

    /** Turn USB debugging off (honours the auto-disable-USB-debugging toggle). */
    fun disarmUsbDebugging(context: Context): Boolean {
        return setUsbDebuggingEnabled(context, false)
    }

    fun setUsbDebuggingEnabled(context: Context, enabled: Boolean): Boolean {
        val app = context.applicationContext
        if (!hasWriteSecureSettings(app)) return false
        val expected = if (enabled) 1 else 0
        val written = putInt(app, Settings.Global.ADB_ENABLED, expected)
        return written && runCatching {
            Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, 0) == expected
        }.getOrDefault(false)
    }

    private fun putInt(context: Context, key: String, value: Int): Boolean {
        return try {
            Settings.Global.putInt(context.contentResolver, key, value)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "writing $key=$value failed", e)
            false
        }
    }
}
