package com.dreamxi.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Dream XI's theme is a fixed, deliberately-designed dark brand look (§10.1) —
 * not an adaptive light/dark Material theme, and not dynamic color. Every
 * screen renders against this same near-black + gold palette regardless of
 * system theme, matching the reference mood (FUT pack openings, broadcast
 * graphics).
 */
private val DreamXiColorScheme = darkColorScheme(
    primary = AccentGold,
    onPrimary = OnAccentGold,
    primaryContainer = AccentGoldDim,
    onPrimaryContainer = OnAccentGold,

    secondary = PositionMidfielder,
    onSecondary = OnAccentGold,

    tertiary = PositionGoalkeeper,
    onTertiary = OnSurfacePrimary,

    background = SurfaceBase,
    onBackground = OnSurfacePrimary,

    surface = SurfaceRaised1,
    onSurface = OnSurfacePrimary,
    surfaceVariant = SurfaceRaised2,
    onSurfaceVariant = OnSurfaceMuted,
    surfaceContainer = SurfaceRaised1,
    surfaceContainerHigh = SurfaceRaised2,
    surfaceContainerHighest = SurfaceRaised3,

    outline = OutlineSubtle,
    outlineVariant = OutlineSubtle,

    error = ResultLoss,
    onError = OnSurfacePrimary,
)

@Composable
fun DreamXITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DreamXiColorScheme,
        typography = DreamXiTypography,
        shapes = DreamXiShapes,
        content = content,
    )
}
