package io.ahmed.sysmon.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.ahmed.sysmon.data.entity.JobEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: JobEntity)

    @Query("SELECT * FROM jobs")
    fun observeAll(): Flow<List<JobEntity>>

    @Query("SELECT * FROM jobs")
    suspend fun getAll(): List<JobEntity>

    @Query("SELECT * FROM jobs WHERE name = :name")
    suspend fun byName(name: String): JobEntity?
}
