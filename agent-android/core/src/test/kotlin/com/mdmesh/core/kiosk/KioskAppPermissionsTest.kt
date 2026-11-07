package com.mdmesh.core.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class KioskAppPermissionsTest {
    private val runtime = setOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
    private val none = { _: String -> false }

    @Test fun `only the runtime permissions the app asks for are granted, each once`() {
        val requested = listOf(
            "android.permission.INTERNET", "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
        )
        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
            KioskAppPermissions.toGrant(requested, isRuntime = { it in runtime }, isGranted = none),
        )
    }

    @Test fun `a permission the platform does not know is skipped`() {
        val requested = listOf("com.example.UNKNOWN", "android.permission.CAMERA")
        val found = KioskAppPermissions.toGrant(requested, isRuntime = { p ->
            if (p == "com.example.UNKNOWN") throw NoSuchElementException("unknown") else p in runtime
        }, isGranted = none)
        assertEquals(listOf("android.permission.CAMERA"), found)
    }

    @Test fun `a permission the app already holds is not granted again`() {
        // Each grant is a slow synchronous call; the launcher reopens the app often, so held ones are skipped.
        val requested = listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")
        val found = KioskAppPermissions.toGrant(
            requested, isRuntime = { it in runtime }, isGranted = { it == "android.permission.CAMERA" },
        )
        assertEquals(listOf("android.permission.RECORD_AUDIO"), found)
    }
}
