package com.pitchspeed.app.tracking

import kotlin.math.abs

/** A compact moving blob found in one frame: centroid in scan-grid pixels, and its size. */
data class BlobCandidate(val cx: Double, val cy: Double, val pixelCount: Int)

data class ScanThresholds(
    val brightMin: Int,
    val diffMin: Int,
    val minPixels: Int,
    val maxPixels: Int
)

/**
 * Finds a ball-sized moving blob in a downsampled luma frame. Pure array math with no Android
 * dependencies so the whole detection path can be unit-tested on the JVM.
 *
 * Motion (frame-to-frame luma diff) is the primary signal; brightness is only a floor to reject
 * shadow/noise flicker, low enough that a colored or dimly-lit ball still passes. To survive a
 * moving person in frame, passing pixels are bucketed into coarse energy cells; the candidates
 * are the highest-energy cells, and the first one whose 3x3-cell neighborhood contains a
 * ball-plausible number of moving pixels wins. A person's swing lights up a big region (too many
 * pixels -> that cell is skipped), camera shake lights up everything (every neighborhood too big
 * -> no candidate), while a ball stays a small tight cluster somewhere else in the frame.
 */
object FrameScanner {

    private const val CELLS_X = 16
    private const val CELLS_Y = 9
    private const val BLOCK = 8 // px; granularity of the stationary-flicker suppression mask

    fun maskSize(w: Int, h: Int): Int = ((w + BLOCK - 1) / BLOCK) * ((h + BLOCK - 1) / BLOCK)

    /**
     * @param curU/prevU/curV/prevV optional chroma planes sampled on the same grid as the luma
     *        arrays. Color change counts toward the motion score, so a white ball crossing an
     *        equally-bright blue sky — invisible to a luma diff — is still detected, because the
     *        sky is strongly blue where the ball is neutral.
     * @param suppressMask blocks (see [maskSize]) that contained motion in the PREVIOUS frame
     *        pair. Motion recurring in the same block is stationary flicker — a person shifting
     *        their weight, swaying leaves, a flapping edge — never a ball, which lands somewhere
     *        new every frame. Suppressed pixels are excluded from candidates but still recorded
     *        in [outMovingMask].
     * @param outMovingMask filled with this frame pair's moving blocks; pass it back as
     *        [suppressMask] on the next call.
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
    ): BlobCandidate? {
        if (cur.size < w * h || prev.size < w * h) return null
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
        val count = IntArray(nCells)

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
                if (suppress != null && suppress[b].toInt() != 0) continue
                val c = cellRow + x / cellW
                val wgt = diff.toDouble()
                sumW[c] += wgt
                sumWx[c] += wgt * x
                sumWy[c] += wgt * y
                count[c]++
            }
        }

        // Walk every active cell from most to least motion energy: a moving person can light
        // up dozens of cells (each rejected below for being too big), and the ball's dimmer,
        // tighter cluster must still get its turn.
        val order = (0 until nCells).sortedByDescending { sumW[it] }
        for (c in order) {
            if (sumW[c] <= 0.0) break
            val cellX = c % CELLS_X
            val cellY = c / CELLS_X
            var nW = 0.0
            var nWx = 0.0
            var nWy = 0.0
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
                    nCount += count[n]
                }
            }
            if (nCount in t.minPixels..t.maxPixels && nW > 0) {
                return BlobCandidate(nWx / nW, nWy / nW, nCount)
            }
        }
        return null
    }
}
