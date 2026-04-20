package io.ahmed.sysmon.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.ahmed.sysmon.data.entity.DeviceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY isOnline DESC, hostname")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices")
    suspend fun getAll(): List<DeviceEntity>

    @Query("SELECT * FROM devices WHERE mac = :mac")
    suspend fun byMac(mac: String): DeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    @Query("UPDATE devices SET isOnline = 0 WHERE mac NOT IN (:seenMacs)")
    suspend fun markOfflineExcept(seenMacs: List<String>)

    @Query("SELECT COUNT(*) FROM devices WHERE isOnline = 1")
    suspend fun onlineCount(): Int

    @Query("SELECT COUNT(*) FROM devices")
    suspend fun totalCount(): Int

    @Query("SELECT * FROM devices WHERE firstSeen LIKE :dayPrefix || '%' AND isOnline = 1")
    suspend fun newToday(dayPrefix: String): List<DeviceEntity>
}
