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
import androidx.work.NetworkType
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
private const val PRE_GRACE_MS = 90 * MIN

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
                m.dateMillis > now - 2 * 24 * HOUR && m.teamIds.any { it in followed }
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
        Store.state.value.matches.values.forEach { handleTransition(context, it) }
        scheduleNextWatch(context)
    }

    /**
     * Every channel and streaming service showing each game, from SofaScore (all countries).
     * Re-checked while a game is within two days, since channels get added late. Failures leave
     * what we had, and are shown on the home screen so a blocked source is visible.
     */
    private fun findChannels(now: Long) {
        var problem = ""
        fun failed(e: Throwable) { problem = e.message ?: e.javaClass.simpleName; Log.w(TAG, "SofaScore", e) }

        for (team in Store.state.value.teams.filter { it.sofaId.isBlank() }) {
            val id = runCatching { Sofa.findTeam(team.name) }.onFailure(::failed).getOrNull() ?: continue
            Store.update { s -> s.copy(teams = s.teams.map { if (it.id == team.id) it.copy(sofaId = id) else it }) }
        }
        val s0 = Store.state.value
        val events = mutableMapOf<String, List<Sofa.Event>>()   // ESPN team id to its SofaScore games
        for (team in s0.teams.filter { it.sofaId.isNotBlank() }) {
            runCatching { Sofa.teamEvents(team.sofaId) }.onSuccess { events[team.id] = it }.onFailure(::failed)
        }
        // Refused outright (e.g. HTTP 403): don't send a request per game that will be refused too.
        if (events.isEmpty() && problem.isNotEmpty()) {
            Store.update { it.copy(tvStatus = problem) }
            return
        }
        val found = mutableMapOf<String, Pair<String, Map<String, List<Int>>>>()  // match id to (event, tvIds)
        val names = mutableMapOf<Int, String>()
        for (m in s0.matches.values) {
            if (m.state == "post" || m.dateMillis > now + 14 * 24 * HOUR) continue
            if (m.tvIds.isNotEmpty() && m.dateMillis > now + 2 * 24 * HOUR) continue
            // Same team, kickoff within 3 hours: a team never plays twice that close together.
            val event = m.sofaEventId.ifBlank { null } ?: s0.teams.filter { it.id in m.teamIds }
                .flatMap { events[it.id].orEmpty() }
                .firstOrNull { abs(it.startMillis - m.dateMillis) <= 3 * HOUR }?.id ?: continue
            runCatching { Sofa.countryChannels(event) }
                .onSuccess { (ids, n) -> found[m.id] = event to ids; names += n }
                .onFailure(::failed)
        }
        // Names for this phone's country now (cards and alerts show them); other countries load
        // when a game is opened.
        val my = Streaming.myCountryCode
        val myIds = (found.values.map { it.second } + s0.matches.values.map { it.tvIds })
            .flatMap { it[my].orEmpty() }.toSet()
        fun missing() = myIds.filter { it !in names && it !in s0.channelNames }
        if (missing().isNotEmpty()) runCatching { names += Sofa.channelNames(my) }.onFailure(::failed)
        for (id in missing().take(20)) runCatching { Sofa.channelName(id) }.onSuccess { n -> if (n != null) names[id] = n }
        Store.update { s ->
            val matches = s.matches.mapValues { (id, m) ->
                found[id]?.let { (event, ids) -> m.copy(sofaEventId = event, tvIds = ids) } ?: m
            }
            // Keep names only for channels some stored game uses.
            val used = matches.values.flatMap { it.tvIds.values.flatten() }.toSet()
            s.copy(matches = matches, channelNames = (s.channelNames + names).filterKeys { it in used }, tvStatus = problem)
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
        // ESPN knows nothing of SofaScore's listings; keep them.
        return (if (keepOld) old else fresh)
            .copy(broadcasts = broadcasts, sofaEventId = old.sofaEventId, tvIds = old.tvIds)
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
                    sofaEventId = cur.sofaEventId, tvIds = cur.tvIds)
                merged = next
                st.copy(matches = st.matches + (m.id to next))
            }
            handleTransition(context, merged ?: continue)
        }
        return gamesToWatch().isNotEmpty()
    }

    /**
     * Kickoff always notifies (it's the first alert of a game). Second half and full time only
     * notify while the user hasn't opened any notification for that game.
     */
    private fun handleTransition(context: Context, match: Match) {
        val phase = match.phase
        val now = System.currentTimeMillis()
        // Do not alert about games that ended long ago (e.g. the phone was off).
        val stale = now > match.dateMillis + WATCH_MAX_MS
        // Postponed/canceled: no alert, and no phase recorded, so a rescheduled game still alerts.
        if (match.state == "post" && !match.completed) return
        // Decide inside the store lock, so a notification tap (markRead) or a second poller running
        // at the same time can't be overwritten or cause a duplicate alert.
        var shouldNotify = false
        val updated = Store.update { st ->
            val alert = st.alerts[match.id] ?: AlertState()
            if (phase <= alert.phase) return@update st
            shouldNotify = !stale && !alert.read
            st.copy(alerts = st.alerts + (match.id to alert.copy(phase = phase, notified = alert.notified || shouldNotify)))
        }
        if (shouldNotify) Notifier.gameAlert(context, match, phase, updated.showScores)
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
        return Result.success()
    }
}
