package com.example.pinkschedule.ui.theme

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

private val LightColors = lightColorScheme(
    primary = CandyPink,
    onPrimary = SugarWhite,
    primaryContainer = BlossomPink,
    onPrimaryContainer = PlumText,
    secondary = SoftPeach,
    onSecondary = PlumText,
    secondaryContainer = Color(0xFFFFE9DD),
    onSecondaryContainer = PlumText,
    tertiary = Color(0xFF5FB995),
    onTertiary = SugarWhite,
    background = CreamPink,
    onBackground = PlumText,
    surface = SugarWhite,
    onSurface = PlumText,
    surfaceVariant = CottonSurface,
    onSurfaceVariant = RoseText,
    outlineVariant = CandyDivider
)

private val DarkColors = darkColorScheme(
    primary = CandyPink,
    onPrimary = SugarWhite,
    secondary = SoftPeach,
    onSecondary = PlumText,
    background = NightBerry,
    onBackground = SugarWhite,
    surface = NightPlum,
    onSurface = SugarWhite,
    surfaceVariant = NightCard,
    onSurfaceVariant = Color(0xFFF1D7E5)
)

@Composable
fun PinkScheduleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        content = content
    )
}
