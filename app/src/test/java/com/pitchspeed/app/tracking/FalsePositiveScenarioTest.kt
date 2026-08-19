package com.pitchspeed.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Simulations of the reported false-positive failure: filming a throw in a bright backyard with
 * other people moving in or near the shot. The app was reporting speeds for those people instead
 * of the ball.
 *
 * Two things made that happen. A person in a red shirt on green grass is an enormous chroma
 * signal, so their leading/trailing edge outranked the ball on motion energy and, since the
 * scanner only ever reported its single loudest blob, the ball was never even sampled. And every
 * blob fed one shared sample list, so whoever moved first owned the track.
 *
 * The fix is shape plus separation: a person's edge is a tall thin vertical ribbon and is vetoed
 * on aspect ratio, while a ball is round or smeared horizontally along its flight; and the
 * scanner now reports several blobs per frame into independent [TrackSet] tracks so the ball can
 * build a clean line of its own even when junk got there first.
 */
class FalsePositiveScenarioTest {

    private val w = 640
    private val h = 360
    private val fov = Math.toRadians(68.0)

    private val medium = ScanThresholds(brightMin = 90, diffMin = 24, minPixels = 3, maxPixels = 600)

    private data class Yuv(val y: ByteArray, val u: ByteArray, val v: ByteArray)

    /** A person: an upright bright rectangle, chroma-loud like a red shirt over green grass. */
    private data class Person(
        val x: Double, val width: Int, val top: Int, val bottom: Int,
        val lumaY: Int, val u: Int, val v: Int
    )

    /** A ball: [rx] > [ry] renders the horizontal smear of a fast ball on a slow shutter. */
    private data class Ball(
        val x: Double, val y: Int, val rx: Int, val ry: Int,
        val lumaY: Int, val u: Int = 128, val v: Int = 128
    )

    private class YuvScene(
        val w: Int, val h: Int,
        val bgY: Int, val bgU: Int, val bgV: Int, seed: Int = 5
    ) {
        private val rng = Random(seed)
        fun render(person: Person?, ball: Ball?, noise: Int = 3): Yuv {
            val y = ByteArray(w * h)
            val u = ByteArray(w * h)
            val v = ByteArray(w * h)
            for (i in y.indices) {
                y[i] = (bgY + rng.nextInt(-noise, noise + 1)).coerceIn(0, 255).toByte()
                u[i] = bgU.toByte()
                v[i] = bgV.toByte()
            }
            if (person != null) {
                val x0 = person.x.roundToInt()
                for (py in person.top until person.bottom) {
                    for (px in x0 until x0 + person.width) {
                        if (px !in 0 until w || py !in 0 until h) continue
                        val i = py * w + px
                        y[i] = person.lumaY.toByte()
                        u[i] = person.u.toByte()
                        v[i] = person.v.toByte()
                    }
                }
            }
            if (ball != null) {
                val cx = ball.x.roundToInt()
                for (py in (ball.y - ball.ry)..(ball.y + ball.ry)) {
                    for (px in (cx - ball.rx)..(cx + ball.rx)) {
                        if (px !in 0 until w || py !in 0 until h) continue
                        val dx = (px - cx).toDouble() / ball.rx
                        val dy = (py - ball.y).toDouble() / ball.ry
                        if (dx * dx + dy * dy > 1.0) continue
                        val i = py * w + px
                        y[i] = ball.lumaY.toByte()
                        u[i] = ball.u.toByte()
                        v[i] = ball.v.toByte()
                    }
                }
            }
            return Yuv(y, u, v)
        }
    }

    /**
     * Runs rendered frames through the exact pipeline [PitchAnalyzer] uses: multi-candidate
     * scanning, concurrent tracks, stale-gap finalization, cooldown. Returns every reading the
     * app would have shown.
     */
    private fun runPipeline(
        distanceFeet: Double, fps: Double, frames: Int, thresholds: ScanThresholds,
        render: (Int) -> Yuv
    ): List<PitchResult> {
        val distanceM = distanceFeet * 0.3048
        val tracks = TrackSet()
        val readings = mutableListOf<PitchResult>()
        var prev: Yuv? = null
        var mask: ByteArray? = null
        var cooldownUntilNs = 0L
        var lastTNs = 0L
        for (frame in 0 until frames) {
            val tNs = (frame / fps * 1e9).toLong()
            lastTNs = tNs
            val cur = render(frame)
            val p = prev
            prev = cur
            if (p == null) continue
            if (tNs < cooldownUntilNs) continue
            val curMask = ByteArray(FrameScanner.maskSize(w, h))
            val candidates = FrameScanner.findCandidates(
                cur.y, p.y, w, h, thresholds,
                suppressMask = mask, outMovingMask = curMask,
                curU = cur.u, prevU = p.u, curV = cur.v, prevV = p.v
            )
            mask = curMask
            var fired = false
            for (track in tracks.takeStale(tNs)) {
                val r = SpeedMath.computeResult(track, distanceM, fov) ?: continue
                readings.add(r)
                tracks.clear()
                mask = null
                cooldownUntilNs = tNs + 1_400_000_000L
                fired = true
                break
            }
            if (!fired && candidates.isNotEmpty()) {
                tracks.addFrame(candidates.map { it.cx / w }, tNs)
            }
        }
        // Flush whatever is still open, as the analyzer would on the next quiet frame.
        for (track in tracks.takeStale(lastTNs + 1_000_000_000L)) {
            SpeedMath.computeResult(track, distanceM, fov)?.let { readings.add(it) }
        }
        return readings
    }

