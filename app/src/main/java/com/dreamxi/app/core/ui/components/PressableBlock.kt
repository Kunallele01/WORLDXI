package com.dreamxi.app.core.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dream XI's one press interaction: a solid offset block that the face
 * physically sinks into on tap. Flat colours throughout — no gradients or
 * gloss, which read as dated skeuomorphism rather than premium (a look the
 * design language explicitly rejected).
 *
 * Lives here, shared, rather than inside the button file: the Spin button is
 * a different shape and size but must feel *identical* under the thumb. When
 * the press physics were private to DreamXiButtons.kt, any hero control had
 * to reimplement them, and two copies of an interaction drift.
 */
internal const val PressAnimMs = 90
internal val BlockShadowOffset = 6.dp

@Composable
internal fun PressableBlock(
    onClick: () -> Unit,
    faceColor: Color,
    shadowColor: Color,
    height: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    faceBorderColor: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressOffset by animateDpAsState(
        targetValue = if (isPressed && enabled) BlockShadowOffset else 0.dp,
        animationSpec = tween(durationMillis = PressAnimMs),
        label = "blockPressOffset",
    )

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .height(height + BlockShadowOffset),
    ) {
        // Static base block — stays put; the face sinks down to meet it.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .align(Alignment.BottomCenter)
                .clip(shape)
                .background(shadowColor),
        )
        // Face — raised by ShadowOffset at rest, flush with the base on press.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .offset(y = pressOffset)
                .clip(shape)
                .background(faceColor)
                .then(
                    if (faceBorderColor != null) {
                        Modifier.border(width = 1.dp, color = faceBorderColor, shape = shape)
                    } else {
                        Modifier
                    },
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}
