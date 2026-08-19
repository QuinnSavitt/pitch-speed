package com.pitchspeed.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pitchspeed.app.data.DistancePresets

@Composable
fun CalibrationDialog(
    currentDistanceFeet: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    var customText by remember { mutableStateOf(currentDistanceFeet.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Camera distance") },
        text = {
            Column {
                Text(
                    "How far is the camera from the ball's flight path, measured straight across (perpendicular to the throw)? This is NOT the pitcher-to-catcher distance. Pace it off if needed — one big step is about 3 ft. Speed scales directly with this number, so measure it honestly.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(modifier = Modifier.height(160.dp)) {
                    items(DistancePresets.presets) { (label, feet) ->
                        FilterChip(
                            selected = customText.toDoubleOrNull() == feet,
                            onClick = { customText = feet.toString() },
                            label = { Text(label) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Custom distance (feet)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val feet = customText.toDoubleOrNull()
                if (feet != null && feet in 5.0..200.0) onConfirm(feet)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.Center) {
                Button(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
