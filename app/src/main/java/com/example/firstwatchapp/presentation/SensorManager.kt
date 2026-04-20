package com.example.firstwatchapp.presentation

import android.content.Context
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

    // Pairs of (timestamp_ms, value)
    private val ibiBuffer = ArrayDeque<Pair<Long, Int>>()
    private val edaBuffer = ArrayDeque<Pair<Long, Float>>()

    private companion object {
        const val WINDOW_MS = 60_000L
        // Samsung HeartRateStatus: 0=none, 1=initial, 2=measuring, 8=normal
        val VALID_HR_STATUSES = setOf(2, 8)
    }

    // ConnectionListener is a top-level interface
    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() = checkCapabilitiesAndStart()
        override fun onConnectionEnded() {}
        override fun onConnectionFailed(e: HealthTrackerException) {
            val reason = when (e.errorCode) {
                HealthTrackerException.PACKAGE_NOT_INSTALLED -> "Samsung Health not installed"
                HealthTrackerException.OLD_PLATFORM_VERSION -> "Samsung Health needs updating"
                else -> "Connection failed (code ${e.errorCode})"
            }
            _state.value = _state.value.copy(error = reason)
        }
    }

    fun connect() {
        service = HealthTrackingService(connectionListener, context)
        service?.connectService()
    }

    fun disconnect() {
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

        val hasHR = supported.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)
        val hasEDA = supported.contains(HealthTrackerType.EDA_CONTINUOUS)

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
    }

    private val hrListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            val now = System.currentTimeMillis()
            for (dp in dataPoints) {
                val status: Int = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) ?: continue
                if (status !in VALID_HR_STATUSES) continue

                val bpm: Int = dp.getValue(ValueKey.HeartRateSet.HEART_RATE) ?: continue
                val ibiValues: List<Int> = dp.getValue(ValueKey.HeartRateSet.IBI_LIST) ?: emptyList()
                val ibiStatuses: List<Int> = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) ?: emptyList()

                ibiValues.forEachIndexed { i, ibi ->
                    if ((ibiStatuses.getOrNull(i) ?: 1) == 0 && ibi > 0) {
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

        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) {
            _state.value = _state.value.copy(hrValid = false)
        }
    }

    private val edaListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            val now = System.currentTimeMillis()
            for (dp in dataPoints) {
                val status: Int = dp.getValue(ValueKey.EdaSet.STATUS) ?: continue
                if (status != 0) continue

                val conductance: Float = dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE) ?: continue
                edaBuffer.add(Pair(now, conductance))
                pruneBuffer(edaBuffer, now)

                _state.value = _state.value.copy(
                    skinConductance = conductance,
                    edaDeviation = calculateEdaDeviation(conductance),
                    edaValid = true
                )
            }
        }

        override fun onFlushCompleted() {}
        override fun onError(e: HealthTracker.TrackerError) {
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
        val span = ibiBuffer.last().first - ibiBuffer.first().first
        if (span < WINDOW_MS) return null

        val ibis = ibiBuffer.map { it.second }
        val squaredDiffs = ibis.zipWithNext { a, b -> (b - a).toDouble().let { it * it } }
        return sqrt(squaredDiffs.average())
    }

    private fun calculateEdaDeviation(current: Float): Double? {
        if (edaBuffer.size < 2) return null
        val span = edaBuffer.last().first - edaBuffer.first().first
        if (span < WINDOW_MS) return null

        val baseline = edaBuffer.map { it.second.toDouble() }.average()
        return if (baseline != 0.0) ((current - baseline) / baseline * 100.0) else null
    }
}
