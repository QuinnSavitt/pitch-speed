package com.pitchspeed.app.tracking

import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.pitchspeed.app.data.Sensitivity

private fun thresholdsFor(sensitivity: Sensitivity): ScanThresholds = when (sensitivity) {
    Sensitivity.LOW -> ScanThresholds(brightMin = 180, diffMin = 40, minPixels = 6, maxPixels = 350)
    Sensitivity.MEDIUM -> ScanThresholds(brightMin = 90, diffMin = 24, minPixels = 3, maxPixels = 600)
    Sensitivity.HIGH -> ScanThresholds(brightMin = 50, diffMin = 12, minPixels = 1, maxPixels = 800)
}

/**
 * Watches the camera feed for a bright object sweeping across the frame and turns that into a
 * speed estimate.
 *
 * How it works:
 *  1. Every analyzed frame's luma plane is scanned at half resolution by [FrameScanner],
 *     which finds the few most compact fast-changing blobs — the ball among them — as
 *     diff-weighted centroids, expressed as a 0..1 fraction of the tracking axis. Motion is the
 *     primary signal (so colored balls and indoor light work); big moving regions like the
 *     thrower are skipped by clustering rather than blanking the whole frame, and tall thin
 *     blobs (a walking person's edge) are vetoed on shape.
 *  2. Blobs feed a small set of concurrent [TrackSet] tracks, each following one object, so a
 *     person wandering through the shot cannot steal or pollute the ball's samples. A track is
 *     only finalized once nothing has continued it for [staleSampleGapNs] — i.e. that object has
 *     left the frame — so the speed fit uses the whole visible flight, not a truncated slice.
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
    private val onPitchDetected: (PitchResult) -> Unit,
    private val diagnostics: DiagnosticsLog? = null
) : ImageAnalysis.Analyzer {

    private val step = 2 // scan every 2nd pixel in both axes: dense enough for a distant ball
    private var prevLuma: ByteArray? = null
    private var prevChromaU: ByteArray? = null
    private var prevChromaV: ByteArray? = null
    private var prevW = 0
    private var prevH = 0
    private var prevMovingMask: ByteArray? = null
    private var trackAxisFraction = 1.0
    private var cooldownUntilNs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cooldownNs = 1_400_000_000L
    private val staleSampleGapNs = 250_000_000L
    private val maxWindowNs = 900_000_000L

    /**
     * Three slots, not two. With two, a pair of specks of junk motion could hold both for the
     * whole session and the ball never got one; [TrackSet] also now evicts a barely-started slot
     * rather than turning a fresh candidate away.
     */
    private val tracks = TrackSet(
        staleGapNs = staleSampleGapNs,
        maxWindowNs = maxWindowNs,
        maxTracks = 3
    )

    /** Timestamp of the frame being scanned, so scanner vetoes can be logged against it. */
    private var currentFrameNs = 0L

    private val statsSink: ScanStatsSink? = diagnostics?.let { log ->
        ScanStatsSink { reason, cx, cy, pixelCount ->
            // Scanner reports scan-grid pixels; the log speaks in 0..1 frame fractions like
            // every other position, and a negative marker (whole-frame events) passes through.
            val nx = if (cx < 0.0 || prevW <= 0) cx else cx / prevW
            val ny = if (cy < 0.0 || prevH <= 0) cy else cy / prevH
            log.candidateVetoed(currentFrameNs, reason, nx, ny, pixelCount)
        }
    }

    init {
        diagnostics?.let { log ->
            tracks.onTrackEvent = { event, samples, xNorm ->
                log.trackEvent(currentFrameNs, event, samples, xNorm)
            }
        }
    }

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
            currentFrameNs = tNs

            val sw = width / step
            val sh = height / step
            diagnostics?.noteFrame(tNs)
            diagnostics?.setCamera(
                fovDegrees = Math.toDegrees(fovRadiansProvider()),
                analysisWidth = width,
                analysisHeight = height,
                scanWidth = sw,
                scanHeight = sh,
                rotationDegrees = rotation
            )
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

            var curU: ByteArray? = null
            var curV: ByteArray? = null
            if (image.planes.size >= 3) {
                val planeU = image.planes[1]
                val planeV = image.planes[2]
                val bufU = planeU.buffer
                val bufV = planeV.buffer
                val capU = bufU.capacity()
                val capV = bufV.capacity()
                val cu = ByteArray(sw * sh)
                val cv = ByteArray(sw * sh)
                for (sy in 0 until sh) {
                    val rowU = sy * planeU.rowStride
                    val rowV = sy * planeV.rowStride
                    val outRow = sy * sw
                    for (sx in 0 until sw) {
                        val iu = rowU + sx * planeU.pixelStride
                        val iv = rowV + sx * planeV.pixelStride
                        cu[outRow + sx] = if (iu < capU) bufU.get(iu) else 0
                        cv[outRow + sx] = if (iv < capV) bufV.get(iv) else 0
                    }
                }
                curU = cu
                curV = cv
            }

            val prev = prevLuma
            val inCooldown = tNs < cooldownUntilNs

            if (prev != null && prevW == sw && prevH == sh && !inCooldown) {
                val curMask = ByteArray(FrameScanner.maskSize(sw, sh))
                val candidates = FrameScanner.findCandidates(
                    cur, prev, sw, sh, thresholdsFor(sensitivityProvider()),
                    suppressMask = prevMovingMask, outMovingMask = curMask,
                    curU = curU, prevU = prevChromaU, curV = curV, prevV = prevChromaV,
                    stats = statsSink
                )
                prevMovingMask = curMask
                diagnostics?.let { log ->
                    for (c in candidates) {
                        log.candidateAccepted(tNs, c.cx / sw, c.cy / sh, c.pixelCount)
                    }
                }

                val useRawHeightAsTrackAxis = rotation == 90 || rotation == 270
                trackAxisFraction = if (useRawHeightAsTrackAxis) height.toDouble() / width else 1.0

                // Any object that stopped producing samples long enough ago has left the frame:
                // score its track before feeding this frame in, since new blobs belong to
                // whatever is moving now.
                var fired = false
                for (track in tracks.takeStale(tNs)) {
                    if (finalizeTrack(track, tNs)) {
                        fired = true
                        break
                    }
                }

                if (!fired && tNs >= cooldownUntilNs && candidates.isNotEmpty()) {
                    tracks.addFrame(
                        candidates.map { if (useRawHeightAsTrackAxis) it.cy / sh else it.cx / sw },
                        tNs,
                        candidates.map { it.pixelCount }
                    )
                }
            }

            prevLuma = cur
            prevChromaU = curU
            prevChromaV = curV
            prevW = sw
            prevH = sh
        } finally {
            image.close()
        }
    }

    /** Scores one finished track; reports it and starts the cooldown if it flew like a ball. */
    private fun finalizeTrack(track: List<TrackSample>, nowNs: Long): Boolean {
        val verdict = SpeedMath.computeResultDetailed(
            samples = track,
            distanceMeters = distanceMetersProvider(),
            fovRadians = fovRadiansProvider(),
            trackAxisFraction = trackAxisFraction
        )
        diagnostics?.finalized(nowNs, verdict)
        // not a ball flight - drop that track, leave the others running
        val result = verdict.result ?: return false
        tracks.clear()
        prevMovingMask = null // mask will be stale after the cooldown; start fresh
        cooldownUntilNs = nowNs + cooldownNs
        mainHandler.post { onPitchDetected(result) }
        return true
    }
}
