package app.kickoff

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.time.LocalDate

/**
 * Bolivian channels for Bolivian league and cup games, from futbolenvivobolivia.com's team pages
 * (FotMob has no US channel for most of them). Its sister sites (futbolenvivoperu.com, ...) share the
 * layout, so [listings] reads any of them (see NationalTv). Unofficial: the page is read as HTML, so
 * any change to it just means no listing. A game is a table row: home and away teams, then its
 * channels; the date ("Domingo, 8/11/2026", in the site's country's time) heads its rows.
 */
object BoliviaTv {
    data class Listing(val date: LocalDate, val teams: List<String>, val channels: List<String>)

    private const val BASE = "https://www.futbolenvivobolivia.com"
    private val DATE = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{4})\b""")

    /**
     * The team's upcoming televised games in the site's country; empty if the site has no page for
     * [team]. [site] is the site's address, e.g. "https://www.futbolenvivoperu.com".
     */
    fun listings(team: String, site: String = BASE): List<Listing> = parse(
        Jsoup.connect("$site/equipo/${slug(team)}")
            .userAgent("Mozilla/5.0 (Linux; Android) Kickoff/1.0")
            .timeout(15_000)
            .get()
    )

    fun parse(doc: org.jsoup.nodes.Document): List<Listing> {
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
        // A game's row (a table row with a "canales" cell): its teams in the "local" and "visitante"
        // cells, its channels in a list, linked to /canal/ pages or not (channels without a page).
        return doc.select("tr:has(td.canales)").mapNotNull { row ->
            val day = dateAt[row] ?: return@mapNotNull null
            val teams = row.select("td.local, td.visitante").map { td ->
                td.selectFirst("[title]")?.attr("title")?.ifBlank { null } ?: td.text()
            }.map { it.trim() }.filter { it.isNotEmpty() }
            val channels = row.select("td.canales ul li").mapNotNull { li ->
                channelName(li.attr("title").ifBlank { li.text() })
            }.distinct()
            if (channels.isEmpty()) null else Listing(day, teams, channels)
        }
    }

    /**
     * "Entel Gol (Canal 56)" -> "Entel Gol", "DSports (610/1610)" -> "DSports", "DAZN (Ver en directo)"
     * -> "DAZN": cable channel numbers and notes make the list long.
     */
    private fun channelName(raw: String): String? {
        return raw.trim().replace(Regex("""\s*\([^)]*\)"""), "").trim().ifEmpty { null }
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
