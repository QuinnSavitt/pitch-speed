package com.pitchspeed.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlin.random.Random

/**
 * End-to-end simulation of the detection pipeline: renders synthetic camera frames of a ball
 * crossing the frame at a known real-world speed, runs every frame through [FrameScanner]
 * exactly like [PitchAnalyzer] does (including its sliding sample window), and checks that
 * [SpeedMath] recovers the true speed. This is the closest a JVM test can get to a field test.
 */
class PipelineSimulationTest {

    private val w = 640 // scanner-space frame (1280x720 stream scanned at step 2)
    private val h = 360
    private val fov = Math.toRadians(68.0)
    private val maxWindowNs = 900_000_000L

    private class Scene(val w: Int, val h: Int, val backgroundLuma: Int, seed: Int = 11) {
        private val rng = Random(seed)
        fun render(
            ballX: Double?, ballY: Int, ballR: Int, ballLuma: Int,
            personX: Int? = null, personLuma: Int = 200, noise: Int = 3
        ): ByteArray {
            val f = ByteArray(w * h)
            for (i in f.indices) {
                f[i] = (backgroundLuma + rng.nextInt(-noise, noise + 1)).coerceIn(0, 255).toByte()
            }
            if (personX != null) { // torso-sized moving region, like the thrower
                for (y in 60 until h) for (x in personX until (personX + 90).coerceAtMost(w)) {
                    f[y * w + x] = personLuma.toByte()
                }
            }
            if (ballX != null) {
                val cx = ballX.roundToInt()
                for (y in (ballY - ballR)..(ballY + ballR)) for (x in (cx - ballR)..(cx + ballR)) {
                    if (x in 0 until w && y in 0 until h &&
                        (x - cx) * (x - cx) + (y - ballY) * (y - ballY) <= ballR * ballR
                    ) f[y * w + x] = ballLuma.toByte()
                }
            }
            return f
        }
    }

    /** Runs a rendered throw through scanner + windowing + SpeedMath, like PitchAnalyzer. */
    private fun simulateThrow(
        mph: Double, distanceFeet: Double, fps: Double, thresholds: ScanThresholds,
        ballR: Int, ballLuma: Int, backgroundLuma: Int, withPerson: Boolean = false
    ): PitchResult? {
        val distanceM = distanceFeet * 0.3048
        val spanM = 2.0 * distanceM * tan(fov / 2.0)
        val pxPerSec = (mph / SpeedMath.MPS_TO_MPH) / spanM * w
        val scene = Scene(w, h, backgroundLuma)
        val samples = mutableListOf<TrackSample>()
        var prev: ByteArray? = null
        var mask: ByteArray? = null
        var frame = 0
        while (true) {
            val t = frame / fps
            val ballX = 5.0 + pxPerSec * t
            val inFlight = ballX < w - 5
            val tNs = (t * 1e9).toLong()
            val cur = scene.render(
                ballX = if (inFlight) ballX else null, ballY = h / 2, ballR = ballR,
                ballLuma = ballLuma,
                personX = if (withPerson) (frame % 8) else null
            )
            prev?.let { p ->
                val curMask = ByteArray(FrameScanner.maskSize(w, h))
                FrameScanner.findCandidate(
                    cur, p, w, h, thresholds, suppressMask = mask, outMovingMask = curMask
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

    private val high = ScanThresholds(brightMin = 50, diffMin = 12, minPixels = 2, maxPixels = 800)
    private val medium = ScanThresholds(brightMin = 110, diffMin = 24, minPixels = 3, maxPixels = 600)

    @Test
    fun `indoor stress ball - dim, soft 10 mph toss at 10 ft, high sensitivity`() {
        // The exact scenario reported from the field: colored foam ball, indoor light.
        val r = simulateThrow(
            mph = 10.0, distanceFeet = 10.0, fps = 24.0, thresholds = high,
            ballR = 5, ballLuma = 120, backgroundLuma = 85
        )
        assertNotNull("stress ball toss must produce a reading", r)
        assertEquals(10.0, r!!.speedMph, 1.0)
    }

    @Test
    fun `indoor stress ball works on MEDIUM too when ball is lighter than walls`() {
        val r = simulateThrow(
            mph = 12.0, distanceFeet = 10.0, fps = 30.0, thresholds = medium,
            ballR = 5, ballLuma = 150, backgroundLuma = 85
        )
        assertNotNull(r)
        assertEquals(12.0, r!!.speedMph, 1.2)
    }

    @Test
    fun `outdoor baseball - 55 mph at 20 ft in daylight`() {
        val r = simulateThrow(
            mph = 55.0, distanceFeet = 20.0, fps = 30.0, thresholds = medium,
            ballR = 2, ballLuma = 230, backgroundLuma = 100
        )
        assertNotNull(r)
        assertEquals(55.0, r!!.speedMph, 3.0)
    }

    @Test
    fun `outdoor fastball - 85 mph at 25 ft`() {
        val r = simulateThrow(
            mph = 85.0, distanceFeet = 25.0, fps = 30.0, thresholds = medium,
            ballR = 2, ballLuma = 230, backgroundLuma = 100
        )
        assertNotNull(r)
        assertEquals(85.0, r!!.speedMph, 5.0)
    }

    @Test
    fun `ball is tracked while the thrower moves in frame`() {
        val r = simulateThrow(
            mph = 45.0, distanceFeet = 20.0, fps = 30.0, thresholds = medium,
            ballR = 3, ballLuma = 230, backgroundLuma = 100, withPerson = true
        )
        assertNotNull("moving person must not mask the ball", r)
        assertEquals(45.0, r!!.speedMph, 3.0)
    }

    @Test
    fun `no ball means no reading`() {
        val distanceM = 20.0 * 0.3048
        val scene = Scene(w, h, 100)
        val samples = mutableListOf<TrackSample>()
        var prev: ByteArray? = null
        for (frame in 0 until 60) {
            val cur = scene.render(ballX = null, ballY = 0, ballR = 0, ballLuma = 0)
            prev?.let { p ->
                FrameScanner.findCandidate(cur, p, w, h, high)?.let { c ->
                    samples.add(TrackSample(c.cx / w, (frame / 30.0 * 1e9).toLong()))
                }
            }
            prev = cur
        }
        org.junit.Assert.assertNull(SpeedMath.computeResult(samples, distanceM, fov))
    }
}
