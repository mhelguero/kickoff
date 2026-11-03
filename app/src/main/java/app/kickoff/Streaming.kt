package app.kickoff

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A channel to show. [launch] is set only when the matching app is installed on this phone. */
data class WatchOption(
    val name: String,
    val country: String,
    val launch: Intent?,
)

object Streaming {
    /**
     * [keys] match the channel name; [packages] are the known Android apps; [labels] find the app by
     * its home-screen name if the package differs. [anyCountry] apps get a button even when the
     * listing is for another country (e.g. Entel Gol is Bolivian but its app is the same everywhere).
     */
    private class Provider(
        val keys: List<String>,
        val packages: List<String>,
        val labels: List<String>,
        val anyCountry: Boolean = false,
    )

    private val providers = listOf(
        Provider(listOf("peacock", "telemundo", "universo"), listOf("com.peacocktv.peacockandroid"), listOf("peacock")),
        Provider(listOf("apple tv", "mls season pass"), listOf("com.apple.atve.androidtv.appletv"), listOf("apple tv")),
        Provider(listOf("espn"), listOf("com.espn.score_center"), listOf("espn")),
        Provider(listOf("paramount", "cbs", "golazo"), listOf("com.cbs.app"), listOf("paramount+")),
        Provider(listOf("fox", "fs1", "fs2"), listOf("com.foxsports.android"), listOf("fox one", "fox sports")),
        Provider(listOf("vix", "tudn", "univision", "unimás", "unimas"), listOf("com.univision.prendetv"), listOf("vix")),
        Provider(listOf("dazn"), listOf("com.dazn"), listOf("dazn")),
        Provider(listOf("fubo"), listOf("com.fubo.mobile"), listOf("fubo")),
        Provider(listOf("bein"), emptyList(), listOf("bein")),
        Provider(listOf("entel"), emptyList(), listOf("entel tv", "entel gol"), anyCountry = true),
        Provider(listOf("tigo"), emptyList(), listOf("tigo sports"), anyCountry = true),
        Provider(listOf("fanatiz"), emptyList(), listOf("fanatiz"), anyCountry = true),
    )

    /** This phone's country as Live Soccer TV names it ("USA", "Bolivia", ...). */
    val myCountry: String = when (val c = Locale.getDefault().country) {
        "US", "" -> "USA"
        "GB" -> "UK"
        else -> Locale("", c).getDisplayCountry(Locale.ENGLISH)
    }

    /**
     * Every known channel for a game. ESPN only lists US channels.
     * Bolivian league games fall back to Entel Gol, which holds the exclusive rights.
     */
    fun channels(match: Match): List<Channel> {
        val all = match.broadcasts.map { Channel(it.name, "USA") }
        if (all.isEmpty() && match.leagueSlug.startsWith("bol.")) return listOf(Channel("Entel Gol", "Bolivia"))
        return all.distinctBy { it.country.lowercase() to it.name.lowercase() }
    }

    /** Channels grouped by country, this phone's country first. */
    fun byCountry(match: Match): List<Pair<String, List<Channel>>> =
        channels(match).groupBy { it.country }.toList()
            .sortedBy { (country, _) -> if (country == myCountry) 0 else 1 }

    /** Short text for notifications and the game list, e.g. "Apple TV" or "Entel Gol (Bolivia)". */
    fun summary(match: Match): String {
        val (country, list) = byCountry(match).firstOrNull() ?: return ""
        val names = list.joinToString(", ") { it.name }
        return if (country == myCountry) names else "$names ($country)"
    }

    fun options(context: Context, channels: List<Channel>): List<WatchOption> = channels.map { ch ->
        val provider = providers.firstOrNull { p -> p.keys.any { ch.name.lowercase().contains(it) } }
        val usable = provider != null && (ch.country == myCountry || provider.anyCountry)
        WatchOption(ch.name, ch.country, if (usable) installedApp(context, provider!!) else null)
    }

    private fun installedApp(context: Context, p: Provider): Intent? {
        val pm = context.packageManager
        p.packages.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }?.let { return it }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val app = pm.queryIntentActivities(launcher, 0).firstOrNull { ri ->
            val label = ri.loadLabel(pm).toString().lowercase()
            p.labels.any { label.startsWith(it) }
        } ?: return null
        return pm.getLaunchIntentForPackage(app.activityInfo.packageName)
    }

    /** Web search for this exact game, in Spanish for South American competitions. */
    fun findMoreUrl(match: Match): String {
        val date = DateTimeFormatter.ofPattern("MMMM d yyyy")
            .format(Instant.ofEpochMilli(match.dateMillis).atZone(ZoneId.systemDefault()))
        val spanish = match.leagueSlug.startsWith("bol.") || match.leagueSlug.startsWith("conmebol")
        val q = if (spanish) "${match.home} vs ${match.away} dónde ver en vivo $date"
        else "${match.home} vs ${match.away} where to watch $date"
        return "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
    }

    // Live Soccer TV team pages (ESPN team id -> page). Each lists the team's games with their channels.
    private val liveSoccerTvTeams = mapOf(
        "86" to "spain/real-madrid",
        "6077" to "united-states/houston-dynamo",
        "2681" to "bolivia/bolivar-la-paz",
    )

    /** A followed team's Live Soccer TV page, or a web search that finds this game's page there. */
    fun liveSoccerTvUrl(match: Match, teams: List<Team>): String {
        val path = teams.firstNotNullOfOrNull { t -> liveSoccerTvTeams[t.id]?.takeIf { t.id in match.teamIds } }
        if (path != null) return "https://www.livesoccertv.com/teams/$path/"
        val q = "livesoccertv ${match.home} vs ${match.away}"
        return "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
    }

    fun open(context: Context, intent: Intent) {
        try {
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    fun openUrl(context: Context, url: String) = open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
