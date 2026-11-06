package app.kickoff

import kotlinx.serialization.Serializable

@Serializable
data class Team(
    val id: String,
    val name: String,
    val league: String = "",
    val color: String = "",
    val fotmobId: String = "",          // FotMob team id, found by name search; used for channel listings
)

@Serializable
data class Broadcast(val name: String, val kind: String = "")

@Serializable
data class Match(
    val id: String,
    val dateMillis: Long,
    val home: String,
    val away: String,
    val homeScore: String? = null,
    val awayScore: String? = null,
    val league: String = "",
    val leagueSlug: String = "",
    val state: String = "pre",          // ESPN: pre | in | post
    val statusName: String = "",        // ESPN: STATUS_FIRST_HALF, STATUS_HALFTIME, ...
    val period: Int = 0,
    val completed: Boolean = false,
    val clock: String = "",
    val broadcasts: List<Broadcast> = emptyList(),
    val teamIds: List<String> = emptyList(),
    val timeValid: Boolean = true,      // false when ESPN has a date but no confirmed kickoff time
    val fotmobId: String = "",          // the same game on FotMob
    val tv: Map<String, List<String>> = emptyMap(), // FotMob channels by country code ("US", "ES", ...)
) {
    val title get() = "$home vs $away"

    /** Which alert-worthy point of the game we are at. */
    val phase: Int
        get() = when {
            state == "post" -> Phase.ENDED
            state == "in" && period >= 2 && statusName != "STATUS_HALFTIME" -> Phase.SECOND_HALF
            state == "in" && statusName != "STATUS_DELAYED" -> Phase.FIRST_HALF
            else -> Phase.NONE
        }

    val statusLabel: String
        get() = when {
            statusName == "STATUS_HALFTIME" -> "HT"
            state == "in" -> "LIVE ${clock.ifBlank { if (period >= 2) "2H" else "1H" }}"
            state == "post" && completed -> "FT"
            state == "post" -> statusName.removePrefix("STATUS_").replace('_', ' ').lowercase()
                .replaceFirstChar { it.uppercase() }
            else -> ""
        }
}

object Phase {
    const val NONE = 0
    const val FIRST_HALF = 1
    const val SECOND_HALF = 2
    const val ENDED = 3
}

/** Per-game notification bookkeeping. */
@Serializable
data class AlertState(
    val phase: Int = Phase.NONE,   // highest phase already handled
    val notified: Boolean = false, // at least one notification was posted
    val muted: Boolean = false,    // the user turned this game's alerts off (its card's bell)
)

/** A TV channel or streaming service and the country code it's shown in (e.g. "ViX", "US"). */
data class Channel(val name: String, val country: String)

/** A YouTube highlights search for a game (FotMob match id): the video found, if any, and when. */
@Serializable
data class VideoSearch(
    val url: String? = null,
    val image: String? = null,
    val checkedAt: Long = 0,
    val version: Int = 0,          // News.SEARCH_VERSION when searched: older misses are searched again
)

@Serializable
data class AppState(
    val teams: List<Team> = DEFAULT_TEAMS,
    val matches: Map<String, Match> = emptyMap(),
    val alerts: Map<String, AlertState> = emptyMap(),
    val scoreboardChecked: Set<String> = emptySet(),
    val videoSearches: Map<String, VideoSearch> = emptyMap(),  // cached, as searches use a small daily allowance   // team ids already checked for a game in progress
    val lastRefresh: Long = 0,
    val highlightWeeks: Int = 1,   // how far back the Highlights feed goes: 1 or 3 weeks
)

// ESPN's Bolívar color (3c96c4) is darker than the club's light blue.
const val BOLIVAR_LIGHT_BLUE = "7cc4ec"

/** Stored when a team has no known color: not valid hex, so its cards use the default look. */
const val NO_TEAM_COLOR = "none"

/** Colors ESPN gets wrong (ESPN team id to hex). The Strongest is listed as plain black. */
val TEAM_COLOR_OVERRIDES = mapOf("2681" to BOLIVAR_LIGHT_BLUE, "2687" to "ffd100")

val DEFAULT_TEAMS = listOf(
    Team("86", "Real Madrid", "LALIGA", "ffffff"),
    Team("6077", "Houston Dynamo FC", "MLS", "ff6b00"),
    Team("2681", "Bolívar", "Bolivian Liga Profesional", BOLIVAR_LIGHT_BLUE),
)
