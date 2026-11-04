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

    /** FotMob's US channels and streaming services for a game, e.g. TUDN, ViX, Paramount+. */
    fun channels(match: Match): List<Channel> = match.tv[COUNTRY].orEmpty().map { Channel(it, COUNTRY) }

    /** Short text for notifications and the game list, e.g. "TUDN, ViX, Paramount+". */
    fun summary(match: Match): String = channels(match).joinToString(", ") { it.name }

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

    fun open(context: Context, intent: Intent) {
        try {
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    fun openUrl(context: Context, url: String) = open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
