package io.ahmed.sysmon.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.ahmed.sysmon.data.entity.BundleCycleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BundleCycleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: BundleCycleEntity): Long

    @Update
    suspend fun update(row: BundleCycleEntity): Int

    @Delete
    suspend fun delete(row: BundleCycleEntity): Int

    /** Most recent anchor <= now. Drives the "active cycle" on the dashboard. */
    @Query("""
        SELECT * FROM bundle_cycles
        WHERE startDateIso <= :nowIso
        ORDER BY startDateIso DESC, id DESC
        LIMIT 1
    """)
    suspend fun activeAt(nowIso: String): BundleCycleEntity?

    @Query("""
        SELECT * FROM bundle_cycles
        WHERE startDateIso <= :nowIso
        ORDER BY startDateIso DESC, id DESC
        LIMIT 1
    """)
    fun activeAtFlow(nowIso: String): Flow<BundleCycleEntity?>

    @Query("SELECT * FROM bundle_cycles ORDER BY startDateIso DESC, id DESC")
    fun observeAll(): Flow<List<BundleCycleEntity>>

    /**
     * All anchor rows applicable to the window [since, now]. Used to sum top-ups
     * added mid-cycle so remaining-GB math includes them.
     */
    @Query("""
        SELECT * FROM bundle_cycles
        WHERE startDateIso >= :sinceIso AND startDateIso <= :nowIso
        ORDER BY startDateIso ASC
    """)
    suspend fun between(sinceIso: String, nowIso: String): List<BundleCycleEntity>
}
