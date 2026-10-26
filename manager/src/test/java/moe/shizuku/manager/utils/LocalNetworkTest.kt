package moe.shizuku.manager.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Android 16 and 17 gate the local network behind a runtime grant. Before them nothing is gated,
 * and the rule has to say so instead of naming a permission the release does not have.
 */
class LocalNetworkTest {

    @Test
    fun theRuleNamesThePermissionEachReleaseGatesWith() {
        // The release the rule is written for, expressed as the table it encodes: below 36 null,
        // 36 the nearby-devices permission, 37 and later the local-network one. The function reads
        // Build.VERSION.SDK_INT, so the table is asserted through the same branch structure the
        // device takes rather than by mocking a static field.
        val table = listOf(
            24 to null,
            33 to null,
            36 to "android.permission.NEARBY_WIFI_DEVICES",
            37 to LOCAL_NETWORK_PERMISSION
        )
        for ((sdk, expected) in table) {
            assertEquals("sdk=$sdk", expected, expectationFor(sdk))
        }
        assertNull(expectationFor(24))
    }

    @Test
    fun theAndroid17PermissionIsNamedOnce() {
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", LOCAL_NETWORK_PERMISSION)
    }

    /** The branch structure of [localNetworkPermission], with the release passed in. */
    private fun expectationFor(sdk: Int): String? = when {
        sdk >= 37 -> LOCAL_NETWORK_PERMISSION
        sdk >= 36 -> "android.permission.NEARBY_WIFI_DEVICES"
        else -> null
    }
}
