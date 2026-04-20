package io.ahmed.sysmon.repo

import android.content.Context
import io.ahmed.sysmon.data.AppDatabase
import io.ahmed.sysmon.data.entity.AlertEntity
import io.ahmed.sysmon.data.entity.BundleCycleEntity
import io.ahmed.sysmon.data.entity.DeviceEntity
import io.ahmed.sysmon.data.entity.DeviceUsageEntity
import io.ahmed.sysmon.data.entity.JobEntity
import io.ahmed.sysmon.data.entity.UsageSampleEntity
import io.ahmed.sysmon.util.Preferences
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class Repository(context: Context) {
    private val db = AppDatabase.get(context)
    val usage = db.usageSampleDao()
    val devices = db.deviceDao()
    val alerts = db.alertDao()
    val jobs = db.jobDao()
    val deviceUsage = db.deviceUsageDao()
    val logs = db.logDao()
    val cycles = db.bundleCycleDao()
    val schedules = db.blockScheduleDao()
    val prefs = Preferences(context)

    suspend fun insertSample(s: UsageSampleEntity) = usage.insert(s)
    suspend fun upsertDevice(d: DeviceEntity) = devices.upsert(d)
    suspend fun markDevicesOfflineExcept(seen: List<String>) = devices.markOfflineExcept(seen)

    /**
     * Poll-loop-safe upsert: if the device already exists, only router-sourced
     * fields are refreshed — user-editable fields (label, group, iconKind,
     * budgets) are never touched. If the device is new, a full insert happens.
     */
    suspend fun upsertRouterFields(
        mac: String, hostname: String?, lastIp: String?,
        firstSeen: String, lastSeen: String, isOnline: Int
    ) {
        val updated = devices.updateRouterFields(mac, hostname, lastIp, lastSeen, isOnline)
        if (updated == 0) {
            devices.upsert(DeviceEntity(
                mac = mac, hostname = hostname, lastIp = lastIp,
                firstSeen = firstSeen, lastSeen = lastSeen, isOnline = isOnline
            ))
        }
    }

    /** Save only the user-editable fields. No side effects on router state. */
    suspend fun saveDeviceEdits(
        mac: String, label: String?, group: String?, iconKind: String?,
        dailyBudgetMb: Int?, monthlyBudgetMb: Int?
    ) {
        devices.updateUserFields(mac, label, group, iconKind, dailyBudgetMb, monthlyBudgetMb)
    }
    suspend fun insertAlert(a: AlertEntity) = alerts.insert(a)
    suspend fun upsertJob(j: JobEntity) = jobs.upsert(j)
    suspend fun latestSample(source: String) = usage.latest(source)
    suspend fun insertDeviceUsage(rows: List<DeviceUsageEntity>) {
        if (rows.isNotEmpty()) deviceUsage.insertAll(rows)
    }

    // ---- Bundle cycle helpers --------------------------------------------

    suspend fun insertCycle(c: BundleCycleEntity): Long = cycles.insert(c)

    /**
     * Computes the active bundle balance. Distinguishes three roles for anchor rows:
     *
     *  - **Plan anchor** (`MANUAL_FRESH` / `SMS_AUTO`): defines the cycle's *total GB*.
     *  - **Top-up** (`MANUAL_TOPUP`): adds to the total.
     *  - **Remaining checkpoint** (`MANUAL_REMAINING`): overrides the *current balance*
     *    as of its `startDateIso` — doesn't change the plan total.
     *
     * Total = latest plan anchor's GB + all top-ups since it.
     * If a `MANUAL_REMAINING` exists after the plan anchor, the "remaining" base
     * is its value at its own timestamp; we subtract WAN MB since then.
     * Otherwise remaining = total − WAN MB since the plan anchor.
     */
    suspend fun bundleBalance(): BundleBalance? {
        val nowIso = LocalDateTime.now().format(FMT)
        val all = cycles.between("1970-01-01T00:00:00", nowIso)
        if (all.isEmpty()) return null

        // The active plan anchor is the most recent FRESH / SMS_AUTO; without one we
        // treat the oldest row as an effective anchor so MANUAL_REMAINING-only setups still work.
        val planAnchor = all.lastOrNull {
            it.kind == BundleCycleEntity.KIND_MANUAL_FRESH ||
                it.kind == BundleCycleEntity.KIND_SMS_AUTO
        } ?: all.first()

        val topupsSincePlan = all.filter {
            it.startDateIso > planAnchor.startDateIso &&
                it.kind == BundleCycleEntity.KIND_MANUAL_TOPUP
        }
        val planTotalGb = planAnchor.totalGb + topupsSincePlan.sumOf { it.totalGb }

        val lastRemaining = all.lastOrNull {
            it.kind == BundleCycleEntity.KIND_MANUAL_REMAINING &&
                it.startDateIso >= planAnchor.startDateIso
        }

        val baseStartIso: String
        val baseRemainingGb: Double
        if (lastRemaining != null) {
            baseStartIso = lastRemaining.startDateIso
            baseRemainingGb = lastRemaining.manualRemainingGb ?: lastRemaining.totalGb
        } else {
            baseStartIso = planAnchor.startDateIso
            baseRemainingGb = planTotalGb
        }

        val usedGbSinceBase = usage.sumMbSince("router_wan", baseStartIso) / 1024.0
        val remainingGb = (baseRemainingGb - usedGbSinceBase).coerceAtLeast(0.0)
        val usedGb = (planTotalGb - remainingGb).coerceAtLeast(0.0)

        return BundleBalance(
            anchor = planAnchor,
            totalGb = planTotalGb,
            usedGb = usedGb,
            remainingGb = remainingGb
        )
    }

    data class BundleBalance(
        val anchor: BundleCycleEntity,
        val totalGb: Double,
        val usedGb: Double,
        val remainingGb: Double
    )

    companion object {
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
