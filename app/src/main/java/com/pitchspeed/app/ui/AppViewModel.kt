package com.pitchspeed.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pitchspeed.app.data.AppSettings
import com.pitchspeed.app.data.Pitch
import com.pitchspeed.app.data.PitchSession
import com.pitchspeed.app.data.Repository
import java.util.UUID

/** Single source of truth for the whole app: settings, saved history, and the in-progress session. */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = Repository(application)

    var settings by mutableStateOf(repository.loadSettings())
        private set

    var sessions by mutableStateOf(repository.loadSessions())
        private set

    // In-progress session state, live while the Capture screen is open.
    var activeSessionId by mutableStateOf(UUID.randomUUID().toString())
        private set
    var activePitcherName by mutableStateOf("")
        private set
    var activePitches by mutableStateOf(listOf<Pitch>())
        private set

    /**
     * Rendered detection log for the session that just ended. Sessions reopened from history
     * predate this run and carry none, so the export affordances stay hidden for them.
     */
    var lastDiagnostics by mutableStateOf<SessionDiagnostics?>(null)
        private set

    /**
     * Set by the Capture screen for the life of a session; [finishSession] snapshots it. Kept as
     * a plain provider rather than a shared object so the diagnostics recorder never outlives the
     * camera that fills it.
     */
    var diagnosticsProvider: (() -> String)? = null

    fun updateSettings(update: (AppSettings) -> AppSettings) {
        settings = update(settings)
        repository.saveSettings(settings)
    }

    fun startNewSession(pitcherName: String) {
        activeSessionId = UUID.randomUUID().toString()
        activePitcherName = pitcherName.ifBlank { "Pitcher" }
        activePitches = emptyList()
        updateSettings { it.copy(lastPitcherName = activePitcherName) }
    }

    fun recordPitch(speedMph: Double, confidence: Float) {
        activePitches = activePitches + Pitch(speedMph, System.currentTimeMillis(), confidence)
    }

    fun removeLastPitch() {
        if (activePitches.isNotEmpty()) activePitches = activePitches.dropLast(1)
    }

    /**
     * Ends the session, saving it if it caught anything.
     *
     * The diagnostics snapshot is taken FIRST, before the empty-session early return, because a
     * session that detected no pitches is the one whose log we most want back from a tester. It
     * is tagged as having no summary so the UI knows to offer it somewhere other than the summary
     * screen it will never reach.
     */
    fun finishSession(): PitchSession? {
        val log = diagnosticsProvider?.invoke()
        if (activePitches.isEmpty()) {
            lastDiagnostics = log?.let {
                SessionDiagnostics(activeSessionId, it, producedSummary = false)
            }
            return null
        }
        val session = PitchSession(
            id = activeSessionId,
            pitcherName = activePitcherName,
            dateMillis = System.currentTimeMillis(),
            distanceFeet = settings.distanceFeet,
            pitches = activePitches
        )
        repository.saveSession(session)
        sessions = repository.loadSessions()
        lastDiagnostics = log?.let { SessionDiagnostics(session.id, it, producedSummary = true) }
        return session
    }

    fun sessionById(id: String): PitchSession? = sessions.find { it.id == id }

    /** The detection log for [sessionId], or null when none was recorded for it. */
    fun diagnosticsFor(sessionId: String): String? = lastDiagnostics?.textForSummary(sessionId)

    /**
     * The detection log of a session that ended without a single pitch, which therefore has no
     * summary screen to export from. Null whenever the last session did produce one.
     */
    val diagnosticsWithoutSummary: String? get() = lastDiagnostics?.textWithoutSummary

    fun clearAllData() {
        repository.deleteAllData()
        settings = repository.loadSettings()
        sessions = repository.loadSessions()
    }
}
