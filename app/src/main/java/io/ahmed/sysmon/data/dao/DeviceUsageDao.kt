package io.ahmed.sysmon.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.ahmed.sysmon.data.entity.DeviceUsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceUsageDao {
    @Insert
    suspend fun insert(row: DeviceUsageEntity): Long

    @Insert
    suspend fun insertAll(rows: List<DeviceUsageEntity>): List<Long>

    /**
     * Today's total MB estimate per MAC. Reactive. Excludes the `_gap_` sentinel
     * row used by RouterPollService to track offline-window WAN bytes.
     */
    @Query("""
        SELECT mac, COALESCE(SUM(deltaMb),0) AS total
        FROM device_usage
        WHERE ts LIKE :dayPrefix || '%' AND mac != '_gap_'
        GROUP BY mac
    """)
    fun todaysPerDeviceFlow(dayPrefix: String): Flow<List<PerDevice>>

    /** Today's total MB in the offline-gap sentinel (sum across all catch-up polls). */
    @Query("""
        SELECT COALESCE(SUM(deltaMb),0)
        FROM device_usage
        WHERE ts LIKE :dayPrefix || '%' AND mac = '_gap_'
    """)
    fun todaysGapMbFlow(dayPrefix: String): Flow<Double>

    @Query("SELECT COALESCE(SUM(deltaMb),0) FROM device_usage WHERE mac = :mac AND ts LIKE :dayPrefix || '%'")
    suspend fun mbOnDay(mac: String, dayPrefix: String): Double

    @Query("SELECT COALESCE(SUM(deltaMb),0) FROM device_usage WHERE mac = :mac AND ts LIKE :dayPrefix || '%'")
    fun mbOnDayFlow(mac: String, dayPrefix: String): Flow<Double>

    /** Daily totals for one device. Day key = YYYY-MM-DD. Reactive. */
    @Query("""
        SELECT substr(ts,1,10) AS day, COALESCE(SUM(deltaMb),0) AS total
        FROM device_usage
        WHERE mac = :mac AND ts >= :sinceIso
        GROUP BY day
        ORDER BY day
    """)
    fun dailyForDevice(mac: String, sinceIso: String): Flow<List<DailyTotal>>

    /**
     * Monthly totals for one device. Month key = YYYY-MM.
     * `dayCount` tells the UI how many distinct calendar days contributed — a
     * row with dayCount=1 is a "month-to-date" row, not a completed month.
     */
    @Query("""
        SELECT substr(ts,1,7) AS month,
               COALESCE(SUM(deltaMb),0) AS total,
               COUNT(DISTINCT substr(ts,1,10)) AS dayCount
        FROM device_usage
        WHERE mac = :mac AND ts >= :sinceIso
        GROUP BY month
        ORDER BY month
    """)
    fun monthlyForDevice(mac: String, sinceIso: String): Flow<List<MonthlyTotal>>

    /** Hourly breakdown for one device on one day. 24 buckets. */
    @Query("""
        SELECT substr(ts,12,2) AS hour, COALESCE(SUM(deltaMb),0) AS total
        FROM device_usage
        WHERE mac = :mac AND ts LIKE :dayPrefix || '%'
        GROUP BY hour
        ORDER BY hour
    """)
    fun hourlyForDeviceOnDay(mac: String, dayPrefix: String): Flow<List<HourlyTotal>>

    /** Top-N devices by usage today (for dashboard strip). Excludes `_gap_` sentinel. */
    @Query("""
        SELECT mac, COALESCE(SUM(deltaMb),0) AS total
        FROM device_usage
        WHERE ts LIKE :dayPrefix || '%' AND mac != '_gap_'
        GROUP BY mac
        ORDER BY total DESC
        LIMIT :limit
    """)
    fun topDevicesOnDay(dayPrefix: String, limit: Int): Flow<List<PerDevice>>

    @Query("DELETE FROM device_usage WHERE ts < :cutoffIso")
    suspend fun deleteBefore(cutoffIso: String): Int

    data class PerDevice(val mac: String, val total: Double)
    data class DailyTotal(val day: String, val total: Double)
    data class MonthlyTotal(val month: String, val total: Double, val dayCount: Int)
    data class HourlyTotal(val hour: String, val total: Double)
}
