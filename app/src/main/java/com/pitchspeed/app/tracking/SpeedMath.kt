package com.pitchspeed.app.tracking

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

data class PitchResult(val speedMph: Double, val confidence: Float)

/**
 * One tracked ball position: fraction (0..1) along the tracking axis, the frame timestamp, and
 * how many moving pixels the blob was made of.
 *
 * [pixelCount] defaults to 0, which means "unknown" and switches the size-consistency gate off
 * for that track — hand-built sample lists in tests and any caller that has no size information
 * behave exactly as they did before.
 */
data class TrackSample(val xNorm: Double, val tNs: Long, val pixelCount: Int = 0)

/** Why a finished track did not become a reading. Drives the diagnostics log. */
enum class RejectionReason {
    /** Fewer than [SpeedMath.MIN_SAMPLES] positions in the track. */
    TOO_FEW_SAMPLES,

    /** Distance, FOV or track-axis fraction was not a usable positive number. */
    BAD_GEOMETRY,

    /** Every sample shared one timestamp, so no slope exists. */
    NO_TIME_SPREAD,

    /** Too few samples sat near the robust line — the track was mostly junk. */
    TOO_FEW_INLIERS,

    /** The surviving inliers had no spread in time; the fit is degenerate. */
    DEGENERATE_FIT,

    /** The inliers spanned less than 20 ms — not a flight, a flicker. */
    SHORT_TIME_SPAN,

    /** Crossed less than [SpeedMath.MIN_SWEEP_FRACTION] of the frame. */
    SHORT_SWEEP,

    /** The object changed direction; a thrown ball never does. */
    DIRECTION_REVERSALS,

    /** The blob size jumped around wildly, so it was not one consistent object. */
    SIZE_INCONSISTENT,

    /**
     * The track blinked in and out instead of being seen on consecutive frames — a chain of
     * unrelated specks, not one object crossing.
     */
    INTERMITTENT_TRACK,

    /** Fitted slope and endpoint average disagreed — the track was still noise. */
    ENDPOINT_DISAGREEMENT,

    /** Outside 6..115 mph. */
    SPEED_OUT_OF_RANGE,

    /** Passed every hard gate but the evidence was too thin to show a number. */
    LOW_CONFIDENCE
}

/**
 * Full outcome of scoring one track: either a [result] or a [rejection] naming the gate that
 * stopped it, never both. [sampleCount] and [inlierCount] are carried for the diagnostics log.
 */
data class SpeedVerdict(
    val result: PitchResult?,
    val rejection: RejectionReason?,
    val sampleCount: Int = 0,
    val inlierCount: Int = 0
)

/**
 * Pure math for turning a tracked sweep into a speed. Kept free of Android dependencies so it
 * can be unit-tested on the JVM.
 *
 * Geometry: a rectilinear camera lens maps world positions on a line parallel to the sensor
 * linearly onto pixels. So at perpendicular distance D from the flight path, the full frame
 * width spans exactly 2 * D * tan(fov / 2) meters, and a ball's normalized pixel position is
 * linear in its real position — no per-sample trig needed.
 *
 * On top of the fit sit a set of physics gates. A thrown ball is a single rigid object crossing
 * the frame: it never reverses direction, it does not change size by an order of magnitude
 * between frames, and it leaves enough evidence behind to be sure about. Junk motion — swaying
 * leaves, a flag, sensor noise, a hand fidgeting — fails at least one of those every time, which
 * is what keeps the app from reporting a number for every tiny thing that moves.
 */
object SpeedMath {

    /**
     * A three-sample track is only two intervals of evidence: far too easy for three unrelated
     * flickers to line up by chance.
     *
     * Five is where the simulations put the boundary. Across every simulated throw the tracker can
     * actually follow, the ball leaves 6 to 26 samples, because it is visible on every frame while
     * it crosses. Junk chains that survived the other gates were almost always exactly four
     * samples long - the shortest track the fit will look at, and therefore the easiest length for
     * a coincidence to reach. Raising the bar to five costs no simulated throw a reading and
     * removes that whole class of false positive.
     */
    const val MIN_SAMPLES = 5
    const val MIN_SWEEP_FRACTION = 0.12
    const val MIN_MPH = 6.0
    const val INLIER_TOLERANCE = 0.04 // xNorm units; samples farther from the robust line are junk
    const val MAX_MPH = 115.0
    const val MPS_TO_MPH = 2.23694

    /**
     * Fraction of consecutive inlier steps that must share one direction. A thrown ball moves one
     * way, full stop; a track that doubles back is two different objects being confused for one,
     * or noise. One stalled or slightly-backwards step in five is tolerated because centroid
     * jitter can flip the sign of a very small step.
     */
    const val MIN_DIRECTION_AGREEMENT = 0.80

