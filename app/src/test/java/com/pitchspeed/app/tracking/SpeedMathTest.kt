package com.pitchspeed.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan
import kotlin.random.Random

class SpeedMathTest {

    private val distance20ftMeters = 20.0 * 0.3048
    private val fov68 = Math.toRadians(68.0)

    /** Frame width in meters at the flight path, for converting a real speed into xNorm/sec. */
    private fun frameSpan(distanceM: Double, fov: Double, fraction: Double = 1.0) =
        2.0 * distanceM * tan(fov / 2.0) * fraction

    /**
     * Builds the samples a ball moving at [mph] would produce: position advances linearly
     * across the frame at 30 fps, entering at xNorm 0.1.
     */
    private fun ballSweep(
        mph: Double,
        distanceM: Double = distance20ftMeters,
        fov: Double = fov68,
        fps: Double = 30.0,
        startX: Double = 0.1,
        noise: Double = 0.0,
        seed: Int = 42
    ): List<TrackSample> {
        val mps = mph / SpeedMath.MPS_TO_MPH
        val xPerSec = mps / frameSpan(distanceM, fov)
        val rng = Random(seed)
        val samples = mutableListOf<TrackSample>()
        var i = 0
        while (true) {
            val t = i / fps
            val x = startX + xPerSec * t
            if (x > 0.95) break
            val jitter = if (noise > 0) (rng.nextDouble() * 2 - 1) * noise else 0.0
            samples.add(TrackSample(x + jitter, (t * 1e9).toLong()))
            i++
        }
        return samples
    }

    @Test
    fun `recovers a 50 mph throw exactly from clean samples`() {
        val result = SpeedMath.computeResult(ballSweep(50.0), distance20ftMeters, fov68)
        assertNotNull(result)
        assertEquals(50.0, result!!.speedMph, 0.5)
    }

    @Test
    fun `recovers a slow 25 mph lob`() {
        val result = SpeedMath.computeResult(ballSweep(25.0), distance20ftMeters, fov68)
        assertNotNull(result)
        assertEquals(25.0, result!!.speedMph, 0.5)
    }

    @Test
    fun `recovers a 90 mph fastball from the few frames it is visible`() {
        val samples = ballSweep(90.0)
        assertTrue("fastball must still yield >=3 frames", samples.size >= 3)
        val result = SpeedMath.computeResult(samples, distance20ftMeters, fov68)
        assertNotNull(result)
        assertEquals(90.0, result!!.speedMph, 1.0)
    }

    @Test
    fun `centroid noise averages out within 10 percent`() {
        val result = SpeedMath.computeResult(
            ballSweep(55.0, noise = 0.01), distance20ftMeters, fov68
        )
        assertNotNull(result)
        assertEquals(55.0, result!!.speedMph, 5.5)
    }

    @Test
    fun `portrait fallback scales by the track axis fraction`() {
        // Same physical throw tracked along a 9:16 portrait axis: xNorm/sec is faster because
        // the axis spans less of the world, and trackAxisFraction must undo exactly that.
        val fraction = 720.0 / 1280.0
        val mps = 50.0 / SpeedMath.MPS_TO_MPH
        val xPerSec = mps / frameSpan(distance20ftMeters, fov68, fraction)
        val samples = (0..8).map { i ->
            val t = i / 30.0
            TrackSample(0.1 + xPerSec * t, (t * 1e9).toLong())
        }.filter { it.xNorm <= 0.95 }
        val result = SpeedMath.computeResult(samples, distance20ftMeters, fov68, fraction)
        assertNotNull(result)
        assertEquals(50.0, result!!.speedMph, 0.5)
    }

    @Test
    fun `rejects tracks with too few samples`() {
        val samples = ballSweep(50.0).take(2)
        assertNull(SpeedMath.computeResult(samples, distance20ftMeters, fov68))
    }

    @Test
    fun `rejects a sweep across too little of the frame`() {
        val short = ballSweep(50.0).let { s ->
            s.filter { it.xNorm < s.first().xNorm + 0.10 }
        }
        assertNull(SpeedMath.computeResult(short, distance20ftMeters, fov68))
    }

    @Test
    fun `rejects speeds outside the plausible pitch range`() {
        assertNull(SpeedMath.computeResult(ballSweep(4.0), distance20ftMeters, fov68))
        assertNotNull(SpeedMath.computeResult(ballSweep(8.0), distance20ftMeters, fov68))
        assertNull(SpeedMath.computeResult(ballSweep(150.0), distance20ftMeters, fov68))
    }

    @Test
    fun `rejects an erratic multi-object track`() {
        // Positions bouncing back and forth (arm swing + ball in the same frame region):
        // the least-squares slope and endpoint speed disagree wildly.
        val samples = listOf(
            TrackSample(0.20, 0L),
            TrackSample(0.70, 33_000_000L),
            TrackSample(0.25, 66_000_000L),
            TrackSample(0.75, 99_000_000L),
            TrackSample(0.30, 132_000_000L),
            TrackSample(0.80, 165_000_000L)
        )
        assertNull(SpeedMath.computeResult(samples, distance20ftMeters, fov68))
    }

    @Test
    fun `right to left throws read the same as left to right`() {
        val ltr = SpeedMath.computeResult(ballSweep(45.0), distance20ftMeters, fov68)
        val rtl = SpeedMath.computeResult(
            ballSweep(45.0).map { it.copy(xNorm = 1.0 - it.xNorm) },
            distance20ftMeters, fov68
        )
        assertNotNull(ltr)
        assertNotNull(rtl)
        assertEquals(ltr!!.speedMph, rtl!!.speedMph, 0.01)
    }

    @Test
    fun `speed scales linearly with entered distance`() {
        val at20 = SpeedMath.computeResult(ballSweep(40.0), distance20ftMeters, fov68)!!
        val at40 = SpeedMath.computeResult(ballSweep(40.0), distance20ftMeters * 2, fov68)!!
        assertEquals(at20.speedMph * 2, at40.speedMph, 0.1)
    }

    @Test
    fun `confidence is higher for longer cleaner tracks`() {
        val clean = SpeedMath.computeResult(ballSweep(30.0), distance20ftMeters, fov68)!!
        val short = SpeedMath.computeResult(
            ballSweep(85.0), distance20ftMeters, fov68
        )!!
        assertTrue(clean.confidence >= short.confidence)
    }
}
