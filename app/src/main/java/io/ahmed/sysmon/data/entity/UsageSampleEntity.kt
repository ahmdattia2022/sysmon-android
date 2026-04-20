package io.ahmed.sysmon.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "usage_samples",
    indices = [Index(value = ["ts"]), Index(value = ["source", "ts"])]
)
data class UsageSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: String,
    val source: String,                 // "router_wan" | "laptop_wifi"
    val rxBytes: Long?,
    val txBytes: Long?,
    val deltaMb: Double?,
    val bootTime: String?,
    val topProcesses: String?           // JSON string
)
