package io.ahmed.sysmon.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Recurring block window for a single device. `daysOfWeekMask` is a 7-bit mask
 * (bit 0 = Sunday ... bit 6 = Saturday). `startMinuteOfDay` / `endMinuteOfDay`
 * are 0..1439 (minute of local day). The enforcer wakes at each boundary and
 * calls `adapter.blockMac(mac, …)`.
 *
 * Windows that cross midnight are represented as two adjacent windows with
 * the same parent id — the UI offers that transparently; the enforcer treats
 * them independently.
 */
@Entity(
    tableName = "block_schedules",
    indices = [Index(value = ["mac"]), Index(value = ["enabled"])]
)
data class BlockScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mac: String,
    /** Bitmask: bit 0 = Sunday ... bit 6 = Saturday. */
    val daysOfWeekMask: Int,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val enabled: Int = 1,
    val note: String? = null
)
