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
    const val MIN_MPH = 6.0
    const val INLIER_TOLERANCE = 0.04 // xNorm units; samples farther from the robust line are junk
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

        val frameSpanMeters = 2.0 * distanceMeters * tan(fovRadians / 2.0) * trackAxisFraction

        val n = samples.size
        val t0 = samples.first().tNs
        val ts = DoubleArray(n) { (samples[it].tNs - t0) / 1e9 }
        val xs = DoubleArray(n) { samples[it].xNorm }

        // Theil-Sen estimate (median of pairwise slopes): robust to the occasional sample that
        // isn't the ball — a person's edge caught before suppression kicked in, a bird, a glove
        // flash. Up to ~a third of the samples can be junk without dragging the line.
        val pairSlopes = mutableListOf<Double>()
        for (i in 0 until n - 1) {
            for (j in i + 1 until n) {
                val dt = ts[j] - ts[i]
                if (dt > 1e-3) pairSlopes.add((xs[j] - xs[i]) / dt)
            }
        }
        if (pairSlopes.isEmpty()) return null
        val tsSlope = median(pairSlopes)
        val intercept = median(List(n) { xs[it] - tsSlope * ts[it] })

        // Keep only samples near the robust line, then refine with least squares on those:
        // centroid noise averages out and junk samples fall away entirely.
        val inliers = (0 until n).filter { abs(xs[it] - (tsSlope * ts[it] + intercept)) <= INLIER_TOLERANCE }
        if (inliers.size < MIN_SAMPLES) return null

        var tMean = 0.0
        var xMean = 0.0
        for (i in inliers) {
            tMean += ts[i]
            xMean += xs[i]
        }
        tMean /= inliers.size
        xMean /= inliers.size
        var num = 0.0
        var den = 0.0
        for (i in inliers) {
            num += (ts[i] - tMean) * (xs[i] - xMean)
            den += (ts[i] - tMean) * (ts[i] - tMean)
        }
        if (den < 1e-9) return null
        val speedMps = abs(num / den) * frameSpanMeters

        val iFirst = inliers.first()
        val iLast = inliers.last()
        val dtEndpoint = ts[iLast] - ts[iFirst]
        if (dtEndpoint < 0.02) return null

        val sweep = abs(xs[iLast] - xs[iFirst])
        if (sweep < MIN_SWEEP_FRACTION) return null // didn't cross enough of the frame to trust

        // Endpoint cross-check: a real ball moves near-uniformly across the frame, so the fitted
        // slope and the endpoint average must agree. Disagreement means the surviving track was
        // still mostly noise — better no reading than a wrong one.
        val endpointMps = sweep / dtEndpoint * frameSpanMeters
        val agreement = 1.0 - abs(speedMps - endpointMps) / maxOf(speedMps, endpointMps, 1e-6)
        if (agreement < 0.65) return null

        val speedMph = speedMps * MPS_TO_MPH
        if (speedMph !in MIN_MPH..MAX_MPH) return null

        val sampleScore = (inliers.size / 8.0).coerceIn(0.0, 1.0)
        val inlierFraction = inliers.size.toDouble() / n
        val confidence = ((agreement * 0.5) + (sampleScore * 0.3) + (inlierFraction * 0.2))
            .coerceIn(0.05, 1.0).toFloat()
        return PitchResult(speedMph, confidence)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}
