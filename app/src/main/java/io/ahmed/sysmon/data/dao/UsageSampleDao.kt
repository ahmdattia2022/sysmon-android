package io.ahmed.sysmon.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.ahmed.sysmon.data.entity.UsageSampleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageSampleDao {
    @Insert
    suspend fun insert(sample: UsageSampleEntity): Long

    @Query("SELECT * FROM usage_samples WHERE source = :source ORDER BY ts DESC LIMIT 1")
    suspend fun latest(source: String): UsageSampleEntity?

    @Query("SELECT * FROM usage_samples WHERE source = :source AND ts >= :cutoffIso ORDER BY ts")
    suspend fun since(source: String, cutoffIso: String): List<UsageSampleEntity>

    @Query("SELECT COALESCE(SUM(deltaMb),0) FROM usage_samples WHERE source = :source AND ts >= :cutoffIso")
    suspend fun sumMbSince(source: String, cutoffIso: String): Double

    @Query("SELECT COALESCE(SUM(deltaMb),0) FROM usage_samples WHERE source = :source AND ts LIKE :dayPrefix || '%'")
    suspend fun sumMbOnDay(source: String, dayPrefix: String): Double

    @Query("SELECT COALESCE(SUM(deltaMb),0) FROM usage_samples WHERE source = :source AND ts LIKE :dayPrefix || '%'")
    fun sumMbOnDayFlow(source: String, dayPrefix: String): Flow<Double>

    @Query("SELECT COALESCE(SUM(deltaMb),0) FROM usage_samples WHERE source = :source AND ts >= :cutoffIso")
    fun sumMbSinceFlow(source: String, cutoffIso: String): Flow<Double>

    @Query("SELECT COUNT(*) FROM usage_samples WHERE source = :source")
    suspend fun count(source: String): Int

    @Query("SELECT COUNT(*) FROM usage_samples WHERE source = :source")
    fun countFlow(source: String): Flow<Int>

    @Query("SELECT * FROM usage_samples ORDER BY ts DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<UsageSampleEntity>>

    @Query("DELETE FROM usage_samples WHERE ts < :cutoffIso")
    suspend fun deleteBefore(cutoffIso: String): Int
}
