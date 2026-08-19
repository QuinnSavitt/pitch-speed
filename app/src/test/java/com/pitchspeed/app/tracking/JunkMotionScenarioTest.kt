package com.pitchspeed.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/**
 * Simulations of the reported over-sensitivity: "wayyyy too sensitive, picking up every tiny
 * thing but somehow not picking up the ball."
 *
 * The scene here is a backyard full of small junk motion — specks of sensor noise, leaves
 * catching light, a bug crossing the lens, glints that live for two or three frames and vanish.
 * Individually none of them is ball-shaped, but there are enough of them that a few will always
 * land near a straight line by luck, and the old pipeline (three samples, no direction check, no
 * size check, two track slots) would occasionally fit one and report a speed. Worse, two specks
 * could hold both track slots for the entire session, so the real throw never got one.
 *
 * The physics gates in [SpeedMath] are what separate the two cases: a thrown ball crosses the
 * frame one way without ever reversing, holds a steady apparent size, and leaves at least four
 * samples of evidence behind. Junk fails at least one of those every time.
 */
class JunkMotionScenarioTest {

    private val w = 640
    private val h = 360
    private val fov = Math.toRadians(68.0)

    private val medium = ScanThresholds(brightMin = 90, diffMin = 24, minPixels = 3, maxPixels = 600)

    private data class Yuv(val y: ByteArray, val u: ByteArray, val v: ByteArray)

    /** Anything drawn into a frame: a ball, a speck of junk, an oscillating blob. */
    private data class Blob(
        val x: Double, val y: Double, val rx: Int, val ry: Int,
        val lumaY: Int, val u: Int = 128, val v: Int = 128
    )

    private class Scene(val w: Int, val h: Int, val bgY: Int, val bgU: Int, val bgV: Int, seed: Int = 7) {
        private val rng = Random(seed)
        fun render(blobs: List<Blob>, noise: Int = 3): Yuv {
            val y = ByteArray(w * h)
            val u = ByteArray(w * h)
            val v = ByteArray(w * h)
            for (i in y.indices) {
                y[i] = (bgY + rng.nextInt(-noise, noise + 1)).coerceIn(0, 255).toByte()
                u[i] = bgU.toByte()
                v[i] = bgV.toByte()
            }
            for (b in blobs) {
                val cx = b.x.roundToInt()
                val cy = b.y.roundToInt()
                for (py in (cy - b.ry)..(cy + b.ry)) {
                    for (px in (cx - b.rx)..(cx + b.rx)) {
                        if (px !in 0 until w || py !in 0 until h) continue
                        val dx = (px - cx).toDouble() / maxOf(b.rx, 1)
                        val dy = (py - cy).toDouble() / maxOf(b.ry, 1)
                        if (dx * dx + dy * dy > 1.0) continue
                        val i = py * w + px
                        y[i] = b.lumaY.toByte()
                        u[i] = b.u.toByte()
                        v[i] = b.v.toByte()
                    }
                }
            }
            return Yuv(y, u, v)
        }
    }

    /**
     * A backyard's worth of small transient motion. Each speck lives a few frames, some drift
     * while they live, and new ones keep appearing somewhere else — the "every tiny thing" the
     * tester was seeing.
     */
    private class JunkStorm(seed: Int, private val w: Int, private val h: Int) {
        private val rng = Random(seed)

        private class Speck(
            var x: Double, var y: Double,
            val rx: Int, val ry: Int,
            val vx: Double, val vy: Double,
            var life: Int, val luma: Int
        )

        private val live = mutableListOf<Speck>()

