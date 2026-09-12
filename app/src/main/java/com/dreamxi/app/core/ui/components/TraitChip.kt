package com.dreamxi.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.SurfaceRaised3

/**
 * A rule-based trait tag (Playmaker, Pacy, Aerial Threat, ...) — §7.1/§7.2,
 * §10.5. A subtle gold-tinted rim and a small accent dot mark it as a
 * "notable trait" badge rather than a plain label chip; the underlying
 * threshold logic that assigns traits lives in the ETL (§6.3), never
 * computed on-device.
 */
@Composable
fun TraitChip(label: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .background(
                color = SurfaceRaised3,
                shape = MaterialTheme.shapes.extraSmall,
            )
            .border(
                width = 1.dp,
                color = AccentGold.copy(alpha = 0.45f),
                shape = MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color = AccentGold, shape = CircleShape),
        )
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0D0F)
@Composable
private fun TraitChipPreview() {
    DreamXITheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TraitChip(label = "Playmaker")
            TraitChip(label = "Aerial Threat")
            TraitChip(label = "Pacy")
        }
    }
}
