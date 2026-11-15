package app.kickoff

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val TAG = "Tracker"
private const val MIN = 60_000L
private const val HOUR = 60 * MIN

/** Start watching a little before kickoff; give up on a game this long after its scheduled start. */
const val WATCH_LEAD_MS = 3 * MIN
const val WATCH_MAX_MS = 4 * HOUR
private val ESPN_DAY_ZONE = ZoneId.of("America/New_York")
private val BOLIVIA_ZONE = ZoneId.of("America/La_Paz")
private const val PRE_GRACE_MS = 90 * MIN
/** Played games are kept this long: enough for the whole of last week (Sunday to Saturday). */
private const val KEEP_MS = 15 * 24 * HOUR

object Tracker {

    /** Pull fixtures for every followed team and merge them into the store. */
    fun refresh(context: Context) {
        val teams = Store.state.value.teams
        fillTeamColors(teams)
        val fresh = mutableMapOf<String, Match>()
        val now = System.currentTimeMillis()
        for (team in teams) {
            runCatching { Espn.fixtures(team.id) }
                .onSuccess { list -> list.forEach { fresh[it.id] = it } }
                .onFailure { Log.w(TAG, "fixtures ${team.name} failed", it) }
            // Played games of the last two weeks, for the Last Week section (upcoming ones win).
            runCatching { Espn.results(team.id) }
                .onSuccess { list ->
                    list.filter { it.state == "post" && it.dateMillis > now - KEEP_MS }.forEach { fresh.putIfAbsent(it.id, it) }
                }
                .onFailure { Log.w(TAG, "results ${team.name} failed", it) }
        }
        // A game already under way isn't in the fixtures list. Teams with nothing stored yet (just
        // added, or first launch) may be mid-game, so check the scoreboards for the last few hours.
        val newTeams = teams.map { it.id }.filter { it !in Store.state.value.scoreboardChecked }.toSet()
        var scoreboardOk = true
        if (newTeams.isNotEmpty()) {
            // ESPN's scoreboard days run on US Eastern time, not UTC.
            val days = listOf(now - WATCH_MAX_MS, now).map {
                DateTimeFormatter.ofPattern("yyyyMMdd").format(Instant.ofEpochMilli(it).atZone(ESPN_DAY_ZONE))
            }.distinct()
            for (day in days) {
                runCatching { Espn.scoreboard(day) }
                    .onSuccess { list ->
                        // Only games under way: upcoming ones come from fixtures, and a game that ended
                        // before the team was followed shouldn't send a "has ended" alert.
                        list.filter { m -> m.state == "in" && m.teamIds.any { it in newTeams } }
                            .forEach { fresh.putIfAbsent(it.id, it) }
                    }
                    .onFailure { scoreboardOk = false; Log.w(TAG, "scoreboard $day failed", it) }
            }
        }
        // Games we stopped expecting (kickoff delayed past the grace period, or no confirmed time):
        // the fixtures list drops a game once it's under way, so ask ESPN about each one directly.
        for (m in Store.state.value.matches.values) {
            val delayed = m.state == "pre" && now > m.dateMillis + PRE_GRACE_MS && now < m.dateMillis + WATCH_MAX_MS
            val unconfirmed = !m.timeValid && m.state == "pre" && abs(now - m.dateMillis) < 24 * HOUR
            if (delayed || unconfirmed) runCatching { Espn.match(m.id) }.onSuccess { fresh[m.id] = it }
        }
        Store.update { s ->
            val followed = s.teams.map { it.id }.toSet()
            val merged = fresh.mapValues { (id, f) -> mergeRefresh(s.matches[id], f) }
            val kept = (s.matches + merged).filterValues { m ->
                m.dateMillis > now - KEEP_MS && m.teamIds.any { it in followed }
            }
            s.copy(
                matches = kept,
                alerts = s.alerts.filterKeys { it in kept },
                // Checked once per team; dropped when unfollowed so re-adding checks again.
                scoreboardChecked = (s.scoreboardChecked + if (scoreboardOk) newTeams else emptySet())
                    .filter { it in followed }.toSet(),
                lastRefresh = now,
            )
        }
        findChannels(now)
        findBolivianChannels(now)
        Store.state.value.matches.values.forEach { handleTransition(context, it) }
        scheduleNextWatch(context)
    }

