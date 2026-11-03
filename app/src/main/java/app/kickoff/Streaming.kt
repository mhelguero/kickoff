package app.kickoff

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A place to watch. [launch] is set only when the matching app is installed on this phone. */
data class WatchOption(
    val name: String,
    val kind: String,
    val launch: Intent?,
)

object Streaming {
    /**
     * [keys] match the broadcaster name from ESPN; [packages] are the known Android apps;
     * [labels] find the app by its home-screen name if the package differs (e.g. a regional build).
     */
    private class Provider(val keys: List<String>, val packages: List<String>, val labels: List<String>)

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
        Provider(listOf("tigo"), emptyList(), listOf("tigo sports")),
        Provider(listOf("fanatiz"), emptyList(), listOf("fanatiz")),
    )

    /** Broadcaster names for a game, e.g. "Apple TV". Bolivian games fall back to the usual rights holder. */
    fun names(match: Match): List<Pair<String, String>> {
        val listed = match.broadcasts.map { b ->
            b.name to when {
                b.kind.equals("STREAMING", true) -> "Streaming"
                b.kind.isBlank() -> ""
                else -> "TV"
            }
        }
        if (listed.isNotEmpty()) return listed
        // ESPN lists US broadcasters only and has none for the Bolivian league.
        if (match.leagueSlug.startsWith("bol.")) return listOf("Tigo Sports" to "Usual Bolivian league broadcaster")
        return emptyList()
    }

    fun optionsFor(context: Context, match: Match): List<WatchOption> = names(match).map { (name, kind) ->
        val provider = providers.firstOrNull { p -> p.keys.any { name.lowercase().contains(it) } }
        WatchOption(name, kind, provider?.let { installedApp(context, it) })
    }

    fun summary(match: Match): String = names(match).joinToString(", ") { it.first }

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

    fun open(context: Context, intent: Intent) {
        try {
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    fun openUrl(context: Context, url: String) = open(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
