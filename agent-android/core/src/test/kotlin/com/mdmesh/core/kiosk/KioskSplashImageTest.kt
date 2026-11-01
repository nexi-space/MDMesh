package com.mdmesh.core.kiosk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class KioskSplashImageTest {
    @Test fun `only an https address is taken`() {
        val url = "https://mdm.example.com/files/splash.png"
        assertEquals(url, KioskSplashImage.validUrl(" $url "))
        assertNull(KioskSplashImage.validUrl("http://mdm.example.com/files/splash.png"))
        assertNull(KioskSplashImage.validUrl("file:///sdcard/splash.png"))
        assertNull(KioskSplashImage.validUrl(""))
        assertNull(KioskSplashImage.validUrl(null))
    }

    @Test fun `an image within the cap is copied whole`() {
        val bytes = ByteArray(1000) { it.toByte() }
        val out = ByteArrayOutputStream()
        KioskSplashImage.copyCapped(ByteArrayInputStream(bytes), out, maxBytes = 1000)
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test(expected = IOException::class)
    fun `an image over the cap is refused`() {
        KioskSplashImage.copyCapped(ByteArrayInputStream(ByteArray(1001)), ByteArrayOutputStream(), maxBytes = 1000)
    }
}
