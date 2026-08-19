package com.pitchspeed.app.data

import java.util.UUID

enum class SpeedUnit { MPH, KMH }

enum class Sensitivity(val label: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High")
}

data class Pitch(
    val speedMph: Double,
    val timestampMillis: Long,
    val confidence: Float
)

data class PitchSession(
    val id: String = UUID.randomUUID().toString(),
    val pitcherName: String,
    val dateMillis: Long,
    val distanceFeet: Double,
    val pitches: List<Pitch>
) {
    val fastestMph: Double get() = pitches.maxOfOrNull { it.speedMph } ?: 0.0
    val averageMph: Double get() = if (pitches.isEmpty()) 0.0 else pitches.sumOf { it.speedMph } / pitches.size
}

data class AppSettings(
    val distanceFeet: Double = 20.0,
    val unit: SpeedUnit = SpeedUnit.MPH,
    val sensitivity: Sensitivity = Sensitivity.MEDIUM,
    val onboardingComplete: Boolean = false,
    val lastPitcherName: String = ""
)

/**
 * Quick-pick distances for the calibration UI. These are how far the CAMERA sits from the
 * ball's flight path (measured straight across, perpendicular to the throw) — NOT the
 * pitcher-to-catcher distance. The speed math scales linearly with this number, so getting
 * it right matters more than anything else.
 */
object DistancePresets {
    val presets = listOf(
        "Up close — 10 ft" to 10.0,
        "Backyard — 15 ft" to 15.0,
        "Recommended — 20 ft" to 20.0,
        "Wide view — 30 ft" to 30.0,
        "Far back — 40 ft" to 40.0
    )
}

fun mphToDisplay(mph: Double, unit: SpeedUnit): Double =
    if (unit == SpeedUnit.MPH) mph else mph * 1.60934

fun unitLabel(unit: SpeedUnit): String = if (unit == SpeedUnit.MPH) "mph" else "km/h"
