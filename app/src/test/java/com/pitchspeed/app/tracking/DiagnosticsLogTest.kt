package com.pitchspeed.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The diagnostics log is the only thing a tester can actually hand back to us, so its job is to
 * survive a whole session on the camera thread and still render something a person can read and
 * paste into an email.
 */
class DiagnosticsLogTest {

    private fun ms(v: Long) = v * 1_000_000L

    @Test
    fun `header carries the settings and camera context`() {
        val log = DiagnosticsLog()
        log.setSession(versionName = "1.5", distanceFeet = 20.0, sensitivity = "MEDIUM", unit = "mph")
        log.setCamera(
            fovDegrees = 68.0, analysisWidth = 1280, analysisHeight = 720,
            scanWidth = 640, scanHeight = 360, rotationDegrees = 90
        )
        val text = log.render()

        assertTrue(text, text.contains("app 1.5"))
        assertTrue(text, text.contains("distance 20.0 ft"))
        assertTrue(text, text.contains("sensitivity MEDIUM"))
        assertTrue(text, text.contains("unit mph"))
        assertTrue(text, text.contains("fov 68.0 deg"))
        assertTrue(text, text.contains("analysis 1280x720"))
        assertTrue(text, text.contains("scan 640x360"))
        assertTrue(text, text.contains("rotation 90 deg"))
    }

    @Test
    fun `fps is estimated from the frames it was shown`() {
        val log = DiagnosticsLog()
        for (frame in 0..29) log.noteFrame(ms(frame * 100L / 3L)) // ~30 fps
        assertEquals(30.0, log.fpsEstimate(), 0.6)
        assertTrue(log.render().contains("fps ~30.0"))
    }

    @Test
    fun `candidate events record position, size and the veto that stopped them`() {
        val log = DiagnosticsLog()
        log.noteFrame(0L)
        log.candidateAccepted(ms(33), xNorm = 0.312, yNorm = 0.501, pixelCount = 7)
        log.candidateVetoed(ms(33), VetoReason.VERTICAL, x = 0.11, y = 0.5, pixelCount = 120)
        log.candidateVetoed(ms(66), VetoReason.TOO_SMALL, x = 0.9, y = 0.2, pixelCount = 1)
        val text = log.render()

        assertTrue(text, text.contains("CAND x=0.312 y=0.501 px=7 ACCEPTED"))
        assertTrue(text, text.contains("veto=vertical"))
        assertTrue(text, text.contains("veto=too_small"))
        assertTrue(text, text.contains("33 CAND"))
    }

    @Test
    fun `whole-frame suppression is logged without a meaningless position`() {
        val log = DiagnosticsLog()
        log.noteFrame(0L)
        log.candidateVetoed(ms(33), VetoReason.SUPPRESSED, x = -1.0, y = -1.0, pixelCount = 412)
        val text = log.render()

        assertTrue(text, text.contains("CAND px=412 veto=suppressed"))
        assertTrue("a negative marker position must not be printed", !text.contains("x=-1"))
    }

    @Test
    fun `track lifecycle events are logged`() {
        val log = DiagnosticsLog()
        log.noteFrame(0L)
        log.trackEvent(ms(0), TrackEvent.SEEDED, samples = 1, xNorm = 0.10)
        log.trackEvent(ms(33), TrackEvent.ASSIGNED, samples = 2, xNorm = 0.20)
        log.trackEvent(ms(66), TrackEvent.EVICTED, samples = 1, xNorm = 0.80)
        val text = log.render()

        assertTrue(text, text.contains("TRACK seeded n=1 x=0.100"))
        assertTrue(text, text.contains("TRACK assigned n=2 x=0.200"))
        assertTrue(text, text.contains("TRACK evicted n=1 x=0.800"))
    }

    @Test
    fun `a fired track is logged with its speed and confidence`() {
        val log = DiagnosticsLog()
        log.noteFrame(0L)
        log.finalized(ms(500), SpeedVerdict(PitchResult(48.3, 0.82f), null, sampleCount = 7, inlierCount = 7))
        val text = log.render()

        assertTrue(text, text.contains("FINAL FIRED 48.3 mph conf=0.82 samples=7 inliers=7"))
    }

    @Test
    fun `a rejected track names the gate that stopped it`() {
        val log = DiagnosticsLog()
        log.noteFrame(0L)
        log.finalized(ms(400), SpeedVerdict(null, RejectionReason.DIRECTION_REVERSALS, 9, 8))
        log.finalized(ms(600), SpeedVerdict(null, RejectionReason.SIZE_INCONSISTENT, 6, 6))
        log.finalized(ms(800), SpeedVerdict(null, RejectionReason.INTERMITTENT_TRACK, 5, 5))
        val text = log.render()

        assertTrue(text, text.contains("FINAL REJECTED direction_reversals samples=9 inliers=8"))
        assertTrue(text, text.contains("FINAL REJECTED size_inconsistent"))
        assertTrue(text, text.contains("FINAL REJECTED intermittent_track"))
    }

