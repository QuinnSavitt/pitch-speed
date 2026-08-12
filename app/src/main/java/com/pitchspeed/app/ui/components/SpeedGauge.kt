package com.pitchspeed.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * A speedometer-style arc gauge. [value] and [maxValue] are in whatever unit the caller wants
 * displayed (the label is passed separately so this stays unit-agnostic).
 */
@Composable
fun SpeedGauge(
    value: Double,
    maxValue: Double,
    unitLabel: String,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    progressColor: Color = MaterialTheme.colorScheme.secondary
) {
    val fraction = (value / maxValue).toFloat().coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 650),
        label = "gaugeFraction"
    )

    Box(modifier = modifier.aspectRatio(1.25f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1.25f)) {
            val strokeWidth = size.minDimension * 0.11f
            val arcSize = Size(size.width - strokeWidth, size.width - strokeWidth)
            val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
            val startAngle = 150f
            val sweepAngle = 240f

            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(progressColor.copy(alpha = 0.5f), progressColor)),
                startAngle = startAngle,
                sweepAngle = sweepAngle * animatedFraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "${value.roundToInt()}",
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
    Text(unitLabel, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
}
