package com.mdmesh.kiosk

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager

/**
 * Real [KioskController] backed by the native lock-task APIs.
 *
 * Constructed directly with a [DevicePolicyManager] and the admin [ComponentName]
 * (no `DpmHandle` indirection). The agent's own package, the admin's, stays on the
 * allowlist so the kiosk launcher can run.
 *
 * HOME is claimed for the agent's launcher or for a single-app kiosk's own HOME app,
 * so each claim first releases the one made before, whichever package it named, and
 * [exit] releases it too.
 *
 * SDK floors:
 * - lock-task allowlisting / persistent HOME: API 21 (module minSdk is 24, so always met).
 * - [DevicePolicyManager.setLockTaskFeatures]: API 28 (P) — guarded.
 *
 * Every entry point checks [DevicePolicyManager.isDeviceOwnerApp] first and returns
 * [KioskResult.Unsupported] when the agent is not the Device Owner. Framework calls
 * are wrapped so a [SecurityException] (or any throwable) becomes
 * [KioskResult.Failed] rather than propagating.
 */
class LockTaskKioskController(
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
) : KioskController {

    override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult {
        val ownPackage = admin.packageName
        if (!dpm.isDeviceOwnerApp(ownPackage)) return KioskResult.Unsupported
        return runGuarded {
            // Before the allowlist is replaced: it names the app that may hold HOME now.
            releaseHomeClaims(ownPackage)
            // Own package must stay allowlisted so the kiosk launcher can run.
            val allowlist = (allowedPackages + ownPackage).distinct().toTypedArray()
            dpm.setLockTaskPackages(admin, allowlist)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(admin, features)
            }

            // Claim HOME: pressing home returns to the kiosk launcher, or to the kiosk's own HOME app.
            val homeFilter = IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, homeFilter, homeComponent)
        }
    }

    /** The agent's own package is the admin component's. */
    override fun exit(): KioskResult {
        val ownPackage = admin.packageName
        if (!dpm.isDeviceOwnerApp(ownPackage)) return KioskResult.Unsupported
        return runGuarded {
            releaseHomeClaims(ownPackage) // before the allowlist is cleared, as in enter
            dpm.setLockTaskPackages(admin, emptyArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Reset features to the framework default (only SYSTEM_INFO / status bar).
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
        }
    }

    override fun setVolumeLocked(locked: Boolean): KioskResult {
        if (!dpm.isDeviceOwnerApp(admin.packageName)) return KioskResult.Unsupported
        return runGuarded {
            if (locked) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADJUST_VOLUME)
            }
        }
    }

    override fun setSafeModeBlocked(blocked: Boolean): KioskResult {
        if (!dpm.isDeviceOwnerApp(admin.packageName)) return KioskResult.Unsupported
        return runGuarded {
            if (blocked) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            }
        }
    }

    /** The restriction is API 28+. */
    override fun setErrorDialogsQuiet(quiet: Boolean): KioskResult {
        if (!dpm.isDeviceOwnerApp(admin.packageName) || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return KioskResult.Unsupported
        }
        return runGuarded {
            if (quiet) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS)
            }
        }
    }

    override fun isLocked(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
    }

    override fun allowedPackages(): List<String> {
        if (!dpm.isDeviceOwnerApp(admin.packageName)) return emptyList()
        // getLockTaskPackages() was added in API 26 (O); on 24/25 the method doesn't exist.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        return try {
            dpm.getLockTaskPackages(admin).toList()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * Release the HOME claim, whichever package holds it: the agent, or an app on the allowlist (a single-app kiosk's
     * own HOME app). Only claims this admin made are released.
     */
    private fun releaseHomeClaims(ownPackage: String) {
        (listOf(ownPackage) + allowedPackages()).distinct().forEach {
            dpm.clearPackagePersistentPreferredActivities(admin, it)
        }
    }

    /** Run [block]; convert success to [KioskResult.Ok] and any throwable to [KioskResult.Failed]. */
    private inline fun runGuarded(block: () -> Unit): KioskResult =
        try {
            block()
            KioskResult.Ok
        } catch (t: Throwable) {
            KioskResult.Failed(t.message ?: t.javaClass.simpleName)
        }
}