    /**
     * Every channel and streaming service showing each game in the USA, from FotMob.
     * Re-checked while a game is within two days, since channels get added late. Failures keep
     * what we had; the home screen shows how it went.
     */
    private fun findChannels(now: Long) {
        var problem = ""
        // Each step's error, named, so the status line shows where listings stop.
        fun failed(step: String): (Throwable) -> Unit = { e ->
            problem = "$step: ${e.message ?: e.javaClass.simpleName}".take(120)
            Log.w(TAG, "FotMob $step", e)
        }

        val fixtures = mutableMapOf<String, List<FotMob.Fixture>>()   // ESPN team id to its FotMob games
        for (team in Store.state.value.teams) {
            // The team's ESPN games: a FotMob team is the right one if its games are at the same times.
            val games = Store.state.value.matches.values.filter { team.id in it.teamIds }
            fun lineUp(list: List<FotMob.Fixture>) = games.isEmpty() ||
                games.any { m -> list.any { abs(it.startMillis - m.dateMillis) <= 3 * HOUR } }
            if (team.fotmobId.isNotBlank()) {
                val list = runCatching { FotMob.fixtures(team.fotmobId) }.onFailure(failed("games")).getOrNull() ?: continue
                if (lineUp(list)) { fixtures[team.id] = list; continue }
                Log.w(TAG, "FotMob team ${team.fotmobId} isn't ${team.name}: searching again")
            }
            // Search: the first candidate whose games line up (FotMob's names can differ from ESPN's).
            val candidates = runCatching { FotMob.findTeams(team.name) }.onFailure(failed("team search")).getOrNull() ?: continue
            var picked: Pair<String, List<FotMob.Fixture>>? = null
            for (id in candidates.take(4)) {
                val list = runCatching { FotMob.fixtures(id) }.onFailure(failed("games")).getOrNull() ?: continue
                if (lineUp(list)) { picked = id to list; break }
            }
            // Not found by name (e.g. ESPN's "United States" is FotMob's "USA"): find it through
            // the opponent of one of its next games, whose search results include that game.
            if (picked == null) {
                val upcoming = games.filter { it.dateMillis > now - 3 * HOUR }.sortedBy { it.dateMillis }.take(2)
                for (m in upcoming) {
                    val opponent = if (m.home == team.name) m.away else m.home
                    val byOpponent = runCatching { FotMob.teamsByOpponent(opponent, m.dateMillis) }
                        .onFailure(failed("team search")).getOrDefault(emptyList())
                    for (id in byOpponent.filter { it !in candidates.take(4) }) {
                        val list = runCatching { FotMob.fixtures(id) }.onFailure(failed("games")).getOrNull() ?: continue
                        if (lineUp(list)) { picked = id to list; break }
                    }
                    if (picked != null) break
                }
            }
            // None found: forget a wrong pick, so the next refresh searches again.
            val id = picked?.first ?: ""
            if (id.isEmpty() && team.fotmobId.isBlank()) continue
            picked?.let { fixtures[team.id] = it.second }
            Store.update { s -> s.copy(teams = s.teams.map { if (it.id == team.id) it.copy(fotmobId = id) else it }) }
        }
        val s0 = Store.state.value
        val teamsFound = "${s0.teams.count { it.fotmobId.isNotBlank() }}/${s0.teams.size} teams"
        // Refused outright (e.g. HTTP 403): don't send a request per game that will be refused too.
        if (fixtures.isEmpty() && problem.isNotEmpty()) {
            Log.w(TAG, "FotMob: $teamsFound. Error: $problem")
            return
        }
        val my = Streaming.COUNTRY
        val found = mutableMapOf<String, Pair<String, List<String>>>()  // match id to (FotMob id, channels here)
        for (m in s0.matches.values) {
            if (m.state == "post" || m.dateMillis > now + 14 * 24 * HOUR) continue
            if (my in m.tv && m.dateMillis > now + 2 * 24 * HOUR) continue
            // Same team, kickoff within 3 hours: a team never plays twice that close together.
            val fid = m.fotmobId.ifBlank { null } ?: s0.teams.filter { it.id in m.teamIds }
                .flatMap { fixtures[it.id].orEmpty() }
                .firstOrNull { abs(it.startMillis - m.dateMillis) <= 3 * HOUR }?.id ?: continue
            runCatching { FotMob.channels(fid, my) }.onSuccess { found[m.id] = fid to it }.onFailure(failed("channels"))
        }
        Log.i(TAG, "FotMob: $teamsFound, ${found.size} games checked, " +
            "${found.values.count { it.second.isNotEmpty() }} with US channels" +
            problem.ifBlank { null }?.let { ". Error: $it" }.orEmpty())
        Store.update { s ->
            val matches = s.matches.mapValues { (id, m) ->
                // An empty list is kept too: it records that this country was checked.
                found[id]?.let { (fid, list) -> m.copy(fotmobId = fid, tv = m.tv + (my to list)) } ?: m
            }
            s.copy(matches = matches)
        }
    }

