package io.ahmed.sysmon.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.service.RouterPollService

/**
 * Restart the foreground polling service when the phone reboots or the app updates —
 * but only if the user has actually logged in. Respects the explicit "Log out" state.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (Repository(context).prefs.loggedIn) {
                    RouterPollService.start(context)
                }
            }
        }
    }
}
