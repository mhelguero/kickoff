package app.kickoff

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Fires at kickoff and then every minute while a followed team is playing (see Tracker.watchTick). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Store.init(app)
        // goAsync keeps the phone awake until finish(); stay well under the broadcast time limit.
        // A cut-short poll is fine: watchTick sets the next minute's alarm before polling.
        val pending = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)
        // Network calls block and can't be cancelled, so stop waiting on them rather than cancel them.
        val work = scope.async { Tracker.watchTick(app) }
        scope.launch {
            try {
                withTimeoutOrNull(40_000) { work.join() }
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        Tracker.schedulePeriodicRefresh(context)
        Tracker.scheduleNextWatch(context)
    }
}
