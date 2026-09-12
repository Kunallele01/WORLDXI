package com.dreamxi.app.feature.simulating

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dreamxi.app.core.ui.PlaceholderScreen

/**
 * Anticipation screen while the drafted XI's season is simulated by the
 * standalone Kotlin/Ktor sim service (§8.4) and results are written back to
 * Supabase. Not a bare spinner - this is a tension-building beat (§10.4).
 */
@Composable
fun SimulatingScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(title = "Simulating", modifier = modifier)
}
