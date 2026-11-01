package com.mdmesh.core.kiosk

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * A full-screen image the kiosk launcher shows in place of its plain "Loading…" splash: behind the pinned app as it
 * launches, and while the app is not installed yet, which at enrollment is the better part of a minute for a large
 * app. A deployment's own branding then fills that wait instead of the agent's.
 *
 * Its address comes from the enrollment QR (admin extra [EXTRA], https only) and is kept, and the image is downloaded
 * once into the agent's files. Until it is here, or without one, the launcher shows its own splash.
 */
class KioskSplashImage(context: Context) {
    private val context = context.applicationContext
    private val prefs by lazy { this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** The downloaded image, or null. */
    val file: File?
        get() = File(context.filesDir, FILE).takeIf { it.isFile && it.length() > 0 }

    /** Keeps the QR's address, when it is a valid one. */
    fun save(rawUrl: String?) {
        val url = validUrl(rawUrl) ?: return
        prefs.edit().putString(KEY_URL, url).apply()
    }

    /**
     * Downloads the image when it has an address and is not here yet. Blocking: call it off the main thread.
     * @return true when the image is here.
     */
    fun fetchIfMissing(): Boolean {
        val url = prefs.getString(KEY_URL, null)
        if (file != null || url == null) return file != null
        val part = File(context.filesDir, "$FILE.part")
        return runCatching { download(url, part) }.onFailure { part.delete() }.isSuccess
    }

    private fun download(url: String, part: File) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body?.takeIf { response.isSuccessful } ?: throw IOException("HTTP ${response.code}")
            part.outputStream().use { out -> body.byteStream().use { copyCapped(it, out, MAX_BYTES) } }
        }
        if (!part.renameTo(File(context.filesDir, FILE))) throw IOException("could not keep the image")
    }

    companion object {
        /** The admin-extras key: the image's https address. */
        const val EXTRA = "com.mdmesh.SPLASH_IMAGE_URL"

        /** A splash is one screen-sized image; anything larger is refused rather than kept. */
        const val MAX_BYTES = 8L * 1024 * 1024

        private const val PREFS = "kiosk_splash"
        private const val KEY_URL = "url"
        private const val FILE = "kiosk_splash_image"
        private const val TIMEOUT_S = 30L

        private val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .build()
        }

        /** [raw] trimmed when it is an https address, else null. */
        fun validUrl(raw: String?): String? =
            raw?.trim()?.takeIf { it.startsWith("https://") && it.length > "https://".length }

        /** Copies [input] to [output], refusing more than [maxBytes]. */
        fun copyCapped(input: InputStream, output: OutputStream, maxBytes: Long) {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return
                total += n
                if (total > maxBytes) throw IOException("splash image larger than $maxBytes bytes")
                output.write(buffer, 0, n)
            }
        }
    }
}
