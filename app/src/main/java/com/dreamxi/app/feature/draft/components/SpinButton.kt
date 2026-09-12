package com.dreamxi.app.feature.draft.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamxi.app.core.ui.components.PressableBlock
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.AccentGoldDim
import com.dreamxi.app.ui.theme.DisplayFontFamily
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnAccentGold
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OutlineSubtle
import com.dreamxi.app.ui.theme.SurfaceRaised2

/**
 * The Spin control.
 *
 * Spin is the signature action of the whole game and the single most-pressed
 * control in it — eleven times per run, plus rerolls. The first pass rendered
 * it with the generic 52dp full-width primary button, which is the same
 * component as "Back" and "Confirm": it read as a form-submit bar, gave no
 * sense of a wheel being pulled, and had no state while the spin resolved.
 *
 * What this does differently, in priority order:
 *
 *  - SIZE AND TYPE MATCH ITS IMPORTANCE. 76dp tall with the word set in the
 *    condensed display face at 40sp, so it is unmistakably the thing to press
 *    on a screen that also contains a scrollable list of 25 players.
 *  - IT HAS A BUSY STATE. Spinning is a real async moment (a network round
 *    trip to `draft_eligible_club_seasons` plus the reveal animation). Idle
 *    and spinning are different faces of the same control rather than a
 *    button that goes dead and leaves the user tapping.
 *  - IT CARRIES THE ROUND COUNT. "Round 4 of 11" belongs on the control that
 *    advances it, not orphaned in a header — the user reads progress in the
 *    same glance as the action.
 *  - MOTION, NOT GLOSS. The busy state animates opacity on flat colour. The
 *    design language rejected gradients and gloss, so energy has to come from
 *    movement and scale, never from a shine.
 *
 * The press physics are the shared [PressableBlock] so it feels identical to
 * every other button under the thumb — only louder.
 */
enum class SpinPhase { Idle, Spinning }

private val SpinButtonHeight = 76.dp

@Composable
fun SpinButton(
    phase: SpinPhase,
    round: Int,
    totalRounds: Int,
    onSpin: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = "Spin",
) {
    val spinning = phase == SpinPhase.Spinning
    PressableBlock(
        onClick = onSpin,
        faceColor = AccentGold,
        shadowColor = AccentGoldDim,
        height = SpinButtonHeight,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
        enabled = enabled && !spinning,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            Text(
                text = if (spinning) "SPINNING" else label.uppercase(),
                fontFamily = DisplayFontFamily,
                fontSize = 40.sp,
                lineHeight = 40.sp,
                letterSpacing = 1.sp,
                color = OnAccentGold,
            )
            if (spinning) {
                Spacer(Modifier.width(10.dp))
                PulsingDots()
            }
            Spacer(Modifier.weight(1f))
            // Round counter, right-aligned behind a hairline. Kept on the
            // control that advances it so progress and action read together.
            Box(
                Modifier.width(1.dp).height(34.dp)
                    .background(OnAccentGold.copy(alpha = 0.25f)),
            )
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$round",
                    fontFamily = DisplayFontFamily,
                    fontSize = 26.sp,
                    lineHeight = 26.sp,
                    color = OnAccentGold,
                )
                Text(
                    text = "OF $totalRounds",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    letterSpacing = 1.sp,
                    color = OnAccentGold.copy(alpha = 0.65f),
                )
            }
        }
    }
}

/** Three dots breathing out of phase — the busy state, flat colour only. */
@Composable
private fun PulsingDots() {
    val transition = rememberInfiniteTransition(label = "spinDots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 420, delayMillis = i * 140),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).alpha(a).background(OnAccentGold, CircleShape))
        }
    }
}

/**
 * "Change spin" (reroll) control, sat directly under [SpinButton].
 *
 * The allowance is a difficulty setting (1-3, default 2) and is deliberately
 * scarce, so the remaining count must be visible WITHOUT being pressed —
 * hence pips rather than a number buried in a label. Spent pips stay on
 * screen as hollow rings instead of disappearing: the user needs to see what
 * they started with to judge whether spending one now is worth it.
 *
 * Sits below the primary action and in a muted surface so it never competes
 * with Spin for the thumb.
 */
@Composable
fun ChangeSpinControl(
    remaining: Int,
    allowance: Int,
    onChangeSpin: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val usable = enabled && remaining > 0
    PressableBlock(
        onClick = onChangeSpin,
        faceColor = SurfaceRaised2,
        shadowColor = MaterialTheme.colorScheme.background,
        height = 46.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
        enabled = usable,
        faceBorderColor = if (usable) AccentGold.copy(alpha = 0.35f) else OutlineSubtle,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text(
                text = if (remaining > 0) "CHANGE SPIN" else "NO CHANGE SPINS LEFT",
                style = MaterialTheme.typography.labelLarge,
                color = if (usable) MaterialTheme.colorScheme.onSurface else OnSurfaceFaint,
            )
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(allowance) { i ->
                    val spent = i >= remaining
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(
                                color = if (spent) OnSurfaceFaint.copy(alpha = 0.25f) else AccentGold,
                                shape = CircleShape,
                            ),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0D0F)
@Composable
private fun SpinControlsPreview() {
    DreamXITheme {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            SpinButton(phase = SpinPhase.Idle, round = 4, totalRounds = 11, onSpin = {}, modifier = Modifier.fillMaxWidth())
            SpinButton(phase = SpinPhase.Spinning, round = 4, totalRounds = 11, onSpin = {}, modifier = Modifier.fillMaxWidth())
            ChangeSpinControl(remaining = 2, allowance = 2, onChangeSpin = {}, modifier = Modifier.fillMaxWidth())
            ChangeSpinControl(remaining = 0, allowance = 3, onChangeSpin = {}, modifier = Modifier.fillMaxWidth())
            Text("muted", color = OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
