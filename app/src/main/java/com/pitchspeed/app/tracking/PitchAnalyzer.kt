package com.pitchspeed.app.tracking

import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.pitchspeed.app.data.Sensitivity
import kotlin.math.abs
import kotlin.math.tan

data class PitchResult(val speedMph: Double, val confidence: Float)

private data class TrackSample(val xNorm: Double, val tNs: Long)

private data class Thresholds(
    val brightMin: Int,
    val diffMin: Int,
    val minBlobCells: Int,
    val maxBlobCells: Int
)

private fun thresholdsFor(sensitivity: Sensitivity): Thresholds = when (sensitivity) {
    Sensitivity.LOW -> Thresholds(brightMin = 200, diffMin = 45, minBlobCells = 3, maxBlobCells = 160)
    Sensitivity.MEDIUM -> Thresholds(brightMin = 170, diffMin = 30, minBlobCells = 2, maxBlobCells = 220)
    Sensitivity.HIGH -> Thresholds(brightMin = 140, diffMin = 18, minBlobCells = 1, maxBlobCells = 260)
}

/**
 * Watches the camera feed for a bright object sweeping across the frame and turns that into a
 * speed estimate.
 *
 * How the math works:
 *  1. Every analyzed frame is downsampled to a coarse luma grid. Cells that are both bright
 *     (likely a white/light-colored ball) and changed a lot since the previous frame (likely
 *     moving) are candidates; their diff-weighted centroid is the ball's estimated position for
 *     that frame, expressed as a 0..1 fraction of the tracking axis.
 *  2. [horizontalFovRadians] plus the user-entered distance-to-release-point turns that fraction
 *     into a real angle, and the angle into a real lateral position: realX = distance * tan(angle).
 *  3. Speed = the change in realX over the change in time between the first and last sample of a
 *     detected sweep (an average over the transit, similar to how a Doppler radar's reported
 *     speed is effectively averaged over its detection window).
 *
 * This is a fun approximation, not a certified radar: accuracy depends on lighting, frame rate,
 * and the phone being held roughly perpendicular to the pitch with an accurate distance entry.
 */
class PitchAnalyzer(
    private val distanceMetersProvider: () -> Double,
    private val fovRadiansProvider: () -> Double,
    private val sensitivityProvider: () -> Sensitivity,
    private val onPitchDetected: (PitchResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val gridW = 64
    private val gridH = 36
    private var prevGrid: FloatArray? = null
    private val samples = mutableListOf<TrackSample>()
    private var cooldownUntilNs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cooldownNs = 1_400_000_000L
    private val staleSampleGapNs = 250_000_000L
    private val maxWindowNs = 700_000_000L

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val width = image.width
            val height = image.height
            val rotation = image.imageInfo.rotationDegrees
            val tNs = image.imageInfo.timestamp

            val grid = FloatArray(gridW * gridH)
            for (gy in 0 until gridH) {
                val py = ((gy + 0.5f) / gridH * height).toInt().coerceIn(0, height - 1)
                val rowOffset = py * rowStride
                for (gx in 0 until gridW) {
                    val px = ((gx + 0.5f) / gridW * width).toInt().coerceIn(0, width - 1)
                    val idx = rowOffset + px * pixelStride
                    val luma = if (idx in 0 until buffer.capacity()) (buffer.get(idx).toInt() and 0xFF) else 0
                    grid[gy * gridW + gx] = luma.toFloat()
                }
            }

            val prev = prevGrid
            val inCooldown = tNs < cooldownUntilNs

            if (prev != null && !inCooldown) {
                val t = thresholdsFor(sensitivityProvider())
                var sumW = 0.0
                var sumWx = 0.0
                var sumWy = 0.0
                var count = 0
                for (gy in 0 until gridH) {
                    for (gx in 0 until gridW) {
                        val i = gy * gridW + gx
                        val cur = grid[i]
                        val diff = abs(cur - prev[i])
                        if (cur >= t.brightMin && diff >= t.diffMin) {
                            sumW += diff
                            sumWx += diff * gx
                            sumWy += diff * gy
                            count++
                        }
                    }
                }

                val useRawHeightAsTrackAxis = rotation == 90 || rotation == 270
                if (count in t.minBlobCells..t.maxBlobCells && sumW > 0) {
                    val gxCentroid = sumWx / sumW
                    val gyCentroid = sumWy / sumW
                    val xNorm = if (useRawHeightAsTrackAxis) gyCentroid / gridH else gxCentroid / gridW
                    samples.add(TrackSample(xNorm, tNs))
                    while (samples.isNotEmpty() && tNs - samples.first().tNs > maxWindowNs) samples.removeAt(0)

                    if (samples.size >= 4) {
                        tryFinalize(tNs)
                    }
                } else if (samples.isNotEmpty() && tNs - samples.last().tNs > staleSampleGapNs) {
                    tryFinalize(tNs)
                }
            }

            prevGrid = grid
        } finally {
            image.close()
        }
    }

    private fun tryFinalize(nowNs: Long) {
        val result = computeResult(samples.toList())
        samples.clear()
        if (result != null) {
            cooldownUntilNs = nowNs + cooldownNs
            mainHandler.post { onPitchDetected(result) }
        }
    }

    private fun computeResult(pts: List<TrackSample>): PitchResult? {
        if (pts.size < 2) return null
        val distanceMeters = distanceMetersProvider()
        val fov = fovRadiansProvider()

        fun realX(xNorm: Double): Double {
            val angle = (xNorm - 0.5) * fov
            return distanceMeters * tan(angle)
        }

        val first = pts.first()
        val last = pts.last()
        val dtEndpoint = (last.tNs - first.tNs) / 1_000_000_000.0
        if (dtEndpoint < 0.01) return null

        val xDisplacementNorm = abs(last.xNorm - first.xNorm)
        if (xDisplacementNorm < 0.12) return null // didn't sweep enough of the frame to trust

        val speedEndpointMps = abs(realX(last.xNorm) - realX(first.xNorm)) / dtEndpoint

        val pairSpeeds = mutableListOf<Double>()
        for (i in 0 until pts.size - 1) {
            val dt = (pts[i + 1].tNs - pts[i].tNs) / 1_000_000_000.0
            if (dt > 0.001) {
                pairSpeeds.add(abs(realX(pts[i + 1].xNorm) - realX(pts[i].xNorm)) / dt)
            }
        }
        val medianPairMps = median(pairSpeeds)

        val speedMph = speedEndpointMps * 2.23694
        if (speedMph !in 12.0..110.0) return null

        val consistency = if (medianPairMps > 0.0) {
            1.0 - (abs(medianPairMps - speedEndpointMps) / medianPairMps).coerceIn(0.0, 1.0)
        } else 0.5
        val sampleScore = (pts.size / 8.0).coerceIn(0.0, 1.0)
        val confidence = ((consistency * 0.6) + (sampleScore * 0.4)).coerceIn(0.05, 1.0).toFloat()

        return PitchResult(speedMph, confidence)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}