    /** px/frame of a ball travelling [mph] across a frame [distanceFeet] away. */
    private fun ballPxPerFrame(mph: Double, distanceFeet: Double, fps: Double): Double {
        val spanM = 2.0 * (distanceFeet * 0.3048) * tan(fov / 2.0)
        return (mph / SpeedMath.MPS_TO_MPH) / spanM * w / fps
    }

    @Test
    fun `a person walking across frame produces no reading`() {
        // Red shirt on green grass: the chroma diff at their edges dwarfs anything the ball
        // makes, and filming from 60 ft back their stroll would score as a believable ~8 mph
        // "pitch" if the detector let it build a track. It must not.
        val scene = YuvScene(w, h, bgY = 120, bgU = 110, bgV = 112)
        val render = { frame: Int ->
            scene.render(
                person = Person(
                    x = 60.0 + 3.0 * frame, width = 70, top = 40, bottom = 330,
                    lumaY = 140, u = 100, v = 180
                ),
                ball = null
            )
        }
        val readings = runPipeline(
            distanceFeet = 60.0, fps = 30.0, frames = 100, thresholds = medium, render = render
        )
        assertTrue("a walking person must not read as a pitch, got $readings", readings.isEmpty())

        // Stronger than "no reading": the person must not even reach the tracker. Their edges
        // are the loudest thing in the frame, so before the aspect veto they were sampled on
        // every single frame and the ball had nowhere to go.
        var framesWithBlob = 0
        var prev: Yuv? = null
        var mask: ByteArray? = null
        for (frame in 0 until 100) {
            val cur = render(frame)
            prev?.let { p ->
                val curMask = ByteArray(FrameScanner.maskSize(w, h))
                val found = FrameScanner.findCandidates(
                    cur.y, p.y, w, h, medium, suppressMask = mask, outMovingMask = curMask,
                    curU = cur.u, prevU = p.u, curV = cur.v, prevV = p.v
                )
                if (found.isNotEmpty()) framesWithBlob++
                mask = curMask
            }
            prev = cur
        }
        // Before the aspect veto this was every frame; a handful of stragglers (where the body
        // clips a cell boundary into a squarish chunk) can never accumulate into a track.
        assertTrue(
            "a person's edges must be vetoed on shape, but blobs survived on $framesWithBlob frames",
            framesWithBlob <= 5
        )
    }

    @Test
    fun `ball is read correctly while a person walks through the shot`() {
        val scene = YuvScene(w, h, bgY = 120, bgU = 110, bgV = 112)
        val mph = 45.0
        val distanceFeet = 20.0
        val perFrame = ballPxPerFrame(mph, distanceFeet, 30.0)
        val readings = runPipeline(
            distanceFeet = distanceFeet, fps = 30.0, frames = 60, thresholds = medium
        ) { frame ->
            // The person is already walking before the throw, so they own the frame first.
            val ballX = 5.0 + perFrame * (frame - 8)
            scene.render(
                person = Person(
                    x = 40.0 + 3.0 * frame, width = 70, top = 40, bottom = 330,
                    lumaY = 140, u = 100, v = 180
                ),
                ball = if (frame >= 8 && ballX < w - 5) {
                    Ball(x = ballX, y = 110, rx = 3, ry = 3, lumaY = 235)
                } else null
            )
        }
        assertTrue("the throw must produce a reading, got none", readings.isNotEmpty())
        val best = readings.minByOrNull { kotlin.math.abs(it.speedMph - mph) }
        assertNotNull(best)
        assertEquals(
            "must report the ball's speed, not the person's; got $readings",
            mph, best!!.speedMph, 3.0
        )
    }

    @Test
    fun `a horizontally motion-blurred ball is still detected`() {
        // Fast ball on a slow shutter: a streak 16 px long and 4 px tall. The vertical-aspect
        // veto that kills a person's edge must never touch a blob smeared along the flight path.
        val scene = YuvScene(w, h, bgY = 120, bgU = 110, bgV = 112)
        val mph = 60.0
        val distanceFeet = 25.0
        val perFrame = ballPxPerFrame(mph, distanceFeet, 30.0)
        val readings = runPipeline(
            distanceFeet = distanceFeet, fps = 30.0, frames = 40, thresholds = medium
        ) { frame ->
            val ballX = 15.0 + perFrame * frame
            scene.render(
                person = null,
                ball = if (ballX < w - 15) {
                    Ball(x = ballX, y = h / 2, rx = 8, ry = 2, lumaY = 235)
                } else null
            )
        }
        assertTrue("a blurred streak is still a ball, got no reading", readings.isNotEmpty())
        assertEquals(mph, readings.first().speedMph, 4.0)
    }

    @Test
    fun `scanner reports several distinct blobs and vetoes the tall thin one`() {
        // Direct check on the two new scanner behaviours, independent of the fit.
        val scene = YuvScene(w, h, bgY = 120, bgU = 110, bgV = 112)
        val a = scene.render(
            person = Person(x = 80.0, width = 70, top = 40, bottom = 330, lumaY = 140, u = 100, v = 180),
            ball = Ball(x = 400.0, y = 120, rx = 3, ry = 3, lumaY = 235)
        )
        val b = scene.render(
            person = Person(x = 92.0, width = 70, top = 40, bottom = 330, lumaY = 140, u = 100, v = 180),
            ball = Ball(x = 460.0, y = 120, rx = 3, ry = 3, lumaY = 235)
        )
        val found = FrameScanner.findCandidates(b.y, a.y, w, h, medium, curU = b.u, prevU = a.u, curV = b.v, prevV = a.v)
        assertTrue("the ball must be among the reported blobs, got $found", found.any {
            kotlin.math.abs(it.cx - 460.0) < 25.0 && kotlin.math.abs(it.cy - 120.0) < 25.0
        })
        assertTrue("no blob may sit on the person's vertical edges, got $found", found.none { it.cx < 200.0 })
    }
}
