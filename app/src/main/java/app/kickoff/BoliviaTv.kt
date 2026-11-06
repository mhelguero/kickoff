package app.kickoff

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.time.LocalDate

/**
 * Bolivian channels for Bolivian league and cup games, from futbolenvivobolivia.com's team pages
 * (FotMob has no US channel for most of them). Unofficial: the page is read as HTML, so any change
 * to it just means no listing. A game row has its kickoff time ("17:15") and links its channels to
 * /canal/ pages; the date ("Domingo, 8/11/2026", in Bolivian time) comes before the rows. Teams link
 * to /equipo/ pages, except the page's own team, which is only a name and a crest.
 */
object BoliviaTv {
    data class Listing(val date: LocalDate, val teams: List<String>, val channels: List<String>)

    private const val BASE = "https://www.futbolenvivobolivia.com"
    private val DATE = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{4})\b""")
    private val TIME = Regex("""\b\d{1,2}:\d{2}\b""")

    /** The team's upcoming televised games; empty if the site has no page for [team]. */
    fun listings(team: String): List<Listing> {
        val doc = Jsoup.connect("$BASE/equipo/${slug(team)}")
            .userAgent("Mozilla/5.0 (Linux; Android) Kickoff/1.0")
            .timeout(15_000)
            .get()
        // The date in effect at each element, in document order: a date heading, then its games.
        val dateAt = java.util.IdentityHashMap<Element, LocalDate>()
        var date: LocalDate? = null
        for (el in doc.body().allElements) {
            DATE.find(el.ownText())?.let { m ->
                val (d, mo, y) = m.destructured
                date = runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull() ?: date
            }
            date?.let { dateAt[el] = it }
        }
        // A game's row: the nearest element around its channel links that also holds the kickoff time.
        val rows = LinkedHashMap<Element, MutableList<String>>()
        for (a in doc.select("a[href*=/canal/]")) {
            val row = a.parents().firstOrNull { TIME.containsMatchIn(it.ownText()) || it.children().any { c -> TIME.containsMatchIn(c.ownText()) } }
                ?: continue
            val name = channelName(a) ?: continue
            rows.getOrPut(row) { mutableListOf() }.let { if (name !in it) it += name }
        }
        return rows.mapNotNull { (row, channels) ->
            val day = dateAt[row] ?: return@mapNotNull null
            // Linked teams, plus the page's own team (named by its crest's title or alt text).
            val teams = (row.select("a[href*=/equipo/]").map { it.text() } +
                row.select("img[title], img[alt]").filter { it.closest("a[href*=/canal/], a[href*=/competicion/]") == null }
                    .map { it.attr("title").ifBlank { it.attr("alt") } })
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            Listing(day, teams, channels)
        }
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
