package com.pitchspeed.app.tracking

import kotlin.math.abs
import kotlin.math.sqrt

/** A compact moving blob found in one frame: centroid in scan-grid pixels, and its size. */
data class BlobCandidate(val cx: Double, val cy: Double, val pixelCount: Int)

data class ScanThresholds(
    val brightMin: Int,
    val diffMin: Int,
    val minPixels: Int,
    val maxPixels: Int
)

/** Why a lump of motion was not reported as a ball candidate. */
enum class VetoReason {
    /** Fewer moving pixels than [ScanThresholds.minPixels] — sensor noise, not an object. */
    TOO_SMALL,

    /** More moving pixels than [ScanThresholds.maxPixels] — a body, a swing, camera shake. */
    TOO_BIG,

    /** A tall thin ribbon of motion: a person's or post's edge, never a ball. */
    VERTICAL,

    /** Motion recurring in blocks that already moved last frame — stationary flicker. */
    SUPPRESSED
}

/**
 * Optional cheap hook for the diagnostics log: told about the motion the scanner looked at and
 * threw away. Called on the camera thread, at most [FrameScanner.MAX_VETO_REPORTS] times per
 * frame plus one suppression summary, so implementations must be allocation-light.
 */
fun interface ScanStatsSink {
    fun onVeto(reason: VetoReason, cx: Double, cy: Double, pixelCount: Int)
}

/**
 * Finds ball-sized moving blobs in a downsampled luma frame. Pure array math with no Android
 * dependencies so the whole detection path can be unit-tested on the JVM.
 *
 * Motion (frame-to-frame luma diff) is the primary signal; brightness is only a floor to reject
 * shadow/noise flicker, low enough that a colored or dimly-lit ball still passes. To survive a
 * moving person in frame, passing pixels are bucketed into coarse energy cells; the candidates
 * are the highest-energy cells, and a cell wins when its 3x3-cell neighborhood contains a
 * ball-plausible number of moving pixels AND a ball-plausible SHAPE. A person's swing lights up
 * a big region (too many pixels -> that cell is skipped), camera shake lights up everything
 * (every neighborhood too big -> no candidate), while a ball stays a small tight cluster
 * somewhere else in the frame.
 *
 * Shape matters as much as size. A walking person's leading and trailing edges are tall, thin
 * vertical strips of moving pixels - often only a few pixels wide but a whole body tall - and on
 * a bright day in a colored shirt they carry far more motion energy than the ball. Those are
 * vetoed by aspect ratio (see [VERTICAL_ASPECT_VETO]). A real ball is either round or, when it
 * is moving fast enough to smear, elongated ALONG the track axis, so horizontal blobs pass.
 *
 * Because the loudest blob in a backyard is usually not the ball, [findCandidates] returns up to
 * [DEFAULT_MAX_CANDIDATES] spatially distinct blobs per frame; the caller keeps a separate track
 * per object and lets the fit decide which one flew like a ball.
 */
object FrameScanner {

    private const val CELLS_X = 16
    private const val CELLS_Y = 9
    private const val BLOCK = 8 // px; granularity of the stationary-flicker suppression mask

    /** Max blobs reported per frame. */
    const val DEFAULT_MAX_CANDIDATES = 3

    /** Two accepted candidates must be more than this many cells apart (Chebyshev distance). */
    private const val MIN_CANDIDATE_CELL_SEPARATION = 2

    /**
     * A blob is discarded when its vertical spread exceeds this multiple of its horizontal
     * spread: that is a person, pole or fence-post edge, never a ball.
     */
    private const val VERTICAL_ASPECT_VETO = 2.5

    /** Below this many moving pixels a blob is too small for its spread to mean anything. */
    private const val ASPECT_MIN_PIXELS = 4

    /**
     * Most frames have dozens of active cells and reporting every rejection would bury the log
     * and cost more than the scan. Only the loudest few rejections per frame are reported — those
     * are the ones that were competing with the ball.
     */
    const val MAX_VETO_REPORTS = 3

    fun maskSize(w: Int, h: Int): Int = ((w + BLOCK - 1) / BLOCK) * ((h + BLOCK - 1) / BLOCK)

