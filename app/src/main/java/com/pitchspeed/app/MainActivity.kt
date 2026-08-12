package com.pitchspeed.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pitchspeed.app.ui.AppViewModel
import com.pitchspeed.app.ui.components.shareSessionCard
import com.pitchspeed.app.ui.nav.Routes
import com.pitchspeed.app.ui.screens.CaptureScreen
import com.pitchspeed.app.ui.screens.HistoryScreen
import com.pitchspeed.app.ui.screens.HomeScreen
import com.pitchspeed.app.ui.screens.OnboardingScreen
import com.pitchspeed.app.ui.screens.SessionSummaryScreen
import com.pitchspeed.app.ui.screens.SettingsScreen
import com.pitchspeed.app.ui.theme.PitchSpeedTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PitchSpeedTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PitchSpeedRoot()
                }
            }
        }
    }
}

@Composable
private fun PitchSpeedRoot() {
    val viewModel: AppViewModel = viewModel()
    val navController = rememberNavController()
    val context = LocalContext.current

    NavHost(
        navController = navController,
        startDestination = if (viewModel.settings.onboardingComplete) Routes.Home else Routes.Onboarding
    ) {
        composable(Routes.Onboarding) {
            OnboardingScreen(onDone = {
                viewModel.updateSettings { it.copy(onboardingComplete = true) }
                navController.navigate(Routes.Home) { popUpTo(Routes.Onboarding) { inclusive = true } }
            })
        }

        composable(Routes.HowItWorks) {
            OnboardingScreen(onDone = { navController.popBackStack() })
        }

        composable(Routes.Home) {
            HomeScreen(
                viewModel = viewModel,
                onStartSession = { name ->
                    viewModel.startNewSession(name)
                    navController.navigate(Routes.Capture)
                },
                onHistory = { navController.navigate(Routes.History) },
                onSettings = { navController.navigate(Routes.Settings) }
            )
        }

        composable(Routes.Capture) {
            CaptureScreen(
                viewModel = viewModel,
                onEndSession = {
                    val session = viewModel.finishSession()
                    if (session != null) {
                        navController.navigate(Routes.summary(session.id)) {
                            popUpTo(Routes.Home)
                        }
                    } else {
                        navController.popBackStack(Routes.Home, inclusive = false)
                    }
                }
            )
        }

        composable(
            Routes.Summary,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("sessionId") ?: ""
            val session = viewModel.sessionById(id)
            if (session == null) {
                LaunchedEffect(Unit) {
                    navController.navigate(Routes.Home) { popUpTo(Routes.Home) { inclusive = true } }
                }
            } else {
                SessionSummaryScreen(
                    session = session,
                    unit = viewModel.settings.unit,
                    onShare = { shareSessionCard(context, session, viewModel.settings.unit) },
                    onDone = {
                        navController.navigate(Routes.Home) { popUpTo(Routes.Home) { inclusive = true } }
                    }
                )
            }
        }

        composable(Routes.History) {
            HistoryScreen(
                sessions = viewModel.sessions,
                unit = viewModel.settings.unit,
                onBack = { navController.popBackStack() },
                onOpenSession = { id -> navController.navigate(Routes.historyDetail(id)) }
            )
        }

        composable(
            Routes.HistoryDetail,
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("sessionId") ?: ""
            val session = viewModel.sessionById(id)
            if (session == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                SessionSummaryScreen(
                    session = session,
                    unit = viewModel.settings.unit,
                    onShare = { shareSessionCard(context, session, viewModel.settings.unit) },
                    onDone = { navController.popBackStack() },
                    doneLabel = "Back"
                )
            }
        }

        composable(Routes.Settings) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onHowItWorks = { navController.navigate(Routes.HowItWorks) }
            )
        }
    }
}
