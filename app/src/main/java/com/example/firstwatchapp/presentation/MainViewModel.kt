package com.example.firstwatchapp.presentation

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.firstwatchapp.presentation.data.AppDatabase
import com.example.firstwatchapp.presentation.data.MeasurementEntity
import com.example.firstwatchapp.presentation.sheets.DataUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "MainViewModel"

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sensorManager = SensorManager(application)
    val state: StateFlow<SensorState> = sensorManager.state

    private val dao = AppDatabase.getInstance(application).measurementDao()

    // ── Sheets uploader ──────────────────────────────────────────────────────
    // Replace YOUR_WEB_APP_URL with the URL from Deploy → Manage deployments
    // in your Google Apps Script editor. Looks like:
    // https://script.google.com/macros/s/AKfyc.../exec
    private val uploader = DataUploader(
        dao = dao,
        webAppUrl = "https://script.google.com/macros/s/AKfycbyFUImIHsq7F_VAsr5RjjoaClOB-WQW2oL6y-kp3RhBJFZ4opVzj_MduSgaKoYOsATvOA/exec"
    )

    /** Expose session ID to the UI so the user knows which ID to filter by in Sheets. */
    val sessionId: String get() = uploader.sessionId

    private val _rowCount = MutableStateFlow(0)
    val rowCount: StateFlow<Int> = _rowCount.asStateFlow()

    // Minimum ms between DB inserts — prevents duplicate rows when both HR and EDA
    // listeners fire nearly simultaneously for the same moment in time.
    private var lastSaveTime = 0L
    private val SAVE_INTERVAL_MS = 1_000L

    init {
        uploader.start()
        uploader.onFlushComplete = {
            // Update row count after a successful Sheets upload clears the DB
            viewModelScope.launch { _rowCount.value = dao.count() }
        }

        viewModelScope.launch { _rowCount.value = dao.count() }

        // Save one row per second to the local DB queue once both sensors are calibrated
        viewModelScope.launch {
            state.collect { s ->
                if (s.hrValid && s.edaValid && s.hrv != null && s.edaDeviation != null
                    && s.bpm != null && s.skinConductance != null && s.edaBaseline != null
                ) {
                    val now = System.currentTimeMillis()
                    if (now - lastSaveTime >= SAVE_INTERVAL_MS) {
                        lastSaveTime = now
                        launch(Dispatchers.IO) { saveReading(s, now) }
                    }
                }
            }
        }
    }

    fun connect() = sensorManager.connect()

    private suspend fun saveReading(s: SensorState, now: Long) {
        val entity = MeasurementEntity(
            timestamp = now,
            bpm = s.bpm,
            hrv = s.hrv,
            edaMicrosiemens = s.skinConductance,
            edaBaseline = s.edaBaseline,
            edaPercentChange = s.edaDeviation,
            sessionId = uploader.sessionId
        )
        // Safety cap: if offline long enough to fill the queue, overwrite oldest rows
        while (dao.count() >= AppDatabase.MAX_ROWS) dao.deleteOldest()
        dao.insert(entity)
        _rowCount.value = dao.count()
    }

    override fun onCleared() {
        sensorManager.disconnect()
        uploader.stop()
        super.onCleared()
    }
}
