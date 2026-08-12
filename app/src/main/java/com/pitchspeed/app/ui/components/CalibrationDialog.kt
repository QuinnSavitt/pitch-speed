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
        title = { Text("Distance to pitcher") },
        text = {
            Column {
                Text(
                    "Measure from the camera straight across to where the ball is released (the mound or throwing line). This is what turns pixels into real speed.",
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
