package com.example.firstwatchapp.presentation

import android.Manifest
import android.os.Bundle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.example.firstwatchapp.presentation.theme.FirstWatchAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchApp()
        }
    }
}

@Composable
fun WatchApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.connect()
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
                else -> SensorScreen(state)
            }
        }
    }
}

@Composable
fun SensorScreen(state: SensorState) {
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
            primaryUnit = "μS",
            secondary = state.edaDeviation?.let { "%+.1f%%".format(it) },
            secondaryUnit = "baseline"
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
            text = value ?: "—",
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
                SensorState(
                    bpm = 72,
                    hrv = 38.4,
                    skinConductance = 4.21f,
                    edaDeviation = -2.5,
                    hrValid = true,
                    edaValid = true
                )
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
            SensorScreen(SensorState(bpm = 68, hrValid = true, edaValid = false))
        }
    }
}
