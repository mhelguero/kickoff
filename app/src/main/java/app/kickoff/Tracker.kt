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
import java.util.concurrent.TimeUnit

private const val TAG = "Tracker"
private const val MIN = 60_000L
private const val HOUR = 60 * MIN

/** Start watching a little before kickoff; give up on a game this long after its scheduled start. */
const val WATCH_LEAD_MS = 3 * MIN
const val WATCH_MAX_MS = 4 * HOUR

object Tracker {

    /** Pull fixtures for every followed team and merge them into the store. */
    fun refresh(context: Context) {
        val teams = Store.state.value.teams
        val fresh = mutableMapOf<String, Match>()
        for (team in teams) {
            runCatching { Espn.fixtures(team.id) }
                .onSuccess { list -> list.forEach { fresh[it.id] = it } }
                .onFailure { Log.w(TAG, "fixtures ${team.name} failed", it) }
        }
        val now = System.currentTimeMillis()
        val updated = Store.update { s ->
            val followed = s.teams.map { it.id }.toSet()
            val kept = (s.matches + fresh).filterValues { m ->
                m.dateMillis > now - 2 * 24 * HOUR && m.teamIds.any { it in followed }
            }
            s.copy(
                matches = kept,
                alerts = s.alerts.filterKeys { it in kept },
                lastRefresh = now,
            )
        }
        updated.matches.values.forEach { handleTransition(context, it) }
        scheduleNextWatch(context)
    }

    /** Games that need live polling right now. */
    fun gamesToWatch(now: Long = System.currentTimeMillis()): List<Match> {
        val s = Store.state.value
        return s.matches.values.filter { m ->
            m.dateMillis - WATCH_LEAD_MS <= now &&
                now <= m.dateMillis + WATCH_MAX_MS &&
                (s.alerts[m.id]?.phase ?: Phase.NONE) < Phase.ENDED &&
                !(m.state == "post")
        }
    }

    /** One polling pass over live games. Returns true if any game still needs watching. */
    fun pollOnce(context: Context): Boolean {
        for (m in gamesToWatch()) {
            val live = runCatching { Espn.match(m.id) }
                .onFailure { Log.w(TAG, "poll ${m.id} failed", it) }
                .getOrNull() ?: continue
            // Summary has no broadcasts for some games; keep what the schedule gave us.
            val merged = live.copy(broadcasts = live.broadcasts.ifEmpty { m.broadcasts })
            Store.update { it.copy(matches = it.matches + (m.id to merged)) }
            handleTransition(context, merged)
        }
        return gamesToWatch().isNotEmpty()
    }

    /**
     * Kickoff always notifies (it's the first alert of a game). Second half and full time only
     * notify while the user hasn't opened any notification for that game.
     */
    private fun handleTransition(context: Context, match: Match) {
        val phase = match.phase
        val s = Store.state.value
        val alert = s.alerts[match.id] ?: AlertState()
        if (phase <= alert.phase) return
        val now = System.currentTimeMillis()
        // Don't alert about games that ended long ago (e.g. phone was off) or were postponed.
        val stale = now > match.dateMillis + WATCH_MAX_MS
        val postponed = match.state == "post" && !match.completed
        val shouldNotify = !stale && !postponed && !alert.read
        Store.update {
            it.copy(alerts = it.alerts + (match.id to alert.copy(phase = phase, notified = alert.notified || shouldNotify)))
        }
        if (shouldNotify) Notifier.gameAlert(context, match, phase, s.showScores)
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
        val next = s.matches.values
            .filter { (s.alerts[it.id]?.phase ?: 0) < Phase.ENDED && it.dateMillis + WATCH_MAX_MS > now && it.state != "post" }
            .minOfOrNull { it.dateMillis - WATCH_LEAD_MS } ?: return
        val at = maxOf(next, now + 1000)
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
        Log.i(TAG, "next watch alarm at $at")
    }

    /** Start live polling: a foreground service if allowed, else a WorkManager chain. */
    fun startWatching(context: Context) {
        try {
            context.startForegroundService(Intent(context, WatchService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "can't start service, falling back to WorkManager", e)
            enqueuePoll(context, 0)
        }
    }

    fun enqueuePoll(context: Context, delayMs: Long) {
        val req = OneTimeWorkRequestBuilder<PollWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("poll", ExistingWorkPolicy.REPLACE, req)
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        Tracker.refresh(applicationContext)
        return Result.success()
    }
}

class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Store.init(applicationContext)
        if (Tracker.pollOnce(applicationContext)) Tracker.enqueuePoll(applicationContext, MIN)
        else Tracker.scheduleNextWatch(applicationContext)
        return Result.success()
    }
}