    /**
     * Largest allowed coefficient of variation (sd / mean) of the inlier blob sizes.
     *
     * A real ball does breathe as it blurs, brightens and passes through shade — across the
     * simulated throws its size CV runs 0.00 to 0.27, the top of that range being the frame where
     * it first appears and reads double-size. Junk chains, being a different speck each frame,
     * run 0.38 to 0.58. The bar sits at 0.45: well clear of a real ball's worst frame, low enough
     * that a "track" whose blob goes 5 px, 23 px, 15 px, 7 px is recognised as four different
     * things rather than one ball.
     */
    const val MAX_SIZE_VARIATION = 0.45

    /**
     * Largest allowed ratio between the biggest and smallest gap in a track's inlier timestamps.
     *
     * Every analyzed frame is offered to the tracker, so an object actually crossing the frame is
     * seen on consecutive frames and all its gaps equal one frame period. A chain of unrelated
     * specks only gains a sample when something happens to appear near the prediction, so it
     * blinks: one frame, then four, then two. Allowing 3x lets a real ball vanish for a couple of
     * frames behind a branch, into shade, or under a bad exposure without losing its track, while
     * still rejecting motion that was never continuously there.
     */
    const val MAX_FRAME_GAP_RATIO = 3.0

    /**
     * Confidence a track must reach before the app will show a number. Everything below it is a
     * maybe, and a wrong number is worse than no number.
     */
    const val MIN_CONFIDENCE = 0.45f

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
    ): PitchResult? =
        computeResultDetailed(samples, distanceMeters, fovRadians, trackAxisFraction).result

    /**
     * Same computation as [computeResult] but reports WHY a track was thrown away, so the
     * diagnostics log can tell a tester "your throw was seen, and rejected for X" instead of
     * silently showing nothing.
     */
    fun computeResultDetailed(
        samples: List<TrackSample>,
        distanceMeters: Double,
        fovRadians: Double,
        trackAxisFraction: Double = 1.0
    ): SpeedVerdict {
        val n = samples.size
        if (n < MIN_SAMPLES) return reject(RejectionReason.TOO_FEW_SAMPLES, n, 0)
        if (distanceMeters <= 0.0 || fovRadians <= 0.0 || trackAxisFraction <= 0.0) {
            return reject(RejectionReason.BAD_GEOMETRY, n, 0)
        }

        val frameSpanMeters = 2.0 * distanceMeters * tan(fovRadians / 2.0) * trackAxisFraction

        val t0 = samples.first().tNs
        val ts = DoubleArray(n) { (samples[it].tNs - t0) / 1e9 }
        val xs = DoubleArray(n) { samples[it].xNorm }

        // Theil-Sen estimate (median of pairwise slopes): robust to the occasional sample that
        // is not the ball — a person's edge caught before suppression kicked in, a bird, a glove
        // flash. Up to ~a third of the samples can be junk without dragging the line.
        val pairSlopes = mutableListOf<Double>()
        for (i in 0 until n - 1) {
            for (j in i + 1 until n) {
                val dt = ts[j] - ts[i]
                if (dt > 1e-3) pairSlopes.add((xs[j] - xs[i]) / dt)
            }
        }
        if (pairSlopes.isEmpty()) return reject(RejectionReason.NO_TIME_SPREAD, n, 0)
        val tsSlope = median(pairSlopes)
        val intercept = median(List(n) { xs[it] - tsSlope * ts[it] })

        // Keep only samples near the robust line, then refine with least squares on those:
        // centroid noise averages out and junk samples fall away entirely.
        val inliers = (0 until n).filter { abs(xs[it] - (tsSlope * ts[it] + intercept)) <= INLIER_TOLERANCE }
        if (inliers.size < MIN_SAMPLES) return reject(RejectionReason.TOO_FEW_INLIERS, n, inliers.size)

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
        if (den < 1e-9) return reject(RejectionReason.DEGENERATE_FIT, n, inliers.size)
        val speedMps = abs(num / den) * frameSpanMeters

        val iFirst = inliers.first()
        val iLast = inliers.last()
        val dtEndpoint = ts[iLast] - ts[iFirst]
        if (dtEndpoint < 0.02) return reject(RejectionReason.SHORT_TIME_SPAN, n, inliers.size)

        val sweep = abs(xs[iLast] - xs[iFirst])
        // didn't cross enough of the frame to trust
        if (sweep < MIN_SWEEP_FRACTION) return reject(RejectionReason.SHORT_SWEEP, n, inliers.size)

        // A ball goes ONE way. Count the sign of every step between consecutive inliers; if the
        // majority direction does not own at least MIN_DIRECTION_AGREEMENT of them, whatever this
        // was oscillated — a branch in the wind, a hand waving, two objects stitched into one
        // track — and it is not a pitch.
        if (!directionIsConsistent(xs, inliers)) {
            return reject(RejectionReason.DIRECTION_REVERSALS, n, inliers.size)
        }

        // A ball is THERE, frame after frame, for as long as it is in shot. Junk that merely
        // resembles a flight is assembled from whatever happened to show up near the predicted
        // position, so it arrives in bursts with holes between them.
        if (!samplingIsContinuous(ts, inliers)) {
            return reject(RejectionReason.INTERMITTENT_TRACK, n, inliers.size)
        }

        // A ball keeps roughly the same apparent size for the fraction of a second it is in shot.
        // Wildly swinging blob sizes mean the "track" is a chain of unrelated specks that
        // happened to fall near a line.
        if (!sizeIsConsistent(samples, inliers)) {
            return reject(RejectionReason.SIZE_INCONSISTENT, n, inliers.size)
        }

        // Endpoint cross-check: a real ball moves near-uniformly across the frame, so the fitted
        // slope and the endpoint average must agree. Disagreement means the surviving track was
        // still mostly noise — better no reading than a wrong one.
        val endpointMps = sweep / dtEndpoint * frameSpanMeters
        val agreement = 1.0 - abs(speedMps - endpointMps) / maxOf(speedMps, endpointMps, 1e-6)
        if (agreement < 0.65) return reject(RejectionReason.ENDPOINT_DISAGREEMENT, n, inliers.size)

        val speedMph = speedMps * MPS_TO_MPH
        if (speedMph !in MIN_MPH..MAX_MPH) {
            return reject(RejectionReason.SPEED_OUT_OF_RANGE, n, inliers.size)
        }

        val sampleScore = (inliers.size / 8.0).coerceIn(0.0, 1.0)
        val inlierFraction = inliers.size.toDouble() / n
        val confidence = ((agreement * 0.5) + (sampleScore * 0.3) + (inlierFraction * 0.2))
            .coerceIn(0.05, 1.0).toFloat()
        if (confidence < MIN_CONFIDENCE) return reject(RejectionReason.LOW_CONFIDENCE, n, inliers.size)
        return SpeedVerdict(PitchResult(speedMph, confidence), null, n, inliers.size)
    }

    /** True when the inliers overwhelmingly step the same way along the track axis. */
    private fun directionIsConsistent(xs: DoubleArray, inliers: List<Int>): Boolean {
        var forward = 0
        var backward = 0
        for (k in 1 until inliers.size) {
            val d = xs[inliers[k]] - xs[inliers[k - 1]]
            if (d > 0.0) forward++ else if (d < 0.0) backward++
        }
        val moves = forward + backward
        if (moves == 0) return false // never actually went anywhere
        return maxOf(forward, backward).toDouble() / moves >= MIN_DIRECTION_AGREEMENT
    }

    /**
     * True when the inliers arrive on a steady cadence rather than in bursts. The smallest gap is
     * taken as the frame period, since the tracker can never sample an object twice in one frame.
     */
    private fun samplingIsContinuous(ts: DoubleArray, inliers: List<Int>): Boolean {
        if (inliers.size < 3) return true // two samples make one gap; nothing to compare it to
        var minGap = Double.MAX_VALUE
        var maxGap = 0.0
        for (k in 1 until inliers.size) {
            val gap = ts[inliers[k]] - ts[inliers[k - 1]]
            if (gap <= 0.0) continue
            if (gap < minGap) minGap = gap
            if (gap > maxGap) maxGap = gap
        }
        if (minGap == Double.MAX_VALUE) return true
        return maxGap <= MAX_FRAME_GAP_RATIO * minGap
    }

    /**
     * True when the inlier blob sizes hold together. Tracks whose samples carry no size
     * information (pixelCount 0, the default) skip the gate rather than fail it.
     */
    private fun sizeIsConsistent(samples: List<TrackSample>, inliers: List<Int>): Boolean {
        var sum = 0.0
        var known = 0
        for (i in inliers) {
            val px = samples[i].pixelCount
            if (px <= 0) continue
            sum += px
            known++
        }
        if (known < 3) return true // not enough size data to judge; let the other gates decide
        val mean = sum / known
        if (mean <= 0.0) return true
        var varSum = 0.0
        for (i in inliers) {
            val px = samples[i].pixelCount
            if (px <= 0) continue
            val d = px - mean
            varSum += d * d
        }
        val cv = sqrt(varSum / known) / mean
        return cv <= MAX_SIZE_VARIATION
    }

    private fun reject(reason: RejectionReason, n: Int, inliers: Int) =
        SpeedVerdict(null, reason, n, inliers)

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}
