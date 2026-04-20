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

    /**
     * Full replace — call ONLY when the device row is newly discovered, or when
     * the caller has already merged user-editable fields (label, group, iconKind,
     * dailyBudgetMb, monthlyBudgetMb) onto the new value. Naive use from the
     * poll loop would clobber those user fields on every tick.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    /**
     * Router-authoritative fields only. Used every poll without disturbing the
     * user-editable ones. Insert if missing is handled via a separate check in
     * the repo (see upsertRouterFields).
     */
    @Query("""
        UPDATE devices
        SET hostname = :hostname, lastIp = :lastIp, lastSeen = :lastSeen, isOnline = :isOnline
        WHERE mac = :mac
    """)
    suspend fun updateRouterFields(
        mac: String, hostname: String?, lastIp: String?, lastSeen: String, isOnline: Int
    ): Int

    /** User-editable fields only — survives poll ticks. */
    @Query("""
        UPDATE devices
        SET label = :label, `group` = :group, iconKind = :iconKind,
            dailyBudgetMb = :dailyBudgetMb, monthlyBudgetMb = :monthlyBudgetMb
        WHERE mac = :mac
    """)
    suspend fun updateUserFields(
        mac: String,
        label: String?,
        group: String?,
        iconKind: String?,
        dailyBudgetMb: Int?,
        monthlyBudgetMb: Int?
    ): Int

    @Query("UPDATE devices SET isOnline = 0 WHERE mac NOT IN (:seenMacs)")
    suspend fun markOfflineExcept(seenMacs: List<String>)

    @Query("SELECT COUNT(*) FROM devices WHERE isOnline = 1")
    suspend fun onlineCount(): Int

    @Query("SELECT COUNT(*) FROM devices")
    suspend fun totalCount(): Int

    @Query("SELECT * FROM devices WHERE firstSeen LIKE :dayPrefix || '%' AND isOnline = 1")
    suspend fun newToday(dayPrefix: String): List<DeviceEntity>
}
