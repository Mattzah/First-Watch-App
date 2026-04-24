package com.example.firstwatchapp.presentation

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.firstwatchapp.presentation.data.AppDatabase
import com.example.firstwatchapp.presentation.data.MeasurementEntity
import com.example.firstwatchapp.presentation.drive.DriveUploadManager
import com.example.firstwatchapp.presentation.drive.UploadCsvResult
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "MainViewModel"

sealed class UploadStatus {
    object Idle : UploadStatus()
    object Uploading : UploadStatus()
    data class Success(val fileName: String) : UploadStatus()
    // Drive permission not yet granted — UI must launch this intent
    data class NeedsPermission(val intent: Intent) : UploadStatus()
    data class Error(val message: String) : UploadStatus()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val sensorManager = SensorManager(application)
    val state: StateFlow<SensorState> = sensorManager.state

    private val dao = AppDatabase.getInstance(application).measurementDao()
    private val driveUploadManager = DriveUploadManager(application)

    private val _rowCount = MutableStateFlow(0)
    val rowCount: StateFlow<Int> = _rowCount.asStateFlow()

    private val _uploadStatus = MutableStateFlow<UploadStatus>(UploadStatus.Idle)
    val uploadStatus: StateFlow<UploadStatus> = _uploadStatus.asStateFlow()

    private var lastSaveTime = 0L
    private val SAVE_INTERVAL_MS = 30_000L

    init {
        viewModelScope.launch { _rowCount.value = dao.count() }

        // Auto-save every 30 s once both sensors have passed the 60 s calibration window
        viewModelScope.launch {
            state.collect { s ->
                if (s.hrValid && s.edaValid && s.hrv != null && s.edaDeviation != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastSaveTime >= SAVE_INTERVAL_MS) {
                        lastSaveTime = now
                        launch(Dispatchers.IO) { saveReading(s) }
                    }
                }
            }
        }
    }

    fun connect() = sensorManager.connect()

    private suspend fun saveReading(s: SensorState) {
        val entity = MeasurementEntity(
            bpm = s.bpm,
            hrv = s.hrv,
            edaMicrosiemens = s.skinConductance,
            edaBaseline = s.edaBaseline,
            edaPercentChange = s.edaDeviation
        )
        while (dao.count() >= AppDatabase.MAX_ROWS) dao.deleteOldest()
        dao.insert(entity)
        _rowCount.value = dao.count()
    }

    fun uploadToDrive(account: GoogleSignInAccount) {
        if (_uploadStatus.value is UploadStatus.Uploading) return
        Log.d(TAG, "uploadToDrive() — account=${account.email}")
        viewModelScope.launch {
            _uploadStatus.value = UploadStatus.Uploading
            val measurements = dao.getAll()
            Log.d(TAG, "Fetched ${measurements.size} rows from DB")
            when (val result = driveUploadManager.uploadCsv(account, measurements)) {
                is UploadCsvResult.Success -> {
                    Log.d(TAG, "Upload succeeded: ${result.fileName}")
                    _uploadStatus.value = UploadStatus.Success(result.fileName)
                    delay(4_000)
                    _uploadStatus.value = UploadStatus.Idle
                }
                is UploadCsvResult.NeedsPermission -> {
                    Log.w(TAG, "Drive permission needed — surfacing intent to UI")
                    // UI will observe this and launch the intent; no auto-reset
                    _uploadStatus.value = UploadStatus.NeedsPermission(result.intent)
                }
                is UploadCsvResult.Failed -> {
                    Log.e(TAG, "Upload failed: ${result.message}")
                    _uploadStatus.value = UploadStatus.Error(result.message)
                    delay(6_000)
                    _uploadStatus.value = UploadStatus.Idle
                }
            }
        }
    }

    /** Called after the user completes the Drive permission grant screen. */
    fun onPermissionGranted(account: GoogleSignInAccount) {
        _uploadStatus.value = UploadStatus.Idle
        uploadToDrive(account)
    }

    override fun onCleared() {
        sensorManager.disconnect()
        super.onCleared()
    }
}
