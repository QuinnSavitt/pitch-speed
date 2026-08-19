package com.pitchspeed.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Simulations of the reported failure environment: a backyard throw filmed against a sunlit
 * beige house and a bright partly-cloudy sky. These are the conditions where a luma-only diff
 * goes blind (white ball vs. equally-bright background), where tree shade drops the ball below
 * a brightness gate mid-flight, and where auto-exposure swings flood the frame with fake motion.
 * Frames carry Y, U and V planes like the real camera.
 */
class BackyardScenarioTest {

    private val w = 640
    private val h = 360
    private val fov = Math.toRadians(68.0)
    private val maxWindowNs = 900_000_000L

    private val medium = ScanThresholds(brightMin = 90, diffMin = 24, minPixels = 3, maxPixels = 600)
    private val high = ScanThresholds(brightMin = 50, diffMin = 12, minPixels = 1, maxPixels = 800)

    private data class Yuv(val y: ByteArray, val u: ByteArray, val v: ByteArray)

    private class YuvScene(
        val w: Int, val h: Int,
        val bgY: Int, val bgU: Int, val bgV: Int, seed: Int = 5
    ) {
        private val rng = Random(seed)
        fun render(
            ballX: Double?, ballY: Int, ballR: Int,
            ballLumaAt: (Double) -> Int, ballU: Int, ballV: Int,
            exposureShift: Int = 0, noise: Int = 3
        ): Yuv {
            val y = ByteArray(w * h)
            val u = ByteArray(w * h)
            val v = ByteArray(w * h)
            for (i in y.indices) {
                y[i] = (bgY + exposureShift + rng.nextInt(-noise, noise + 1)).coerceIn(0, 255).toByte()
                u[i] = bgU.toByte()
                v[i] = bgV.toByte()
            }
            if (ballX != null) {
                val cx = ballX.roundToInt()
                val luma = (ballLumaAt(ballX) + exposureShift).coerceIn(0, 255)
                for (py in (ballY - ballR)..(ballY + ballR)) for (px in (cx - ballR)..(cx + ballR)) {
                    if (px in 0 until w && py in 0 until h &&
                        (px - cx) * (px - cx) + (py - ballY) * (py - ballY) <= ballR * ballR
                    ) {
                        val i = py * w + px
                        y[i] = luma.toByte()
                        u[i] = 128.toByte() // a white/gray ball is chroma-neutral
                        v[i] = 128.toByte()
                    }
                }
            }
            return Yuv(y, u, v)
        }
    }

    private fun simulate(
        mph: Double, distanceFeet: Double, fps: Double, thresholds: ScanThresholds,
        scene: YuvScene, ballR: Int,
        ballLumaAt: (Double) -> Int, ballU: Int = 128, ballV: Int = 128,
        exposureShiftAt: (Int) -> Int = { 0 }
    ): PitchResult? {
        val distanceM = distanceFeet * 0.3048
        val spanM = 2.0 * distanceM * tan(fov / 2.0)
        val pxPerSec = (mph / SpeedMath.MPS_TO_MPH) / spanM * w
        val samples = mutableListOf<TrackSample>()
        var prev: Yuv? = null
        var mask: ByteArray? = null
        var frame = 0
        while (true) {
            val t = frame / fps
            val ballX = 5.0 + pxPerSec * t
            val inFlight = ballX < w - 5
            val tNs = (t * 1e9).toLong()
            val cur = scene.render(
                ballX = if (inFlight) ballX else null, ballY = h / 2, ballR = ballR,
                ballLumaAt = ballLumaAt, ballU = ballU, ballV = ballV,
                exposureShift = exposureShiftAt(frame)
            )
            prev?.let { p ->
                val curMask = ByteArray(FrameScanner.maskSize(w, h))
                FrameScanner.findCandidate(
                    cur.y, p.y, w, h, thresholds,
                    suppressMask = mask, outMovingMask = curMask,
                    curU = cur.u, prevU = p.u, curV = cur.v, prevV = p.v
                )?.let { c ->
                    samples.add(TrackSample(c.cx / w, tNs))
                    while (samples.isNotEmpty() && tNs - samples.first().tNs > maxWindowNs) {
                        samples.removeAt(0)
                    }
                }
                mask = curMask
            }
            prev = cur
            frame++
            if (!inFlight && frame > 3) break
            if (frame > 200) break
        }
        return SpeedMath.computeResult(samples, distanceM, fov)
    }