    /**
     * Backwards-compatible single-blob entry point: the highest-energy blob that passes the size
     * and shape gates, or null.
     */
    fun findCandidate(
        cur: ByteArray,
        prev: ByteArray,
        w: Int,
        h: Int,
        t: ScanThresholds,
        suppressMask: ByteArray? = null,
        outMovingMask: ByteArray? = null,
        curU: ByteArray? = null,
        prevU: ByteArray? = null,
        curV: ByteArray? = null,
        prevV: ByteArray? = null
    ): BlobCandidate? = findCandidates(
        cur, prev, w, h, t, suppressMask, outMovingMask, curU, prevU, curV, prevV,
        maxCandidates = 1
    ).firstOrNull()

    /**
     * @param curU/prevU/curV/prevV optional chroma planes sampled on the same grid as the luma
     *        arrays. Color change counts toward the motion score, so a white ball crossing an
     *        equally-bright blue sky - invisible to a luma diff - is still detected, because the
     *        sky is strongly blue where the ball is neutral.
     * @param suppressMask blocks (see [maskSize]) that contained motion in the PREVIOUS frame
     *        pair. Motion recurring in the same block is stationary flicker - a person shifting
     *        their weight, swaying leaves, a flapping edge - never a ball, which lands somewhere
     *        new every frame. Suppressed pixels are excluded from candidates but still recorded
     *        in [outMovingMask].
     * @param outMovingMask filled with this frame pair's moving blocks; pass it back as
     *        [suppressMask] on the next call.
     * @param maxCandidates how many spatially distinct blobs to report, strongest first.
     * @param stats optional diagnostics hook told about the motion that was rejected and why.
     * @return up to [maxCandidates] blobs ordered by motion energy, descending.
     */
    fun findCandidates(
        cur: ByteArray,
        prev: ByteArray,
        w: Int,
        h: Int,
        t: ScanThresholds,
        suppressMask: ByteArray? = null,
        outMovingMask: ByteArray? = null,
        curU: ByteArray? = null,
        prevU: ByteArray? = null,
        curV: ByteArray? = null,
        prevV: ByteArray? = null,
        maxCandidates: Int = DEFAULT_MAX_CANDIDATES,
        stats: ScanStatsSink? = null
    ): List<BlobCandidate> {
        if (cur.size < w * h || prev.size < w * h) return emptyList()
        if (maxCandidates < 1) return emptyList()
        val bw = (w + BLOCK - 1) / BLOCK
        val nBlocks = maskSize(w, h)
        val suppress = suppressMask?.takeIf { it.size == nBlocks }
        val outMask = outMovingMask?.takeIf { it.size == nBlocks }
        val useChroma = curU != null && prevU != null && curV != null && prevV != null &&
            curU.size >= w * h && prevU.size >= w * h && curV.size >= w * h && prevV.size >= w * h

        // Auto-exposure swings (a cloud crossing the sun, AE hunting on a bright day) shift the
        // WHOLE frame's luma between frames and would read as wall-to-wall motion, blinding the
        // detector. Estimate the global shift from a sparse sample and subtract it, so only
        // motion relative to the scene counts.
        var shiftSum = 0.0
        var shiftN = 0
        var si = 0
        while (si < w * h) {
            val d = (cur[si].toInt() and 0xFF) - (prev[si].toInt() and 0xFF)
            shiftSum += d.coerceIn(-40, 40)
            shiftN++
            si += 251 // prime stride: samples spread over the whole frame
        }
        val globalShift = if (shiftN > 0) (shiftSum / shiftN).toInt() else 0
        val cellW = (w + CELLS_X - 1) / CELLS_X
        val cellH = (h + CELLS_Y - 1) / CELLS_Y
        val nCells = CELLS_X * CELLS_Y
        val sumW = DoubleArray(nCells)
        val sumWx = DoubleArray(nCells)
        val sumWy = DoubleArray(nCells)
        // Second moments, so a neighborhood's spread in x and y - the blob's shape - can be
        // recovered without a second pass over the pixels.
        val sumWx2 = DoubleArray(nCells)
        val sumWy2 = DoubleArray(nCells)
        val count = IntArray(nCells)
        var suppressedPixels = 0

        for (y in 0 until h) {
            val row = y * w
            val cellRow = (y / cellH) * CELLS_X
            for (x in 0 until w) {
                val i = row + x
                val luma = cur[i].toInt() and 0xFF
                if (luma < t.brightMin) continue
                var diff = abs(luma - (prev[i].toInt() and 0xFF) - globalShift)
                if (useChroma) {
                    // Chroma changes are subtler in magnitude but far more specific to a real
                    // object passing, so they count double.
                    diff += 2 * (abs((curU!![i].toInt() and 0xFF) - (prevU!![i].toInt() and 0xFF)) +
                        abs((curV!![i].toInt() and 0xFF) - (prevV!![i].toInt() and 0xFF)))
                }
                if (diff < t.diffMin) continue
                val b = (y / BLOCK) * bw + x / BLOCK
                outMask?.set(b, 1)
                if (suppress != null && suppress[b].toInt() != 0) {
                    suppressedPixels++
                    continue
                }
                val c = cellRow + x / cellW
                val wgt = diff.toDouble()
                sumW[c] += wgt
                sumWx[c] += wgt * x
                sumWy[c] += wgt * y
                sumWx2[c] += wgt * x * x
                sumWy2[c] += wgt * y * y
                count[c]++
            }
        }

        // Walk every active cell from most to least motion energy: a moving person can light
        // up dozens of cells (each rejected below for being too big or too tall), and the ball's
        // dimmer, tighter cluster must still get its turn.
        val order = (0 until nCells).sortedByDescending { sumW[it] }
        val out = ArrayList<BlobCandidate>(maxCandidates)
        val takenX = IntArray(maxCandidates)
        val takenY = IntArray(maxCandidates)
        var vetoReports = 0
        if (stats != null && suppressedPixels > 0) {
            stats.onVeto(VetoReason.SUPPRESSED, -1.0, -1.0, suppressedPixels)
        }
        for (c in order) {
            if (sumW[c] <= 0.0) break
            val cellX = c % CELLS_X
            val cellY = c / CELLS_X
            var tooClose = false
            for (k in out.indices) {
                if (abs(cellX - takenX[k]) <= MIN_CANDIDATE_CELL_SEPARATION &&
                    abs(cellY - takenY[k]) <= MIN_CANDIDATE_CELL_SEPARATION
                ) {
                    tooClose = true
                    break
                }
            }
            if (tooClose) continue
            var nW = 0.0
            var nWx = 0.0
            var nWy = 0.0
            var nWx2 = 0.0
            var nWy2 = 0.0
            var nCount = 0
            for (dy in -1..1) {
                val ny = cellY + dy
                if (ny !in 0 until CELLS_Y) continue
                for (dx in -1..1) {
                    val nx = cellX + dx
                    if (nx !in 0 until CELLS_X) continue
                    val n = ny * CELLS_X + nx
                    nW += sumW[n]
                    nWx += sumWx[n]
                    nWy += sumWy[n]
                    nWx2 += sumWx2[n]
                    nWy2 += sumWy2[n]
                    nCount += count[n]
                }
            }
            if (nW <= 0) continue
            if (nCount !in t.minPixels..t.maxPixels) {
                if (stats != null && vetoReports < MAX_VETO_REPORTS) {
                    vetoReports++
                    val reason = if (nCount < t.minPixels) VetoReason.TOO_SMALL else VetoReason.TOO_BIG
                    stats.onVeto(reason, nWx / nW, nWy / nW, nCount)
                }
                continue
            }
            val mx = nWx / nW
            val my = nWy / nW
            if (nCount >= ASPECT_MIN_PIXELS) {
                val sdX = sqrt((nWx2 / nW - mx * mx).coerceAtLeast(0.0))
                val sdY = sqrt((nWy2 / nW - my * my).coerceAtLeast(0.0))
                // Tall and thin: a person's or post's edge sweeping sideways. A ball is round,
                // or smeared horizontally along the track axis - never a vertical ribbon.
                if (sdY > VERTICAL_ASPECT_VETO * maxOf(sdX, 1.0)) {
                    if (stats != null && vetoReports < MAX_VETO_REPORTS) {
                        vetoReports++
                        stats.onVeto(VetoReason.VERTICAL, mx, my, nCount)
                    }
                    continue
                }
            }
            takenX[out.size] = cellX
            takenY[out.size] = cellY
            out.add(BlobCandidate(mx, my, nCount))
            if (out.size >= maxCandidates) break
        }
        return out
    }
}
