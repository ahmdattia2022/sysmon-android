package io.ahmed.sysmon.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.ahmed.sysmon.data.entity.AlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {
    @Insert
    suspend fun insert(alert: AlertEntity): Long

    @Query("SELECT * FROM alerts ORDER BY ts DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts ORDER BY ts DESC LIMIT :limit")
    suspend fun recentOnce(limit: Int): List<AlertEntity>

    @Query("SELECT * FROM alerts WHERE reason = :reason ORDER BY ts DESC LIMIT 1")
    suspend fun lastByReason(reason: String): AlertEntity?

    @Query("SELECT * FROM alerts WHERE ts LIKE :dayPrefix || '%' ORDER BY ts")
    suspend fun forDay(dayPrefix: String): List<AlertEntity>
}
