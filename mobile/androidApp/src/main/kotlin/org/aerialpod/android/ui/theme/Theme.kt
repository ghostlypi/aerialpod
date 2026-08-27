package org.aerialpod.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun rgb(value: Int): Color = Color(value or 0xFF000000.toInt())

/**
 * Material 3 dressed in the desktop's tokens.
 *
 * Notably *not* dynamic colour: the accent is a setting the two apps share, so
 * letting Android override it from the wallpaper would make the same account
 * look different on each device for no reason the user asked for.
 */
fun colorSchemeFor(dark: Boolean, accent: String): ColorScheme {
    val p = palette(dark, accent)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = rgb(p.accent),
        onPrimary = rgb(p.onAccent),
        primaryContainer = rgb(p.accentHover),
        onPrimaryContainer = rgb(p.onAccent),
        secondary = rgb(p.accent),
        onSecondary = rgb(p.onAccent),
        secondaryContainer = rgb(p.surfaceHover),
        onSecondaryContainer = rgb(p.text),
        tertiary = rgb(p.accent),
        onTertiary = rgb(p.onAccent),
        background = rgb(p.bg),
        onBackground = rgb(p.text),
        surface = rgb(p.bg),
        onSurface = rgb(p.text),
        surfaceVariant = rgb(p.surface),
        onSurfaceVariant = rgb(p.textDim),
        surfaceContainerLowest = rgb(p.bg),
        surfaceContainerLow = rgb(p.bg),
        surfaceContainer = rgb(p.surface),
        surfaceContainerHigh = rgb(p.surface),
        surfaceContainerHighest = rgb(p.surfaceHover),
        outline = rgb(p.border),
        outlineVariant = rgb(p.border),
        error = rgb(p.danger),
        onError = rgb(0xFFFFFF),
        scrim = Color.Black,
    )
}

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun AerialPodTheme(
    prefs: ThemePrefs = ThemePrefs(),
    content: @Composable () -> Unit,
) {
    val dark = isDark(prefs.mode)

    // The status and navigation bars draw their icons over our background, and
    // the platform picks their colour from the *system* theme. With the app set
    // to Dark while the phone is in Light, that leaves dark icons on a dark bar
    // and nothing is readable. Follow the app's own choice instead.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorSchemeFor(dark, prefs.accent),
        content = content,
    )
}
