package app.kickoff

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
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
     * [keys] are found anywhere in the channel name, [exact] must be the whole name (short names like
     * "Tele" or "Max" would match too much otherwise); [packages] are the known Android apps;
     * [labels] find the app by its home-screen name if the package differs.
     */
    private class Provider(
        val keys: List<String>,
        val packages: List<String>,
        val labels: List<String>,
        val exact: List<String> = emptyList(),
    ) {
        fun matches(channel: String) = channel.lowercase().let { n -> n in exact || keys.any { n.contains(it) } }
    }

    private val providers = listOf(
        // Peacock streams NBC, USA Network and Telemundo/Universo soccer (ESPN calls Telemundo "Tele").
        Provider(listOf("peacock", "telemundo", "universo", "nbc", "usa net"), listOf("com.peacocktv.peacockandroid"),
            listOf("peacock"), exact = listOf("tele")),
        Provider(listOf("apple tv", "mls season pass"), listOf("com.apple.atve.androidtv.appletv"), listOf("apple tv")),
        Provider(listOf("espn"), listOf("com.espn.score_center"), listOf("espn")),
        Provider(listOf("paramount", "cbs", "golazo"), listOf("com.cbs.app"), listOf("paramount+")),
        Provider(listOf("fox", "fs1", "fs2"), listOf("com.foxsports.android"), listOf("fox one", "fox sports")),
        Provider(listOf("vix", "tudn", "univision", "unimás", "unimas"), listOf("com.univision.prendetv"), listOf("vix")),
        // HBO Max carries the TNT Sports channels (TNT, truTV, TBS).
        Provider(listOf("hbo max", "tnt", "trutv", "tbs"), listOf("com.wbd.stream"), listOf("hbo max", "max"),
            exact = listOf("max")),
        Provider(listOf("prime video"), listOf("com.amazon.avod.thirdpartyclient"), listOf("prime video")),
        Provider(listOf("youtube"), listOf("com.google.android.youtube"), listOf("youtube")),
        Provider(listOf("flosports", "flofootball"), emptyList(), listOf("flosports")),
        Provider(listOf("dazn"), listOf("com.dazn"), listOf("dazn")),
        Provider(listOf("fubo"), listOf("com.fubo.mobile"), listOf("fubo")),
        Provider(listOf("bein"), emptyList(), listOf("bein")),
        Provider(listOf("entel"), emptyList(), listOf("entel tv", "entel gol")),
        Provider(listOf("tigo"), emptyList(), listOf("tigo sports")),
        Provider(listOf("fanatiz"), emptyList(), listOf("fanatiz")),
    )

    /**
     * This phone's country code ("US", "BO", ...) from the mobile network (where the phone is),
     * falling back to the language setting. Only decides which country's channels are listed first;
     * Open buttons don't depend on it.
     */
    var myCountryCode: String = "US"
        private set
    val myCountry get() = countryName(myCountryCode)

    fun detectCountry(context: Context) {
        val network = context.getSystemService(TelephonyManager::class.java)?.networkCountryIso.orEmpty()
        myCountryCode = network.ifBlank { Locale.getDefault().country }.uppercase().ifBlank { "US" }
    }

    fun countryName(code: String) = when (code) {
        "US" -> "USA"
        "GB" -> "UK"
        else -> Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code }
    }

    /**
     * Every known channel for a game: SofaScore's listings for every country, plus ESPN's US ones.
     * [names] is SofaScore's channel id to name; ids without a name yet are left out.
     */
    fun channels(match: Match, names: Map<Int, String>): List<Channel> {
        val espn = match.broadcasts.map { Channel(it.name, "US") }
        val sofa = match.tvIds.flatMap { (country, ids) -> ids.mapNotNull { names[it] }.map { Channel(it, country) } }
        return (espn + sofa).distinctBy { it.country to sameName(it.name) }
    }

    /** "Paramount+" and "Paramount Plus" are the same service. */
    private fun sameName(name: String) =
        name.lowercase().replace("plus", "+").filter { it.isLetterOrDigit() || it == '+' }

    /** Channels grouped by country code, this phone's country first. */
    fun byCountry(match: Match, names: Map<Int, String>): List<Pair<String, List<Channel>>> =
        channels(match, names).groupBy { it.country }.toList()
            .sortedBy { (country, _) -> if (country == myCountryCode) 0 else 1 }

    /** Short text for notifications and the game list, e.g. "Paramount+, ViX" or "Entel Gol (Bolivia)". */
    fun summary(match: Match, names: Map<Int, String>): String {
        val (country, list) = byCountry(match, names).firstOrNull() ?: return ""
        val text = list.joinToString(", ") { it.name }
        return if (country == myCountryCode) text else "$text (${countryName(country)})"
    }

    /** Slow (reads every installed app's name): call off the main thread. */
    fun options(context: Context, channels: List<Channel>): List<WatchOption> {
        val pm = context.packageManager
        // Home-screen names of installed apps, loaded at most once per call and only if needed.
        val launcherApps by lazy {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map { it.loadLabel(pm).toString().lowercase() to it.activityInfo.packageName }
        }
        fun installedApp(p: Provider): Intent? {
            p.packages.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }?.let { return it }
            // "fantasy" skips e.g. ESPN Fantasy, which can't play games.
            val pkg = launcherApps.firstOrNull { (label, _) ->
                "fantasy" !in label && p.labels.any { label == it || label.startsWith("$it ") || label.startsWith("$it+") }
            }?.second
            return pkg?.let { pm.getLaunchIntentForPackage(it) }
        }
        return channels.map { ch ->
            // Installed is all that matters: the phone's region setting is often not where the user is.
            val provider = providers.firstOrNull { it.matches(ch.name) }
            WatchOption(ch.name, ch.country, provider?.let { installedApp(it) })
        }
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