    /**
     * Bolivian league and cup games rarely have a US channel: list the Bolivian one instead (usually
     * Entel Gol), from futbolenvivobolivia.com, matched by the game's date in Bolivia and its teams.
     * Re-checked each refresh while the game is upcoming; a failed read keeps what we had.
     */
    private fun findBolivianChannels(now: Long) {
        val s0 = Store.state.value
        // Bolivian competitions, or any game of a followed Bolivian team (its page lists cup games too).
        val bolivianTeams = s0.teams.filter { "bolivia" in it.league.lowercase() }.map { it.id }.toSet()
        val games = s0.matches.values.filter { m ->
            (m.leagueSlug.startsWith("bol.") || m.teamIds.any { it in bolivianTeams }) &&
                m.state != "post" && m.dateMillis <= now + 14 * 24 * HOUR && m.tv[Streaming.COUNTRY].isNullOrEmpty()
        }
        if (games.isEmpty()) return
        val pages = mutableMapOf<String, List<BoliviaTv.Listing>>()   // followed team id -> its listings
        val found = mutableMapOf<String, List<String>>()               // match id -> Bolivian channels
        for (m in games) {
            val date = Instant.ofEpochMilli(m.dateMillis).atZone(BOLIVIA_ZONE).toLocalDate()
            val listing = s0.teams.filter { it.id in m.teamIds }.firstNotNullOfOrNull { team ->
                val list = pages.getOrPut(team.id) {
                    runCatching { BoliviaTv.listings(team.name) }.onFailure { Log.w(TAG, "Bolivia TV ${team.name}", it) }
                        .getOrDefault(emptyList())
                }
                // The page is this team's, and a team plays once a day: the date settles it, the
                // names (which can differ: "GV San José") only pick between rows if ever needed.
                val sameDay = list.filter { it.date == date }
                fun names(l: BoliviaTv.Listing, team: String) = l.teams.any { BoliviaTv.sameTeam(it, team) }
                sameDay.firstOrNull { names(it, m.home) && names(it, m.away) } ?: sameDay.firstOrNull()
            } ?: continue
            found[m.id] = listing.channels
        }
        Log.i(TAG, "Bolivia TV: ${games.size} games, ${found.size} listed")
        if (found.isEmpty()) return
        Store.update { s ->
            s.copy(matches = s.matches.mapValues { (id, m) ->
                found[id]?.let { m.copy(tv = m.tv + (Streaming.BOLIVIA to it)) } ?: m
            })
        }
    }

    /** Teams added from search (or saved before colors existed) get their card color here. */
    private fun fillTeamColors(teams: List<Team>) {
        for (team in teams.filter { it.color.isBlank() }) {
            val color = TEAM_COLOR_OVERRIDES[team.id]
                ?: runCatching { Espn.teamColor(team.id) }.getOrNull()
                ?: continue
            Store.update { s -> s.copy(teams = s.teams.map { if (it.id == team.id) it.copy(color = color) else it }) }
        }
    }

    /**
     * Could this game need watching now or later? Skips games without a confirmed kickoff time
     * (unless already live), and gives up on one still not started 90 minutes after its scheduled
     * time; refresh checks those directly and brings them back once they're live.
     */
    private fun stillOpen(m: Match, s: AppState, now: Long) =
        (m.timeValid || m.state == "in") && m.state != "post" &&
            (s.alerts[m.id]?.phase ?: Phase.NONE) < Phase.ENDED &&
            now <= m.dateMillis + if (m.state == "pre") PRE_GRACE_MS else WATCH_MAX_MS

    /**
     * The schedule can lag behind live polling: don't let it move a game backwards (finished to live
     * to not started) at the same kickoff time, and keep channels we already know when it has none.
     * A new kickoff time means the game was rescheduled, so the fresh data wins.
     */
    private fun mergeRefresh(old: Match?, fresh: Match): Match {
        if (old == null) return fresh
        val broadcasts = fresh.broadcasts.ifEmpty { old.broadcasts }
        fun rank(m: Match) = when (m.state) { "pre" -> 0; "in" -> 1; else -> 2 }
        val keepOld = rank(old) > rank(fresh) && old.dateMillis == fresh.dateMillis
        // ESPN knows nothing of FotMob's listings; keep them.
        return (if (keepOld) old else fresh)
            .copy(broadcasts = broadcasts, fotmobId = old.fotmobId, tv = old.tv)
    }

    /** Games that need live polling right now. */
    fun gamesToWatch(now: Long = System.currentTimeMillis()): List<Match> {
        val s = Store.state.value
        return s.matches.values.filter { m -> m.dateMillis - WATCH_LEAD_MS <= now && stillOpen(m, s, now) }
    }

