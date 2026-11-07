package com.mdmesh.core.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build

/**
 * Grants a kiosk app the runtime permissions it asks for, as the Device Owner, so a kiosk device never shows a
 * permission prompt nobody is there to answer. The permission policy's auto-grant does not cover them all: on a
 * Lenovo TB311XU (Android 16) the pinned app still prompted for its camera, microphone and notifications.
 *
 * Every runtime permission the app requests is granted, since the app is what the device is for. Granted by policy,
 * so the user cannot revoke it either.
 */
object KioskAppPermissions {
    /** The runtime permissions among [requested], once each; one [isRuntime] cannot judge is left out. */
    fun runtime(requested: List<String>, isRuntime: (String) -> Boolean): List<String> =
        requested.distinct().filter { runCatching { isRuntime(it) }.getOrDefault(false) }

    /** Grants [pkg] its requested runtime permissions as [admin]. Best effort: one that fails is skipped. */
    fun grant(context: Context, admin: ComponentName, pkg: String) {
        val pm = context.packageManager
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val requested = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions }
            .getOrNull()?.toList().orEmpty()
        runtime(requested) { isDangerous(pm.getPermissionInfo(it, 0)) }.forEach {
            runCatching {
                dpm.setPermissionGrantState(admin, pkg, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        }
    }

    @Suppress("DEPRECATION") // protectionLevel's base bits are getProtection() before API 28
    private fun isDangerous(info: PermissionInfo): Boolean {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.protection
        } else {
            info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        }
        return base == PermissionInfo.PROTECTION_DANGEROUS
    }
}
