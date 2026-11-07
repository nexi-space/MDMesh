package com.mdmesh.core.kiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class KioskAppPermissionsTest {
    private val runtime = setOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")

    @Test fun `only the runtime permissions the app asks for are granted, each once`() {
        val requested = listOf(
            "android.permission.INTERNET", "android.permission.CAMERA", "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
        )
        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
            KioskAppPermissions.runtime(requested) { it in runtime },
        )
    }

    @Test fun `a permission the platform does not know is skipped`() {
        val requested = listOf("com.example.UNKNOWN", "android.permission.CAMERA")
        val found = KioskAppPermissions.runtime(requested) { p ->
            if (p == "com.example.UNKNOWN") throw NoSuchElementException("unknown") else p in runtime
        }
        assertEquals(listOf("android.permission.CAMERA"), found)
    }
}
