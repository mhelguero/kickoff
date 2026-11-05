package app.kickoff

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

/** A headline about a followed team ([teamId] is ESPN's id, as on [Team]). */
data class NewsItem(val title: String, val source: String, val timeMillis: Long, val url: String, val teamId: String)

/**
 * The last week's headlines for each followed team, from FotMob (which gathers SI, BBC, talkSPORT,
 * its own reporters and more) and ESPN. Kept in memory only; loaded when the News tab is shown.
 */
object News {
    private const val WEEK = 7 * 24 * 60 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    private val _items = MutableStateFlow<List<NewsItem>>(emptyList())
    val items: StateFlow<List<NewsItem>> get() = _items
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> get() = _loading
    var lastLoad = 0L
        private set

    /** Reloads every team's news, newest first; a source that fails is left out. */
    suspend fun load(teams: List<Team>) {
        if (_loading.value) return
        _loading.value = true
        try {
            val since = System.currentTimeMillis() - WEEK
            val all = coroutineScope {
                teams.flatMap { t ->
                    listOf(
                        async(Dispatchers.IO) { runCatching { fotmob(t, since) }.onFailure { Log.w("News", "FotMob ${t.name}", it) }.getOrDefault(emptyList()) },
                        async(Dispatchers.IO) { runCatching { espn(t, since) }.onFailure { Log.w("News", "ESPN ${t.name}", it) }.getOrDefault(emptyList()) },
                    )
                }.awaitAll().flatten()
            }
            // The same story can come from both sources, or be about two followed teams.
            _items.value = all.sortedByDescending { it.timeMillis }.distinctBy { it.title.lowercase().filter(Char::isLetterOrDigit) }
            lastLoad = System.currentTimeMillis()
        } finally {
            _loading.value = false
        }
    }

    /** FotMob's team news, 20 a page, newest first, back to [since]. */
    private fun fotmob(team: Team, since: Long): List<NewsItem> {
        if (team.fotmobId.isBlank()) return emptyList()
        val out = mutableListOf<NewsItem>()
        for (page in 0 until 15) {
            val data = get("https://www.fotmob.com/api/data/tlnews?id=${team.fotmobId}&type=team&language=en&startIndex=${page * 20}")
                .obj["data"].arr.map { it.obj }
            if (data.isEmpty()) break
            var oldest = Long.MAX_VALUE
            for (a in data) {
                val title = a.str("title") ?: continue
                val time = a.str("gmtTime")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: continue
                oldest = minOf(oldest, time)
                val path = a["page"].obj.str("url") ?: continue
                val url = if (path.startsWith("/")) "https://www.fotmob.com$path" else path
                if (time >= since) out += NewsItem(title.trim(), a.str("sourceStr").orEmpty(), time, url, team.id)
            }
            if (oldest < since) break
        }
        return out
    }

    /** ESPN's team headlines (videos left out), back to [since]. */
    private fun espn(team: Team, since: Long): List<NewsItem> =
        get("https://site.api.espn.com/apis/site/v2/sports/soccer/all/news?team=${team.id}&limit=50").obj["articles"].arr
            .map { it.obj }
            .filter { it.str("type") != "Media" }
            .mapNotNull { a ->
                val title = a.str("headline") ?: return@mapNotNull null
                val time = a.str("published")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                    ?: return@mapNotNull null
                val url = a["links"].obj["web"].obj.str("href") ?: return@mapNotNull null
                if (time < since) null else NewsItem(title.trim(), "ESPN", time, url, team.id)
            }

    private fun get(url: String): JsonElement {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Kickoff/1.0")   // ESPN refuses requests without one
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().readText()
            return if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body)
        } finally {
            conn.disconnect()
        }
    }

    private val JsonElement?.obj get() = this as? JsonObject ?: JsonObject(emptyMap())
    private val JsonElement?.arr get() = this as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
