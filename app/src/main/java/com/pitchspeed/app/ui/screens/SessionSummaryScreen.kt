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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pitchspeed.app.data.PitchSession
import com.pitchspeed.app.data.SpeedUnit
import com.pitchspeed.app.data.mphToDisplay
import com.pitchspeed.app.data.unitLabel
import com.pitchspeed.app.ui.components.SpeedGauge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SessionSummaryScreen(
    session: PitchSession,
    unit: SpeedUnit,
    onShare: () -> Unit,
    onDone: () -> Unit,
    doneLabel: String = "Done"
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
        ) {
            Text(session.pitcherName, style = MaterialTheme.typography.headlineMedium)
            Text(
                SimpleDateFormat("EEEE, MMM d 'at' h:mm a", Locale.getDefault()).format(Date(session.dateMillis)),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            Spacer(Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.width(6.dp))
                        Text("Fastest pitch", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    SpeedGauge(
                        value = mphToDisplay(session.fastestMph, unit),
                        maxValue = if (unit == SpeedUnit.MPH) 105.0 else 170.0,
                        unitLabel = unitLabel(unit),
                        trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                        progressColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.fillMaxWidth(0.7f)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = "Average",
                    value = "${mphToDisplay(session.averageMph, unit).roundToInt()} ${unitLabel(unit)}",
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = "Pitches",
                    value = "${session.pitches.size}",
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("All pitches", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(session.pitches.reversed()) { pitch ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(pitch.timestampMillis)),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                        Text(
                            "${mphToDisplay(pitch.speedMph, unit).roundToInt()} ${unitLabel(unit)}",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Share result card")
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(doneLabel)
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
