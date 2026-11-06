package app.kickoff

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.time.LocalDate

/**
 * Bolivian channels for Bolivian league and cup games, from futbolenvivobolivia.com's team pages
 * (FotMob has no US channel for most of them). Unofficial: the page is read as HTML, so any change
 * to it just means no listing. Each game row links its teams to /equipo/ pages and its channels to
 * /canal/ pages; the date ("Domingo, 8/11/2026", in Bolivian time) comes before the rows.
 */
object BoliviaTv {
    data class Listing(val date: LocalDate, val home: String, val away: String, val channels: List<String>)

    private const val BASE = "https://www.futbolenvivobolivia.com"
    private val DATE = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{4})\b""")

    /** The team's upcoming televised games; empty if the site has no page for [team]. */
    fun listings(team: String): List<Listing> {
        val doc = Jsoup.connect("$BASE/equipo/${slug(team)}")
            .userAgent("Mozilla/5.0 (Linux; Android) Kickoff/1.0")
            .timeout(15_000)
            .get()
        val out = mutableListOf<Listing>()
        var date: LocalDate? = null
        // Document order: a date heading, then that day's game rows.
        for (el in doc.body().allElements) {
            DATE.find(el.ownText())?.let { m ->
                val (d, mo, y) = m.destructured
                date = runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull() ?: date
            }
            if (el.tagName() != "tr") continue
            val teams = el.select("a[href*=/equipo/]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
            val channels = el.select("a[href*=/canal/]").mapNotNull { channelName(it) }.distinct()
            val day = date ?: continue
            if (teams.size >= 2 && channels.isNotEmpty()) out += Listing(day, teams[0], teams[1], channels)
        }
        return out
    }

    /** "Entel Gol (Canal 56)" -> "Entel Gol": the cable channel numbers make the list long. */
    private fun channelName(a: Element): String? {
        val raw = a.text().ifBlank { a.attr("title") }.ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }.trim()
        return raw.replace(Regex("""\s*\(canal[^)]*\)""", RegexOption.IGNORE_CASE), "").trim().ifEmpty { null }
    }

    /** Is this the same team? "Bolívar" and "Club Bolivar", "GV San José" and "Gualberto Villarroel San José"... */
    fun sameTeam(a: String, b: String): Boolean {
        val wa = words(a)
        val wb = words(b)
        return norm(a) == norm(b) || (wa.isNotEmpty() && wb.isNotEmpty() && (wa.containsAll(wb) || wb.containsAll(wa)))
    }

    private val GENERIC = setOf("club", "fc", "cd", "sc", "de", "deportivo", "the")
    private fun words(s: String) = Regex("[a-z0-9]+").findAll(norm(s, keepSpaces = true)).map { it.value }
        .filter { it !in GENERIC }.toSet()

    /** The site's page name for a team: "Bolívar" -> "bolivar", "The Strongest" -> "the-strongest". */
    private fun slug(team: String) = norm(team, keepSpaces = true).trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun norm(s: String, keepSpaces: Boolean = false): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "").lowercase()
        return if (keepSpaces) n else n.filter { it.isLetterOrDigit() }
    }
}
