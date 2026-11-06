package app.kickoff

import android.content.Context
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from this app's GitHub releases. Each CI build is published as release "build-N" with
 * version code N, so a higher N is newer.
 */
object Updater {
    private const val REPO = "mhelguero/kickoff"
    private const val APK = "Kickoff.apk"
    private val json = Json { ignoreUnknownKeys = true }

    data class Release(val versionCode: Long, val apkUrl: String) {
        val versionName get() = "1.$versionCode"
    }

    fun installedVersion(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    /** The latest release if it's newer than this install, else null. Slow: call off the main thread. */
    fun checkForUpdate(context: Context): Release? {
        val latest = latest() ?: return null
        if (latest.versionCode > installedVersion(context)) return latest
        // Up to date: an update downloaded earlier is installed now, so its file can go.
        updatesDir(context).deleteRecursively()
        return null
    }

    private fun latest(): Release? {
        val root = json.parseToJsonElement(read("https://api.github.com/repos/$REPO/releases/latest")) as? JsonObject
            ?: return null
        val code = root.str("tag_name")?.removePrefix("build-")?.toLongOrNull() ?: return null
        val url = (root["assets"] as? JsonArray).orEmpty().map { it as? JsonObject }
            .firstOrNull { it?.str("name") == APK }?.str("browser_download_url") ?: return null
        return Release(code, url)
    }

    /** Downloads the update into the app's private cache (not Downloads). Slow: call off the main thread. */
    fun download(context: Context, release: Release): File {
        val dir = updatesDir(context).apply { mkdirs() }
        val file = File(dir, "Kickoff-${release.versionCode}.apk")
        if (file.exists()) return file
        // Earlier, older downloads are of no use any more.
        dir.listFiles()?.forEach { it.delete() }
        val tmp = File(dir, "download.tmp")
        val conn = open(release.apkUrl)
        try {
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        check(tmp.renameTo(file)) { "couldn't save the update" }
        return file
    }

    /** Can this app open Android's installer? On Android 8+ the user allows it once in settings. */
    fun canInstall(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Settings screen where the user allows Kickoff to install updates. */
    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        )
    }

    /**
     * Installs a downloaded update through Android's package installer session: Android asks the
     * user to confirm (see [InstallReceiver]) and then just updates, with no "Done / Open" screen
     * afterwards. On Android 12+, once Kickoff installed itself this way, later updates may not
     * even need the confirmation. Slow (copies the file): call off the main thread.
     */
    fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite(APK, 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // Android fills in the result, so the intent must be mutable on Android 12+.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val result = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
                session.commit(result.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates")

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Kickoff")
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            error("HTTP ${conn.responseCode}")
        }
        return conn
    }

    private fun read(url: String): String {
        val conn = open(url)
        try {
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * The installer's answer for an update: when it needs the user's OK, show Android's confirmation;
 * on success there's nothing to do (Kickoff restarts as the new version); a failure is reported.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }.onFailure { Log.w("Updater", "confirm", it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            // The user tapped Cancel: nothing to say.
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("Updater", "install failed: $status $message")
                Toast.makeText(context, "Update failed${message?.let { ": $it" }.orEmpty()}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
