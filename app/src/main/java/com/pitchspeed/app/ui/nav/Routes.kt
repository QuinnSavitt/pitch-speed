package com.pitchspeed.app.ui.nav

object Routes {
    const val Onboarding = "onboarding"
    const val Home = "home"
    const val Capture = "capture"
    const val Summary = "summary/{sessionId}"
    const val History = "history"
    const val HistoryDetail = "history_detail/{sessionId}"
    const val Settings = "settings"
    const val HowItWorks = "how_it_works"

    fun summary(id: String) = "summary/$id"
    fun historyDetail(id: String) = "history_detail/$id"
}
