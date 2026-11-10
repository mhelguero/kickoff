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

    data class Fixture(
        val id: String,
        val startMillis: Long,
        val home: String = "",
        val away: String = "",
        val score: String = "",        // e.g. "2 - 3" once played
        val finished: Boolean = false,
        val tournament: String = "",   // e.g. "LaLiga", "Champions League"
    )

    /**
     * FotMob teams that may be [name] (men's teams), likeliest first: the full name, then shorter
     * forms, since FotMob's names can differ from ESPN's ("Pumas UNAM" may be just "Pumas").
     */
    fun findTeams(name: String): List<String> {
        val words = name.split(Regex("\\s+")).filter { it.isNotBlank() }
        val queries = listOf(name, words.filter { norm(it) !in GENERIC }.joinToString(" "),
            words.maxByOrNull { if (norm(it) in GENERIC) 0 else it.length }.orEmpty())
            .map { it.trim() }.filter { it.length >= 3 }.distinct()
        val ids = mutableListOf<String>()
        for (q in queries) {
            val root = get("$BASE/search/suggest?term=${URLEncoder.encode(q, "UTF-8")}&lang=en")
            val teams = root.arr.flatMap { it.obj["suggestions"].arr }.map { it.obj }
                .filter { it.str("type") == "team" }
                // "(W)" is the women's team; FotMob's own ranking puts the best match first.
                .filter { !it.str("name").orEmpty().endsWith("(W)") }
            val exact = teams.filter { norm(it.str("name")) == norm(name) }
            (exact + teams).mapNotNullTo(ids) { it.str("id") }
        }
        return ids.distinct()
    }

    /**
     * When a team's own name finds nothing (e.g. ESPN's "United States" is FotMob's "USA"), search
     * for an opponent instead: FotMob's results include their game, which names both sides. Returns
     * the other side of games against [opponent] kicking off within 3 hours of [kickoffMillis].
     */
    fun teamsByOpponent(opponent: String, kickoffMillis: Long): List<String> {
        val root = get("$BASE/search/suggest?term=${URLEncoder.encode(opponent, "UTF-8")}&lang=en")
        return root.arr.flatMap { it.obj["suggestions"].arr }.map { it.obj }
            .filter { it.str("type") == "match" }
            .filter { m ->
                val t = m.str("matchDate")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                t != null && kotlin.math.abs(t - kickoffMillis) <= 3 * 60 * 60 * 1000L
            }
            .mapNotNull { m ->
                // The side that isn't the opponent we searched for.
                when (norm(opponent)) {
                    norm(m.str("homeTeamName")) -> m.str("awayTeamId")
                    norm(m.str("awayTeamName")) -> m.str("homeTeamId")
                    else -> null
                }
            }
            .distinct()
    }

    data class Player(val name: String, val number: String = "")
    /** A player who'll miss the game, e.g. ("Rodrygo", "Injury", "Late December 2026"). */
    data class Absence(val name: String, val reason: String, val back: String)
    data class Side(
        val name: String,
        val formation: String,
        val starters: List<Player>,   // empty until the lineups are announced (about an hour before)
        val bench: List<Player>,
        val coach: String,
        val out: List<Absence>,
    )
    data class Lineups(val home: Side, val away: Side) {
        val announced get() = home.starters.isNotEmpty() || away.starters.isNotEmpty()
        val anyOut get() = home.out.isNotEmpty() || away.out.isNotEmpty()
    }

    /** A game's lineups and absences (injuries, suspensions), or null if FotMob has none. */
    fun lineups(matchId: String): Lineups? {
        val lu = get("$BASE/matchDetails?matchId=$matchId").obj["content"].obj["lineup"] as? JsonObject ?: return null
        fun players(e: JsonElement?) = e.arr.mapNotNull { p ->
            p.obj.str("name")?.let { Player(it, p.obj.str("shirtNumber").orEmpty()) }
        }
        fun side(key: String): Side {
            val t = lu[key].obj
            val out = t["unavailable"].arr.mapNotNull { p ->
                val name = p.obj.str("name") ?: return@mapNotNull null
                val u = p.obj["unavailability"].obj
                val type = u.str("type").orEmpty().replace('_', ' ').replaceFirstChar { it.uppercase() }
                Absence(name, type, u.str("expectedReturn").orEmpty())
            }
            return Side(t.str("name").orEmpty(), t.str("formation").orEmpty(), players(t["starters"]), players(t["subs"]),
                t["coach"].obj.str("name").orEmpty(), out)
        }
        return Lineups(side("homeTeam"), side("awayTeam")).takeIf { it.announced || it.anyOut }
    }

    private val GENERIC = setOf("fc", "cf", "sc", "cd", "club", "deportivo", "atletico", "real", "united", "city")

    /** The team's games this season, past, live and upcoming. */
    fun fixtures(teamId: String): List<Fixture> =
        get("$BASE/teams?id=$teamId").obj["fixtures"].obj["allFixtures"].obj["fixtures"].arr.mapNotNull { f ->
            val id = f.obj.str("id") ?: return@mapNotNull null
            val time = f.obj["status"].obj.str("utcTime") ?: return@mapNotNull null
            val status = f.obj["status"].obj
            runCatching {
                Fixture(id, OffsetDateTime.parse(time).toInstant().toEpochMilli(),
                    home = f.obj["home"].obj.str("name").orEmpty(), away = f.obj["away"].obj.str("name").orEmpty(),
                    score = status.str("scoreStr").orEmpty(), finished = status.str("finished") == "true",
                    tournament = f.obj["tournament"].obj.str("name").orEmpty())
            }.getOrNull()
        }

    /** A game's official highlights video (usually YouTube) and its thumbnail image. */
    data class Highlight(val url: String, val image: String?)

    /** The highlights for a finished game, or null if FotMob has none yet. */
    fun highlights(matchId: String): Highlight? {
        val h = get("$BASE/matchDetails?matchId=$matchId").obj["content"].obj["matchFacts"].obj["highlights"].obj
        return h.str("url")?.let { Highlight(it, h.str("image")) }
    }

    /** Names of the team's current squad, coach included. */
    fun squad(teamId: String): List<String> =
        get("$BASE/teams?id=$teamId").obj["squad"].obj["squad"].arr.flatMap { g ->
            g.obj["members"].arr.mapNotNull { it.obj.str("name") }
        }

    /**
     * Channels showing a game in one country (ISO code like "US"), e.g. ["TUDN", "ViX", "Paramount+"].
     * FotMob gives one " / "-separated name and tags some with the country, e.g. "Paramount+ (US)".
     */
    fun channels(matchId: String, country: String): List<String> =
        get("$BASE/tvlisting?matchId=$matchId&countryCode=$country").obj.str("name").orEmpty()
            .split(" / ")
            .map { it.replace(COUNTRY_TAG, "").trim() }
            .filter { it.isNotEmpty() }
            .distinct()

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
