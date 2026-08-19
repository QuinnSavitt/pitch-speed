package com.pitchspeed.app.tracking

import kotlin.math.abs
import kotlin.math.tan

data class PitchResult(val speedMph: Double, val confidence: Float)

/** One tracked ball position: fraction (0..1) along the tracking axis, and the frame timestamp. */
data class TrackSample(val xNorm: Double, val tNs: Long)

/**
 * Pure math for turning a tracked sweep into a speed. Kept free of Android dependencies so it
 * can be unit-tested on the JVM.
 *
 * Geometry: a rectilinear camera lens maps world positions on a line parallel to the sensor
 * linearly onto pixels. So at perpendicular distance D from the flight path, the full frame
 * width spans exactly 2 * D * tan(fov / 2) meters, and a ball's normalized pixel position is
 * linear in its real position — no per-sample trig needed.
 */
object SpeedMath {

    const val MIN_SAMPLES = 3
    const val MIN_SWEEP_FRACTION = 0.12
    const val MIN_MPH = 12.0
    const val MAX_MPH = 115.0
    const val MPS_TO_MPH = 2.23694

    /**
     * @param samples tracked positions, oldest first, timestamps in nanoseconds
     * @param distanceMeters perpendicular distance from the camera to the ball's flight path
     * @param fovRadians horizontal field of view across the full sensor width
     * @param trackAxisFraction fraction of the full-width FOV spanned by the tracking axis:
     *        1.0 when tracking along the buffer's width (landscape), bufferHeight / bufferWidth
     *        when tracking along its height (portrait fallback)
     */
    fun computeResult(
        samples: List<TrackSample>,
        distanceMeters: Double,
        fovRadians: Double,
        trackAxisFraction: Double = 1.0
    ): PitchResult? {
        if (samples.size < MIN_SAMPLES) return null
        if (distanceMeters <= 0.0 || fovRadians <= 0.0 || trackAxisFraction <= 0.0) return null

        val first = samples.first()
        val last = samples.last()
        val dtEndpoint = (last.tNs - first.tNs) / 1e9
        if (dtEndpoint < 0.02) return null

        val sweep = abs(last.xNorm - first.xNorm)
        if (sweep < MIN_SWEEP_FRACTION) return null // didn't cross enough of the frame to trust

        val frameSpanMeters = 2.0 * distanceMeters * tan(fovRadians / 2.0) * trackAxisFraction

        // Least-squares slope of position vs. time over the whole sweep: uses every sample,
        // so single-frame centroid noise averages out instead of landing on an endpoint.
        val t0 = first.tNs
        val n = samples.size
        var tMean = 0.0
        var xMean = 0.0
        for (s in samples) {
            tMean += (s.tNs - t0) / 1e9
            xMean += s.xNorm
        }
        tMean /= n
        xMean /= n
        var num = 0.0
        var den = 0.0
        for (s in samples) {
            val dt = (s.tNs - t0) / 1e9 - tMean
            num += dt * (s.xNorm - xMean)
            den += dt * dt
        }
        if (den < 1e-9) return null
        val speedMps = abs(num / den) * frameSpanMeters

        // Endpoint cross-check: a real ball moves near-uniformly across the frame, so the fitted
        // slope and the endpoint average must agree. Disagreement means the track mixed multiple
        // objects (arm swing + ball) or was mostly noise — better no reading than a wrong one.
        val endpointMps = sweep / dtEndpoint * frameSpanMeters
        val agreement = 1.0 - abs(speedMps - endpointMps) / maxOf(speedMps, endpointMps, 1e-6)
        if (agreement < 0.65) return null

        val speedMph = speedMps * MPS_TO_MPH
        if (speedMph !in MIN_MPH..MAX_MPH) return null

        val sampleScore = (n / 8.0).coerceIn(0.0, 1.0)
        val confidence = ((agreement * 0.6) + (sampleScore * 0.4)).coerceIn(0.05, 1.0).toFloat()
        return PitchResult(speedMph, confidence)
    }
}
