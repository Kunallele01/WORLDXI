package com.dreamxi.app.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.AccentGoldDim
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnAccentGold

/**
 * Standard buttons. The press physics they share with every other pressable
 * surface live in [PressableBlock].
 */
private val ButtonHeight = 52.dp

@Composable
private fun LabelledBlockButton(
    text: String,
    onClick: () -> Unit,
    faceColor: Color,
    shadowColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    faceBorderColor: Color? = null,
) {
    PressableBlock(
        onClick = onClick,
        faceColor = faceColor,
        shadowColor = shadowColor,
        height = ButtonHeight,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
        enabled = enabled,
        faceBorderColor = faceBorderColor,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
        )
    }
}

/**
 * Primary actions — the one confident accent (§10.2).
 *
 * Note this is NOT the Spin control. Spin is the game's signature action and
 * has its own hero component; see `feature/draft/components/SpinButton.kt`.
 */
@Composable
fun DreamXiPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LabelledBlockButton(
        text = text,
        onClick = onClick,
        faceColor = AccentGold,
        shadowColor = AccentGoldDim,
        textColor = OnAccentGold,
        modifier = modifier,
        enabled = enabled,
    )
}

/** Secondary actions (skip, cancel, back) — never competes with the accent. */
@Composable
fun DreamXiSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LabelledBlockButton(
        text = text,
        onClick = onClick,
        faceColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowColor = MaterialTheme.colorScheme.background,
        textColor = MaterialTheme.colorScheme.onSurface,
        faceBorderColor = MaterialTheme.colorScheme.outline,
        modifier = modifier,
        enabled = enabled,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0D0F)
@Composable
private fun DreamXiButtonsPreview() {
    DreamXITheme {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(PaddingValues(4.dp)),
        ) {
            DreamXiPrimaryButton(text = "Confirm Pick", onClick = {}, modifier = Modifier.fillMaxWidth())
            DreamXiSecondaryButton(text = "Back", onClick = {}, modifier = Modifier.fillMaxWidth())
        }
    }
}
