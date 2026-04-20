package io.ahmed.sysmon.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Local alert delivery. Uses Android NotificationManager — no FCM, no Google Play Services,
 * no internet. Works in airplane mode so long as the phone has home WiFi.
 */
object Notifier {
    const val CHANNEL_ALERTS = "sysmon_alerts"
    const val CHANNEL_SERVICE = "sysmon_service"

    private const val LABEL_ALERT = "Alerts"
    private const val LABEL_SERVICE = "Router polling"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS, LABEL_ALERT,
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Data-usage anomalies on the home router" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE, LABEL_SERVICE,
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Minute-by-minute polling (persistent)" }
        )
    }

    fun fire(context: Context, reason: String, valueMb: Double, note: String? = null) {
        if (!hasPostPermission(context)) return

        val title = "Sysmon — $reason"
        val subtext = buildString {
            if (valueMb > 0) append("${valueMb.toInt()} MB")
            if (!note.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append(note)
            }
        }.ifEmpty { "Triggered" }

        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(subtext)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()

        NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), n)
    }

    private fun hasPostPermission(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
