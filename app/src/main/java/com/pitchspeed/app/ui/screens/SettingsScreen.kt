package com.pitchspeed.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pitchspeed.app.data.Sensitivity
import com.pitchspeed.app.data.SpeedUnit
import com.pitchspeed.app.ui.AppViewModel
import com.pitchspeed.app.ui.components.CalibrationDialog
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onHowItWorks: () -> Unit
) {
    var showCalibration by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    val settings = viewModel.settings

    if (showCalibration) {
        CalibrationDialog(
            currentDistanceFeet = settings.distanceFeet,
            onDismiss = { showCalibration = false },
            onConfirm = { feet -> viewModel.updateSettings { it.copy(distanceFeet = feet) } }
        )
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all data?") },
            text = { Text("This deletes every saved session and resets your settings. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllData()
                    showClearConfirm = false
                }) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
        ) {
            SectionLabel("Units")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = settings.unit == SpeedUnit.MPH,
                    onClick = { viewModel.updateSettings { it.copy(unit = SpeedUnit.MPH) } },
                    label = { Text("mph") }
                )
                FilterChip(
                    selected = settings.unit == SpeedUnit.KMH,
                    onClick = { viewModel.updateSettings { it.copy(unit = SpeedUnit.KMH) } },
                    label = { Text("km/h") }
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Distance to release point")
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${settings.distanceFeet.roundToInt()} ft", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { showCalibration = true }) { Text("Change") }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Detection sensitivity")
            Text(
                "Higher sensitivity catches dimmer or smaller balls but may trigger on other motion. Start with Medium.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Sensitivity.entries.forEach { level ->
                    FilterChip(
                        selected = settings.sensitivity == level,
                        onClick = { viewModel.updateSettings { it.copy(sensitivity = level) } },
                        label = { Text(level.label) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onHowItWorks, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("How it works")
            }

            Spacer(Modifier.height(32.dp))
            Text(
                "Pitch Speed estimates velocity from your phone camera using the distance you " +
                    "enter and the ball's motion across frame. It's a fun approximation, not a " +
                    "certified radar gun — accuracy depends on lighting, camera angle, and an " +
                    "accurate distance.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
            )

            Spacer(Modifier.weight(1f))
            Button(
                onClick = { showClearConfirm = true },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Clear all data")
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}
