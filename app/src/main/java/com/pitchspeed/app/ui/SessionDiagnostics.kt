package com.pitchspeed.app.ui

/**
 * A rendered diagnostics log, tagged with the session it was recorded for and with whether that
 * session produced anything to review.
 *
 * The distinction matters because of how the app ends a session. A session with at least one
 * pitch is saved and lands on [com.pitchspeed.app.ui.screens.SessionSummaryScreen], which is
 * where its log is offered for export. A session that detected NOTHING is never saved and never
 * reaches a summary, so without somewhere else to hang it, its log would be unreachable - and
 * that is precisely the log worth sending us, because it is the recording of the app failing to
 * see the ball.
 *
 * Deliberately free of Android types so the routing below can be unit-tested on the JVM.
 */
data class SessionDiagnostics(
    val sessionId: String,
    val text: String,
    /** True when the session was saved and has a summary screen to export from. */
    val producedSummary: Boolean
) {
    /** The log to offer on the summary screen for [id], or null if this log is not that one. */
    fun textForSummary(id: String): String? =
        if (producedSummary && sessionId == id) text else null

    /**
     * The log to offer somewhere else entirely, because its session produced no pitches and so
     * has no summary screen of its own. Null once a session with pitches replaces it.
     */
    val textWithoutSummary: String? get() = if (producedSummary) null else text
}
