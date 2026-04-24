package com.example.firstwatchapp.presentation.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MeasurementDao {
    @Insert
    suspend fun insert(measurement: MeasurementEntity)

    @Query("SELECT COUNT(*) FROM measurements")
    suspend fun count(): Int

    @Query("DELETE FROM measurements WHERE id = (SELECT MIN(id) FROM measurements)")
    suspend fun deleteOldest()

    @Query("SELECT * FROM measurements ORDER BY timestamp ASC")
    suspend fun getAll(): List<MeasurementEntity>
}
