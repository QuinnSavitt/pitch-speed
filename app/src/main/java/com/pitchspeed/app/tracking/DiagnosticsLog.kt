package com.pitchspeed.app.tracking

import java.util.Locale

/**
 * A session-length flight recorder for the detection pipeline, small enough to run on the camera
 * thread and plain enough to paste into an email.
 *
 * When a tester says "it picks up every tiny thing but not the ball" there is no way to tell from
 * the outside whether the ball was never detected, was detected and assigned to the wrong track,
 * or was tracked perfectly and then thrown away by one of [SpeedMath]'s gates. This records
 * exactly that: what the scanner saw each frame, what it rejected and why, how tracks were
 * seeded and assigned, and for every finished track either the speed it fired or the name of the
 * gate that stopped it.
 *
 * Cost discipline, because [record] runs per frame on the analysis thread:
 *  - the ring is [capacity] pre-allocated mutable slots, so steady-state logging allocates
 *    nothing at all;
 *  - all formatting happens in [render], once, when the tester asks for it;
 *  - when the ring wraps, the oldest events are overwritten and counted in [droppedEvents], so a
 *    long session degrades to "the last few seconds in full detail" instead of eating memory.
 *
 * Every method is safe to call from any thread.
 */
class DiagnosticsLog(
    private val capacity: Int = 400,
    private val maxRenderChars: Int = 24_000
) {

    private companion object {
        const val KIND_CANDIDATE = 1
        const val KIND_TRACK = 2
        const val KIND_FINALIZE = 3

        /** [Slot.code] for a candidate the scanner accepted and handed to the tracker. */
        const val CODE_ACCEPTED = -1

        /** Characters held back from the budget for the elision notice and the end marker. */
        const val FOOTER_RESERVE = 64
    }

    /** One recorded event. Mutated in place; never handed outside the lock. */
    private class Slot {
        var tMs = 0L
        var kind = 0
        var code = 0
        var x = 0.0
        var y = 0.0
        var count = 0
        var extra = 0
        var mph = 0.0
        var conf = 0f
    }

    private val lock = Any()
    private val ring = Array(capacity) { Slot() }
    private var writeIndex = 0
    private var size = 0

    /** Events overwritten because the session outran the ring. */
    var droppedEvents = 0
        private set

    // --- Header, filled in as the camera and settings become known. ---
    private var versionName = "?"
    private var distanceFeet = 0.0
    private var sensitivity = "?"
    private var unit = "?"
    private var fovDegrees = 0.0
    private var analysisWidth = 0
    private var analysisHeight = 0
    private var scanWidth = 0
    private var scanHeight = 0
    private var rotationDegrees = 0

    // --- Frame timing, for the fps estimate. ---
    private var firstFrameNs = 0L
    private var lastFrameNs = 0L
    private var frameCount = 0
    private var haveT0 = false

    /** Number of events currently held. */
    val eventCount: Int get() = synchronized(lock) { size }

    /** Settings context for the header. Safe to call repeatedly; last call wins. */
    fun setSession(
        versionName: String,
        distanceFeet: Double,
        sensitivity: String,
        unit: String
    ) = synchronized(lock) {
        this.versionName = versionName
        this.distanceFeet = distanceFeet
        this.sensitivity = sensitivity
        this.unit = unit
    }

    /** Camera geometry for the header, known only once the camera has bound. */
    fun setCamera(
        fovDegrees: Double,
        analysisWidth: Int,
        analysisHeight: Int,
        scanWidth: Int,
        scanHeight: Int,
        rotationDegrees: Int
    ) = synchronized(lock) {
        this.fovDegrees = fovDegrees
        this.analysisWidth = analysisWidth
        this.analysisHeight = analysisHeight
        this.scanWidth = scanWidth
        this.scanHeight = scanHeight
        this.rotationDegrees = rotationDegrees
    }

    /** Call once per analyzed frame. Establishes the time base and the fps estimate. */
    fun noteFrame(tNs: Long) = synchronized(lock) {
        if (!haveT0) {
            firstFrameNs = tNs
            haveT0 = true
        }
        lastFrameNs = tNs
        frameCount++
    }

    /** Measured frames per second over the session, or 0 if too few frames to say. */
    fun fpsEstimate(): Double = synchronized(lock) { fpsLocked() }

    private fun fpsLocked(): Double {
        val span = (lastFrameNs - firstFrameNs) / 1e9
        return if (frameCount > 1 && span > 0.0) (frameCount - 1) / span else 0.0
    }

    /** A blob the scanner accepted and passed to the tracker. */
    fun candidateAccepted(tNs: Long, xNorm: Double, yNorm: Double, pixelCount: Int) =
        record(tNs, KIND_CANDIDATE, CODE_ACCEPTED) {
            it.x = xNorm
            it.y = yNorm
            it.count = pixelCount
        }

    /** Motion the scanner looked at and threw away, and why. */
    fun candidateVetoed(tNs: Long, reason: VetoReason, x: Double, y: Double, pixelCount: Int) =
        record(tNs, KIND_CANDIDATE, reason.ordinal) {
            it.x = x
            it.y = y
            it.count = pixelCount
        }

    /** A track slot was seeded, continued, or evicted. */
    fun trackEvent(tNs: Long, event: TrackEvent, samples: Int, xNorm: Double) =
        record(tNs, KIND_TRACK, event.ordinal) {
            it.count = samples
            it.x = xNorm
        }

    /** A finished track was scored: either it fired or a gate stopped it. */
    fun finalized(tNs: Long, verdict: SpeedVerdict) =
        record(tNs, KIND_FINALIZE, verdict.rejection?.ordinal ?: CODE_ACCEPTED) {
            it.count = verdict.sampleCount
            it.extra = verdict.inlierCount
            it.mph = verdict.result?.speedMph ?: 0.0
            it.conf = verdict.result?.confidence ?: 0f
        }

    private inline fun record(tNs: Long, kind: Int, code: Int, fill: (Slot) -> Unit) {
        synchronized(lock) {
            if (!haveT0) {
                firstFrameNs = tNs
                lastFrameNs = tNs
                haveT0 = true
            }
            val slot = ring[writeIndex]
            slot.tMs = (tNs - firstFrameNs) / 1_000_000L
            slot.kind = kind
            slot.code = code
            slot.x = 0.0
            slot.y = 0.0
            slot.count = 0
            slot.extra = 0
            slot.mph = 0.0
            slot.conf = 0f
            fill(slot)
            writeIndex = (writeIndex + 1) % capacity
            if (size < capacity) size++ else droppedEvents++
        }
    }

    /**
     * Renders the whole log as plain pasteable text: a header block, then one line per event
     * oldest first. Capped at [maxRenderChars] by dropping the OLDEST event lines, so the tail
     * around whatever just went wrong always survives.
     */
    fun render(): String = synchronized(lock) {
        val header = buildString {
            append("=== Pitch Speed diagnostics ===\n")
            append("app ").append(versionName).append('\n')
            append(
                String.format(
                    Locale.US,
                    "distance %.1f ft | sensitivity %s | unit %s\n",
                    distanceFeet, sensitivity, unit
                )
            )
            append(
                String.format(
                    Locale.US,
                    "fov %.1f deg | analysis %dx%d | scan %dx%d | rotation %d deg\n",
                    fovDegrees, analysisWidth, analysisHeight, scanWidth, scanHeight, rotationDegrees
                )
            )
            append(
                String.format(
                    Locale.US,
                    "frames %d | fps ~%.1f | span %.1f s\n",
                    frameCount, fpsLocked(), (lastFrameNs - firstFrameNs) / 1e9
                )
            )
            append("events ").append(size)
            if (droppedEvents > 0) append(" (+").append(droppedEvents).append(" older dropped)")
            append('\n')
            append("--- t_ms | event ---\n")
        }

        // Walk newest to oldest so the character budget is spent on the most recent events.
        // The reserve covers the "older events omitted" notice and the trailing marker, both of
        // which are appended after the budget has been spent.
        val budget = maxRenderChars - header.length - FOOTER_RESERVE
        val lines = ArrayList<String>(size)
        var used = 0
        var elided = 0
        for (k in size - 1 downTo 0) {
            val slot = ring[((writeIndex - size + k) % capacity + capacity) % capacity]
            val line = formatSlot(slot)
            if (used + line.length + 1 > budget && lines.isNotEmpty()) {
                elided = k + 1
                break
            }
            used += line.length + 1
            lines.add(line)
        }
        lines.reverse()

        buildString {
            append(header)
            if (elided > 0) append("... ").append(elided).append(" older events omitted\n")
            for (line in lines) append(line).append('\n')
            append("=== end ===\n")
        }
    }

    private fun formatSlot(s: Slot): String = when (s.kind) {
        KIND_CANDIDATE -> if (s.code == CODE_ACCEPTED) {
            String.format(
                Locale.US, "%6d CAND x=%.3f y=%.3f px=%d ACCEPTED",
                s.tMs, s.x, s.y, s.count
            )
        } else {
            val reason = VetoReason.entries.getOrNull(s.code)?.name?.lowercase(Locale.US) ?: "?"
            if (s.x < 0.0) {
                String.format(Locale.US, "%6d CAND px=%d veto=%s", s.tMs, s.count, reason)
            } else {
                String.format(
                    Locale.US, "%6d CAND x=%.3f y=%.3f px=%d veto=%s",
                    s.tMs, s.x, s.y, s.count, reason
                )
            }
        }

        KIND_TRACK -> String.format(
            Locale.US, "%6d TRACK %s n=%d x=%.3f",
            s.tMs,
            TrackEvent.entries.getOrNull(s.code)?.name?.lowercase(Locale.US) ?: "?",
            s.count, s.x
        )

        KIND_FINALIZE -> if (s.code == CODE_ACCEPTED) {
            String.format(
                Locale.US, "%6d FINAL FIRED %.1f mph conf=%.2f samples=%d inliers=%d",
                s.tMs, s.mph, s.conf, s.count, s.extra
            )
        } else {
            String.format(
                Locale.US, "%6d FINAL REJECTED %s samples=%d inliers=%d",
                s.tMs,
                RejectionReason.entries.getOrNull(s.code)?.name?.lowercase(Locale.US) ?: "?",
                s.count, s.extra
            )
        }

        else -> String.format(Locale.US, "%6d ?", s.tMs)
    }
}
