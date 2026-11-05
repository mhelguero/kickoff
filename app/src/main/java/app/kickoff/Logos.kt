package app.kickoff

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Images from the web (team crests), kept in memory and in the app's cache folder. */
object Logos {
    private val memory = mutableMapOf<String, ImageBitmap>()

    /** The image, or null if it can't be loaded. Slow: call off the main thread. */
    fun load(context: Context, url: String): ImageBitmap? {
        synchronized(memory) { memory[url] }?.let { return it }
        val file = File(File(context.cacheDir, "logos").apply { mkdirs() }, hash(url))
        val bytes = runCatching { if (file.exists()) file.readBytes() else download(url).also { file.writeBytes(it) } }
            .getOrNull() ?: return null
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        if (image == null) {
            file.delete()   // not an image: try again next time
            return null
        }
        synchronized(memory) { memory[url] = image }
        return image
    }

    private fun download(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.readBytes()
        } finally {
            conn.disconnect()
        }
    }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
