package io.ahmed.sysmon.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.ahmed.sysmon.data.dao.AlertDao
import io.ahmed.sysmon.data.dao.BlockScheduleDao
import io.ahmed.sysmon.data.dao.BundleCycleDao
import io.ahmed.sysmon.data.dao.DeviceDao
import io.ahmed.sysmon.data.dao.DeviceUsageDao
import io.ahmed.sysmon.data.dao.JobDao
import io.ahmed.sysmon.data.dao.LogDao
import io.ahmed.sysmon.data.dao.UsageSampleDao
import io.ahmed.sysmon.data.entity.AlertEntity
import io.ahmed.sysmon.data.entity.BlockScheduleEntity
import io.ahmed.sysmon.data.entity.BundleCycleEntity
import io.ahmed.sysmon.data.entity.DeviceEntity
import io.ahmed.sysmon.data.entity.DeviceUsageEntity
import io.ahmed.sysmon.data.entity.JobEntity
import io.ahmed.sysmon.data.entity.LogEntry
import io.ahmed.sysmon.data.entity.UsageSampleEntity

@Database(
    entities = [
        UsageSampleEntity::class,
        DeviceEntity::class,
        AlertEntity::class,
        JobEntity::class,
        DeviceUsageEntity::class,
        LogEntry::class,
        BundleCycleEntity::class,
        BlockScheduleEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageSampleDao(): UsageSampleDao
    abstract fun deviceDao(): DeviceDao
    abstract fun alertDao(): AlertDao
    abstract fun jobDao(): JobDao
    abstract fun deviceUsageDao(): DeviceUsageDao
    abstract fun logDao(): LogDao
    abstract fun bundleCycleDao(): BundleCycleDao
    abstract fun blockScheduleDao(): BlockScheduleDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sysmon.db"
                )
                    // v1 -> v2 adds device_usage table. Early dev; no prod data to preserve.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
