package com.example.firstwatchapp.presentation.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Version bumped to 2 — added sessionId column.
// fallbackToDestructiveMigration() drops and recreates the table on upgrade
// (acceptable here since any pre-upgrade rows were not yet sent to Sheets anyway).
@Database(entities = [MeasurementEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun measurementDao(): MeasurementDao

    companion object {
        // Safety cap: if offline for a long time, oldest rows are overwritten.
        // At 1 row/s this is ~2.8 hours of offline data before rolling kicks in.
        const val MAX_ROWS = 10_000

        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "measurements.db"
                )
                .fallbackToDestructiveMigration()
                .build().also { INSTANCE = it }
            }
    }
}
