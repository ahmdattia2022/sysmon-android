package io.ahmed.sysmon.service

import android.content.Context
import io.ahmed.sysmon.data.entity.AlertEntity
import io.ahmed.sysmon.repo.Repository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Port of `06_hourly_usage_check.ps1` + `router_scraper.py` anomaly block, adapted for Room.
 * Called once per minute after poll_and_store. Cooldown prevents spam.
 */
class AnomalyEvaluator(private val context: Context, private val repo: Repository) {

    private val cooldowns = mapOf(
        "HIGH_HOURLY" to 60L,
        "SUSTAINED_3H" to 60L,
        "OVER_DAILY_BUDGET" to 60L,
        "ROUTER_HIGH_HOURLY" to 60L,
        "ROUTER_OVER_DAILY" to 60L,
        "ROUTER_NEW_DEVICE" to 24 * 60L
    )

    suspend fun evaluateAll(): List<String> {
        val fired = mutableListOf<String>()
        val prefs = repo.prefs
        val nowIso = isoSeconds(LocalDateTime.now())
        val dayPrefix = LocalDate.now().toString()

        suspend fun maybeFire(reason: String, value: Double, note: String? = null) {
            if (!inCooldown(reason)) {
                repo.insertAlert(AlertEntity(
                    ts = nowIso,
                    reason = reason,
                    valueMb = value,
                    sentOk = 1,
                    error = note
                ))
                Notifier.fire(context, reason, value, note)
                fired += reason
            }
        }

        val routerHourMb = repo.usage.sumMbSince("router_wan",
            isoSeconds(LocalDateTime.now().minusMinutes(60)))
        if (routerHourMb > prefs.routerHourlyMbLimit) {
            maybeFire("ROUTER_HIGH_HOURLY", routerHourMb)
        }

        val routerTodayMb = repo.usage.sumMbOnDay("router_wan", dayPrefix)
        if (routerTodayMb > prefs.routerDailyMbLimit) {
            maybeFire("ROUTER_OVER_DAILY", routerTodayMb)
        }

        val laptopHourMb = repo.usage.sumMbSince("laptop_wifi",
            isoSeconds(LocalDateTime.now().minusMinutes(60)))
        if (laptopHourMb > prefs.hourlyMbLimit) {
            maybeFire("HIGH_HOURLY", laptopHourMb)
        }

        val laptopTodayMb = repo.usage.sumMbOnDay("laptop_wifi", dayPrefix)
        if (laptopTodayMb > 3000) {
            maybeFire("OVER_DAILY_BUDGET", laptopTodayMb)
        }

        for (dev in repo.devices.newToday(dayPrefix)) {
            maybeFire("ROUTER_NEW_DEVICE", 0.0, "${dev.hostname ?: "?"}|${dev.mac}")
        }

        return fired
    }

    private suspend fun inCooldown(reason: String): Boolean {
        val minutes = cooldowns[reason] ?: 60L
        val last = repo.alerts.lastByReason(reason) ?: return false
        val lastTs = runCatching { LocalDateTime.parse(last.ts) }.getOrNull() ?: return false
        return java.time.Duration.between(lastTs, LocalDateTime.now()).toMinutes() < minutes
    }

    companion object {
        private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        fun isoSeconds(t: LocalDateTime): String = t.format(FMT)
    }
}
