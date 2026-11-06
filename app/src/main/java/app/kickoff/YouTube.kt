package app.kickoff

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant

/**
 * Finds a game's highlights on YouTube when FotMob has none (FotMob only links them for some
 * competitions). Uses the YouTube Data API with the key from the build (repo secret
 * YOUTUBE_API_KEY), which is locked to this app, so requests say which app and signature they
 * come from. Each search costs a share of a small free daily allowance: callers cache results.
 */
object YouTube {
    private val json = Json { ignoreUnknownKeys = true }

    data class Video(val url: String, val image: String?)

    val enabled get() = BuildConfig.YOUTUBE_API_KEY.isNotBlank()

    /** The best highlights video of [home] vs [away] uploaded after [kickoffMillis], or null. */
    fun findHighlights(context: Context, home: String, away: String, kickoffMillis: Long): Video? {
        if (!enabled) return null
        // One search for English and Spanish titles ("|" is YouTube's OR): Bolivian and other
        // Latin American highlights are usually "resumen" or "goles".
        val q = Uri.encode("$home vs $away highlights|resumen|goles")
        val after = Instant.ofEpochMilli(kickoffMillis).toString()
        val root = get(context, "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=10" +
            "&order=relevance&q=$q&publishedAfter=$after&key=${BuildConfig.YOUTUBE_API_KEY}")
        val homeWords = words(home)
        val awayWords = words(away)
        return (root as? JsonObject)?.get("items").arr.map { it.obj }.firstNotNullOfOrNull { item ->
            val id = item["id"].obj.str("videoId") ?: return@firstNotNullOfOrNull null
            val snippet = item["snippet"].obj
            val title = norm(snippet.str("title").orEmpty())
            val titleWords = Regex("[a-z0-9]+").findAll(title).map { it.value }.toSet()
            // Both teams named, called highlights (or giving the score), and not a video game or a reaction video.
            // Any uploader: fan uploads are often just the score ("Bolivar 3-1 The Strongest").
            val ok = (HIGHLIGHT.containsMatchIn(title) || SCORE.containsMatchIn(title)) && !FAKE.containsMatchIn(title) &&
                homeWords.any { it in titleWords } && awayWords.any { it in titleWords }
            if (!ok) return@firstNotNullOfOrNull null
            val thumbs = snippet["thumbnails"].obj
            val image = listOf("maxres", "high", "medium", "default").firstNotNullOfOrNull { thumbs[it].obj.str("url") }
            Video("https://www.youtube.com/watch?v=$id", image)
        }
    }

    /** The length in seconds of each of these YouTube videos (by id) that YouTube knows; 1 unit per 50 ids. */
    fun durations(context: Context, ids: List<String>): Map<String, Int> {
        if (!enabled) return emptyMap()
        return ids.distinct().chunked(50).flatMap { chunk ->
            val root = get(context, "https://www.googleapis.com/youtube/v3/videos?part=contentDetails" +
                "&id=${chunk.joinToString(",")}&key=${BuildConfig.YOUTUBE_API_KEY}")
            (root as? JsonObject)?.get("items").arr.map { it.obj }.mapNotNull { item ->
                val id = item.str("id") ?: return@mapNotNull null
                // ISO 8601, e.g. "PT9M42S"; "P0D" while a video is live.
                val secs = item["contentDetails"].obj.str("duration")
                    ?.let { runCatching { java.time.Duration.parse(it).seconds.toInt() }.getOrNull() }
                    ?.takeIf { it > 0 } ?: return@mapNotNull null
                id to secs
            }
        }.toMap()
    }

    private val HIGHLIGHT = Regex("""\b(highlights?|resumen|goles|golazos?|lo mejor|compacto|melhores momentos|gols)\b""")
    private val FAKE = Regex("""\b(efootball|fifa|pes|fc ?2\d|ea sports|simulation|gameplay|prediction|preview|reaction|reacts?|watch ?along|live|en vivo|en directo|previa|pronostico)\b""")
    private val SCORE = Regex("""\b\d{1,2} ?[-x–] ?\d{1,2}\b""")
    private val GENERIC = setOf("club", "football", "futbol", "calcio", "sport", "sports")

    /**
     * Distinctive words of a team name ("Houston Dynamo FC" -> houston, dynamo). A name with none
     * that long (e.g. "ABB") keeps its short words instead, so the team can still be matched.
     */
    private fun words(team: String): Set<String> {
        val all = Regex("[a-z0-9]+").findAll(norm(team)).map { it.value }.filter { it !in GENERIC }.toSet()
        return all.filter { it.length >= 4 }.toSet().ifEmpty { all.filter { it.length >= 2 }.toSet() }
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "").lowercase()

    /** SHA-1 of the app's signing certificate, as the key's Android-app restriction expects. */
    private fun certSha1(context: Context): String {
        val pm = context.packageManager
        val sig = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return ""
        return MessageDigest.getInstance("SHA-1").digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }
    }

    private fun get(context: Context, url: String): JsonElement {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("X-Android-Package", context.packageName)
        conn.setRequestProperty("X-Android-Cert", certSha1(context))
        try {
            if (conn.responseCode !in 200..299) error("YouTube HTTP ${conn.responseCode}")
            return json.parseToJsonElement(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private val JsonElement?.obj get() = this as? JsonObject ?: JsonObject(emptyMap())
    private val JsonElement?.arr get() = this as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
