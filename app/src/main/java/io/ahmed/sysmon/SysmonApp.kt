package io.ahmed.sysmon

import android.app.Application
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.service.DailyReportWorker
import io.ahmed.sysmon.service.Notifier
import io.ahmed.sysmon.service.RouterPollService

class SysmonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)

        // Only start the always-on poller if the user has actually logged in.
        // First-launch flow: LoginScreen → login() → start service.
        val prefs = Repository(this).prefs
        if (prefs.loggedIn) {
            RouterPollService.start(this)
            DailyReportWorker.schedule(this)
        }
    }
}
