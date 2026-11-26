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
        val leagueId: String = "",     // the competition's FotMob id
    )

    /**
     * FotMob teams that may be [name] (men's teams), likeliest first: the full name, then shorter
     * forms, since FotMob's names can differ from ESPN's ("Pumas UNAM" may be just "Pumas").
     */
    /** ESPN team names FotMob calls something else (normalized ESPN name to FotMob's). */
    private val ALIASES = mapOf("unitedstates" to "USA")

    fun findTeams(name: String, women: Boolean = false): List<String> {
        val words = name.split(Regex("\\s+")).filter { it.isNotBlank() }
        // FotMob's name for a few teams differs from ESPN's ("United States" is "USA").
        val alias = ALIASES[norm(name)]
        val queries = (listOfNotNull(alias, name, words.filter { norm(it) !in GENERIC }.joinToString(" "),
            words.maxByOrNull { if (norm(it) in GENERIC) 0 else it.length }.orEmpty())
            // A women's team is "Name (W)" on FotMob.
            .let { q -> if (women) q.map { "$it (W)" } + q else q })
            .map { it.trim() }.filter { it.length >= 3 }.distinct()
        val ids = mutableListOf<String>()
        for (q in queries) {
            val root = get("$BASE/search/suggest?term=${URLEncoder.encode(q, "UTF-8")}&lang=en")
            val teams = root.arr.flatMap { it.obj["suggestions"].arr }.map { it.obj }
                .filter { it.str("type") == "team" }
                // "(W)" is the women's team: only those for a women's team, never for a men's.
                // FotMob's own ranking puts the best match first.
                .filter { it.str("name").orEmpty().endsWith("(W)") == women }
            val exact = teams.filter { norm(it.str("name")?.removeSuffix("(W)")) in listOfNotNull(norm(name), alias?.let { a -> norm(a) }) }
            (exact + teams).mapNotNullTo(ids) { it.str("id") }
        }
        return ids.distinct()
    }

    /**
     * When a team's own name finds nothing (e.g. ESPN's "United States" is FotMob's "USA"), search
     * for an opponent instead: FotMob's results include their game, which names both sides. Returns
     * the other side of games against [opponent] kicking off within 3 hours of [kickoffMillis].
     */
    fun teamsByOpponent(opponent: String, kickoffMillis: Long, women: Boolean = false): List<String> {
        val term = if (women) "$opponent (W)" else opponent
        val root = get("$BASE/search/suggest?term=${URLEncoder.encode(term, "UTF-8")}&lang=en")
        return root.arr.flatMap { it.obj["suggestions"].arr }.map { it.obj }
            .filter { it.str("type") == "match" }
            .filter { m ->
                val t = m.str("matchDate")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                t != null && kotlin.math.abs(t - kickoffMillis) <= 3 * 60 * 60 * 1000L
            }
            .mapNotNull { m ->
                // The side that isn't the opponent we searched for.
                when (norm(opponent)) {
                    norm(m.str("homeTeamName")?.removeSuffix("(W)")) -> m.str("awayTeamId")
                    norm(m.str("awayTeamName")?.removeSuffix("(W)")) -> m.str("homeTeamId")
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
        val age: String = "",
        val country: String = "",
        val wentOff: Int? = null,   // for starters: the minute they were subbed off, if they were
        val replaced: String = "",  // for subs who came on: who they replaced
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
        val playerStats: Map<String, PlayerStats> = emptyMap(),  // FotMob player id -> this game's stats
    )

    /** One stat, ready to show: "Accurate passes", "16/17 (94%)". */
    data class Stat(val label: String, val value: String)
    data class StatGroup(val title: String, val stats: List<Stat>)

    /** A player's stats in one game, in FotMob's groups (Top stats, Attack, Defense, ...). */
    data class PlayerStats(val position: String, val groups: List<StatGroup>)

    /** One knockout tie: the teams, the aggregate score once started, and the winner once decided. */
    data class Tie(
        val home: String, val away: String, val homeId: String, val awayId: String,
        val homeScore: Int?, val awayScore: Int?, val winnerId: String?, val bestOf: Int, val firstGameMillis: Long?,
        val games: List<TieGame> = emptyList(),
    )

    /** One game of a knockout tie (a leg, or a game of a best-of series): FotMob's id, teams, score, time. */
    data class TieGame(
        val id: String, val home: String, val away: String, val homeScore: String?, val awayScore: String?,
        val startMillis: Long, val started: Boolean, val finished: Boolean,
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
            // In order of the minute (FotMob sometimes lists a goal out of order, e.g. 25' before 23').
            .sortedWith(compareBy({ it.str("time")?.toIntOrNull() ?: 0 }, { it.str("overloadTime")?.toIntOrNull() ?: 0 }))
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
        // Who came on for whom: each substitution event names the player coming on, then the one going off.
        val replacedFor = facts["events"].obj["events"].arr.map { it.obj }.filter { it.str("type") == "Substitution" }
            .mapNotNull { e ->
                val swap = e["swap"].arr.map { it.obj }
                val on = swap.getOrNull(0)?.str("id") ?: return@mapNotNull null
                val off = swap.getOrNull(1)?.str("name") ?: return@mapNotNull null
                on to off
            }.toMap()
        return Details(lineups(content["lineup"] as? JsonObject, replacedFor), goals, star, tableLeague, teamIds.ifEmpty { listOf(homeId, awayId) },
            general.str("parentLeagueId") ?: general.str("leagueId"),
            mapOf(homeId to colors.str("home").orEmpty(), awayId to colors.str("away").orEmpty()).filterValues { it.isNotBlank() },
            playerStats(content["playerStats"] as? JsonObject))
    }

    /**
     * Each player's stats in the game, by FotMob player id. Values are formatted the way FotMob shows
     * them: "16/17 (94%)", "0.29", "11.67 km", "30 km/h". The rating (shown as a badge) and the
     * shot map flag are left out.
     */
    private fun playerStats(all: JsonObject?): Map<String, PlayerStats> {
        if (all == null) return emptyMap()
        fun num(d: Double) = if (d == Math.floor(d) && Math.abs(d) < 1e9) d.toLong().toString()
            else "%.2f".format(java.util.Locale.US, d).trimEnd('0').trimEnd('.')
        fun km(m: Double) = "%.2f km".format(java.util.Locale.US, m / 1000)
        fun pct(v: Double, t: Double) = if (t > 0) " (${Math.round(100 * v / t)}%)" else ""
        val positions = mapOf("0" to "Goalkeeper", "1" to "Defender", "2" to "Midfielder", "3" to "Forward")
        return all.mapNotNull { (id, e) ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val groups = p["stats"].arr.mapNotNull { g ->
                val stats = (g.obj["stats"] as? JsonObject).orEmpty().mapNotNull { (label, v) ->
                    if (v.obj.str("key") == "rating_title") return@mapNotNull null
                    val st = v.obj["stat"].obj
                    val value = st.str("value")?.toDoubleOrNull() ?: return@mapNotNull null
                    val total = st.str("total")?.toDoubleOrNull()
                    val shown = when (st.str("type")) {
                        "boolean" -> return@mapNotNull null
                        "speed" -> "${num(value)} km/h"
                        "distance" -> km(value)
                        "distanceWithPercentage" -> km(value) + (total?.let { pct(value, it) }.orEmpty())
                        "fractionWithPercentage" -> "${num(value)}/${num(total ?: 0.0)}" + (total?.let { pct(value, it) }.orEmpty())
                        else -> num(value)
                    }
                    Stat(label, shown)
                }
                stats.takeIf { it.isNotEmpty() }?.let { StatGroup(g.obj.str("title").orEmpty(), it) }
            }
            id to PlayerStats(positions[p.str("usualPosition")].orEmpty(), groups)
        }.toMap()
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
                    val legs = games.mapNotNull { g ->
                        val st = g["status"].obj
                        val time = st.str("utcTime")?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
                            ?: return@mapNotNull null
                        val begun = st.str("started") == "true"
                        TieGame((g.str("matchId") ?: return@mapNotNull null), g["home"].obj.str("name").orEmpty(), g["away"].obj.str("name").orEmpty(),
                            if (begun) g["home"].obj.str("score") else null, if (begun) g["away"].obj.str("score") else null,
                            time, begun, st.str("finished") == "true")
                    }.sortedBy { it.startMillis }
                    Tie(t.str("homeTeam")!!, t.str("awayTeam")!!, t.str("homeTeamId").orEmpty(), t.str("awayTeamId").orEmpty(),
                        if (started) agg.str("homeScore")?.toIntOrNull() else null, if (started) agg.str("awayScore")?.toIntOrNull() else null,
                        t.str("aggregatedWinner") ?: t.str("winner"), t.str("bestOf")?.toIntOrNull()?.coerceAtLeast(1) ?: 1, first, legs)
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

    private fun lineups(lu: JsonObject?, replacedFor: Map<String, String> = emptyMap()): Lineups? {
        if (lu == null) return null
        fun players(e: JsonElement?) = e.arr.mapNotNull { p ->
            val perf = p.obj["performance"].obj
            val rating = perf.str("rating")?.toDoubleOrNull()
            val subs = perf["substitutionEvents"].arr.map { it.obj }
            val cameOn = subs.firstOrNull { it.str("type") == "subIn" }?.str("time")?.toIntOrNull()
            val wentOff = subs.firstOrNull { it.str("type") == "subOut" }?.str("time")?.toIntOrNull()
            // FotMob's vertical layout is mirrored for a pitch with the goalkeeper at the bottom (its
            // right backs are near x = 0), so x is flipped: left-sided players end up on the left.
            val at = p.obj["verticalLayout"].obj
            p.obj.str("name")?.let {
                Player(it, p.obj.str("shirtNumber").orEmpty(), rating, at.str("x")?.toFloatOrNull()?.let { 1f - it }, at.str("y")?.toFloatOrNull(),
                    p.obj.str("id").orEmpty(), p.obj.str("lastName").orEmpty(), cameOn,
                    p.obj.str("age").orEmpty(), p.obj.str("countryName").orEmpty(),
                    wentOff, replacedFor[p.obj.str("id").orEmpty()].orEmpty())
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
                    tournament = f.obj["tournament"].obj.str("name").orEmpty(),
                    leagueId = f.obj["tournament"].obj.str("leagueId").orEmpty())
            }.getOrNull()
        }

    /**
     * FotMob's league for an ESPN one. By name first: ESPN's names add the country and sponsors
     * ("Spanish LALIGA", "Mexican Liga BBVA MX") that FotMob's search doesn't know, so shorter forms
     * are tried too ("LALIGA", "Liga MX"), the league of ESPN's country first ("esp.1" -> ESP). A
     * candidate counts only if one of the league's [games] is among its games. Then, if none: the
     * league one of those games' teams plays that game in. Null if not found.
     */
    fun findLeague(name: String, slug: String, games: List<Match>): String? {
        val country = slug.substringBefore('.').uppercase()
        val noCountry = name.substringAfter(' ', name)
        val names = listOf(name, noCountry, noCountry.replace("BBVA", "").replace(Regex("\\s+"), " ").trim(),
            noCountry.split(' ').filter { it.uppercase() != "BBVA" }.let { w -> if (w.size > 2) "${w.first()} ${w.last()}" else "" })
            .filter { it.isNotBlank() }.distinct()
        val tried = mutableSetOf<String>()
        for (n in names) {
            val leagues = runCatching {
                get("$BASE/search/suggest?term=${URLEncoder.encode(n, "UTF-8")}&lang=en").arr
                    .flatMap { it.obj["suggestions"].arr }.map { it.obj }.filter { it.str("type") == "league" }
            }.getOrDefault(emptyList())
            val ids = leagues.sortedBy { it.str("ccode") != country }.mapNotNull { it.str("id") }.distinct().filter { tried.add(it) }
            for (id in ids.take(3)) {
                if (games.isEmpty()) return id
                val list = runCatching { leagueFixtures(id) }.getOrNull() ?: continue
                if (games.any { sameGame(list, it) != null }) return id
            }
        }
        // By a team: its game at that time names the competition.
        for (g in games.take(3)) {
            for (team in runCatching { findTeams(g.home) }.getOrDefault(emptyList()).take(3)) {
                val f = runCatching { fixtures(team) }.getOrNull()
                    ?.firstOrNull { Math.abs(it.startMillis - g.dateMillis) <= 15 * 60_000L && it.leagueId.isNotBlank() }
                if (f != null) return f.leagueId
            }
        }
        return null
    }

    /**
     * The FotMob game in a league's [fixtures] that is ESPN's [m]: same kickoff (within 15 minutes;
     * many of a league's games start together) and the same home or away team, its name the same or
     * one inside the other ("Brighton" in "Brighton & Hove Albion").
     */
    fun sameGame(fixtures: List<Fixture>, m: Match): Fixture? = fixtures.firstOrNull { f ->
        Math.abs(f.startMillis - m.dateMillis) <= 15 * 60_000L &&
            (BoliviaTv.sameTeam(f.home, m.home) || BoliviaTv.sameTeam(f.away, m.away))
    }

    /** A league's games this season (the whole season, played and to come). */
    fun leagueFixtures(leagueId: String): List<Fixture> =
        get("$BASE/leagues?id=$leagueId").obj["fixtures"].obj["allMatches"].arr.mapNotNull { f ->
            val id = f.obj.str("id") ?: return@mapNotNull null
            val status = f.obj["status"].obj
            val time = status.str("utcTime") ?: return@mapNotNull null
            runCatching {
                Fixture(id, OffsetDateTime.parse(time).toInstant().toEpochMilli(),
                    home = f.obj["home"].obj.str("name").orEmpty(), away = f.obj["away"].obj.str("name").orEmpty(),
                    score = status.str("scoreStr").orEmpty(), finished = status.str("finished") == "true")
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
