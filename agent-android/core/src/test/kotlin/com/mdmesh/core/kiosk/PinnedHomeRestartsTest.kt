package com.mdmesh.core.kiosk

import android.content.ComponentName
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.kiosk.CrashLoopGuard
import com.mdmesh.kiosk.InMemoryFaultStore
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedHomeRestartsTest {
    private class Controller : KioskController {
        val homes = mutableListOf<ComponentName>()
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult {
            homes += homeComponent
            return KioskResult.Ok
        }
        override fun exit(): KioskResult = KioskResult.Ok
        override fun isLocked(context: android.content.Context): Boolean = true
        override fun allowedPackages(): List<String> = emptyList()
    }
    private class Home(private val homeApps: Map<String, ComponentName>) : KioskHomeSwitch {
        val log = mutableListOf<String>()
        override fun setClaimEnabled(enabled: Boolean) { log += "claim:$enabled" }
        override fun showLauncher() { log += "launcher" }
        override fun showOemHome() { log += "oem" }
        override fun homeActivityOf(packageName: String): ComponentName? = homeApps[packageName]
        override fun showPinnedHome() { log += "pinned-home" }
    }

    private val agentHome = ComponentName("com.mdmesh.agent", "com.mdmesh.agent.KioskHomeAlias")
    /** The HOME each enter claimed, by identity: ComponentName.equals is not available in JVM tests. */
    private fun assertHomes(actual: List<ComponentName>, vararg expected: ComponentName) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> org.junit.Assert.assertSame(e, a) }
    }

    private val appHome = ComponentName("com.a", "com.a.MainActivity")
    private val single = KioskApplyPayload(mode = "single", pinPackage = "com.a", allowedPackages = listOf("com.a"))
    private var clock = 1_000L
    private val guard = CrashLoopGuard(InMemoryFaultStore()) { clock }

    private suspend fun restarts(c: Controller, h: Home): PinnedHomeRestarts {
        val store = InMemoryKioskStateStore(); store.save(single)
        return PinnedHomeRestarts(KioskApplier(c, store, h, agentHome) { true }, guard)
    }

    @Test fun `a pinned HOME app that keeps restarting gives HOME back to the agent's launcher`() = runTest {
        val c = Controller(); val h = Home(mapOf("com.a" to appHome))
        val r = restarts(c, h)
        repeat(CrashLoopGuard.LOOP_CRASHES) { r.onStarted("com.a"); clock += 1_000 }
        assertTrue("no hold before the threshold", c.homes.isEmpty())
        r.onStarted("com.a")
        assertHomes(c.homes, agentHome)
        assertEquals(listOf("launcher"), h.log)
    }

    @Test fun `restarts spread out over time never trip the guard`() = runTest {
        val c = Controller(); val h = Home(mapOf("com.a" to appHome))
        val r = restarts(c, h)
        repeat(CrashLoopGuard.LOOP_CRASHES * 3) {
            r.onStarted("com.a")
            clock += CrashLoopGuard.LOOP_TIME_SPAN + 1
        }
        assertTrue(c.homes.isEmpty())
    }

    @Test fun `only the pinned app counts, and only while it is HOME`() = runTest {
        val other = Controller(); val otherHome = Home(mapOf("com.a" to appHome))
        val r = restarts(other, otherHome)
        repeat(CrashLoopGuard.LOOP_CRASHES * 2) { r.onStarted("com.b") }
        assertTrue(other.homes.isEmpty())

        // The agent's launcher is HOME (the app is not a HOME app): it counts returns itself, so starts do not.
        val c = Controller(); val h = Home(emptyMap())
        val plain = restarts(c, h)
        repeat(CrashLoopGuard.LOOP_CRASHES * 2) { plain.onStarted("com.a") }
        assertTrue(c.homes.isEmpty())
    }
}