    /** One polling pass over live games. Returns true if any game still needs watching. */
    fun pollOnce(context: Context): Boolean {
        for (m in gamesToWatch()) {
            val live = runCatching { Espn.match(m.id) }
                .onFailure { Log.w(TAG, "poll ${m.id} failed", it) }
                .getOrNull() ?: continue
            // Merged inside the update so a refresh running meanwhile (new channel listings) isn't undone.
            var merged: Match? = null
            Store.update { st ->
                // Skip if a refresh removed the game meanwhile (its team was unfollowed).
                val cur = st.matches[m.id] ?: return@update st
                // Summary has no broadcasts for some games; keep what the schedule gave us.
                val next = live.copy(broadcasts = live.broadcasts.ifEmpty { cur.broadcasts },
                    fotmobId = cur.fotmobId, tv = cur.tv)
                merged = next
                st.copy(matches = st.matches + (m.id to next))
            }
            handleTransition(context, merged ?: continue)
        }
        return gamesToWatch().isNotEmpty()
    }

    /**
     * Kickoff, second half and full time each notify once, unless the user turned that game's
     * alerts off with the bell on its card.
     */
    private fun handleTransition(context: Context, match: Match) {
        val phase = match.phase
        val now = System.currentTimeMillis()
        // Do not alert about games that ended long ago (e.g. the phone was off).
        val stale = now > match.dateMillis + WATCH_MAX_MS
        // Postponed/canceled: no alert, and no phase recorded, so a rescheduled game still alerts.
        if (match.state == "post" && !match.completed) return
        // Decide inside the store lock, so a bell tap (setMuted) or a second poller running at the
        // same time can't be overwritten or cause a duplicate alert. A muted game's phases are still
        // recorded, so turning alerts back on doesn't send old ones.
        var shouldNotify = false
        var justEnded = false
        Store.update { st ->
            val alert = st.alerts[match.id] ?: AlertState()
            if (phase <= alert.phase) return@update st
            shouldNotify = !stale && !alert.muted
            justEnded = phase == Phase.ENDED && !stale
            st.copy(alerts = st.alerts + (match.id to alert.copy(phase = phase, notified = alert.notified || shouldNotify)))
        }
        if (shouldNotify) Notifier.gameAlert(context, match, phase)
        // Look for the game's highlights once they've had time to go up.
        if (justEnded) scheduleFeedLoad(context, match.id, 30 * MIN)
    }

    /** Load the Feed (news, highlights, YouTube searches) in the background after [delayMs]. */
    fun scheduleFeedLoad(context: Context, tag: String, delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<FeedWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("feed-$tag", ExistingWorkPolicy.REPLACE, req)
    }

    // ---- scheduling ----

    fun schedulePeriodicRefresh(context: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(3, TimeUnit.HOURS)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Set an alarm for the next kickoff (or now, if a game is already in its window). */
    fun scheduleNextWatch(context: Context) {
        val now = System.currentTimeMillis()
        val s = Store.state.value
        // Same rule as gamesToWatch, so an alarm never fires for a game it then won't watch.
        val next = s.matches.values
            .filter { stillOpen(it, s, now) }
            .minOfOrNull { it.dateMillis - WATCH_LEAD_MS } ?: return
        setWatchAlarm(context, maxOf(next, now + 1000))
    }

    /**
     * One watch step, run from the alarm: polls live games and, while any still needs watching,
     * sets the next alarm a minute out. Exact alarms keep firing while the phone sleeps, so no
     * foreground service (and no permanent notification) is needed.
     */
    fun watchTick(context: Context) {
        if (gamesToWatch().isEmpty()) return scheduleNextWatch(context)
        // Set first: if this poll is cut short, the next minute's alarm still comes.
        setWatchAlarm(context, System.currentTimeMillis() + MIN)
        if (!pollOnce(context)) scheduleNextWatch(context)
    }

    // One alarm slot: the next-kickoff alarm and the per-minute watch alarm replace each other.
    private fun setWatchAlarm(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        Log.i(TAG, "watch alarm at $at")
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        Tracker.refresh(applicationContext)
        // Keep highlights current too (YouTube searches are saved, so the Feed shows them at once).
        runCatching { News.load(applicationContext, Store.state.value.teams) }
        // Install any new version while the app isn't on screen.
        if (!Updater.onScreen) runCatching { Updater.autoUpdate(applicationContext) }.onFailure { Log.w(TAG, "auto update", it) }
        return Result.success()
    }
}

/** Loads the Feed in the background, e.g. half an hour after a followed team's game ends. */
class FeedWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        runCatching { News.load(applicationContext, Store.state.value.teams) }
        // Install any new version while the app isn't on screen.
        if (!Updater.onScreen) runCatching { Updater.autoUpdate(applicationContext) }.onFailure { Log.w(TAG, "auto update", it) }
        return Result.success()
    }
}
