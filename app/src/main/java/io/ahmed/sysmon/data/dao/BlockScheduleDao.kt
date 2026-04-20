package io.ahmed.sysmon.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.ahmed.sysmon.data.entity.BlockScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockScheduleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(schedule: BlockScheduleEntity): Long

    @Update
    suspend fun update(schedule: BlockScheduleEntity): Int

    @Delete
    suspend fun delete(schedule: BlockScheduleEntity): Int

    @Query("SELECT * FROM block_schedules WHERE mac = :mac ORDER BY startMinuteOfDay")
    fun forMacFlow(mac: String): Flow<List<BlockScheduleEntity>>

    @Query("SELECT * FROM block_schedules WHERE enabled = 1")
    suspend fun allEnabled(): List<BlockScheduleEntity>

    @Query("SELECT * FROM block_schedules ORDER BY mac, startMinuteOfDay")
    fun observeAll(): Flow<List<BlockScheduleEntity>>
}
