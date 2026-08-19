package com.pitchspeed.app.tracking

import kotlin.math.abs

/**
 * Keeps a few concurrent object tracks alive at once, so the ball is not locked out by whatever
 * moved first.
 *
 * The old pipeline had a single sample list: the first blob to appear owned it, and every later
 * blob's position was appended to the same line. In a backyard that meant a walking person seeded
 * the list, the ball's samples were mixed into the person's, and the fit either failed or - worse
 * - reported the person's speed. Here each candidate is matched to the track whose next position
 * it plausibly continues, so a person strolling at 3 px/frame and a ball crossing at 60 px/frame
 * build two separate, clean lines and only the ball-shaped one survives [SpeedMath]'s gates.
 *
 * Matching is deliberately dumb and cheap: predict where each track should be at this timestamp
 * by extrapolating its last two samples, and take the nearest candidate within
 * [assignRadiusNorm]. One candidate per track per frame, strongest candidate first.
 */
class TrackSet(
    private val staleGapNs: Long = 250_000_000L,
    private val maxWindowNs: Long = 900_000_000L,
    private val maxTracks: Int = 2,
    private val assignRadiusNorm: Double = 0.15
) {

    private val tracks = mutableListOf<MutableList<TrackSample>>()

    val trackCount: Int get() = tracks.size

    fun clear() = tracks.clear()

    fun isEmpty(): Boolean = tracks.isEmpty()

    /**
     * Removes and returns every track that has not been fed since [staleGapNs] before [nowNs] -
     * i.e. that object has left the frame and its flight, if it was one, is over and ready to be
     * scored. Longest track first, so the most complete evidence gets scored first.
     */
    fun takeStale(nowNs: Long): List<List<TrackSample>> {
        val done = mutableListOf<List<TrackSample>>()
        val it = tracks.iterator()
        while (it.hasNext()) {
            val track = it.next()
            if (track.isEmpty() || nowNs - track.last().tNs > staleGapNs) {
                if (track.isNotEmpty()) done.add(track.toList())
                it.remove()
            }
        }
        done.sortByDescending { it.size }
        return done
    }

    /**
     * Feeds one frame's blobs in, [xs] being their positions along the tracking axis (0..1),
     * ordered strongest first.
     */
    fun addFrame(xs: List<Double>, tNs: Long) {
        val claimed = BooleanArray(tracks.size)
        val assigned = BooleanArray(xs.size)
        for (ci in xs.indices) {
            var best = -1
            var bestDist = assignRadiusNorm
            for (ti in tracks.indices) {
                if (claimed[ti]) continue
                val d = abs(xs[ci] - predict(tracks[ti], tNs))
                if (d <= bestDist) {
                    bestDist = d
                    best = ti
                }
            }
            if (best >= 0) {
                claimed[best] = true
                assigned[ci] = true
                tracks[best].add(TrackSample(xs[ci], tNs))
            }
        }
        for (ci in xs.indices) {
            if (assigned[ci]) continue
            if (tracks.size >= maxTracks) break
            tracks.add(mutableListOf(TrackSample(xs[ci], tNs)))
        }
        for (track in tracks) {
            while (track.isNotEmpty() && tNs - track.first().tNs > maxWindowNs) {
                track.removeAt(0)
            }
        }
        tracks.removeAll { it.isEmpty() }
    }

    /** Where this track should be at [tNs], from its last two samples' velocity. */
    private fun predict(track: List<TrackSample>, tNs: Long): Double {
        val last = track.last()
        if (track.size < 2) return last.xNorm
        val prev = track[track.size - 2]
        val dt = (last.tNs - prev.tNs) / 1e9
        if (dt <= 1e-6) return last.xNorm
        val v = (last.xNorm - prev.xNorm) / dt
        return last.xNorm + v * ((tNs - last.tNs) / 1e9)
    }
}
