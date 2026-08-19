package com.pitchspeed.app.tracking

import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.pitchspeed.app.data.Sensitivity
import kotlin.math.abs

private data class Thresholds(
    val brightMin: Int,
    val diffMin: Int,
    val minPixels: Int,
    val maxPixels: Int
)

private fun thresholdsFor(sensitivity: Sensitivity): Thresholds = when (sensitivity) {
    Sensitivity.LOW -> Thresholds(brightMin = 200, diffMin = 45, minPixels = 6, maxPixels = 350)
    Sensitivity.MEDIUM -> Thresholds(brightMin = 170, diffMin = 30, minPixels = 3, maxPixels = 500)
    Sensitivity.HIGH -> Thresholds(brightMin = 140, diffMin = 18, minPixels = 2, maxPixels = 700)
}

/**
 * Watches the camera feed for a bright object sweeping across the frame and turns that into a
 * speed estimate.
 *
 * How it works:
 *  1. Every analyzed frame's luma plane is scanned at half resolution. Pixels that are both
 *     bright (a white/light ball) and changed a lot since the previous frame (moving) form a
 *     diff-weighted centroid — the ball's position for that frame, as a 0..1 fraction of the
 *     tracking axis. Frames where far too many pixels move (a person, the thrower's arm) are
 *     rejected by the pixel-count cap so they can't pollute the track.
 *  2. Samples accumulate for as long as the ball keeps crossing; the track is only finalized
 *     once no candidate has been seen for [staleSampleGapNs] — i.e. the ball has left the frame —
 *     so the speed fit uses the whole visible flight, not a truncated slice of it.
 *  3. [SpeedMath.computeResult] fits a least-squares line to position-vs-time and converts the
 *     slope to real speed using the camera's field of view and the user-entered perpendicular
 *     distance to the flight path.
 *
 * This is a fun approximation, not a certified radar: accuracy depends on lighting, a steady
 * phone held side-on to the throw, and an accurate distance entry. The reading is an average
 * over the visible flight, so it reads a touch under a radar gun's release-point speed.
 */
class PitchAnalyzer(
    private val distanceMetersProvider: () -> Double,
    private val fovRadiansProvider: () -> Double,
    private val sensitivityProvider: () -> Sensitivity,
    private val onPitchDetected: (PitchResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val step = 2 // scan every 2nd pixel in both axes: dense enough for a distant ball
    private var prevLuma: ByteArray? = null
    private var prevW = 0
    private var prevH = 0
    private val samples = mutableListOf<TrackSample>()
    private var trackAxisFraction = 1.0
    private var cooldownUntilNs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cooldownNs = 1_400_000_000L
    private val staleSampleGapNs = 250_000_000L
    private val maxWindowNs = 900_000_000L

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

            val sw = width / step
            val sh = height / step
            val cur = ByteArray(sw * sh)
            val capacity = buffer.capacity()
            for (sy in 0 until sh) {
                val rowOffset = (sy * step) * rowStride
                val outRow = sy * sw
                for (sx in 0 until sw) {
                    val idx = rowOffset + (sx * step) * pixelStride
                    cur[outRow + sx] = if (idx < capacity) buffer.get(idx) else 0
                }
            }

            val prev = prevLuma
            val inCooldown = tNs < cooldownUntilNs

            if (prev != null && prevW == sw && prevH == sh && !inCooldown) {
                val t = thresholdsFor(sensitivityProvider())
                var sumW = 0.0
                var sumWx = 0.0
                var sumWy = 0.0
                var count = 0
                for (sy in 0 until sh) {
                    val row = sy * sw
                    for (sx in 0 until sw) {
                        val i = row + sx
                        val luma = cur[i].toInt() and 0xFF
                        if (luma < t.brightMin) continue
                        val diff = abs(luma - (prev[i].toInt() and 0xFF))
                        if (diff >= t.diffMin) {
                            val w = diff.toDouble()
                            sumW += w
                            sumWx += w * sx
                            sumWy += w * sy
                            count++
                        }
                    }
                }

                // If the ball vanished long enough ago, the sweep is over: score it before
                // touching this frame's candidate (which would belong to a new sweep).
                if (samples.isNotEmpty() && tNs - samples.last().tNs > staleSampleGapNs) {
                    finalizeTrack(tNs)
                }

                if (count in t.minPixels..t.maxPixels && sumW > 0 && tNs >= cooldownUntilNs) {
                    val useRawHeightAsTrackAxis = rotation == 90 || rotation == 270
                    val xNorm = if (useRawHeightAsTrackAxis) (sumWy / sumW) / sh else (sumWx / sumW) / sw
                    trackAxisFraction = if (useRawHeightAsTrackAxis) height.toDouble() / width else 1.0
                    samples.add(TrackSample(xNorm, tNs))
                    while (samples.isNotEmpty() && tNs - samples.first().tNs > maxWindowNs) {
                        samples.removeAt(0)
                    }
                }
            }

            prevLuma = cur
            prevW = sw
            prevH = sh
        } finally {
            image.close()
        }
    }

    private fun finalizeTrack(nowNs: Long) {
        val result = SpeedMath.computeResult(
            samples = samples.toList(),
            distanceMeters = distanceMetersProvider(),
            fovRadians = fovRadiansProvider(),
            trackAxisFraction = trackAxisFraction
        )
        samples.clear()
        if (result != null) {
            cooldownUntilNs = nowNs + cooldownNs
            mainHandler.post { onPitchDetected(result) }
        }
    }
}
