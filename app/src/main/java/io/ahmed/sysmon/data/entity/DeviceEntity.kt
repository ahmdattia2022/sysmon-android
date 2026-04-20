package io.ahmed.sysmon.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val mac: String,
    val hostname: String?,
    val lastIp: String?,
    val firstSeen: String,
    val lastSeen: String,
    val isOnline: Int
)
