package io.ahmed.sysmon.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-minute estimated bandwidth attribution for each device.
 * Estimated — the Huawei router doesn't expose per-host byte counters, so we
 * split the WAN delta proportionally by each device's instantaneous rx/tx rate.
 */
@Entity(
    tableName = "device_usage",
    indices = [Index(value = ["mac", "ts"]), Index(value = ["ts"])]
)
data class DeviceUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mac: String,
    val ts: String,
    val deltaMb: Double,
    val rxRateKbps: Double,
    val txRateKbps: Double
)
