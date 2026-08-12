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
    val distanceFeet: Double = 60.5,
    val unit: SpeedUnit = SpeedUnit.MPH,
    val sensitivity: Sensitivity = Sensitivity.MEDIUM,
    val onboardingComplete: Boolean = false,
    val lastPitcherName: String = ""
)

/** Common mound / throwing-line distances, in feet, for the quick-pick calibration UI. */
object DistancePresets {
    val presets = listOf(
        "Little League (46 ft)" to 46.0,
        "Youth / 50 ft" to 50.0,
        "Middle School (54 ft)" to 54.0,
        "High School / College / MLB (60.5 ft)" to 60.5,
        "Flat-ground catch (30 ft)" to 30.0
    )
}

fun mphToDisplay(mph: Double, unit: SpeedUnit): Double =
    if (unit == SpeedUnit.MPH) mph else mph * 1.60934

fun unitLabel(unit: SpeedUnit): String = if (unit == SpeedUnit.MPH) "mph" else "km/h"
