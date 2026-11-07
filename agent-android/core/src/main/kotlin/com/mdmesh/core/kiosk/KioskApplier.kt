package com.mdmesh.core.kiosk

import android.content.ComponentName
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.kiosk.KioskToggles
import com.mdmesh.kiosk.lockTaskFeatures
import com.mdmesh.kiosk.safeModeBlocked
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one implementation of "put this device in kiosk with payload P" / "leave kiosk", shared by the
 * `kiosk.enter` / `kiosk.exit` commands and by `config.apply`. Idempotent: entering with the same payload while
 * not locked re-asserts the allowlist and features; exiting when not in kiosk is harmless.
 *
 * Entering with the payload already persisted while lock task is active is a no-op: a re-sent `config.apply`
 * (e.g. after an agent update) must not relaunch the kiosk launcher on a device already showing it; only the
 * volume lock and the Safe Mode block are re-asserted.
 *
 * Safe Mode follows [safeModeBlocked] on every path here: blocked in kiosk unless the configuration allows it, and
 * after kiosk only if the configuration blocks it. [syncSafeMode] is how `config.apply` re-checks it last.
 *
 * Serialized: [enter], [exit], [syncSafeMode] and [releaseStaleVolumeLock] run one at a time, whichever path calls them
 * (`kiosk.enter`/`kiosk.exit`, `config.apply` and its boot re-apply, the launcher's exits, agent start). Otherwise
 * the boot-time release could read "no kiosk", then clear the volume lock a concurrent enter had just set.
 */
// Every change to the lock-task policy and to HOME runs here, under one mutex; split up, they could interleave.
@Suppress("TooManyFunctions")
class KioskApplier(
    private val kiosk: KioskController,
    private val store: KioskStateStore,
    private val home: KioskHomeSwitch,
    private val homeComponent: ComponentName,
    /**
     * The configuration's Safe Mode setting, read on every path ([ConfiguredSafeModeSetting] in production). The
     * default is for tests only: an applier built without it would treat an explicit Block as not managed.
     */
    private val safeMode: SafeModeSetting = SafeModeSetting.NotManaged,
    /** Whether lock task is active now ([KioskController.isLocked]). Last, so a trailing lambda can only be this. */
    private val isLockTaskActive: () -> Boolean,
) {
    private val mutex = Mutex()
    /** Sets Safe Mode per [safeModeBlocked] from [safeMode]; see [KioskSafeMode]. */
    private val safeModeSetter = KioskSafeMode(kiosk, safeMode)

    suspend fun enter(p: KioskApplyPayload): KioskResult = mutex.withLock { enterLocked(p) }

    private suspend fun enterLocked(p: KioskApplyPayload): KioskResult {
        // Already in this kiosk: no re-entry or relaunch, but re-assert the volume lock and the Safe Mode block (either
        // may have failed before).
        val current = store.load()
        if (current == p && isLockTaskActive()) {
            kiosk.setErrorDialogsQuiet(true)
            return applySafeMode(applyVolume(p, KioskResult.Ok))
        }
        home.setClaimEnabled(true)
        val pinnedHome = pinnedHomeOf(p)
        return when (val r = lockTo(p, pinnedHome ?: homeComponent)) {
            KioskResult.Ok -> {
                // The device IS in kiosk now, so the state says so even if the volume lock fails below: a failed
                // volume lock is reported (the config document is then not persisted and is re-applied), and the
                // short-circuit above re-asserts it without relaunching the kiosk.
                store.save(p)
                // Best effort, like the volume lock on exit: nobody is at a kiosk to answer a crash dialog, so a
                // crashed app is closed and reopened (and counted, PinnedHomeRestarts) instead of waiting behind one.
                kiosk.setErrorDialogsQuiet(true)
                home.hideOtherLaunchers(p.kioskApps())
                if (pinnedHome == null) home.showLauncher() else home.showPinnedHome()
                applySafeMode(applyVolume(p, r))
            }
            else -> {
                if (current == null) {
                    home.setClaimEnabled(false) // never leave a non-kiosk device claiming HOME
                } else {
                    // A device in kiosk stays in it: the failed entry may already have replaced the allowlist, so
                    // put back the kiosk it had, and keep HOME, or a restart would boot to the phone's own launcher.
                    lockTo(current, pinnedHomeOf(current) ?: homeComponent)
                    applySafeMode(r) // its result is r again, already Failed: the device keeps its kiosk and the block
                }
                r
            }
        }
    }

    /**
     * The pinned app's HOME activity, when kiosk [p] is single-app and its app declares one: that app is then HOME
     * itself. Android treats it as the home screen, so Back has nothing to leave to, and Android 16 does not draw its
     * app handle (the desktop-windowing "…" bar) over it, as it does over any other app. Null: the agent's launcher is
     * HOME, and opens the app.
     */
    private fun pinnedHomeOf(p: KioskApplyPayload): ComponentName? =
        p.pinPackage?.takeIf { p.mode == "single" }?.let(home::homeActivityOf)

    /** True when [pkg] is the saved kiosk's pinned app and is HOME in it ([pinnedHomeOf]). */
    suspend fun isPinnedHome(pkg: String): Boolean =
        store.load()?.let { it.pinPackage == pkg && pinnedHomeOf(it) != null } ?: false

    /**
     * Make the saved kiosk's pinned app HOME, and bring it forward: the launcher's hand-over once the app is installed,
     * or when someone opens it again after a hold. False, and nothing changes, when it is not a HOME app
     * ([pinnedHomeOf]), no kiosk is saved, or the claim fails.
     */
    suspend fun handHomeToPinnedApp(): Boolean = mutex.withLock {
        val p = store.load() ?: return false
        val pinnedHome = pinnedHomeOf(p) ?: return false
        if (lockTo(p, pinnedHome) != KioskResult.Ok) return false
        home.showPinnedHome()
        true
    }

    /**
     * The pinned app is HOME and keeps closing ([PinnedHomeRestarts]): give HOME back to the agent's launcher, which
     * holds on its own screen instead of reopening it ([com.mdmesh.kiosk.KioskReturnPolicy]). The kiosk stays on.
     */
    suspend fun takeHomeBack(): Boolean = mutex.withLock {
        val p = store.load() ?: return false
        if (lockTo(p, homeComponent) != KioskResult.Ok) return false
        home.showLauncher()
        true
    }

    private fun lockTo(p: KioskApplyPayload, kioskHome: ComponentName): KioskResult {
        val features = lockTaskFeatures(
            KioskToggles(
                home = p.features.home, recents = p.features.recents, notifications = p.features.notifications,
                systemInfo = p.features.systemInfo, keyguard = p.features.keyguard, lockButtons = p.features.lockButtons,
            ),
        )
        return kiosk.enter(kioskHome, p.kioskApps(), features)
    }

    /** In kiosk: block Safe Mode unless the configuration allows it ([safeModeBlocked]). */
    private fun applySafeMode(entered: KioskResult): KioskResult =
        when (val s = safeModeSetter.set(inKiosk = true)) {
            is KioskResult.Failed -> s
            else -> entered
        }

    /**
     * Set Safe Mode to what [safeModeBlocked] says for the kiosk saved now and the configuration's setting.
     * `config.apply` calls it after its policies and kiosk (including the boot re-apply), so a key that stops being
     * managed, or a kiosk that ends, lifts a block nothing asks for any more. The setting comes from [safeMode], the
     * same source `kiosk.enter` and `kiosk.exit` read: one answer, whichever path asks.
     */
    suspend fun syncSafeMode(): KioskResult = mutex.withLock {
        safeModeSetter.set(inKiosk = store.load() != null)
    }

    /** Volume is its own opt-in (it mutes the device); anything else releases a lock set earlier. */
    private fun applyVolume(p: KioskApplyPayload, entered: KioskResult): KioskResult =
        when (val v = kiosk.setVolumeLocked(p.features.lockVolume == true)) {
            is KioskResult.Failed -> v
            else -> entered
        }

    /**
     * THE kiosk exit. Every path goes through here (`kiosk.exit`, `config.apply` kiosk-off and the on-device
     * admin-PIN exit), so the volume lock, which survives reboots and mutes the device, is always released with it,
     * and the kiosk's Safe Mode block is lifted (unless the configuration itself blocks Safe Mode).
     * A crash loop never exits: the launcher holds in kiosk instead (KioskLauncherActivity).
     *
     * @param handBackHome release the HOME claim and show the OEM launcher (false: the agent stays HOME).
     * @param force clear local state and the volume lock even when the controller could not exit (on-device exits:
     *   the user must get out).
     */
    suspend fun exit(handBackHome: Boolean = true, force: Boolean = false): KioskResult = mutex.withLock {
        exitLocked(handBackHome, force)
    }

    private suspend fun exitLocked(handBackHome: Boolean, force: Boolean): KioskResult {
        val r = kiosk.exit()
        if (r != KioskResult.Ok && !force) return r
        kiosk.setVolumeLocked(false) // best effort: lift the kiosk's volume lock (and its mute)
        kiosk.setErrorDialogsQuiet(false) // best effort: crash dialogs show again
        store.save(null)
        safeModeSetter.set(inKiosk = false) // best effort, like the volume lock
        if (handBackHome) {
            home.restoreOtherLaunchers() // first, so there is an OEM launcher to hand HOME back to
            home.setClaimEnabled(false)
            home.showOemHome()
        }
        return r
    }

    /** At agent start: drop a volume lock left behind (e.g. by an interrupted exit) unless the kiosk wants it. */
    suspend fun releaseStaleVolumeLock(): Unit = mutex.withLock {
        if (store.load()?.features?.lockVolume != true) kiosk.setVolumeLocked(false)
    }

    /** True when a kiosk payload is persisted (device believes it is / should be in kiosk). */
    suspend fun isPersisted(): Boolean = store.load() != null
}

/** The apps this kiosk runs: its allowlist and the pinned app. */
private fun KioskApplyPayload.kioskApps(): List<String> = (allowedPackages + listOfNotNull(pinPackage)).distinct()
