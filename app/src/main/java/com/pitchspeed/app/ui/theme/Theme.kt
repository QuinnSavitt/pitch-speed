package com.pitchspeed.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val GrassGreen = Color(0xFF1B5E20)
val GrassGreenLight = Color(0xFF3E8E41)
val StitchRed = Color(0xFFD8433C)
val Cream = Color(0xFFFDFCF5)
val Charcoal = Color(0xFF1C1C1E)
val Amber = Color(0xFFFFB300)

private val LightColors = lightColorScheme(
    primary = GrassGreen,
    onPrimary = Cream,
    secondary = StitchRed,
    onSecondary = Cream,
    tertiary = Amber,
    background = Cream,
    onBackground = Charcoal,
    surface = Color(0xFFFFFFFF),
    onSurface = Charcoal,
    surfaceVariant = Color(0xFFE7EFE6),
    primaryContainer = GrassGreenLight,
    onPrimaryContainer = Color.White
)

private val DarkColors = darkColorScheme(
    primary = GrassGreenLight,
    onPrimary = Color.Black,
    secondary = StitchRed,
    onSecondary = Color.Black,
    tertiary = Amber,
    background = Color(0xFF10140F),
    onBackground = Cream,
    surface = Color(0xFF1A201A),
    onSurface = Cream,
    surfaceVariant = Color(0xFF283028),
    primaryContainer = GrassGreen,
    onPrimaryContainer = Color.White
)

@Composable
fun PitchSpeedTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = PitchSpeedTypography,
        content = content
    )
}
