package com.dreamxi.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dreamxi.app.ui.theme.AccentGold

/**
 * A section separator that fades out at both edges instead of running the
 * full width as a flat line — reads as a deliberate visual break rather than
 * a stray ruled line. [accented] draws a faint gold tint through the middle
 * for separators that should carry a touch more presence (e.g. between major
 * sections of a results report).
 */
@Composable
fun DreamXiDivider(modifier: Modifier = Modifier, accented: Boolean = false) {
    val midColor = if (accented) AccentGold.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.14f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, midColor, Color.Transparent),
                ),
            ),
    )
}
