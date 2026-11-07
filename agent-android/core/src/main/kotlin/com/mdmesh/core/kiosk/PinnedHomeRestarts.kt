package com.mdmesh.core.kiosk

import com.mdmesh.kiosk.CrashLoopGuard

/**
 * The crash-loop guard for a pinned app that is HOME ([KioskApplier]). Android restarts a HOME app that crashes in
 * place, in its own task, so the agent's launcher is not underneath to count the returns and lock task never ends.
 * The app reports each start instead (the local API's `kiosk.started`), and every start counts. Past
 * [CrashLoopGuard]'s threshold the agent takes HOME back and its launcher holds, as it does for an app it opens
 * itself. The kiosk stays on either way.
 */
class PinnedHomeRestarts(private val applier: KioskApplier, private val guard: CrashLoopGuard) {

    /** The pinned app [pkg] reports that it started. Counted only while it is HOME. */
    suspend fun onStarted(pkg: String) {
        if (!applier.isPinnedHome(pkg)) return
        guard.registerFault()
        if (guard.isCrashLoopDetected()) applier.takeHomeBack()
    }
}
