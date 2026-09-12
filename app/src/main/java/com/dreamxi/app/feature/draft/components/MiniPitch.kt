package com.dreamxi.app.feature.draft.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dreamxi.app.feature.draft.Formation
import com.dreamxi.app.feature.draft.Formations
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnSurfaceMuted

/**
 * A formation drawn as eleven dots — no labels, no ratings.
 *
 * Used on the Setup picker cards. "4-2-3-1" is meaningless to a casual player
 * until they see the shape, and even for someone fluent the picture is read
 * faster than the number. Sharing [Formation]'s coordinates means the dots and
 * the real pitch can never disagree about where a position sits.
 */
@Composable
fun MiniPitch(
    formation: Formation,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val r = minOf(w, h) * 0.055f

        // Halfway line only — anything more is noise at this size.
        drawLine(
            accent.copy(alpha = 0.18f),
            Offset(0f, h / 2),
            Offset(w, h / 2),
            strokeWidth = 1f,
        )
        formation.slots.forEach { slot ->
            drawCircle(
                color = if (slot.isGoalkeeper) accent.copy(alpha = 0.5f) else accent,
                radius = r,
                center = Offset(w * slot.x, h * (0.06f + slot.y * 0.88f)),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF15171B)
@Composable
private fun MiniPitchPreview() {
    DreamXITheme {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Formations.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach {
                        MiniPitch(
                            formation = it,
                            accent = OnSurfaceMuted,
                            modifier = Modifier.width(74.dp).height(64.dp),
                        )
                    }
                }
            }
        }
    }
}
