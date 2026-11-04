package app.kickoff

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * SofaScore's public API: which TV channels and streaming services show a game, in every country.
 * ESPN only knows US English-language rights, so this is where e.g. ViX, Movistar+ or DAZN come from.
 * Unofficial: every call can fail, and callers fall back to ESPN's listings.
 */
object Sofa {
    private const val BASE = "https://api.sofascore.com/api/v1"
    private val json = Json { ignoreUnknownKeys = true }

    data class Event(val id: String, val startMillis: Long)

    /** SofaScore id of the men's soccer team best matching [name], or null if none was found. */
    fun findTeam(name: String): String? {
        val root = get("$BASE/search/all?q=${URLEncoder.encode(name, "UTF-8")}&page=0").obj
        val teams = root["results"].arr.map { it.obj }
            .filter { it.str("type") == "team" }
            .map { it["entity"].obj }
            .filter { it["sport"].obj.str("slug") == "football" && it.str("gender") != "F" }
        val pick = teams.firstOrNull { norm(it.str("name")) == norm(name) } ?: teams.firstOrNull()
        return pick?.str("id")
    }

    /** The team's upcoming games, plus recent ones (a game under way can be listed under either). */
    fun teamEvents(teamId: String): List<Event> {
        fun page(dir: String) = get("$BASE/team/$teamId/events/$dir/0").obj["events"].arr.mapNotNull { e ->
            val id = e.obj.str("id") ?: return@mapNotNull null
            val start = e.obj.str("startTimestamp")?.toLongOrNull() ?: return@mapNotNull null
            Event(id, start * 1000)
        }
        return page("next") + runCatching { page("last") }.getOrDefault(emptyList())
    }

    /**
     * Channel ids by country code for a game, plus any channel names the answer happens to include.
     * Countries list plain ids or {id, name} objects; both are read.
     */
    fun countryChannels(eventId: String): Pair<Map<String, List<Int>>, Map<Int, String>> {
        val cc = get("$BASE/tv/event/$eventId/country-channels").obj["countryChannels"].obj
        val ids = cc.mapValues { (_, v) -> v.arr.mapNotNull { idOf(it) }.distinct() }.filterValues { it.isNotEmpty() }
        return ids.mapKeys { it.key.uppercase() } to named(cc)
    }

    /** Names of a country's channels (id to name). */
    fun channelNames(country: String): Map<Int, String> = named(get("$BASE/tv/country/$country/channels"))

    /** One channel's name, for ids its country's list doesn't include. */
    fun channelName(id: Int): String? = named(get("$BASE/tv/channel/$id/schedule"))[id]

    /** Names for many countries at once, a few requests at a time; countries that fail are skipped. */
    suspend fun namesFor(countries: Collection<String>): Map<Int, String> = coroutineScope {
        val limit = Semaphore(6)
        countries.map { c ->
            async(Dispatchers.IO) { limit.withPermit { runCatching { channelNames(c) }.getOrDefault(emptyMap()) } }
        }.awaitAll().fold(emptyMap()) { all, one -> all + one }
    }

    /** Every {id, name} object anywhere in the answer, so small format changes don't break it. */
    private fun named(e: JsonElement?): Map<Int, String> {
        val out = mutableMapOf<Int, String>()
        fun walk(x: JsonElement?) {
            when (x) {
                is JsonObject -> {
                    val id = x.str("id")?.toIntOrNull()
                    val name = x.str("name")
                    if (id != null && !name.isNullOrBlank()) out.putIfAbsent(id, name)
                    x.values.forEach { walk(it) }
                }
                is JsonArray -> x.forEach { walk(it) }
                else -> {}
            }
        }
        walk(e)
        return out
    }

    private fun idOf(e: JsonElement): Int? = when (e) {
        is JsonPrimitive -> e.contentOrNull?.toIntOrNull()
        is JsonObject -> e.str("id")?.toIntOrNull()
        else -> null
    }

    private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    private fun get(url: String): JsonElement {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode !in 200..299) error("SofaScore HTTP ${conn.responseCode}")
            return json.parseToJsonElement(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private val JsonElement?.obj get() = this as? JsonObject ?: JsonObject(emptyMap())
    private val JsonElement?.arr get() = this as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
