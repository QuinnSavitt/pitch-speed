package com.pitchspeed.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * All persistence for the app: a small SharedPreferences blob for settings and a single
 * JSON file for session history. No database dependency needed at this scale, which keeps
 * the build simple and the data trivially inspectable/portable.
 */
class Repository(private val context: Context) {

    private val prefs = context.getSharedPreferences("pitchspeed_prefs", Context.MODE_PRIVATE)
    private val sessionsFile = File(context.filesDir, "sessions.json")

    fun loadSettings(): AppSettings {
        return AppSettings(
            distanceFeet = prefs.getFloat("distanceFeet", 20.0f).toDouble(),
            unit = if (prefs.getString("unit", "MPH") == "KMH") SpeedUnit.KMH else SpeedUnit.MPH,
            sensitivity = when (prefs.getString("sensitivity", "MEDIUM")) {
                "LOW" -> Sensitivity.LOW
                "HIGH" -> Sensitivity.HIGH
                else -> Sensitivity.MEDIUM
            },
            onboardingComplete = prefs.getBoolean("onboardingComplete", false),
            lastPitcherName = prefs.getString("lastPitcherName", "") ?: ""
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putFloat("distanceFeet", settings.distanceFeet.toFloat())
            .putString("unit", settings.unit.name)
            .putString("sensitivity", settings.sensitivity.name)
            .putBoolean("onboardingComplete", settings.onboardingComplete)
            .putString("lastPitcherName", settings.lastPitcherName)
            .apply()
    }

    fun loadSessions(): List<PitchSession> {
        if (!sessionsFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(sessionsFile.readText())
            (0 until arr.length()).map { i -> sessionFromJson(arr.getJSONObject(i)) }
                .sortedByDescending { it.dateMillis }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveSession(session: PitchSession) {
        val current = loadSessions().toMutableList()
        current.removeAll { it.id == session.id }
        current.add(session)
        writeSessions(current)
    }

    fun deleteAllData() {
        prefs.edit().clear().apply()
        if (sessionsFile.exists()) sessionsFile.delete()
    }

    private fun writeSessions(sessions: List<PitchSession>) {
        val arr = JSONArray()
        sessions.forEach { arr.put(sessionToJson(it)) }
        sessionsFile.writeText(arr.toString())
    }

    private fun sessionToJson(s: PitchSession): JSONObject {
        val pitchesArr = JSONArray()
        s.pitches.forEach { p ->
            pitchesArr.put(
                JSONObject()
                    .put("speedMph", p.speedMph)
                    .put("timestampMillis", p.timestampMillis)
                    .put("confidence", p.confidence.toDouble())
            )
        }
        return JSONObject()
            .put("id", s.id)
            .put("pitcherName", s.pitcherName)
            .put("dateMillis", s.dateMillis)
            .put("distanceFeet", s.distanceFeet)
            .put("pitches", pitchesArr)
    }

    private fun sessionFromJson(o: JSONObject): PitchSession {
        val pitchesArr = o.getJSONArray("pitches")
        val pitches = (0 until pitchesArr.length()).map { i ->
            val p = pitchesArr.getJSONObject(i)
            Pitch(
                speedMph = p.getDouble("speedMph"),
                timestampMillis = p.getLong("timestampMillis"),
                confidence = p.getDouble("confidence").toFloat()
            )
        }
        return PitchSession(
            id = o.getString("id"),
            pitcherName = o.optString("pitcherName", "Pitcher"),
            dateMillis = o.getLong("dateMillis"),
            distanceFeet = o.getDouble("distanceFeet"),
            pitches = pitches
        )
    }
}