    @Test
    fun `the ring buffer keeps the newest events and counts what it dropped`() {
        val log = DiagnosticsLog(capacity = 50)
        for (i in 0 until 200) {
            log.candidateAccepted(ms(i.toLong()), xNorm = i / 1000.0, yNorm = 0.5, pixelCount = i)
        }
        assertEquals(50, log.eventCount)
        assertEquals(150, log.droppedEvents)

        val text = log.render()
        assertTrue(text, text.contains("events 50 (+150 older dropped)"))
        assertTrue("the newest event must survive", text.contains("px=199"))
        assertTrue("the oldest event must have been overwritten", !text.contains("px=0 "))
    }

    @Test
    fun `render stays under its character cap and keeps the tail`() {
        val log = DiagnosticsLog(capacity = 400, maxRenderChars = 4_000)
        for (i in 0 until 400) {
            log.candidateAccepted(ms(i.toLong()), xNorm = 0.5, yNorm = 0.5, pixelCount = i)
        }
        val text = log.render()

        assertTrue("rendered ${text.length} chars, cap 4000", text.length <= 4_000)
        assertTrue(text, text.contains("older events omitted"))
        assertTrue("the most recent events must be the ones kept", text.contains("px=399"))
        assertTrue(text, text.trimEnd().endsWith("=== end ==="))
    }

    @Test
    fun `default cap is the documented 24 KB even for a full ring`() {
        val log = DiagnosticsLog()
        for (i in 0 until 400) {
            log.candidateVetoed(ms(i.toLong()), VetoReason.TOO_BIG, x = 0.5, y = 0.5, pixelCount = 999)
        }
        assertTrue("rendered ${log.render().length} chars", log.render().length <= 24_000)
    }

    @Test
    fun `concurrent writers do not corrupt or lose the buffer`() {
        val log = DiagnosticsLog(capacity = 400)
        val threads = 6
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        repeat(threads) { t ->
            pool.execute {
                try {
                    for (i in 0 until perThread) {
                        log.candidateAccepted(ms((t * perThread + i).toLong()), 0.5, 0.5, i)
                        if (i % 50 == 0) log.render()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue("writers did not finish", latch.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals(400, log.eventCount)
        assertEquals(threads * perThread - 400, log.droppedEvents)
        assertTrue(log.render().contains("=== end ==="))
    }

    @Test
    fun `an untouched log still renders`() {
        val text = DiagnosticsLog().render()
        assertTrue(text, text.contains("=== Pitch Speed diagnostics ==="))
        assertTrue(text, text.contains("events 0"))
        assertTrue(text, text.trimEnd().endsWith("=== end ==="))
    }

    @Test
    fun `computeResultDetailed plumbs the real rejection reasons the log prints`() {
        val distanceM = 20.0 * 0.3048
        val fov = Math.toRadians(68.0)

        // Too few samples to look at.
        assertEquals(
            RejectionReason.TOO_FEW_SAMPLES,
            SpeedMath.computeResultDetailed(
                listOf(TrackSample(0.1, 0L), TrackSample(0.5, ms(33))), distanceM, fov
            ).rejection
        )

        // A clean flight: no rejection, and computeResult agrees with the detailed call.
        val clean = (0 until 8).map { TrackSample(0.08 + 0.10 * it, ms(it * 33L), pixelCount = 9) }
        val verdict = SpeedMath.computeResultDetailed(clean, distanceM, fov)
        assertNull(verdict.rejection)
        assertNotNull(verdict.result)
        assertEquals(8, verdict.sampleCount)
        assertEquals(8, verdict.inlierCount)
        assertEquals(
            verdict.result!!.speedMph,
            SpeedMath.computeResult(clean, distanceM, fov)!!.speedMph,
            1e-9
        )

        // Bad geometry is reported rather than silently returning null.
        assertEquals(
            RejectionReason.BAD_GEOMETRY,
            SpeedMath.computeResultDetailed(clean, 0.0, fov).rejection
        )

        // Every rejection path must leave result null and reason non-null, and vice versa.
        for (samples in listOf(clean, clean.take(4), clean.map { it.copy(xNorm = 0.5) })) {
            val v = SpeedMath.computeResultDetailed(samples, distanceM, fov)
            assertTrue(
                "exactly one of result/rejection must be set, got $v",
                (v.result == null) != (v.rejection == null)
            )
        }
    }
}
