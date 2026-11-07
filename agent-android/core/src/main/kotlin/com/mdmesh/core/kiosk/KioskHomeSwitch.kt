package com.mdmesh.core.kiosk

import android.app.ActivityOptions
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** The Android side effects of entering/leaving kiosk, behind an interface so the orchestration is unit-testable. */
interface KioskHomeSwitch {
    /** Enable/disable the agent's HOME activity-alias (the persistent-preferred HOME while in kiosk). */
    fun setClaimEnabled(enabled: Boolean)
    /** Bring the kiosk launcher forward (best-effort; the HOME claim covers the next HOME press). */
    fun showLauncher()
    /** Send the device to the OEM launcher after kiosk exit. */
    fun showOemHome()

    /**
     * While in kiosk, hide the device's other launchers, except [keep] (the kiosk's own apps), and the agent's own app
     * entry. At boot, before storage unlocks, Android only starts a direct-boot-aware HOME. The kiosk launcher is not
     * one and an OEM launcher may be, so the OEM launcher showed first at every restart; hidden, Android's own
     * "starting" screen does instead. The agent's app is on the kiosk's allowlist, because its launcher is, so an OEM
     * panel that lists apps (Lenovo's sidebar) opened it from inside the kiosk. Does nothing by default.
     */
    fun hideOtherLaunchers(keep: Collection<String>) = Unit

    /** Unhide the launchers [hideOtherLaunchers] hid, and enable the agent's app again. Does nothing by default. */
    fun restoreOtherLaunchers() = Unit

    /**
     * The HOME activity [packageName] declares, or null when it declares none. A single-app kiosk whose app has one
     * makes that app HOME ([KioskApplier]). Null by default: the agent's launcher stays HOME.
     */
    fun homeActivityOf(packageName: String): ComponentName? = null

    /** Bring the pinned app forward as HOME, in lock task. Brings the kiosk launcher forward by default. */
    fun showPinnedHome() = showLauncher()
}

/**
 * @param admin the agent's admin component, to hide other launchers with; null hides nothing. Which launchers it hid
 *   is kept in preferences, so a restore after a restart unhides exactly those.
 * @param ownApp the agent's own app entry (its launcher activity), disabled while in kiosk; null leaves it.
 */
class AndroidKioskHomeSwitch(
    private val context: Context,
    private val homeComponent: ComponentName,
    private val admin: ComponentName? = null,
    private val ownApp: ComponentName? = null,
) : KioskHomeSwitch {
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val dpm get() = context.getSystemService(DevicePolicyManager::class.java)

    override fun setClaimEnabled(enabled: Boolean) {
        runCatching {
            val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            context.packageManager.setComponentEnabledSetting(homeComponent, state, PackageManager.DONT_KILL_APP)
        }
    }
    override fun showLauncher() {
        runCatching { context.startActivity(Intent().setComponent(homeComponent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    override fun showOemHome() {
        runCatching { context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * Only a filter that also carries `CATEGORY_DEFAULT` counts: without it the HOME intent never resolves to the
     * activity. API 28+, where [showPinnedHome] can start the app in lock task whether or not it asks to be.
     */
    override fun homeActivityOf(packageName: String): ComponentName? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
        return runCatching { context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) }
            .getOrNull()
            ?.firstOrNull()
            ?.activityInfo
            ?.let { ComponentName(it.packageName, it.name) }
    }

    /**
     * The HOME intent with no component, so Android starts the app as HOME (not as an app opened over HOME), and in
     * lock task: the kiosk's allowlist names it.
     */
    override fun showPinnedHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle()
            } else {
                null
            }
            context.startActivity(home, options)
        }
    }

    /**
     * Ordinary launchers only: HOME filters at priority 0. A setup wizard declares a higher one and Settings'
     * FallbackHome (Android's "starting" screen) a negative one, and neither is ever hidden.
     */
    override fun hideOtherLaunchers(keep: Collection<String>) {
        setOwnAppEnabled(false)
        val admin = admin ?: return
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = runCatching { context.packageManager.queryIntentActivities(homeIntent, 0) }
            .getOrDefault(emptyList())
            .filter { it.priority == 0 }
            .map { it.activityInfo.packageName }
            .filter { it != context.packageName && it !in keep }
            .distinct()
        val hidden = launchers.filter {
            runCatching {
                dpm.setApplicationHidden(admin, it, true)
                dpm.isApplicationHidden(admin, it)
            }.getOrDefault(false)
        }
        if (hidden.isNotEmpty()) {
            prefs.edit().putStringSet(KEY_HIDDEN, hiddenLaunchers() + hidden).apply()
        }
    }

    override fun restoreOtherLaunchers() {
        setOwnAppEnabled(true)
        val admin = admin ?: return
        hiddenLaunchers().forEach { runCatching { dpm.setApplicationHidden(admin, it, false) } }
        prefs.edit().remove(KEY_HIDDEN).apply()
    }

    private fun hiddenLaunchers(): Set<String> = prefs.getStringSet(KEY_HIDDEN, null).orEmpty()

    /** Enabled back to its manifest state ("default"), so an exit leaves the app exactly as installed. */
    private fun setOwnAppEnabled(enabled: Boolean) {
        val app = ownApp ?: return
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching { context.packageManager.setComponentEnabledSetting(app, state, PackageManager.DONT_KILL_APP) }
    }

    private companion object {
        const val PREFS = "kiosk_home"
        const val KEY_HIDDEN = "hidden_launchers"
    }
}
