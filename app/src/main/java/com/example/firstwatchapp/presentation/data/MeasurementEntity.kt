package com.example.firstwatchapp.presentation.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "measurements")
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val bpm: Int?,
    val hrv: Double?,
    val edaMicrosiemens: Float?,
    val edaBaseline: Double?,
    val edaPercentChange: Double?
)
