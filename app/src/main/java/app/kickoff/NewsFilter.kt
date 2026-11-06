package app.kickoff

import java.text.Normalizer

/**
 * Keeps only factual team news: a headline must name someone in the team's squad (players and
 * coach), or be about a transfer, or name the team with a news word (injury, confirms, statement...).
 * Highlight reels are always kept. Opinion, ratings, reactions, previews, threads, lists, game
 * write-ups and league-wide pieces are dropped. Tuned on a real week of Real Madrid and Houston Dynamo headlines.
 */
object NewsFilter {
    private val EXCLUDE = Regex(listOf(
        """\bthread\b""", """\blive (blog|updates|coverage)\b""", """\bas it happened\b""",
        """\bratings?\b""", """\bgrades?\b""", """\brated\b""", """\branked\b""", """\brankings?\b""",
        """\breact\w*""", """\bfans\b""", """\bstats?\b""", """\bdaily\b""", """\bround-?up\b""", """\bbriefing\b""",
        """\bshould(n.t)?\b""", """\bmistake\b""",
        """\bobservations\b""", """\btakeaways\b""", """\btalking points\b""", """\bwhat we learned\b""", """\bthings we learned\b""",
        """\blessons\b""", """\bverdict\b""", """\bopinion\b""", """\bcolumn\b""", """\banalysis\b""", """\btactical\b""",
        """\bdebate\b""", """\bwinners and losers\b""", """\bstock (up|down)\b""",
        """\bbest\b""", """\bbiggest\b""", """\bworst\b""", """\bgreatest\b""", """\bhistory\b""", """\bof all time\b""",
        """\bpreview\b""", """\bpredict""", """\bsupercomputer\b""", """\bodds\b""", """\bbetting\b""", """\bpicks\b""",
        """\bhow to watch\b""", """\bwhere to watch\b""", """\bpodcast\b""", """\bquiz\b""", """\bmailbag\b""",
        """\beverything (at stake|to know|you need)\b""", """\bstorylines\b""", """\bstandings\b""", """\bplayoff picture\b""",
        """\bdestinations\b""", """\bscenarios\b""",
        // Questions and explainers ("Why...", "How one star...", "...?").
        """^\s*(why|how|what|who|which|is|are|can|could|should|would|will|does|do|did|has|have|when|where)\b""",
        """\?\s*$""",
        // A scoreline means a game write-up.
        """\b\d+\s*[-–]\s*\d+\b""",
    ).joinToString("|"))

    private val TRANSFER = Regex(
        """\b(transfers?|rumou?rs?|sign(s|ed|ing)?|deal|loan|bid|contract|targets?|targeted|targeting|talks|""" +
            """agree(s|d|ment)?|linked|interest(ed)?|tracking|swoop|approach|fee|release clause|extension|""" +
            """renew(s|al|ed)?|free agent|departure|exit|leave|leaving|join(s|ed|ing)?|move|wanted|want|wants|""" +
            """eye(s|d|ing)?|keen on|pursuit|race for|battle (over|for)|asking price|replacement|""" +
            """bring\w* .{0,30}back|identified|scouting|scouted)\b|[€£$]\s?\d+(\.\d+)?\s?m\b"""
    )

    private val NEWS = Regex(
        """\b(injur\w*|ruled out|sidelined|suspen\w*|banned|fined?|returns?|returning|confirm\w*|announce\w*|""" +
            """appoint\w*|sack\w*|fired|handed|named|reveals?|slams?|admits?|says|said|insists?|blasts?|warns?|""" +
            """hails?|praises?|criticis\w*|criticiz\w*|squad|line-?up|starting xi|training|fitness|medical|record|""" +
            """captain|statement|apolog\w*|responds?|explains?|demands?|questions?|lands?|job|role|decid\w*|plan|""" +
            """propos\w*|excited|update|news)\b"""
    )

    private val HIGHLIGHTS = Regex("""\bhighlights?\b""")

    /** Too common to tell players apart (e.g. "Vinícius Júnior", "Bernardo Silva"). */
    private val COMMON = setOf("junior", "silva", "santos", "costa", "alexander", "arnold")

    /** Name words worth matching in headlines, from a squad list ("José Mourinho" -> jose, mourinho). */
    fun peopleWords(names: List<String>): Set<String> =
        names.flatMap { norm(it).split(Regex("""[\s\-]+""")) }
            .map { it.trim('.', '\'') }
            .filter { it.length >= 4 && it !in COMMON }
            .toSet()

    fun keep(title: String, teamName: String, people: Set<String>): Boolean {
        val t = norm(title)
        // Highlight reels are kept, game or not.
        if (HIGHLIGHTS.containsMatchIn(t)) return true
        if (EXCLUDE.containsMatchIn(t)) return false
        // Letters and digits only, so "Mbappé's" still matches "mbappe".
        if (Regex("""[a-z0-9]+""").findAll(t).any { it.value in people }) return true
        if (TRANSFER.containsMatchIn(t)) return true
        val team = norm(teamName.replace(Regex("""\b(FC|CF|SC)\b"""), "")).trim()
        return team in t && NEWS.containsMatchIn(t)
    }

    /** Lowercase without accents: "Mbappé" -> "mbappe". */
    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("""\p{Mn}+"""), "").lowercase().replace('’', '\'').replace('‘', '\'')
}
