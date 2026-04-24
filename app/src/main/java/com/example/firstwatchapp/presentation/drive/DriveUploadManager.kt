package com.example.firstwatchapp.presentation.drive

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.firstwatchapp.presentation.data.MeasurementEntity
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "DriveUploadManager"
private const val DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive.file"

sealed class UploadCsvResult {
    data class Success(val fileName: String) : UploadCsvResult()
    data class NeedsPermission(val intent: Intent) : UploadCsvResult()
    data class Failed(val message: String) : UploadCsvResult()
}

class DriveUploadManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun uploadCsv(
        account: GoogleSignInAccount,
        measurements: List<MeasurementEntity>
    ): UploadCsvResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "uploadCsv() called — ${measurements.size} rows, account=${account.email}")

        if (!isNetworkAvailable()) {
            Log.e(TAG, "No internet connection")
            return@withContext UploadCsvResult.Failed("No internet connection — ensure the watch is on WiFi")
        }

        val gAccount = account.account
        if (gAccount == null) {
            Log.e(TAG, "account.account is null")
            return@withContext UploadCsvResult.Failed("Google account not found — try signing in again")
        }

        Log.d(TAG, "Requesting OAuth token for ${gAccount.name}")
        val token = try {
            GoogleAuthUtil.getToken(context, gAccount, DRIVE_SCOPE)
        } catch (e: UserRecoverableAuthException) {
            // First-time permission grant required — surface the intent to the UI
            Log.w(TAG, "UserRecoverableAuthException — user must grant Drive permission")
            val recoveryIntent = e.intent
                ?: return@withContext UploadCsvResult.Failed("Drive permission required — please try again")
            return@withContext UploadCsvResult.NeedsPermission(recoveryIntent)
        } catch (e: Exception) {
            Log.e(TAG, "getToken failed: ${e.message}", e)
            return@withContext UploadCsvResult.Failed("Auth error: ${e.message}")
        }

        Log.d(TAG, "Token obtained, building CSV")
        val csv = buildCsv(measurements)
        val fileName = "watch_data_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
        Log.d(TAG, "Uploading $fileName (${csv.length} chars)")

        return@withContext try {
            uploadMultipart(token, csv, fileName)
            Log.d(TAG, "Upload complete: $fileName")
            UploadCsvResult.Success(fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed: ${e.message}", e)
            UploadCsvResult.Failed(e.message ?: "Upload failed")
        }
    }

    private fun buildCsv(measurements: List<MeasurementEntity>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            appendLine("timestamp,datetime,bpm,hrv_ms,eda_microsiemens,eda_baseline,eda_percent_change")
            for (m in measurements) {
                appendLine(
                    "${m.timestamp}," +
                    "${fmt.format(Date(m.timestamp))}," +
                    "${m.bpm ?: ""}," +
                    "${m.hrv ?: ""}," +
                    "${m.edaMicrosiemens ?: ""}," +
                    "${m.edaBaseline ?: ""}," +
                    "${m.edaPercentChange ?: ""}"
                )
            }
        }
    }

    private fun uploadMultipart(token: String, csv: String, fileName: String) {
        val boundary = "fw_${System.currentTimeMillis()}"
        val metadata = """{"name":"$fileName","mimeType":"text/csv"}"""

        val bodyBytes =
            "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n--$boundary\r\nContent-Type: text/csv\r\n\r\n"
                .toByteArray(Charsets.UTF_8) +
            csv.toByteArray(Charsets.UTF_8) +
            "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            .header("Authorization", "Bearer $token")
            .post(bodyBytes.toRequestBody("multipart/related; boundary=$boundary".toMediaTypeOrNull()))
            .build()

        val response = httpClient.newCall(request).execute()
        Log.d(TAG, "HTTP response: ${response.code} ${response.message}")
        check(response.isSuccessful) { "Drive upload failed: HTTP ${response.code}" }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
