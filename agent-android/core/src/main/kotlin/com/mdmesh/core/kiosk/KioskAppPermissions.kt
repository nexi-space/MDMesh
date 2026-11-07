package com.mdmesh.core.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.util.Log

/**
 * Grants a kiosk app the runtime permissions it asks for, as the Device Owner, so a kiosk device never shows a
 * permission prompt nobody is there to answer. The permission policy's auto-grant does not cover them all: on a
 * Lenovo TB311XU (Android 16) the pinned app still prompted for its camera, microphone and notifications.
 *
 * Every runtime permission the app requests is granted, since the app is what the device is for. Granted by policy,
 * so the user cannot revoke it either.
 *
 * [grant] blocks, never call it on the main thread: each grant is a synchronous call that waits on the permission
 * controller, for up to 20 s, and right after an install that can add up to many seconds. Run on the kiosk
 * launcher's main thread, that froze the launcher on its splash and could get the agent killed as not responding.
 */
object KioskAppPermissions {
    /**
     * The runtime permissions among [requested] that the app does not hold yet, once each; one [isRuntime] cannot
     * judge is left out.
     */
    fun toGrant(requested: List<String>, isRuntime: (String) -> Boolean, isGranted: (String) -> Boolean): List<String> =
        requested.distinct().filter { runCatching { isRuntime(it) && !isGranted(it) }.getOrDefault(false) }

    /** Grants [pkg] its missing runtime permissions as [admin]. Best effort: one that fails is skipped. Blocking. */
    fun grant(context: Context, admin: ComponentName, pkg: String) {
        val pm = context.packageManager
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        val started = System.currentTimeMillis()
        val requested = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions }
            .getOrNull()?.toList().orEmpty()
        val missing = toGrant(
            requested,
            isRuntime = { isDangerous(pm.getPermissionInfo(it, 0)) },
            isGranted = { pm.checkPermission(it, pkg) == PackageManager.PERMISSION_GRANTED },
        )
        val granted = missing.count {
            runCatching {
                dpm.setPermissionGrantState(admin, pkg, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }.getOrDefault(false)
        }
        if (missing.isNotEmpty()) {
            Log.i(TAG, "granted $granted of ${missing.size} to $pkg in ${System.currentTimeMillis() - started} ms")
        }
    }

    private const val TAG = "KioskAppPermissions"

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