    @Test
    fun `white ball against sunlit beige siding - nearly zero luma contrast`() {
        // Ball luma 235 vs siding 225: a 10-point luma diff is invisible to the old detector.
        // The beige is warm (V high) while the ball is neutral — chroma carries the detection.
        val scene = YuvScene(w, h, bgY = 225, bgU = 116, bgV = 148)
        val r = simulate(
            mph = 40.0, distanceFeet = 18.0, fps = 30.0, thresholds = medium,
            scene = scene, ballR = 3, ballLumaAt = { 235 }
        )
        assertNotNull("white-on-beige must be detected via chroma", r)
        assertEquals(40.0, r!!.speedMph, 2.5)
    }

    @Test
    fun `white ball against bright blue sky - identical luma`() {
        val scene = YuvScene(w, h, bgY = 245, bgU = 165, bgV = 105)
        val r = simulate(
            mph = 50.0, distanceFeet = 20.0, fps = 30.0, thresholds = medium,
            scene = scene, ballR = 3, ballLumaAt = { 245 }
        )
        assertNotNull("white-on-sky must be detected via chroma", r)
        assertEquals(50.0, r!!.speedMph, 3.0)
    }

    @Test
    fun `ball dips through tree shade mid-flight`() {
        // Sunlit ball 230, shaded ball 80 for the middle third of the flight; grass bg.
        val scene = YuvScene(w, h, bgY = 120, bgU = 110, bgV = 112)
        val r = simulate(
            mph = 45.0, distanceFeet = 20.0, fps = 30.0, thresholds = high,
            scene = scene, ballR = 3,
            ballLumaAt = { x -> if (x > w / 3.0 && x < 2 * w / 3.0) 80 else 230 }
        )
        assertNotNull("shade crossing must not kill the track", r)
        assertEquals(45.0, r!!.speedMph, 3.0)
    }

    @Test
    fun `auto-exposure swings do not blind the detector`() {
        // Cloud passes: global luma ramps 8 points over a few frames while the ball flies.
        val scene = YuvScene(w, h, bgY = 140, bgU = 110, bgV = 112)
        val r = simulate(
            mph = 45.0, distanceFeet = 20.0, fps = 30.0, thresholds = medium,
            scene = scene, ballR = 3, ballLumaAt = { 230 },
            exposureShiftAt = { frame -> (frame % 4) * 8 } // repeated AE hunting, ±8-24
        )
        assertNotNull("exposure swings must be normalized away", r)
        assertEquals(45.0, r!!.speedMph, 3.0)
    }

    @Test
    fun `exposure swings alone produce no reading`() {
        val scene = YuvScene(w, h, bgY = 140, bgU = 110, bgV = 112)
        val distanceM = 20.0 * 0.3048
        val samples = mutableListOf<TrackSample>()
        var prev: Yuv? = null
        var mask: ByteArray? = null
        for (frame in 0 until 90) {
            val cur = scene.render(
                ballX = null, ballY = 0, ballR = 0, ballLumaAt = { 0 },
                ballU = 128, ballV = 128, exposureShift = (frame % 4) * 8
            )
            prev?.let { p ->
                val curMask = ByteArray(FrameScanner.maskSize(w, h))
                FrameScanner.findCandidate(
                    cur.y, p.y, w, h, high, suppressMask = mask, outMovingMask = curMask,
                    curU = cur.u, prevU = p.u, curV = cur.v, prevV = p.v
                )?.let { c ->
                    samples.add(TrackSample(c.cx / w, (frame / 30.0 * 1e9).toLong()))
                }
                mask = curMask
            }
            prev = cur
        }
        assertNull(SpeedMath.computeResult(samples, distanceM, fov))
    }

    @Test
    fun `tiny ball far away on high sensitivity`() {
        // Standing ~40 ft back: the ball is ~1 px in the scan grid.
        val scene = YuvScene(w, h, bgY = 120, bgU = 110, bgV = 112)
        val r = simulate(
            mph = 50.0, distanceFeet = 40.0, fps = 30.0, thresholds = high,
            scene = scene, ballR = 1, ballLumaAt = { 235 }
        )
        assertNotNull("distant small ball must be detected on HIGH", r)
        assertEquals(50.0, r!!.speedMph, 3.5)
    }
}
