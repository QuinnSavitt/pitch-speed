package com.pitchspeed.app.tracking

import kotlin.math.abs

/** What just happened to a track slot. Reported to [TrackSet.onTrackEvent] for diagnostics. */
enum class TrackEvent {
    /** A candidate that matched nothing started a new track in a free slot. */
    SEEDED,

    /** A candidate continued an existing track. */
    ASSIGNED,

    /** A barely-started track was thrown out to make room for a fresh candidate. */
    EVICTED
}

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
 * by extrapolating its last two samples, and take the nearest candidate within the track's
 * assignment radius (see [radiusFor]). One candidate per track per frame, strongest first.
 *
 * Two rules stop junk motion from owning the tracker, both of them statements about what a real
 * ball does:
 *  - a ball that is in shot is seen on CONSECUTIVE frames, so a track that goes more than
 *    [maxMissedFrames] frames without being continued is finished (see [isAbandoned]) rather
 *    than left open to collect an unrelated speck later;
 *  - slots are scarce and junk usually gets there first, so a candidate that matches nothing
 *    evicts the weakest slot (see [evictWeakest]) instead of being turned away. Without that,
 *    three specks in the opening frames of a session could starve the ball of a slot for the rest
 *    of it - exactly the "picks up every tiny thing but not the ball" failure.
 */
