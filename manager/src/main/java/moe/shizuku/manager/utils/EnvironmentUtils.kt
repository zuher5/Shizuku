package moe.shizuku.manager.utils

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.SystemProperties
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import com.topjohnwu.superuser.Shell
import java.net.InetSocketAddress
import java.net.Socket

private val appContext = ShizukuApplication.appContext

private const val PORT_PROBE_TIMEOUT_MS = 250

object EnvironmentUtils {

    @JvmStatic
    fun isWatch(): Boolean {
        return (appContext.getSystemService(UiModeManager::class.java).currentModeType
                == Configuration.UI_MODE_TYPE_WATCH)
    }

    @JvmStatic
    fun isTelevision(): Boolean {
        return (appContext.getSystemService(UiModeManager::class.java).currentModeType
                == Configuration.UI_MODE_TYPE_TELEVISION ||
                appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
    }

    fun isTlsSupported(): Boolean {
        return if (isTelevision())
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            else Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    fun isWifiRequired(): Boolean {
        val hasTcpPort = getAdbTcpPort() > 0
        // TVs use a static TCP port; mDNS discovery is unreliable on LEANBACK.
        return !(hasTcpPort && (ShizukuSettings.getTcpMode() || isTelevision()))
    }

    fun isRooted(): Boolean {
        return Shell.getShell().isRoot
    }

    fun getAdbTcpPort(): Int {
        var port = SystemProperties.getInt("service.adb.tcp.port", -1)
        if (port == -1) port = SystemProperties.getInt("persist.adb.tcp.port", -1)
        if (port == -1 && isTelevision() && !isTlsSupported()) port = ShizukuSettings.getTcpPort()
        return port
    }

    /**
     * True when something is listening on the given loopback port.
     * A short timeout keeps this cheap enough to call on the start path.
     */
    @JvmStatic
    fun isAdbPortLive(port: Int): Boolean {
        if (port <= 0) return false
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), PORT_PROBE_TIMEOUT_MS)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The ADB port that is actually reachable right now, or -1.
     * Wireless debugging picks a random port per boot, so the configured port,
     * the port used by the last successful start and the TCP mode port are all
     * probed.
     */
    @JvmStatic
    fun getLiveAdbTcpPort(): Int {
        val candidates = sequenceOf(getAdbTcpPort(), ShizukuSettings.getLastAdbPort(), 5555)
            .filter { it > 0 }
            .distinct()
        return candidates.firstOrNull { isAdbPortLive(it) } ?: -1
    }
}
