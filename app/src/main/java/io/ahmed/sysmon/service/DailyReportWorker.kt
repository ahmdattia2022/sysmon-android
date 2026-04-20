package io.ahmed.sysmon.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.ahmed.sysmon.repo.Repository
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Fires at ~09:00 local time, snapshots the day's totals + alerts into a short report
 * written to `context.filesDir/reports/<day>/summary.txt`. Reports screen reads these.
 *
 * No internet.
 */
class DailyReportWorker(ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = try {
        val repo = Repository(applicationContext)
        val day = LocalDate.now().toString()
        val routerTodayMb = repo.usage.sumMbOnDay("router_wan", day)
        val laptopTodayMb = repo.usage.sumMbOnDay("laptop_wifi", day)
        val alertsToday = repo.alerts.forDay(day)

        val text = buildString {
            append("Sysmon daily report — ").append(day).append('\n')
            append("\nHousehold router today: ").append("%.2f".format(routerTodayMb / 1024.0)).append(" GB")
            append(" (").append(routerTodayMb.toInt()).append(" MB)")
            append("\nLaptop Wi-Fi today   : ").append("%.2f".format(laptopTodayMb / 1024.0)).append(" GB")
            append(" (").append(laptopTodayMb.toInt()).append(" MB)")
            append("\n\nAlerts today: ").append(alertsToday.size)
            for (a in alertsToday) {
                append("\n  ").append(a.ts).append("  ").append(a.reason)
                    .append("  ").append("%.0f".format(a.valueMb)).append(" MB")
            }
        }

        val dir = java.io.File(applicationContext.filesDir, "reports/$day").apply { mkdirs() }
        java.io.File(dir, "summary.txt").writeText(text, Charsets.UTF_8)
        Result.success()
    } catch (t: Throwable) {
        Result.retry()
    }

    companion object {
        const val WORK_NAME = "daily_report"

        fun schedule(context: Context) {
            val now = LocalDateTime.now()
            var next = now.withHour(9).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val initialDelay = Duration.between(now, next).toMillis()

            val request = PeriodicWorkRequestBuilder<DailyReportWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
