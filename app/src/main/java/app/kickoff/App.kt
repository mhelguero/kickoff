package app.kickoff

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifier.createChannels(this)
        Streaming.detectCountry(this)
        Tracker.schedulePeriodicRefresh(this)
    }
}
