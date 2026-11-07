package com.mdmesh.core.kiosk

import com.mdmesh.kiosk.CrashLoopGuard

/**
 * The crash-loop guard for a pinned app that is HOME ([KioskApplier]). Android itself reopens a HOME app that closes,
 * and the agent's launcher is not underneath to count the returns, so lock task counts them: a HOME app that crashes
 * leaves lock task, and enters it again when Android reopens it. Past [CrashLoopGuard]'s threshold the agent takes
 * HOME back and its launcher holds, as it does for an app it opens itself. The kiosk stays on either way.
 */
class PinnedHomeRestarts(private val applier: KioskApplier, private val guard: CrashLoopGuard) {

    /** Lock task started for [pkg] (the admin receiver's `onLockTaskModeEntering`). */
    suspend fun onLockTaskEntered(pkg: String) {
        if (!applier.isPinnedHome(pkg)) return
        guard.registerFault()
        if (guard.isCrashLoopDetected()) applier.takeHomeBack()
    }
}
