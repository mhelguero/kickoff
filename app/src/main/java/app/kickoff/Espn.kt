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

    /** Upcoming games for a team. ESPN drops a game from this list once it's under way. */
    fun fixtures(teamId: String): List<Match> = schedule("$BASE/teams/$teamId/schedule?fixture=true")

    /** The team's games played this season (with scores), every competition. */
    fun results(teamId: String): List<Match> = schedule("$BASE/teams/$teamId/schedule")

    /**
     * Every soccer game on a UTC day (yyyyMMdd), including ones under way. Large (~800 KB), so only
     * used to catch a game already live for a newly followed team. League is filled in by the first poll.
     */
    // Without a limit ESPN returns only the first 100 games, which a busy Saturday exceeds.
    fun scoreboard(day: String): List<Match> = schedule("$BASE/scoreboard?dates=$day&limit=1000")

    private fun schedule(url: String): List<Match> {
        val root = get(url).obj
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

    /**
     * The team's primary color as hex without "#", e.g. "ff6b00", or [NO_TEAM_COLOR]. ESPN fills in
     * black with a red alternate for teams it has no real color for.
     */
    fun teamColor(teamId: String): String {
        val team = get("$BASE/teams/$teamId").obj["team"].obj
        val color = team.str("color").orEmpty()
        val placeholder = color == "000000" && team.str("alternateColor").equals("C60000", ignoreCase = true)
        return if (color.isBlank() || placeholder) NO_TEAM_COLOR else color
    }

    fun searchTeams(query: String): List<Team> {
        val q = URLEncoder.encode(query, "UTF-8")
        val root = get("https://site.web.api.espn.com/apis/search/v2?query=$q&limit=50&type=team").obj
        return root["results"].arr.flatMap { it.obj["contents"].arr }
            .map { it.obj }
            .filter { it.str("sport") == "soccer" }
            .mapNotNull { c ->
                val id = c.str("uid")?.substringAfter("~t:", "")?.takeIf { it.isNotEmpty() }
                val name = c.str("displayName")
                // subtitle is the league, e.g. "LALIGA" vs "Liga F" for the two Barcelonas.
                if (id == null || name == null) null else Team(id, name, c.str("subtitle") ?: "")
            }
            .distinctBy { it.id }
    }

    private fun parseEvent(event: JsonObject, comp: JsonObject, league: JsonObject?): Match {
        val competitors = comp["competitors"].arr.map { it.obj }
        val home = competitors.firstOrNull { it.str("homeAway") == "home" } ?: competitors[0]
        val away = competitors.firstOrNull { it.str("homeAway") == "away" } ?: competitors[1]
        val status = (comp["status"] ?: event["status"]).obj
        val type = status["type"].obj
        // Schedule and summary: {media: {shortName}, type: {shortName}}. Scoreboard: {names: [...]}.
        val broadcasts = comp["broadcasts"].arr.flatMap { b ->
            val name = b.obj["media"]?.obj?.str("shortName")
            if (name != null) listOf(Broadcast(name, b.obj["type"]?.obj?.str("shortName") ?: ""))
            else b.obj["names"].arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let { n -> Broadcast(n) } }
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
            timeValid = ((comp["timeValid"] ?: event["timeValid"]) as? JsonPrimitive)?.contentOrNull != "false",
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
