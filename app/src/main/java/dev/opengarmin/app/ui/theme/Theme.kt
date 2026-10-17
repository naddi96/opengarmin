package dev.opengarmin.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val PulseColorScheme = darkColorScheme(
    primary = PulseColors.Accent,
    onPrimary = PulseColors.TextOnAccent,
    primaryContainer = PulseColors.SurfaceElevated,
    onPrimaryContainer = PulseColors.Text,
    secondary = PulseColors.Teal,
    onSecondary = PulseColors.TextOnAccent,
    tertiary = PulseColors.Blue,
    background = PulseColors.Background,
    onBackground = PulseColors.Text,
    surface = PulseColors.Surface,
    onSurface = PulseColors.Text,
    surfaceVariant = PulseColors.SurfaceElevated,
    onSurfaceVariant = PulseColors.TextMuted,
    outline = PulseColors.BorderStrong,
    outlineVariant = PulseColors.Border,
    error = PulseColors.Coral,
    onError = PulseColors.TextOnAccent,
)

@Composable
fun PulseTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = PulseColors.Background.toArgb()
            window.navigationBarColor = PulseColors.Background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = PulseColorScheme,
        typography = PulseTypography,
        shapes = PulseShapes,
        content = content,
    )
}
