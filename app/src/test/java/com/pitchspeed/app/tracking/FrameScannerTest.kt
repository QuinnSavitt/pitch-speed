package com.pitchspeed.app.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class FrameScannerTest {

    private val w = 640
    private val h = 360

    private val high = ScanThresholds(brightMin = 50, diffMin = 12, minPixels = 2, maxPixels = 800)
    private val medium = ScanThresholds(brightMin = 110, diffMin = 24, minPixels = 3, maxPixels = 600)
    private val low = ScanThresholds(brightMin = 180, diffMin = 40, minPixels = 6, maxPixels = 350)

    private fun flatFrame(luma: Int) = ByteArray(w * h) { luma.toByte() }

    private fun drawDisc(frame: ByteArray, cx: Int, cy: Int, r: Int, luma: Int) {
        for (y in (cy - r)..(cy + r)) {
            for (x in (cx - r)..(cx + r)) {
                if (x in 0 until w && y in 0 until h &&
                    (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
                ) {
                    frame[y * w + x] = luma.toByte()
                }
            }
        }
    }

    private fun drawRect(frame: ByteArray, x0: Int, y0: Int, x1: Int, y1: Int, luma: Int) {
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                if (x in 0 until w && y in 0 until h) frame[y * w + x] = luma.toByte()
            }
        }
    }

    @Test
    fun `finds a bright ball where it actually is`() {
        val prev = flatFrame(90)
        val cur = flatFrame(90)
        drawDisc(cur, 320, 180, 5, 230)
        val c = FrameScanner.findCandidate(cur, prev, w, h, medium)
        assertNotNull(c)
        assertEquals(320.0, c!!.cx, 2.0)
        assertEquals(180.0, c.cy, 2.0)
    }

    @Test
    fun `finds a dim indoor stress ball on high sensitivity`() {
        // Indoor scene luma ~85, colored foam ball ~120: far below the old 140+ brightness
        // gates that made the v1_1 detector blind to it.
        val prev = flatFrame(85)
        val cur = flatFrame(85)
        drawDisc(cur, 200, 200, 8, 120)
        assertNotNull(FrameScanner.findCandidate(cur, prev, w, h, high))
    }

    @Test
    fun `low sensitivity still ignores dim objects`() {
        val prev = flatFrame(85)
        val cur = flatFrame(85)
        drawDisc(cur, 200, 200, 8, 120)
        assertNull(FrameScanner.findCandidate(cur, prev, w, h, low))
    }

    @Test
    fun `finds the ball even while a person moves elsewhere in frame`() {
        // A shifting torso-sized bright region (thrower's follow-through) plus a small ball.
        // The old global-centroid scan either blanked the frame or averaged the two; the
        // clustered scan must skip the person (too many pixels) and return the ball.
        val prev = flatFrame(90)
        drawRect(prev, 20, 60, 140, 340, 200)
        val cur = flatFrame(90)
        drawRect(cur, 35, 60, 155, 340, 200) // person moved 15 px
        drawDisc(cur, 480, 170, 5, 220)      // ball on the far side
        val c = FrameScanner.findCandidate(cur, prev, w, h, medium)
        assertNotNull(c)
        assertEquals(480.0, c!!.cx, 3.0)
        assertEquals(170.0, c.cy, 3.0)
    }

    @Test
    fun `rejects camera shake`() {
        // Global movement: everything shifts, every neighborhood has too many moving pixels.
        val rng = Random(7)
        val prev = ByteArray(w * h) { (60 + rng.nextInt(140)).toByte() }
        val cur = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                cur[y * w + x] = prev[y * w + ((x + 6) % w)]
            }
        }
        assertNull(FrameScanner.findCandidate(cur, prev, w, h, high))
    }

    @Test
    fun `rejects a static frame with sensor noise`() {
        val rng = Random(3)
        val base = ByteArray(w * h) { (80 + rng.nextInt(60)).toByte() }
        val cur = ByteArray(w * h) { i ->
            val v = (base[i].toInt() and 0xFF) + rng.nextInt(-6, 7)
            v.coerceIn(0, 255).toByte()
        }
        assertNull(FrameScanner.findCandidate(cur, base, w, h, high))
    }
}
