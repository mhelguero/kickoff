package app.kickoff

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
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
    /** Tried before [launch]: the app straight on this game's page, when it has such a link. */
    val gameLinks: List<Intent> = emptyList(),
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

    /** Listings are always for the USA (FotMob's country code), wherever the phone is. */
    const val COUNTRY = "US"

    /** Separator between channel names on cards and in alerts: a bullet, bigger than "·". */
    const val DOT = " • "

    /** Bolivia's channels, for Bolivian league and cup games with no US channel (see BoliviaTv). */
    const val BOLIVIA = "BO"

    /**
     * FotMob's US channels and streaming services for a game, e.g. TUDN, ViX, Paramount+; with none,
     * the Bolivian channel if it's a Bolivian game that has one (e.g. Entel Gol).
     */
    fun channels(match: Match): List<Channel> {
        val us = match.tv[COUNTRY].orEmpty()
        val (names, country) = if (us.isNotEmpty()) us to COUNTRY else match.tv[BOLIVIA].orEmpty() to BOLIVIA
        return names.map { shortName(it) }.distinct().map { Channel(it, country) }
    }

    /**
     * Shorter, combined names: ESPN Select (formerly ESPN+) shows as "ESPN+"; ESPN Deportes,
     * ESPN Unlimited, ESPN2 and the rest as "ESPN"; "Fubo Sports" as "Fubo"; "Paramount+" as
     * "Paramount Plus". Others are unchanged.
     */
    private fun shortName(name: String): String {
        val n = name.lowercase()
        return when {
            "fubo" in n -> "Fubo"
            "paramount" in n -> "Paramount Plus"
            "espn" !in n -> name
            "select" in n || "+" in n || "plus" in n -> "ESPN+"
            else -> "ESPN"
        }
    }

    /** Short text for notifications and game cards, e.g. "TUDN, ViX, Paramount+". */
    fun summary(match: Match, separator: String = ", "): String {
        val list = channels(match)
        // Bolivian channels say so: they're shown in Bolivia, not the US.
        return list.joinToString(separator) { it.name } + if (list.any { it.country == BOLIVIA }) " (Bolivia)" else ""
    }

    /** Slow (reads every installed app's name): call off the main thread. */
    fun options(context: Context, channels: List<Channel>, match: Match? = null): List<WatchOption> {
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
            val app = provider?.let { installedApp(it) }
            val links = if (app != null && match != null && provider.keys.contains("espn")) espnGame(pm, app, match) else emptyList()
            WatchOption(ch.name, ch.country, app, links)
        }
    }

    /**
     * Links that may open the ESPN app straight on this game's page; the games come from ESPN, so
     * its game id is known. Neither link is documented by ESPN. Only links the app lets other apps
     * open are kept (an ESPN screen that refuses them crashed Kickoff when opened).
     */
    private fun espnGame(pm: PackageManager, app: Intent, match: Match): List<Intent> {
        val pkg = app.`package` ?: app.component?.packageName ?: return emptyList()
        return listOf(
            "sportscenter://x-callback-url/showGame?sportName=soccer&leagueAbbrev=${match.leagueSlug}&gameID=${match.id}",
            "https://www.espn.com/soccer/match/_/gameId/${match.id}",
        ).map { Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(pkg) }
            .filter { link ->
                pm.queryIntentActivities(link, 0).any { it.activityInfo.exported && it.activityInfo.permission == null }
            }
    }

    /** Opens the first of the game links that works, else the app's home screen. Never crashes Kickoff. */
    fun open(context: Context, option: WatchOption) {
        for (intent in option.gameLinks + listOfNotNull(option.launch)) {
            if (tryOpen(context, intent)) return
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

    /** False if Android refused (no such screen, or one other apps may not open). */
    private fun tryOpen(context: Context, intent: Intent): Boolean = try {
        context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        // ActivityNotFoundException, SecurityException (screen not open to other apps), ...
        Log.w("Streaming", "couldn't open $intent", e)
        false
    }

    fun openUrl(context: Context, url: String) { tryOpen(context, Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
