package moe.shizuku.manager.adb

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine

object AdbStarter {

    const val TCP_MODE_PORT = 5555

    private const val CONNECT_RETRIES = 8

    /**
     * Starts the Shizuku server through ADB.
     *
     * @param port the port adbd is reachable at.
     */
    suspend fun startAdb(context: Context, port: Int, log: ((String) -> Unit)? = null) {
        start(port = port, context = context, log = log)
    }

    /**
     * Starts the Shizuku server through ADB.
     *
     * When TCP mode is enabled the daemon is rebound to the fixed TCP port
     * first; the rebind drops the current socket, so the failure of that
     * command is expected and ignored.
     */
    suspend fun start(
        host: String = "127.0.0.1",
        port: Int,
        context: Context? = null,
        listener: ((ByteArray) -> Unit)? = null,
        log: ((String) -> Unit)? = null,
    ) {
        ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
        log?.invoke("Starting with wireless adb...\n")

        val key = withContext(Dispatchers.IO) {
            runCatching { AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku") }
                .getOrElse {
                    if (it is CancellationException) throw it
                    else throw AdbKeyException(it)
                }
        }

        val tcpMode = ShizukuSettings.getTcpMode()
        val targetPort = if (tcpMode) TCP_MODE_PORT else port
        var startedSuccessfully = false

        try {
            withContext(Dispatchers.IO) {
                // Only rebind when the target port is not already serving: a live
                // port must be reused, not switched.
                val targetAlreadyLive = EnvironmentUtils.isAdbPortLive(targetPort)
                if (tcpMode && port != targetPort && !targetAlreadyLive) {
                    log?.invoke("Switching ADB from port $port to TCP port $targetPort...")
                    switchToTcp(host, port, targetPort, key)
                }

                log?.invoke("Connecting to ADB on port $targetPort...")
                connectWithRetry(host, targetPort, key) { client ->
                    ShizukuSettings.setLastLaunchMode(ShizukuSettings.LaunchMethod.ADB)
                    ShizukuSettings.setLastAdbPort(targetPort)
                    client.shellCommand(Starter.internalCommand) { data ->
                        log?.invoke(String(data))
                        listener?.invoke(data)
                    }
                }
                startedSuccessfully = true
            }
        } finally {
            // Only touch wireless debugging when this starter put the device in
            // TCP mode and successfully launched the service. Disabling it
            // unconditionally breaks the automatic retry and the watchdog
            // recovery, because the next attempt would find no adbd to talk to.
            if (tcpMode && startedSuccessfully) {
                disableWirelessDebugging(context)
            }
        }
    }

    private suspend fun switchToTcp(host: String, currentPort: Int, targetPort: Int, key: AdbKey) {
        AdbClient(host, currentPort, key).use { client ->
            client.connect()
            try {
                client.command("tcpip:$targetPort")
            } catch (e: IOException) {
                // Expected: adbd restarts and drops the socket when it switches
                // to TCP mode, so the connection breaks here on purpose.
            }
        }
    }

    private suspend fun connectWithRetry(
        host: String,
        port: Int,
        key: AdbKey,
        retries: Int = CONNECT_RETRIES,
        block: (AdbClient) -> Unit,
    ) {
        var lastError: Throwable? = null
        repeat(retries) { attempt ->
            try {
                AdbClient(host, port, key).use { client ->
                    client.connect()
                    block(client)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                if (attempt == retries - 1) throw e
                delay(500L * (attempt + 1))
            }
        }
        lastError?.let { throw it }
    }

    private fun disableWirelessDebugging(context: Context?) {
        val appContext = context?.applicationContext ?: return
        if (appContext.checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            Settings.Global.putInt(appContext.contentResolver, "adb_wifi_enabled", 0)
        }
    }

    /**
     * Switches adbd back to USB mode. Kept for the settings and home screens.
     */
    suspend fun stopTcp(context: Context, port: Int) {
        runCatching {
            val cr = context.contentResolver
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
            }

            val adbEnabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
            if (adbEnabled == 0) throw IllegalStateException("ADB is not enabled")

            ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            withContext(Dispatchers.IO) {
                AdbClient("127.0.0.1", port, key).use { client ->
                    client.connect()
                    client.command("usb:")
                }
            }
        }.onFailure {
            if (EnvironmentUtils.getAdbTcpPort() > 0) {
                ShizukuStateMachine.update()
                withContext(Dispatchers.Main) {
                    val errorMsg = when (it) {
                        is AdbKeyException -> context.getString(R.string.adb_error_key_store)
                        else -> it.message
                    }
                    Toast.makeText(context, context.getString(R.string.adb_error_stop_tcp) + ". ${errorMsg}", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }
}
