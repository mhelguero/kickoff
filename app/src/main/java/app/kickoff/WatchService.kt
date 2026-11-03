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

/** Polls live games every minute while any followed team is playing, then stops itself. */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.WATCHING_ID, Notifier.watching(this, Tracker.gamesToWatch()), type)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kickoff:watch")
                .apply { acquire(WATCH_MAX_MS + WATCH_LEAD_MS) }
        }
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (Tracker.pollOnce(this@WatchService)) {
                    getSystemService(android.app.NotificationManager::class.java)
                        .notify(Notifier.WATCHING_ID, Notifier.watching(this@WatchService, Tracker.gamesToWatch()))
                    delay(60_000)
                }
                Tracker.scheduleNextWatch(this@WatchService)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
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
