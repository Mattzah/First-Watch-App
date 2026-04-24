package com.example.firstwatchapp.presentation

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.firstwatchapp.presentation.theme.FirstWatchAppTheme
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

private const val TAG = "MainActivity"
private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            WatchApp()
        }
    }
}

@Composable
fun WatchApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val rowCount by vm.rowCount.collectAsState()
    val uploadStatus by vm.uploadStatus.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.connect()
    }

    // Launcher for initial Google Sign-In
    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "signInLauncher result: resultCode=${result.resultCode}")
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            Log.d(TAG, "Sign-in succeeded: ${account.email}")
            vm.uploadToDrive(account)
        } catch (e: ApiException) {
            Log.e(TAG, "Sign-in failed: statusCode=${e.statusCode} message=${e.message}")
        }
    }

    // Launcher for the Drive permission grant screen (UserRecoverableAuthException)
    val drivePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "drivePermLauncher result: resultCode=${result.resultCode}")
        // After granting permission, retry the upload with the existing account
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null) {
            Log.d(TAG, "Permission granted, retrying upload for ${account.email}")
            vm.onPermissionGranted(account)
        } else {
            Log.e(TAG, "No signed-in account after permission grant")
        }
    }

    // Automatically launch the Drive permission screen whenever the VM requests it
    LaunchedEffect(uploadStatus) {
        if (uploadStatus is UploadStatus.NeedsPermission) {
            Log.d(TAG, "Launching Drive permission intent")
            drivePermLauncher.launch((uploadStatus as UploadStatus.NeedsPermission).intent)
        }
    }

    val onUploadClick = {
        Log.d(TAG, "Upload button tapped")
        val gso = GoogleSignInOptions.Builder()
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
            .build()
        val existing = GoogleSignIn.getLastSignedInAccount(context)
        if (existing != null && existing.grantedScopes.contains(Scope(DRIVE_SCOPE))) {
            Log.d(TAG, "Existing account with scope found: ${existing.email}")
            vm.uploadToDrive(existing)
        } else {
            Log.d(TAG, "No valid account — launching sign-in")
            signInLauncher.launch(GoogleSignIn.getClient(context, gso).signInIntent)
        }
        Unit
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.BODY_SENSORS)
    }

    FirstWatchAppTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            when {
                state.error != null -> ErrorScreen(state.error!!)
                else -> SensorScreen(state, rowCount, uploadStatus, onUploadClick)
            }
        }
    }
}

@Composable
fun SensorScreen(
    state: SensorState,
    rowCount: Int,
    uploadStatus: UploadStatus,
    onUploadClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SectionHeader(label = "HEART RATE", valid = state.hrValid)
        Spacer(Modifier.height(2.dp))
        MetricRow(
            primary = state.bpm?.toString(),
            primaryUnit = "bpm",
            secondary = state.hrv?.let { "%.1f".format(it) },
            secondaryUnit = "ms HRV"
        )

        Spacer(Modifier.height(10.dp))

        SectionHeader(label = "EDA", valid = state.edaValid)
        Spacer(Modifier.height(2.dp))
        MetricRow(
            primary = state.skinConductance?.let { "%.2f".format(it) },
            primaryUnit = "\u03bcS",
            secondary = state.edaDeviation?.let { "%+.1f%%".format(it) },
            secondaryUnit = "baseline"
        )

        Spacer(Modifier.height(10.dp))

        val (chipLabel, chipEnabled) = when (uploadStatus) {
            is UploadStatus.Idle          -> "Upload to Drive ($rowCount rec)" to true
            is UploadStatus.Uploading     -> "Uploading..." to false
            is UploadStatus.NeedsPermission -> "Requesting permission..." to false
            is UploadStatus.Success       -> "Uploaded \u2713" to false
            is UploadStatus.Error         -> "Failed \u2014 tap retry" to true
        }

        Chip(
            onClick = onUploadClick,
            enabled = chipEnabled,
            label = { Text(chipLabel, fontSize = 10.sp) },
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun SectionHeader(label: String, valid: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (valid) Color(0xFF4CAF50) else Color(0xFFF44336))
        )
        Text(
            text = "  $label",
            color = Color(0xFF80CBC4),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp
        )
    }
}

@Composable
fun MetricRow(
    primary: String?,
    primaryUnit: String,
    secondary: String?,
    secondaryUnit: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        MetricValue(value = primary, unit = primaryUnit)
        MetricValue(value = secondary, unit = secondaryUnit)
    }
}

@Composable
fun MetricValue(value: String?, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value ?: "\u2014",
            color = if (value != null) Color.White else Color(0xFF555555),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        if (value == null) {
            Text(
                text = "Calibrating...",
                color = Color(0xFF777777),
                fontSize = 9.sp,
                textAlign = TextAlign.Center
            )
        } else {
            Text(
                text = unit,
                color = Color(0xFF9E9E9E),
                fontSize = 9.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ErrorScreen(message: String) {
    Text(
        text = message,
        color = Color(0xFFF44336),
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp)
    )
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun PreviewSensorScreen() {
    FirstWatchAppTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            SensorScreen(
                state = SensorState(
                    bpm = 72,
                    hrv = 38.4,
                    skinConductance = 4.21f,
                    edaBaseline = 4.0,
                    edaDeviation = -2.5,
                    hrValid = true,
                    edaValid = true
                ),
                rowCount = 1234,
                uploadStatus = UploadStatus.Idle,
                onUploadClick = {}
            )
        }
    }
}

@Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@Composable
fun PreviewCalibrating() {
    FirstWatchAppTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            SensorScreen(
                state = SensorState(bpm = 68, hrValid = true, edaValid = false),
                rowCount = 0,
                uploadStatus = UploadStatus.Idle,
                onUploadClick = {}
            )
        }
    }
}
