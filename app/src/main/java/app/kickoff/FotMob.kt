package app.kickoff

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.time.OffsetDateTime

/**
 * FotMob's public data: which TV channels and streaming services show a game in a country.
 * Unofficial: every call can fail; a game then keeps the listing it had.
 */
object FotMob {
    private const val BASE = "https://www.fotmob.com/api/data"
    private val json = Json { ignoreUnknownKeys = true }

    data class Fixture(val id: String, val startMillis: Long)

    /** FotMob id of the team best matching [name] (men's first team), or null if none was found. */
    fun findTeam(name: String): String? {
        val root = get("$BASE/search/suggest?term=${URLEncoder.encode(name, "UTF-8")}&lang=en")
        val teams = root.arr.flatMap { it.obj["suggestions"].arr }.map { it.obj }
            .filter { it.str("type") == "team" }
            // "(W)" is the women's team; FotMob's own ranking puts the best match first.
            .filter { !it.str("name").orEmpty().endsWith("(W)") }
        val pick = teams.firstOrNull { norm(it.str("name")) == norm(name) } ?: teams.firstOrNull()
        return pick?.str("id")
    }

    /** The team's games this season, past, live and upcoming. */
    fun fixtures(teamId: String): List<Fixture> =
        get("$BASE/teams?id=$teamId").obj["fixtures"].obj["allFixtures"].obj["fixtures"].arr.mapNotNull { f ->
            val id = f.obj.str("id") ?: return@mapNotNull null
            val time = f.obj["status"].obj.str("utcTime") ?: return@mapNotNull null
            runCatching { Fixture(id, OffsetDateTime.parse(time).toInstant().toEpochMilli()) }.getOrNull()
        }

    /**
     * What shows a game in one country: [channels] like ["TUDN", "ViX", "Paramount+"], and [logos],
     * service name to logo image URL, for the few services FotMob has a partner link for
     * (e.g. "Fubo", "ESPN", "Paramount+"). Most services have no logo.
     */
    data class Listing(val channels: List<String>, val logos: Map<String, String>)

    fun listing(matchId: String, country: String): Listing {
        val root = get("$BASE/tvlisting?matchId=$matchId&countryCode=$country").obj
        // One " / "-separated name; some are tagged with the country, e.g. "Paramount+ (US)".
        val channels = root.str("name").orEmpty().split(" / ")
            .map { it.replace(COUNTRY_TAG, "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        // Partner links, each in English and Spanish: {title: "Live on Fubo", imageUrl: ...}.
        val partners = root["affiliates"].arr.map { it.obj }
        val logos = (partners.filter { it.str("langCode") == "en" }.ifEmpty { partners }).mapNotNull { p ->
            val name = p.str("title")?.substringAfterLast(" on ")?.trim()
            val image = p.str("imageUrl")
            if (name.isNullOrEmpty() || image.isNullOrEmpty()) null else name to image
        }.toMap()
        return Listing(channels, logos)
    }

    private val COUNTRY_TAG = Regex("""\s*\([A-Z]{2,3}\)$""")

    /** Lowercase letters and digits only, accents removed: "Bolívar" and "Bolivar" match. */
    private fun norm(s: String?) = Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFD)
        .lowercase().filter { it in 'a'..'z' || it.isDigit() }

    private fun get(url: String): JsonElement {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val body = if (conn.responseCode == 204) "" else conn.inputStream.bufferedReader().readText()
            return if (body.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(body)
        } finally {
            conn.disconnect()
        }
    }

    private val JsonElement?.obj get() = this as? JsonObject ?: JsonObject(emptyMap())
    private val JsonElement?.arr get() = this as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
