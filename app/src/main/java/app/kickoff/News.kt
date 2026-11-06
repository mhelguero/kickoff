package app.kickoff

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

/**
 * A headline about a followed team ([teamId] is ESPN's id, as on [Team]). [writer] is the author
 * when the source names one (ESPN does; FotMob gives only the outlet, [source]).
 */
data class NewsItem(
    val title: String,
    val source: String,
    val timeMillis: Long,
    val url: String,
    val teamId: String,
    val writer: String = "",
    val image: String? = null,         // thumbnail, for highlight videos
    val durationSecs: Int? = null,     // video length, for highlight videos, when known
) {
    /** The link is a video (YouTube), not an article. */
    val isVideo get() = android.net.Uri.parse(url).host.orEmpty().let { "youtube" in it || "youtu.be" in it } || source == "YouTube"

    /** A highlights video: a game's highlights card, or a highlight reel from the news (never an article). */
    val isHighlight get() = isVideo && Regex("""\bhighlights?\b""", RegexOption.IGNORE_CASE).containsMatchIn(title)
}

/**
 * The last week's team news for each followed team, from FotMob (which gathers SI, BBC, talkSPORT,
 * its own reporters and more) and ESPN, filtered by [NewsFilter] to factual team, player, coach and
 * transfer news. Kept in memory only; loaded when the News tab is shown.
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
    suspend fun load(context: android.content.Context, teams: List<Team>) {
        if (_loading.value) return
        _loading.value = true
        try {
            val since = System.currentTimeMillis() - WEEK
            val all = coroutineScope {
                teams.map { t ->
                    async(Dispatchers.IO) {
                        val people = NewsFilter.peopleWords(squad(t))
                        val items = listOf(
                            async { runCatching { fotmob(t, since) }.onFailure { Log.w("News", "FotMob ${t.name}", it) }.getOrDefault(emptyList()) },
                            async { runCatching { espn(t, since) }.onFailure { Log.w("News", "ESPN ${t.name}", it) }.getOrDefault(emptyList()) },
                        ).awaitAll().flatten()
                        // Highlights cards first: when the feed has the same video, the card (with its
                        // competition) is the one kept.
                        runCatching { highlights(context, t, since) }.onFailure { Log.w("News", "highlights ${t.name}", it) }.getOrDefault(emptyList()) +
                            items.filter { NewsFilter.keep(it.title, t.name, people, it.isVideo) }
                    }
                }.awaitAll().flatten()
            }
            // The same story can come from both sources, or be about two followed teams.
            // A video counts once however it's titled.
            val unique = all.distinctBy { youtubeId(it.url) ?: it.title.lowercase().filter(Char::isLetterOrDigit) }
            _items.value = withDurations(context, unique).sortedByDescending { it.timeMillis }
            lastLoad = System.currentTimeMillis()
        } finally {
            _loading.value = false
        }
    }

    private val squads = mutableMapOf<String, List<String>>()   // FotMob team id -> names, for this run of the app

    /** The team's player and coach names; none if unknown (the filter then relies on transfer/news words). */
    private fun squad(team: Team): List<String> {
        if (team.fotmobId.isBlank()) return emptyList()
        synchronized(squads) { squads[team.fotmobId] }?.let { return it }
        val names = runCatching { FotMob.squad(team.fotmobId) }.onFailure { Log.w("News", "squad ${team.name}", it) }
            .getOrNull() ?: return emptyList()
        synchronized(squads) { squads[team.fotmobId] = names }
        return names
    }

    private val durations = mutableMapOf<String, Int>()   // YouTube video id -> seconds, for this run of the app

    /** Highlight videos with their length, looked up once per video; left without one if YouTube can't say. */
    private suspend fun withDurations(context: android.content.Context, items: List<NewsItem>): List<NewsItem> {
        val ids = items.filter { it.isHighlight }.mapNotNull { youtubeId(it.url) }
        val missing = synchronized(durations) { ids.filter { it !in durations } }
        if (missing.isNotEmpty()) {
            val found = withContext(Dispatchers.IO) {
                runCatching { YouTube.durations(context, missing) }.onFailure { Log.w("News", "durations", it) }.getOrDefault(emptyMap())
            }
            synchronized(durations) { durations.putAll(found) }
        }
        return items.map { n ->
            val secs = if (n.isHighlight) youtubeId(n.url)?.let { synchronized(durations) { durations[it] } } else null
            if (secs != null) n.copy(durationSecs = secs) else n
        }
    }

    private val highlightUrls = mutableMapOf<String, FotMob.Highlight>()   // FotMob match id -> video, once found

    /** A card per game the team finished in the last week that has an official highlights video. */
    private fun highlights(context: android.content.Context, team: Team, since: Long): List<NewsItem> {
        if (team.fotmobId.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        return FotMob.fixtures(team.fotmobId).filter { it.finished && it.startMillis in since..now }.mapNotNull { f ->
            val h = synchronized(highlightUrls) { highlightUrls[f.id] }
                ?: runCatching { FotMob.highlights(f.id) }.getOrNull()?.also { synchronized(highlightUrls) { highlightUrls[f.id] = it } }
                ?: youtubeSearch(context, f, now)
                ?: return@mapNotNull null
            val url = h.url
            val score = f.score.replace(" - ", "–")
            // Dated at the end of the game (kickoff + about 2 hours), when the video appears.
            val site = android.net.Uri.parse(url).host.orEmpty().removePrefix("www.")
            val source = if ("youtube" in site || "youtu.be" in site) "YouTube" else site
            // The competition goes where an article's writer would: "Champions League • YouTube".
            NewsItem("Highlights: ${f.home} $score ${f.away}", source, f.startMillis + 2 * 60 * 60 * 1000L, url, team.id,
                writer = f.tournament, image = h.image ?: youtubeId(url)?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" })
        }
    }

    /**
     * FotMob has no highlights for this game: search YouTube, once per game, again every 3 hours
     * for two days if nothing is found yet. Results are saved, so restarts don't search again.
     */
    private fun youtubeSearch(context: android.content.Context, f: FotMob.Fixture, now: Long): FotMob.Highlight? {
        if (!YouTube.enabled) return null
        val saved = Store.state.value.videoSearches[f.id]
        saved?.url?.let { return FotMob.Highlight(it, saved.image) }
        val hour = 60 * 60 * 1000L
        // Give the video time to be uploaded (game over about 2 hours after kickoff).
        if (now < f.startMillis + 2 * hour || now > f.startMillis + 2 * 24 * hour) return null
        if (saved != null && now - saved.checkedAt < 3 * hour) return null
        val video = runCatching { YouTube.findHighlights(context, f.home, f.away, f.startMillis) }
            .onFailure { Log.w("News", "YouTube ${f.home} vs ${f.away}", it) }
        // A failed request (no key yet, allowance used up) isn't saved, so it's tried again next time.
        if (video.isFailure) return null
        val v = video.getOrNull()
        Store.update { s ->
            // Searches older than 10 days are for games no longer shown.
            val kept = s.videoSearches.filterValues { now - it.checkedAt < 10 * 24 * hour }
            s.copy(videoSearches = kept + (f.id to VideoSearch(v?.url, v?.image, now)))
        }
        return v?.let { FotMob.Highlight(it.url, it.image) }
    }

    /** The video id in a YouTube link ("watch?v=ID" or "youtu.be/ID"). */
    private fun youtubeId(url: String): String? {
        val uri = android.net.Uri.parse(url)
        val host = uri.host.orEmpty()
        return when {
            "youtu.be" in host -> uri.lastPathSegment
            "youtube" in host -> uri.getQueryParameter("v")
            else -> null
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
                // Videos (highlight reels) get their thumbnail like the highlights cards.
                val image = if (youtubeId(url) != null) a.str("imageUrl") else null
                if (time >= since) out += NewsItem(title.trim(), a.str("sourceStr").orEmpty(), time, url, team.id, image = image)
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
                if (time < since) null else NewsItem(title.trim(), "ESPN", time, url, team.id, a.str("byline").orEmpty().trim())
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
