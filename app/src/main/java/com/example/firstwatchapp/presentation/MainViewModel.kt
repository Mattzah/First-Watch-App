package com.example.firstwatchapp.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sensorManager = SensorManager(application)
    val state: StateFlow<SensorState> = sensorManager.state

    fun connect() = sensorManager.connect()

    override fun onCleared() {
        sensorManager.disconnect()
        super.onCleared()
    }
}
