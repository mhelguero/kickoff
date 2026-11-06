package app.kickoff

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifier.createChannels(this)
        Tracker.schedulePeriodicRefresh(this)
        Updater.schedulePeriodicCheck(this)
    }
}
