package io.ahmed.sysmon.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val name: String,
    val lastRun: String?,
    val lastStatus: String?,
    val nextRun: String?
)
