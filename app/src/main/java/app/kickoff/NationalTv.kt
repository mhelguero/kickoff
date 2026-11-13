package app.kickoff

import java.text.Normalizer
import java.time.ZoneId

/**
 * Domestic channels for national-team games: a followed national team's games are looked up on the
 * TV guide of its own country and of its opponent's (sister sites of futbolenvivobolivia.com, read
 * by BoliviaTv), and shown with the country's flag. E.g. Peru vs Bolivia lists the Bolivian channels
 * and the Peruvian ones. Only Spanish-language guides are listed: their team pages use the Spanish
 * names in [slug].
 */
object NationalTv {
    /**
     * [names] are how ESPN names the national team; [slug] is the team's page on every guide
     * ("/equipo/peru"); [zone] is the guide's time zone (its dates are local).
     */
    class Country(val code: String, val names: List<String>, val slug: String, val site: String, zone: String) {
        val zone: ZoneId = ZoneId.of(zone)
        val flag: String get() = flag(code)
    }

    private fun guide(c: String) = "https://www.futbolenvivo$c.com"

    val countries = listOf(
        Country("AR", listOf("Argentina"), "argentina", guide("argentina"), "America/Argentina/Buenos_Aires"),
        Country("BO", listOf("Bolivia"), "bolivia", guide("bolivia"), "America/La_Paz"),
        Country("CL", listOf("Chile"), "chile", guide("chile"), "America/Santiago"),
        Country("CO", listOf("Colombia"), "colombia", guide("colombia"), "America/Bogota"),
        Country("CR", listOf("Costa Rica"), "costa-rica", guide("costarica"), "America/Costa_Rica"),
        Country("DO", listOf("Dominican Republic"), "republica-dominicana", guide("dominicana"), "America/Santo_Domingo"),
        Country("EC", listOf("Ecuador"), "ecuador", guide("ecuador"), "America/Guayaquil"),
        Country("SV", listOf("El Salvador"), "el-salvador", guide("elsalvador"), "America/El_Salvador"),
        Country("GT", listOf("Guatemala"), "guatemala", guide("guatemala"), "America/Guatemala"),
        Country("HN", listOf("Honduras"), "honduras", guide("honduras"), "America/Tegucigalpa"),
        Country("MX", listOf("Mexico"), "mexico", guide("mexico"), "America/Mexico_City"),
        Country("NI", listOf("Nicaragua"), "nicaragua", guide("nicaragua"), "America/Managua"),
        Country("PA", listOf("Panama"), "panama", guide("panama"), "America/Panama"),
        Country("PY", listOf("Paraguay"), "paraguay", guide("paraguay"), "America/Asuncion"),
        Country("PE", listOf("Peru"), "peru", guide("peru"), "America/Lima"),
        Country("UY", listOf("Uruguay"), "uruguay", guide("uruguay"), "America/Montevideo"),
        Country("VE", listOf("Venezuela"), "venezuela", guide("venezuela"), "America/Caracas"),
        Country("ES", listOf("Spain"), "espana", "https://www.futbolenlatv.es", "Europe/Madrid"),
    )

    /** The country a national team plays for ("Bolivia", "Perú"), or null for clubs and other countries. */
    fun country(team: String): Country? = countries.firstOrNull { c -> c.names.any { norm(it) == norm(team) } }

    /**
     * The countries playing in a game that have a guide: followed teams' first, then the opponent's.
     * Empty for club games.
     */
    fun countriesIn(m: Match, followed: List<Team>): List<Country> {
        val followedNames = followed.filter { it.id in m.teamIds }.map { norm(it.name) }.toSet()
        return listOf(m.home, m.away).sortedBy { norm(it) !in followedNames }.mapNotNull { country(it) }.distinct()
    }

    /** "BO" -> 🇧🇴 (two regional indicator letters). */
    fun flag(code: String): String = code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
        .lowercase().filter { it.isLetterOrDigit() }
}
