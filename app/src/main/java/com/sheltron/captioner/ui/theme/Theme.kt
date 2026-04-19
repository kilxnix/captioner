package com.sheltron.captioner.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CaptionerColors = darkColorScheme(
    primary = Accent,
    onPrimary = AccentOn,
    primaryContainer = AccentMuted,
    onPrimaryContainer = AccentOn,
    secondary = Bone,
    onSecondary = Ink,
    background = Ink,
    onBackground = Bone,
    surface = InkRaised,
    onSurface = Bone,
    surfaceVariant = InkElevated,
    onSurfaceVariant = BoneMuted,
    outline = Divider,
    outlineVariant = Divider,
    error = Accent,
    onError = AccentOn
)

@Composable
fun CaptionerTheme(
    // Dark-only for v1 — keep it simple
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Ink.toArgb()
            window.navigationBarColor = Ink.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = CaptionerColors,
        typography = CaptionerTypography,
        content = content
    )
}
