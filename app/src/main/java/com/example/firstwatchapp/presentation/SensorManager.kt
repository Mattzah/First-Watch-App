package com.example.firstwatchapp.presentation

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

data class SensorState(
    val bpm: Int? = null,
    val hrv: Double? = null,
    val skinConductance: Float? = null,
    val edaBaseline: Double? = null,
    val edaDeviation: Double? = null,
    val hrValid: Boolean = false,
    val edaValid: Boolean = false,
    val error: String? = null
)

class SensorManager(private val context: Context) {

    private var service: HealthTrackingService? = null
    private var hrTracker: HealthTracker? = null
    private var edaTracker: HealthTracker? = null

    private val _state = MutableStateFlow(SensorState())
    val state: StateFlow<SensorState> = _state.asStateFlow()

    private val ibiBuffer = ArrayDeque<Pair<Long, Int>>()
    private val edaBuffer = ArrayDeque<Pair<Long, Float>>()

    // Timestamps of when the first valid sample arrived for each sensor.
    // Used to gate the 60-second calibration window independently of pruning.
    private var ibiStartTime: Long = 0L
    private var edaStartTime: Long = 0L

    private companion object {
        const val TAG = "SensorManager"
        const val WINDOW_MS = 60_000L
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            Log.d(TAG, "onConnectionSuccess — checking capabilities")
            checkCapabilitiesAndStart()
        }

        override fun onConnectionEnded() {
            Log.d(TAG, "onConnectionEnded")
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            val reason = when (e.errorCode) {
                HealthTrackerException.PACKAGE_NOT_INSTALLED -> "Samsung Health not installed"
                HealthTrackerException.OLD_PLATFORM_VERSION -> "Samsung Health needs updating"
                else -> "Connection failed (code ${e.errorCode})"
            }
            Log.e(TAG, "onConnectionFailed: $reason")
            _state.value = _state.value.copy(error = reason)
        }
    }

    fun connect() {
        Log.d(TAG, "connect() called")
        service = HealthTrackingService(connectionListener, context)
        service?.connectService()
    }

    fun disconnect() {
        Log.d(TAG, "disconnect() called")
        hrTracker?.unsetEventListener()
        edaTracker?.unsetEventListener()
        hrTracker = null
        edaTracker = null
        service?.disconnectService()
        service = null
    }

    private fun checkCapabilitiesAndStart() {
        val svc = service ?: return
        val supported = svc.trackingCapability?.supportHealthTrackerTypes ?: emptyList()
        Log.d(TAG, "Supported tracker types: $supported")

        val hasHR = supported.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)
        val hasEDA = supported.contains(HealthTrackerType.EDA_CONTINUOUS)
        Log.d(TAG, "hasHR=$hasHR  hasEDA=$hasEDA")

        if (!hasHR || !hasEDA) {
            val missing = listOfNotNull(
                if (!hasHR) "Heart Rate" else null,
                if (!hasEDA) "EDA" else null
            ).joinToString(", ")
            _state.value = _state.value.copy(error = "Unsupported sensors: $missing")
            return
        }

        hrTracker = svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
        edaTracker = svc.getHealthTracker(HealthTrackerType.EDA_CONTINUOUS)
        hrTracker?.setEventListener(hrListener)
        edaTracker?.setEventListener(edaListener)
        Log.d(TAG, "Both trackers started")
    }

    private val hrListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            Log.d(TAG, "HR onDataReceived: ${dataPoints.size} point(s)")
            val now = System.currentTimeMillis()
            for (dp in dataPoints) {
                val status: Int = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) ?: continue
                val bpm: Int = dp.getValue(ValueKey.HeartRateSet.HEART_RATE) ?: continue
                Log.d(TAG, "  HR status=$status  bpm=$bpm")

                // Accept any reading where the watch has a non-zero BPM.
                // Status 0 = no sensor contact — skip those only.
                if (status == 0 || bpm == 0) continue

                val ibiValues: List<Int> = dp.getValue(ValueKey.HeartRateSet.IBI_LIST) ?: emptyList()
                val ibiStatuses: List<Int> = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) ?: emptyList()
                Log.d(TAG, "  IBI values: $ibiValues  statuses: $ibiStatuses")

                ibiValues.forEachIndexed { i, ibi ->
                    if ((ibiStatuses.getOrNull(i) ?: 1) == 0 && ibi > 0) {
                        if (ibiStartTime == 0L) ibiStartTime = now
                        ibiBuffer.add(Pair(now, ibi))
                    }
                }

                pruneBuffer(ibiBuffer, now)

                _state.value = _state.value.copy(
                    bpm = bpm,
                    hrv = calculateRmssd(),
                    hrValid = true
                )
            }
        }

        override fun onFlushCompleted() {
            Log.d(TAG, "HR onFlushCompleted")
        }

        override fun onError(e: HealthTracker.TrackerError) {
            Log.e(TAG, "HR onError: $e")
            _state.value = _state.value.copy(hrValid = false)
        }
    }

    private val edaListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            Log.d(TAG, "EDA onDataReceived: ${dataPoints.size} point(s)")
            val now = System.currentTimeMillis()
            for (dp in dataPoints) {
                val status: Int = dp.getValue(ValueKey.EdaSet.STATUS) ?: continue
                val conductance: Float = dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE) ?: continue
                Log.d(TAG, "  EDA status=$status  conductance=$conductance")

                // Per spec: discard non-zero status
                if (status != 0) continue

                if (edaStartTime == 0L) edaStartTime = now
                edaBuffer.add(Pair(now, conductance))
                pruneBuffer(edaBuffer, now)

                val baseline = calculateEdaBaseline()
                val deviation = baseline?.let {
                    if (it != 0.0) ((conductance - it) / it * 100.0) else null
                }
                _state.value = _state.value.copy(
                    skinConductance = conductance,
                    edaBaseline = baseline,
                    edaDeviation = deviation,
                    edaValid = true
                )
            }
        }

        override fun onFlushCompleted() {
            Log.d(TAG, "EDA onFlushCompleted")
        }

        override fun onError(e: HealthTracker.TrackerError) {
            Log.e(TAG, "EDA onError: $e")
            _state.value = _state.value.copy(edaValid = false)
        }
    }

    private fun <T> pruneBuffer(buffer: ArrayDeque<Pair<Long, T>>, now: Long) {
        val cutoff = now - WINDOW_MS
        while (buffer.isNotEmpty() && buffer.first().first < cutoff) {
            buffer.removeFirst()
        }
    }

    private fun calculateRmssd(): Double? {
        if (ibiBuffer.size < 2) return null
        if (ibiStartTime == 0L || System.currentTimeMillis() - ibiStartTime < WINDOW_MS) return null

        val ibis = ibiBuffer.map { it.second }
        val squaredDiffs = ibis.zipWithNext { a, b -> (b - a).toDouble().let { it * it } }
        return sqrt(squaredDiffs.average())
    }

    private fun calculateEdaBaseline(): Double? {
        if (edaBuffer.size < 2) return null
        if (edaStartTime == 0L || System.currentTimeMillis() - edaStartTime < WINDOW_MS) return null
        return edaBuffer.map { it.second.toDouble() }.average()
    }
}
