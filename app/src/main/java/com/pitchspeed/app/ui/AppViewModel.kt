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

    fun finishSession(): PitchSession? {
        if (activePitches.isEmpty()) return null
        val session = PitchSession(
            id = activeSessionId,
            pitcherName = activePitcherName,
            dateMillis = System.currentTimeMillis(),
            distanceFeet = settings.distanceFeet,
            pitches = activePitches
        )
        repository.saveSession(session)
        sessions = repository.loadSessions()
        return session
    }

    fun sessionById(id: String): PitchSession? = sessions.find { it.id == id }

    fun clearAllData() {
        repository.deleteAllData()
        settings = repository.loadSettings()
        sessions = repository.loadSessions()
    }
}
