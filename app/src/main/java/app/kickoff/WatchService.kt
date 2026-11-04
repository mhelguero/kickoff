package app.kickoff

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Polls live games every minute while any followed team is playing, then stops itself. */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var lastStartId = 0
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kickoff:watch")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        lastStartId = startId
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.WATCHING_ID, Notifier.watching(this, Tracker.gamesToWatch()), type)
        if (loop?.isActive != true) startLoop()
        return START_NOT_STICKY
    }

    private fun startLoop() {
        loop = scope.launch {
            while (true) {
                // Short, renewed each pass: keeps the CPU awake between polls without one long hold.
                wakeLock.acquire(3 * 60_000L)
                if (!Tracker.pollOnce(this@WatchService)) break
                // If the system kills us mid-game, this alarm restarts watching within 10 minutes.
                Tracker.armWatchdog(this@WatchService)
                getSystemService(android.app.NotificationManager::class.java)
                    .notify(Notifier.WATCHING_ID, Notifier.watching(this@WatchService, Tracker.gamesToWatch()))
                wakeLock.acquire(2 * 60_000L) // the poll above may have used most of the last one
                delay(60_000)
            }
            Tracker.scheduleNextWatch(this@WatchService)
            withContext(Dispatchers.Main) {
                // A new start may have arrived while we were finishing; only stop if nothing needs
                // watching and no newer start is pending (stopping then would crash the app).
                if (Tracker.gamesToWatch().isNotEmpty() || !stopSelfResult(lastStartId)) startLoop()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        if (Tracker.gamesToWatch().isNotEmpty()) Tracker.startWatching(context)
        else Tracker.scheduleNextWatch(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        Tracker.schedulePeriodicRefresh(context)
        Tracker.scheduleNextWatch(context)
    }
}