class TrackSet(
    private val staleGapNs: Long = 250_000_000L,
    private val maxWindowNs: Long = 900_000_000L,
    private val maxTracks: Int = 3,
    private val seedRadiusNorm: Double = 0.15,
    private val trackedRadiusNorm: Double = 0.04,
    private val maxMissedFrames: Int = 0
) {

    private class Track(val samples: MutableList<TrackSample>, var lastFedFrame: Int)

    private val tracks = mutableListOf<Track>()

    /** Frames fed so far, used to tell a continuously-seen object from an intermittent one. */
    private var frameIndex = 0

    /** Optional diagnostics hook: (event, sample count of the slot after the event, position). */
    var onTrackEvent: ((TrackEvent, Int, Double) -> Unit)? = null

    val trackCount: Int get() = tracks.size

    fun clear() = tracks.clear()

    fun isEmpty(): Boolean = tracks.isEmpty()

    /**
     * Removes and returns every track whose object is gone: either nothing has continued it for
     * [staleGapNs], or it has missed more than [maxMissedFrames] frames in a row. Its flight, if
     * it was one, is over and ready to be scored. Longest track first, so the most complete
     * evidence gets scored first.
     */
    fun takeStale(nowNs: Long): List<List<TrackSample>> {
        val done = mutableListOf<List<TrackSample>>()
        val it = tracks.iterator()
        while (it.hasNext()) {
            val track = it.next()
            val timedOut = track.samples.isEmpty() || nowNs - track.samples.last().tNs > staleGapNs
            if (timedOut || isAbandoned(track)) {
                if (track.samples.isNotEmpty()) done.add(track.samples.toList())
                it.remove()
            }
        }
        done.sortByDescending { it.size }
        return done
    }

    /**
     * Feeds one frame's blobs in, [xs] being their positions along the tracking axis (0..1),
     * ordered strongest first. [pixelCounts], when supplied, carries each blob's size so
     * [SpeedMath]'s size-consistency gate has something to work with.
     */
    fun addFrame(xs: List<Double>, tNs: Long, pixelCounts: List<Int>? = null) {
        frameIndex++
        val claimed = BooleanArray(tracks.size)
        val assigned = BooleanArray(xs.size)
        for (ci in xs.indices) {
            var best = -1
            var bestDist = Double.MAX_VALUE
            for (ti in tracks.indices) {
                if (claimed[ti]) continue
                val track = tracks[ti]
                val d = abs(xs[ci] - predict(track.samples, tNs))
                if (d <= radiusFor(track.samples) && d < bestDist) {
                    bestDist = d
                    best = ti
                }
            }
            if (best >= 0) {
                claimed[best] = true
                assigned[ci] = true
                val track = tracks[best]
                track.samples.add(sampleAt(xs, pixelCounts, ci, tNs))
                track.lastFedFrame = frameIndex
                onTrackEvent?.invoke(TrackEvent.ASSIGNED, track.samples.size, xs[ci])
            }
        }
        for (ci in xs.indices) {
            if (assigned[ci]) continue
            if (tracks.size >= maxTracks && !evictWeakest()) break
            tracks.add(Track(mutableListOf(sampleAt(xs, pixelCounts, ci, tNs)), frameIndex))
            onTrackEvent?.invoke(TrackEvent.SEEDED, 1, xs[ci])
        }
        for (track in tracks) {
            while (track.samples.isNotEmpty() && tNs - track.samples.first().tNs > maxWindowNs) {
                track.samples.removeAt(0)
            }
        }
        tracks.removeAll { it.samples.isEmpty() }
    }

    /**
     * True when nothing has continued this track for more than [maxMissedFrames] frames.
     *
     * This is the rule that stops a track from being assembled out of unrelated specks. A ball in
     * flight is present in every frame it crosses, so its track is continued frame after frame;
     * junk only lands near the prediction now and then, so its "track" is really one sample every
     * fourth or fifth frame with nothing in between. Without this, such a track stays open
     * indefinitely, collects five sporadic specks that happen to trend one way, and passes every
     * gate that only ever looks at the samples it did collect.
     *
     * At the default of 0 a track must be continued on the very next frame it competes for. That
     * sounds brutal, but [frameIndex] only advances on frames where the scanner found something at
     * all, so it is strict exactly when the scene is busy - which is when junk is a danger - and
     * naturally lenient when nothing else is moving. A real ball that does drop a frame is not
     * lost, it is simply split into two tracks, and the longer half still reads.
     *
     * Abandonment is only ever acted on in [takeStale], never inside [addFrame]: a track that has
     * just been abandoned is usually a flight that has FINISHED, and it has to be handed back to
     * be scored rather than quietly deleted. Since this fires well before [staleGapNs] does,
     * dropping abandoned tracks inside addFrame would throw away every completed throw before
     * anyone ever looked at it.
     */
    private fun isAbandoned(track: Track): Boolean =
        frameIndex - track.lastFedFrame > maxMissedFrames

    /**
     * Drops the least valuable slot so a fresh candidate can be seeded, and reports whether it
     * managed to. Only single-sample slots are evictable - a track with two or more samples is
     * real evidence of something moving consistently and is never sacrificed for a speck that
     * just appeared. Among those, the one whose sample is oldest goes, since it is the most
     * likely to be a one-frame flicker that will never be continued.
     */
    private fun evictWeakest(): Boolean {
        var victim = -1
        var oldest = Long.MAX_VALUE
        for (ti in tracks.indices) {
            val track = tracks[ti]
            if (track.samples.size > 1) continue
            val t = track.samples.lastOrNull()?.tNs ?: Long.MIN_VALUE
            if (t < oldest) {
                oldest = t
                victim = ti
            }
        }
        if (victim < 0) return false
        val dropped = tracks.removeAt(victim)
        onTrackEvent?.invoke(
            TrackEvent.EVICTED,
            dropped.samples.size,
            dropped.samples.lastOrNull()?.xNorm ?: 0.0
        )
        return true
    }

    private fun sampleAt(xs: List<Double>, pixelCounts: List<Int>?, ci: Int, tNs: Long) =
        TrackSample(xs[ci], tNs, pixelCounts?.getOrNull(ci) ?: 0)

    /**
     * How far from its predicted position a track will accept a blob.
     *
     * A one-sample track has no velocity yet, so its "prediction" is just where it last was and
     * the next real position can legitimately be most of a frame away - it needs the wide
     * [seedRadiusNorm]. But the moment a track has two samples it knows how fast the object is
     * going, and a genuine ball then lands within a pixel or two of the prediction every time.
     *
     * Leaving the wide radius in place for established tracks is what let a backyard full of
     * specks fake a pitch: a track would predict forward and scoop up whatever unrelated speck
     * happened to fall within 96 px of that guess, assembling a straight, one-way, plausibly-sized
     * line out of pure noise. Tightening it once a velocity exists starves that process.
     *
     * [trackedRadiusNorm] is deliberately set to [SpeedMath.INLIER_TOLERANCE]: a sample further
     * from the predicted line than that would be discarded as an outlier by the fit anyway, so
     * accepting it only gives junk another chance to lengthen a track it will never contribute to.
     */
    private fun radiusFor(track: List<TrackSample>): Double =
        if (track.size >= 2) trackedRadiusNorm else seedRadiusNorm

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
