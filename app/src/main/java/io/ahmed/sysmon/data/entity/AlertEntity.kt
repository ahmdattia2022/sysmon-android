package io.ahmed.sysmon.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "alerts",
    indices = [Index(value = ["ts"]), Index(value = ["reason"])]
)
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: String,
    val reason: String,
    val valueMb: Double,
    val sentOk: Int,
    val error: String?
)
