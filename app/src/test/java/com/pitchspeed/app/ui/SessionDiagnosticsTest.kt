package com.pitchspeed.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The routing that decides WHERE a session's detection log can be exported from.
 *
 * A session with pitches is saved and gets a summary screen, so its log belongs there. A session
 * that detected nothing is never saved and never reaches a summary, so its log has to be offered
 * on the Home screen instead - and that is the log a tester reporting "it is not picking up the
 * ball" actually needs to send us. Getting this wrong in either direction either hides the useful
 * log or shows a stale export button forever.
 */
class SessionDiagnosticsTest {

    @Test
    fun `a session with pitches exports from its own summary screen`() {
        val d = SessionDiagnostics("session-a", "LOG A", producedSummary = true)

        assertEquals("LOG A", d.textForSummary("session-a"))
        assertNull("must not leak onto the Home screen", d.textWithoutSummary)
    }

    @Test
    fun `a summary log is only offered for its own session`() {
        val d = SessionDiagnostics("session-a", "LOG A", producedSummary = true)

        assertNull("another session must not show this log", d.textForSummary("session-b"))
    }

    @Test
    fun `a zero-pitch session exports from the Home screen instead`() {
        val d = SessionDiagnostics("session-empty", "LOG EMPTY", producedSummary = false)

        assertEquals(
            "the log of a session that caught nothing must stay reachable",
            "LOG EMPTY", d.textWithoutSummary
        )
        assertNull(
            "it has no summary screen, so it must never be offered as one",
            d.textForSummary("session-empty")
        )
    }

    @Test
    fun `a later session with pitches clears the Home screen offer`() {
        // The Home affordance is derived, not sticky: once a session produces a summary, the
        // stale "export last session diagnostics" row must disappear on its own.
        var latest = SessionDiagnostics("empty-1", "LOG EMPTY", producedSummary = false)
        assertEquals("LOG EMPTY", latest.textWithoutSummary)

        latest = SessionDiagnostics("good-1", "LOG GOOD", producedSummary = true)
        assertNull(latest.textWithoutSummary)
        assertEquals("LOG GOOD", latest.textForSummary("good-1"))
    }
}
