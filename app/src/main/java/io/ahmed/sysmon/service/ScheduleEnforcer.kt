package io.ahmed.sysmon.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.ahmed.sysmon.data.entity.BlockScheduleEntity
import io.ahmed.sysmon.repo.Repository
import io.ahmed.sysmon.service.router.RouterAdapters
import io.ahmed.sysmon.service.router.RouterKind
import io.ahmed.sysmon.util.Logger
import io.ahmed.sysmon.util.WifiNetworkBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar

/**
 * Schedules AlarmManager alarms at the next start/end boundary of every
 * enabled `BlockScheduleEntity`. When an alarm fires, it invokes the router
 * adapter's blockMac/unblockMac for the scheduled MAC, writes an audit row
 * into the `alerts` table with reason = "ACTION", and reschedules itself for
 * the next boundary.
 *
 * Reschedule trigger points:
 *  - App cold-start (called from SysmonApp.onCreate)
 *  - Every time a schedule is inserted / updated / deleted from UI
 *  - Every time the enforcer itself fires (chains forward)
 */
object ScheduleEnforcer {

    private const val ACTION_FIRE = "io.ahmed.sysmon.SCHEDULE_FIRE"
    private const val EXTRA_MAC = "mac"
    private const val EXTRA_BLOCK = "block"
    private const val EXTRA_SCHEDULE_ID = "id"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Walks all enabled schedules and books an alarm at the next
     * start-or-end boundary of each, replacing any previously-set alarm.
     */
    fun rescheduleAll(context: Context) {
        scope.launch {
            val repo = Repository(context)
            val schedules = repo.schedules.allEnabled()
            val am = context.getSystemService(AlarmManager::class.java) ?: return@launch
            for (s in schedules) {
                scheduleNextBoundary(context, am, s)
            }
            Logger.i(context, "schedule",
                "rescheduled ${schedules.size} block windows")
        }
    }

    private fun scheduleNextBoundary(context: Context, am: AlarmManager, s: BlockScheduleEntity) {
        val now = LocalDateTime.now()
        val (fireAt, shouldBlock) = nextBoundary(s, now) ?: return
        val triggerMs = fireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_SCHEDULE_ID, s.id)
            putExtra(EXTRA_MAC, s.mac)
            putExtra(EXTRA_BLOCK, shouldBlock)
        }
        val pi = PendingIntent.getBroadcast(
            context, s.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Inexact by default — spares us the SCHEDULE_EXACT_ALARM permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    /**
     * Given a schedule and current time, returns (nextFireTime, blockAction)
     * where `blockAction=true` at the window start and `false` at its end.
     * Returns null if the schedule has no day bits set.
     */
    private fun nextBoundary(s: BlockScheduleEntity, from: LocalDateTime): Pair<LocalDateTime, Boolean>? {
        if (s.daysOfWeekMask == 0) return null
        // Scan forward up to 8 days to find the nearest boundary (start or end).
        var best: Pair<LocalDateTime, Boolean>? = null
        for (offset in 0..7) {
            val day = from.plusDays(offset.toLong())
            val dowBit = javaDowToMaskBit(day.dayOfWeek)
            if ((s.daysOfWeekMask and (1 shl dowBit)) == 0) continue
            val startAt = day.toLocalDate().atStartOfDay().plusMinutes(s.startMinuteOfDay.toLong())
            val endAt = day.toLocalDate().atStartOfDay().plusMinutes(s.endMinuteOfDay.toLong())
            if (startAt.isAfter(from)) {
                val cand = startAt to true
                if (best == null || cand.first.isBefore(best.first)) best = cand
            }
            if (endAt.isAfter(from)) {
                val cand = endAt to false
                if (best == null || cand.first.isBefore(best.first)) best = cand
            }
            if (best != null) return best
        }
        return best
    }

    /** DayOfWeek (MONDAY=1..SUNDAY=7) to our bit index (SUNDAY=0..SATURDAY=6). */
    private fun javaDowToMaskBit(dow: DayOfWeek): Int = when (dow) {
        DayOfWeek.SUNDAY -> 0
        DayOfWeek.MONDAY -> 1
        DayOfWeek.TUESDAY -> 2
        DayOfWeek.WEDNESDAY -> 3
        DayOfWeek.THURSDAY -> 4
        DayOfWeek.FRIDAY -> 5
        DayOfWeek.SATURDAY -> 6
    }

    /** Invoked by the alarm receiver. Runs the router action, then reschedules. */
    internal fun onAlarmFired(context: Context, mac: String, block: Boolean, scheduleId: Long) {
        scope.launch {
            val repo = Repository(context)
            val prefs = repo.prefs
            runCatching {
                val adapter = RouterAdapters.forKind(
                    kind = RouterKind.fromPref(prefs.routerKind),
                    baseUrl = prefs.routerBase,
                    username = prefs.routerUser,
                    password = prefs.routerPassword,
                    cachedCookie = prefs.sessionCookie.takeIf { it.isNotBlank() },
                    onSessionEstablished = { sid -> prefs.sessionCookie = sid },
                    trace = { line -> Logger.i(context, "schedule", line) }
                )
                WifiNetworkBinder.withWifiProcess(context) { adapter.blockMac(mac, block) }
                    ?: adapter.blockMac(mac, block)
                Logger.i(context, "schedule",
                    "auto ${if (block) "blocked" else "unblocked"} $mac (schedule #$scheduleId)")
                repo.insertAlert(io.ahmed.sysmon.data.entity.AlertEntity(
                    ts = isoSeconds(),
                    reason = "ACTION",
                    valueMb = 0.0,
                    sentOk = 1,
                    error = "auto-${if (block) "block" else "unblock"} $mac (schedule)"
                ))
            }.onFailure {
                Logger.e(context, "schedule", "auto-action failed on $mac", it)
            }
            // Book the next boundary.
            val am = context.getSystemService(AlarmManager::class.java) ?: return@launch
            repo.schedules.allEnabled().firstOrNull { it.id == scheduleId }?.let {
                scheduleNextBoundary(context, am, it)
            }
        }
    }

    private fun isoSeconds(): String {
        val c = Calendar.getInstance()
        return String.format(
            "%04d-%02d-%02dT%02d:%02d:%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)
        )
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mac = intent.getStringExtra("mac") ?: return
        val block = intent.getBooleanExtra("block", false)
        val id = intent.getLongExtra("id", -1)
        ScheduleEnforcer.onAlarmFired(context, mac, block, id)
    }
}