        fun step(): List<Blob> {
            val it = live.iterator()
            while (it.hasNext()) {
                val s = it.next()
                s.x += s.vx
                s.y += s.vy
                s.life--
                if (s.life <= 0 || s.x < 0 || s.x >= w || s.y < 0 || s.y >= h) it.remove()
            }
            repeat(rng.nextInt(0, 3)) {
                val drifts = rng.nextInt(3) == 0 // a third of them wander before they vanish
                live.add(
                    Speck(
                        x = rng.nextDouble() * w,
                        y = rng.nextDouble() * h,
                        rx = rng.nextInt(1, 4),
                        ry = rng.nextInt(1, 4),
                        vx = if (drifts) rng.nextDouble(-9.0, 9.0) else 0.0,
                        vy = if (drifts) rng.nextDouble(-5.0, 5.0) else 0.0,
                        life = rng.nextInt(2, 6),
                        luma = rng.nextInt(150, 215)
                    )
                )
            }
            return live.map { Blob(it.x, it.y, it.rx, it.ry, it.luma) }
        }
    }

    private data class Outcome(
        val readings: List<PitchResult>,
        val rejections: List<RejectionReason>
    )

    /**
     * Runs rendered frames through the exact pipeline [PitchAnalyzer] uses — multi-candidate
     * scanning, concurrent tracks with sizes attached, stale-gap finalization, cooldown — and
     * reports both what it would have shown and which gate stopped everything else.
     */
    private fun runPipeline(
        distanceFeet: Double, fps: Double, frames: Int, thresholds: ScanThresholds,
        render: (Int) -> Yuv
    ): Outcome {
        val distanceM = distanceFeet * 0.3048
        val tracks = TrackSet()
        val readings = mutableListOf<PitchResult>()
        val rejections = mutableListOf<RejectionReason>()
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
                val verdict = SpeedMath.computeResultDetailed(track, distanceM, fov)
                val r = verdict.result
                if (r == null) {
                    verdict.rejection?.let { rejections.add(it) }
                    continue
                }
                readings.add(r)
                tracks.clear()
                mask = null
                cooldownUntilNs = tNs + 1_400_000_000L
                fired = true
                break
            }
            if (!fired && candidates.isNotEmpty()) {
                tracks.addFrame(
                    candidates.map { it.cx / w },
                    tNs,
                    candidates.map { it.pixelCount }
                )
            }
        }
        // Flush whatever is still open, as the analyzer would on the next quiet frame.
        for (track in tracks.takeStale(lastTNs + 1_000_000_000L)) {
            val verdict = SpeedMath.computeResultDetailed(track, distanceM, fov)
            val r = verdict.result
            if (r != null) readings.add(r) else verdict.rejection?.let { rejections.add(it) }
        }
        return Outcome(readings, rejections)
    }

    /** px/frame of a ball travelling [mph] across a frame [distanceFeet] away. */
    private fun ballPxPerFrame(mph: Double, distanceFeet: Double, fps: Double): Double {
        val spanM = 2.0 * (distanceFeet * 0.3048) * tan(fov / 2.0)
        return (mph / SpeedMath.MPS_TO_MPH) / spanM * w / fps
    }

    @Test
    fun `a storm of tiny junk motion produces no reading`() {
        // Swept across many storms, not one lucky one: with a single seed it is easy to land on
        // an arrangement that happens to contain no near-linear run of specks, and the gates look
        // stronger than they are. Every one of these storms must come back silent.
        val offenders = mutableListOf<String>()
        var tracksScored = 0
        for (seed in 1..12) {
            val scene = Scene(w, h, bgY = 120, bgU = 110, bgV = 112)
            val storm = JunkStorm(seed = seed, w = w, h = h)
            val outcome = runPipeline(
                distanceFeet = 20.0, fps = 30.0, frames = 150, thresholds = medium
            ) { scene.render(storm.step()) }
            tracksScored += outcome.rejections.size
            if (outcome.readings.isNotEmpty()) offenders.add("seed " + seed + " -> " + outcome.readings)
        }

        assertTrue("specks and glints must never read as a pitch: " + offenders, offenders.isEmpty())
        // The junk did reach the tracker - this is a test of the gates, not of an empty scene.
        assertTrue(
            "the junk must actually have built tracks for the gates to reject, got " + tracksScored,
            tracksScored >= 20
        )
    }

    @Test
    fun `a real throw is still read correctly through the junk storm`() {
        val scene = Scene(w, h, bgY = 120, bgU = 110, bgV = 112)
        val storm = JunkStorm(seed = 31, w = w, h = h)
        val mph = 50.0
        val distanceFeet = 20.0
        val perFrame = ballPxPerFrame(mph, distanceFeet, 30.0)
        val throwStart = 40
        val outcome = runPipeline(
            distanceFeet = distanceFeet, fps = 30.0, frames = 150, thresholds = medium
        ) { frame ->
            val junk = storm.step()
            val ballX = 8.0 + perFrame * (frame - throwStart)
            val ball = if (frame >= throwStart && ballX < w - 8) {
                Blob(ballX, h / 2.0, rx = 3, ry = 3, lumaY = 235)
            } else null
            scene.render(if (ball != null) junk + ball else junk)
        }

        assertTrue(
            "the throw must survive the junk and produce a reading",
            outcome.readings.isNotEmpty()
        )
        val best = outcome.readings.minByOrNull { abs(it.speedMph - mph) }
        assertNotNull(best)
        assertEquals(
            "must report the ball's speed, not a speck's; got ${outcome.readings}",
            mph, best!!.speedMph, 3.0
        )
    }

    @Test
    fun `a fast direction-reversing oscillator produces no reading`() {
        // Something swinging back and forth across a third of the frame many times a second: a
        // branch in wind, a flag, a hand fidgeting near the camera. It sweeps far enough and
        // fast enough to look like a pitch on every gate EXCEPT the one that matters - a thrown
        // ball never comes back.
        val scene = Scene(w, h, bgY = 120, bgU = 110, bgV = 112)
        val periodFrames = 8.0
        val outcome = runPipeline(
            distanceFeet = 20.0, fps = 30.0, frames = 150, thresholds = medium
        ) { frame ->
            val phase = 2.0 * Math.PI * frame / periodFrames
            val x = w * (0.5 + 0.16 * sin(phase))
            scene.render(listOf(Blob(x, h / 2.0, rx = 3, ry = 3, lumaY = 235)))
        }

        assertTrue(
            "an oscillator must never read as a pitch, got ${outcome.readings}",
            outcome.readings.isEmpty()
        )
        assertTrue(
            "the oscillator must actually have been tracked and then rejected",
            outcome.rejections.isNotEmpty()
        )
    }

    @Test
    fun `the direction gate names itself when a track doubles back`() {
        // Direct check on the gate, independent of rendering: a sweep that is fast, long and
        // sample-rich, but turns around halfway.
        val distanceM = 20.0 * 0.3048
        val out = mutableListOf<TrackSample>()
        var x = 0.10
        for (i in 0 until 10) {
            out.add(TrackSample(x, (i / 30.0 * 1e9).toLong(), pixelCount = 9))
            x += if (i < 5) 0.09 else -0.09
        }
        val verdict = SpeedMath.computeResultDetailed(out, distanceM, fov)
        assertEquals(null, verdict.result)
        assertNotNull(verdict.rejection)
    }

    @Test
    fun `the size gate rejects a track stitched from wildly different blobs`() {
        // A clean, monotonic, fast sweep - but the "object" is a 2-pixel speck one frame and a
        // 90-pixel splash of glare the next. That is not one thing flying.
        val distanceM = 20.0 * 0.3048
        val sizes = listOf(2, 90, 3, 120, 4, 85)
        val samples = sizes.mapIndexed { i, px ->
            TrackSample(0.10 + 0.13 * i, (i / 30.0 * 1e9).toLong(), pixelCount = px)
        }
        val verdict = SpeedMath.computeResultDetailed(samples, distanceM, fov)
        assertEquals(
            "size variation must be what stops it",
            RejectionReason.SIZE_INCONSISTENT, verdict.rejection
        )

        // Same flight, consistent sizes: fires.
        val consistent = sizes.indices.map { i ->
            TrackSample(0.10 + 0.13 * i, (i / 30.0 * 1e9).toLong(), pixelCount = 9)
        }
        assertNotNull(
            "the identical flight with a steady blob size must still read",
            SpeedMath.computeResultDetailed(consistent, distanceM, fov).result
        )
    }
}
