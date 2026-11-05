package app.kickoff

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** The icon of the app Android opens a web link in: the site's own app if it claims it, else the browser. */
object LinkApps {
    private val cache = mutableMapOf<String, ImageBitmap?>()   // by site, e.g. "www.espn.com"

    /** Null if no single app would open it (Android would ask). Slow: call off the main thread. */
    fun icon(context: Context, url: String): ImageBitmap? {
        val site = Uri.parse(url).host ?: return null
        synchronized(cache) { if (site in cache) return cache[site] }
        val pm = context.packageManager
        val icon = (opener(pm, url) ?: opener(pm, "https://example.com"))?.let { pkg ->
            runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
        synchronized(cache) { cache[site] = icon }
        return icon
    }

    /** The app a link opens in, or null when Android would show its "open with" chooser instead. */
    private fun opener(pm: PackageManager, url: String): String? {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        val pkg = pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        // The chooser belongs to the system ("android"), or a phone maker's own resolver.
        return pkg?.takeIf { it != "android" && "resolver" !in it }
    }
}
