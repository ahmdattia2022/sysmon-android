package io.ahmed.sysmon.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * In-app trace log. Emitted by the service + client so the user can see what
 * happened without attaching ADB. Auto-pruned to the last 7 days.
 */
@Entity(tableName = "log_entries", indices = [Index(value = ["ts"])])
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: String,
    /** INFO | WARN | ERROR */
    val level: String,
    /** e.g. "poll", "login", "net" */
    val tag: String,
    val message: String
)
