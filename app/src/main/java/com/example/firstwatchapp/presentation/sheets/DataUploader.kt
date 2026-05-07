package com.example.firstwatchapp.presentation.sheets

import android.util.Log
import com.example.firstwatchapp.presentation.data.MeasurementDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "DataUploader"

/**
 * Reads queued rows from the local Room DB, POSTs them to a Google Apps Script
 * Web App every [flushIntervalMs] ms, and deletes them only after Sheets confirms
 * receipt. If the network is unavailable, rows stay in the DB and are retried on
 * the next flush — so no data is lost across app restarts or connectivity gaps.
 */
class DataUploader(
    private val dao: MeasurementDao,
    private val webAppUrl: String,
    private val flushIntervalMs: Long = 30_000L
) {
    /** 8-char ID unique to this app launch — use it to filter sessions in Sheets. */
    val sessionId: String = UUID.randomUUID().toString().take(8)

    /** Called on the IO thread after a successful flush (rows deleted from DB). */
    var onFlushComplete: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun start() {
        Log.d(TAG, "DataUploader started — session=$sessionId")
        scope.launch {
            while (isActive) {
                delay(flushIntervalMs)
                flush()
            }
        }
    }

    suspend fun flush() {
        val rows = dao.getAll()
        if (rows.isEmpty()) return

        Log.d(TAG, "Flushing ${rows.size} rows to Sheets — session=$sessionId")

        val jsonArray = JSONArray()
        rows.forEach { row ->
            jsonArray.put(JSONObject().apply {
                put("timestamp", row.timestamp)
                put("datetime", dateFmt.format(Date(row.timestamp)))
                put("bpm", row.bpm ?: JSONObject.NULL)
                put("hrv_ms", row.hrv ?: JSONObject.NULL)
                put("eda_microsiemens", row.edaMicrosiemens?.toDouble() ?: JSONObject.NULL)
                put("eda_baseline", row.edaBaseline ?: JSONObject.NULL)
                put("eda_percent_change", row.edaPercentChange ?: JSONObject.NULL)
                put("session_id", row.sessionId)
            })
        }

        val body = jsonArray.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(webAppUrl).post(body).build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    dao.deleteByIds(rows.map { it.id })
                    Log.d(TAG, "Uploaded and cleared ${rows.size} rows — ${response.body?.string()}")
                    onFlushComplete?.invoke()
                } else {
                    Log.e(TAG, "Upload failed HTTP ${response.code} — rows kept for retry")
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network unavailable — ${rows.size} rows kept for next retry: ${e.message}")
        }
    }

    /** Cancel the flush loop. Any unsynced rows remain in the DB and will be
     *  sent on the next app launch. */
    fun stop() {
        scope.cancel()
        Log.d(TAG, "DataUploader stopped — unsynced rows remain in DB for next launch")
    }
}
