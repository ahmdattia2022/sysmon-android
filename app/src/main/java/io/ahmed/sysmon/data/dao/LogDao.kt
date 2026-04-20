package io.ahmed.sysmon.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.ahmed.sysmon.data.entity.LogEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insert(entry: LogEntry): Long

    @Query("SELECT * FROM log_entries ORDER BY ts DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<LogEntry>>

    @Query("DELETE FROM log_entries WHERE ts < :cutoffIso")
    suspend fun deleteBefore(cutoffIso: String): Int

    @Query("SELECT COUNT(*) FROM log_entries")
    suspend fun count(): Int
}
