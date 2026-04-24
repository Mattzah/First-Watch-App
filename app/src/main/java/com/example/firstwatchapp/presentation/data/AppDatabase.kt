package com.example.firstwatchapp.presentation.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MeasurementEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun measurementDao(): MeasurementDao

    companion object {
        // ~10 000 rows × ~100 bytes ≈ 1 MB rolling window (~83 h at 30 s/sample)
        const val MAX_ROWS = 10_000

        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "measurements.db"
                ).build().also { INSTANCE = it }
            }
    }
}
