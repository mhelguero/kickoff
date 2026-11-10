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

    /** [rating]: FotMob's player rating (e.g. 7.2), for players who played in a finished game. */
    data class Player(
        val name: String,
        val number: String = "",
        val rating: Double? = null,
        val x: Float? = null,   // place on the pitch, 0 = left to 1 = right (starters)
        val y: Float? = null,   // 0 = own goal line to 1 = the far end (starters)
        val id: String = "",
        val lastName: String = "",
        val cameOn: Int? = null,  // for subs: the minute they came on, if they did
    ) {
        /** FotMob's photo of the player. */
        val photo get() = "https://images.fotmob.com/image_resources/playerimages/$id.png"

        /** The name on the pitch: the surname, unless that's short and the whole name is too ("Vinícius Júnior"). */
        val short get() = when {
            lastName.isBlank() -> name
            lastName.length <= 6 && name.length <= 16 -> name
            else -> lastName
        }
    }
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

    /** A goal: minute ("90+3'"), scorer, assist (may be ""), which side, and "pen" / "OG" if so. */
    data class Goal(
        val minute: String, val scorer: String, val assist: String, val home: Boolean, val note: String,
        val scorerId: String = "", val assistId: String = "",
    )
    data class Star(val name: String, val team: String, val rating: String)
    /** One table row: position, team, games played, goal difference, points. */
    data class TableRow(val pos: Int, val team: String, val teamId: String, val played: Int, val goalDiff: Int, val points: Int)

    /**
     * What FotMob has for a game: lineups and absences (null if neither), and once it's played, the
     * goals and player of the match. [tableLeague] is the league whose table it belongs to (none for
     * cups without a table, friendlies), with the two teams' FotMob ids.
     */
    data class Details(
        val lineups: Lineups?,
        val goals: List<Goal>,
        val star: Star?,
        val tableLeague: String?,
        val teamIds: List<String>,
        val leagueId: String?,                 // the competition (its parent for e.g. a group stage)
        val colors: Map<String, String>,       // FotMob team id -> team color ("#085098")
    )

    /** One knockout tie: the teams, the aggregate score once started, and the winner once decided. */
    data class Tie(
        val home: String, val away: String, val homeId: String, val awayId: String,
        val homeScore: Int?, val awayScore: Int?, val winnerId: String?, val bestOf: Int, val firstGameMillis: Long?,
    )
    data class Round(val name: String, val ties: List<Tie>)

    /** A game's lineups and absences (injuries, suspensions), or null if FotMob has none. */
    fun lineups(matchId: String): Lineups? = details(matchId).lineups

    fun details(matchId: String): Details {
        val root = get("$BASE/matchDetails?matchId=$matchId").obj
        val content = root["content"].obj
        val facts = content["matchFacts"].obj
        val goals = facts["events"].obj["events"].arr.map { it.obj }
            .filter { it.str("type") == "Goal" && it.str("isPenaltyShootoutEvent") != "true" }
            .map { e ->
                val minute = e.str("timeStr")?.replace(" ", "")?.let { if (it.endsWith("'")) it else "$it'" }
                    ?: "${e.str("time").orEmpty()}${e.str("overloadTime")?.let { "+$it" }.orEmpty()}'"
                val note = when {
                    e.str("ownGoal") == "true" -> "OG"
                    e.str("goalDescription").orEmpty().contains("penalty", ignoreCase = true) -> "pen"
                    else -> ""
                }
                Goal(minute, e.str("nameStr") ?: e["player"].obj.str("name").orEmpty(),
                    e.str("assistStr").orEmpty().removePrefix("assist by ").trim(), e.str("isHome") == "true", note,
                    e.str("playerId").orEmpty(), e.str("assistPlayerId").orEmpty())
            }
        val potm = facts["playerOfTheMatch"].obj
        val star = potm["name"].obj.str("fullName")?.let { Star(it, potm.str("teamName").orEmpty(), potm["rating"].obj.str("num").orEmpty()) }
        val table = content["table"].obj
        val tableLeague = (table.str("parentLeagueId") ?: table.str("leagueId"))?.takeIf { table.isNotEmpty() }
        val teamIds = table["teams"].arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val general = root["general"].obj
        val colors = general["teamColors"].obj["lightMode"].obj
        val homeId = general["homeTeam"].obj.str("id").orEmpty()
        val awayId = general["awayTeam"].obj.str("id").orEmpty()
        return Details(lineups(content["lineup"] as? JsonObject), goals, star, tableLeague, teamIds.ifEmpty { listOf(homeId, awayId) },
            general.str("parentLeagueId") ?: general.str("leagueId"),
            mapOf(homeId to colors.str("home").orEmpty(), awayId to colors.str("away").orEmpty()).filterValues { it.isNotBlank() })
    }

    /**
     * A competition's knockout rounds with the ties whose teams are known (a bracket still all
     * "TBD" gives nothing). E.g. MLS Cup Playoffs, the Champions League from its knockout phase.
     */
    fun knockout(leagueId: String): List<Round> {
        val rounds = get("$BASE/leagues?id=$leagueId").obj["playoff"].obj["rounds"].arr.map { it.obj }
        return rounds.mapNotNull { r ->
            val ties = r["matchups"].arr.map { it.obj }
                // Only ties whose teams are set: not "TBD", and not one of FotMob's possible pairings
                // from the current table before a draw ("Manchester United/Galatasaray", two matchups).
                .filter {
                    it.str("tbdTeam1") != "true" && it.str("tbdTeam2") != "true" && it.str("hasTwoPossibleMatchups") != "true" &&
                        (it.str("homeTeamId")?.toIntOrNull() ?: 0) > 0 && (it.str("awayTeamId")?.toIntOrNull() ?: 0) > 0 &&
                        it.str("homeTeamShortName") != "TBD" && it.str("awayTeamShortName") != "TBD"
                }
                .map { t ->
                    val agg = t["aggregatedResult"].obj
                    val games = t["matches"].arr.map { it.obj }
                    val started = games.any { it["status"].obj.str("started") == "true" }
                    val first = games.mapNotNull { g -> g["status"].obj.str("utcTime")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } }.minOrNull()
                    Tie(t.str("homeTeam")!!, t.str("awayTeam")!!, t.str("homeTeamId").orEmpty(), t.str("awayTeamId").orEmpty(),
                        if (started) agg.str("homeScore")?.toIntOrNull() else null, if (started) agg.str("awayScore")?.toIntOrNull() else null,
                        t.str("aggregatedWinner") ?: t.str("winner"), t.str("bestOf")?.toIntOrNull()?.coerceAtLeast(1) ?: 1, first)
                }
            if (ties.isEmpty()) null else Round(roundName(r.str("stage").orEmpty()), ties)
        }
    }

    private fun roundName(stage: String) = when (stage.lowercase()) {
        "final" -> "Final"
        "1/2" -> "Semi-finals"
        "1/4" -> "Quarter-finals"
        "1/8" -> "Round of 16"
        "1/16" -> "Round of 32"
        "playoff" -> "Knockout play-offs"
        else -> stage.replaceFirstChar { it.uppercase() }
    }

    private fun lineups(lu: JsonObject?): Lineups? {
        if (lu == null) return null
        fun players(e: JsonElement?) = e.arr.mapNotNull { p ->
            val perf = p.obj["performance"].obj
            val rating = perf.str("rating")?.toDoubleOrNull()
            val cameOn = perf["substitutionEvents"].arr.map { it.obj }.firstOrNull { it.str("type") == "subIn" }?.str("time")?.toIntOrNull()
            val at = p.obj["verticalLayout"].obj
            p.obj.str("name")?.let {
                Player(it, p.obj.str("shirtNumber").orEmpty(), rating, at.str("x")?.toFloatOrNull(), at.str("y")?.toFloatOrNull(),
                    p.obj.str("id").orEmpty(), p.obj.str("lastName").orEmpty(), cameOn)
            }
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

    /**
     * The league's current table: the group that holds one of [teamIds] when it has several (e.g.
     * MLS's conferences), else the whole table.
     */
    fun table(leagueId: String, teamIds: List<String>): List<TableRow> {
        val root = get("$BASE/tltable?leagueId=$leagueId").let { (it as? JsonArray)?.firstOrNull() ?: it }.obj
        val data = root["data"].obj.ifEmpty { root }
        val groups = (listOf(data["table"].obj["all"].arr) +
            data["tables"].arr.map { it.obj["table"].obj["all"].arr }).filter { it.isNotEmpty() }
        val rows = groups.firstOrNull { g -> g.any { it.obj.str("id") in teamIds } } ?: groups.firstOrNull() ?: return emptyList()
        return rows.mapNotNull { r ->
            val o = r.obj
            TableRow(o.str("idx")?.toIntOrNull() ?: return@mapNotNull null, o.str("shortName") ?: o.str("name").orEmpty(),
                o.str("id").orEmpty(), o.str("played")?.toIntOrNull() ?: 0, o.str("goalConDiff")?.toIntOrNull() ?: 0,
                o.str("pts")?.toIntOrNull() ?: 0)
        }
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
