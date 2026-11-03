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
import java.time.OffsetDateTime

/** ESPN's public site API. The "all" league path covers every competition a team plays in. */
object Espn {
    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer/all"
    private val json = Json { ignoreUnknownKeys = true }

    /** Upcoming and in-progress games for a team. */
    fun fixtures(teamId: String): List<Match> {
        val root = get("$BASE/teams/$teamId/schedule?fixture=true").obj
        return root["events"].arr.mapNotNull { e ->
            runCatching { parseEvent(e.obj, e.obj["competitions"].arr.first().obj, e.obj["league"]?.obj) }
                .getOrNull()
        }
    }

    /** Live status of a single game. */
    fun match(eventId: String): Match {
        val header = get("$BASE/summary?event=$eventId").obj["header"].obj
        val comp = header["competitions"].arr.first().obj
        return parseEvent(JsonObject(mapOf("id" to JsonPrimitive(eventId))), comp, header["league"]?.obj)
    }

    fun searchTeams(query: String): List<Team> {
        val q = URLEncoder.encode(query, "UTF-8")
        val root = get("https://site.web.api.espn.com/apis/search/v2?query=$q&limit=15&type=team").obj
        return root["results"].arr.flatMap { it.obj["contents"].arr }
            .map { it.obj }
            .filter { it.str("sport") == "soccer" }
            .mapNotNull { c ->
                val id = c.str("uid")?.substringAfter("~t:", "")?.takeIf { it.isNotEmpty() }
                val name = c.str("displayName")
                if (id == null || name == null) null else Team(id, name)
            }
    }

    private fun parseEvent(event: JsonObject, comp: JsonObject, league: JsonObject?): Match {
        val competitors = comp["competitors"].arr.map { it.obj }
        val home = competitors.firstOrNull { it.str("homeAway") == "home" } ?: competitors[0]
        val away = competitors.firstOrNull { it.str("homeAway") == "away" } ?: competitors[1]
        val status = (comp["status"] ?: event["status"]).obj
        val type = status["type"].obj
        val broadcasts = comp["broadcasts"].arr.mapNotNull { b ->
            val name = b.obj["media"]?.obj?.str("shortName") ?: return@mapNotNull null
            Broadcast(name, b.obj["type"]?.obj?.str("shortName") ?: "")
        }.distinctBy { it.name }
        return Match(
            id = event.str("id") ?: comp.str("id")!!,
            dateMillis = OffsetDateTime.parse(comp.str("date") ?: event.str("date")!!).toInstant().toEpochMilli(),
            home = home["team"].obj.str("displayName") ?: "?",
            away = away["team"].obj.str("displayName") ?: "?",
            homeScore = score(home["score"]),
            awayScore = score(away["score"]),
            league = league?.str("abbreviation") ?: league?.str("name") ?: "",
            leagueSlug = league?.str("slug") ?: "",
            state = type.str("state") ?: "pre",
            statusName = type.str("name") ?: "",
            period = (status["period"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
            completed = (type["completed"] as? JsonPrimitive)?.contentOrNull == "true",
            clock = status.str("displayClock") ?: "",
            broadcasts = broadcasts,
            teamIds = competitors.mapNotNull { it["team"].obj.str("id") },
        )
    }

    // Schedule gives score as {displayValue}, summary gives a plain string.
    private fun score(e: JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull
        is JsonObject -> e.str("displayValue")
        else -> null
    }

    private fun get(url: String): JsonElement {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Kickoff/1.0")
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode} for $url")
            return json.parseToJsonElement(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private val JsonElement?.obj get() = this as? JsonObject ?: JsonObject(emptyMap())
    private val JsonElement?.arr get() = this as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
