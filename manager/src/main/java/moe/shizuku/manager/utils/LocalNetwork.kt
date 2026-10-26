package moe.shizuku.manager.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** The Android 17 permission that gates mDNS discovery and local-network connections. */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * The permission that gates the local network on this release, or null below Android 16 where
 * nothing is gated.
 */
fun localNetworkPermission(): String? = when {
    Build.VERSION.SDK_INT >= 37 -> LOCAL_NETWORK_PERMISSION
    Build.VERSION.SDK_INT >= 36 -> Manifest.permission.NEARBY_WIFI_DEVICES
    else -> null
}

/**
 * Whether discovery and connection may use the local network.
 *
 * Below Android 16 nothing is gated, so this is always true there. The grant is read on every call
 * rather than cached: it can be revoked in the system settings while Shizuku is running, and the
 * refusal that follows otherwise reads as a plain connection failure.
 */
fun Context.hasLocalNetworkPermission(): Boolean {
    val permission = localNetworkPermission() ?: return true
    return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
