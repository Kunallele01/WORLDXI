package com.dreamxi.app.feature.draft.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamxi.app.core.ui.components.ClubBadge
import com.dreamxi.app.feature.draft.SpinResult
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DisplayFontFamily
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.OutlineSubtle
import com.dreamxi.app.ui.theme.ResultWin
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.SurfaceRaised2

/**
 * The reveal card: which club-season the spin landed on.
 *
 * This is the payoff of pressing Spin, so it is the loudest surface on the
 * screen after the button itself — club name in the display face at 34sp,
 * season immediately beneath it, and the two facts that decide whether the
 * user is happy (where they finished, how strong the squad is) as chips.
 *
 * Squad strength is shown as a NAMED TIER, not the raw 84.93. The number is
 * meaningless to a player without the distribution in their head, and the
 * quartile is exactly the thing the draft's caps operate on, so naming it
 * keeps the UI and the rules speaking the same language.
 */
@Composable
fun SquadRevealCard(
    spin: SpinResult,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceRaised1)
            .border(1.dp, OutlineSubtle, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            text = "THIS ROUND'S SQUAD",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            color = AccentGold,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClubBadge(clubName = spin.clubName, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                text = spin.clubName.uppercase(),
                fontFamily = DisplayFontFamily,
                fontSize = 34.sp,
                lineHeight = 36.sp,
                letterSpacing = 0.5.sp,
                color = OnSurfacePrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = spin.seasonLabel,
                fontFamily = DisplayFontFamily,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                color = AccentGold,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            spin.finalPosition?.let { FactChip(label = "FINISHED", value = ordinal(it), accent = finishAccent(it)) }
            spin.strengthQuartile?.let { FactChip(label = "SQUAD", value = tierName(it), accent = quartileAccent(it)) }
            Text(
                text = "${spin.players.size} PLAYERS",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
        spin.detail?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun FactChip(label: String, value: String, accent: androidx.compose.ui.graphics.Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(SurfaceRaised2)
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Box(Modifier.size(5.dp).background(accent, CircleShape))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            letterSpacing = 0.8.sp,
            color = OnSurfaceFaint,
        )
        Text(
            text = value.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            color = OnSurfacePrimary,
        )
    }
}

/**
 * Quartile 1 is the weakest, 4 the strongest — see `club_season_draft_pool`.
 * Names, not numbers: "STACKED" tells a player what they got, "Q4" does not.
 */
private fun tierName(quartile: Int) = when (quartile) {
    4 -> "Stacked"
    3 -> "Strong"
    2 -> "Middling"
    else -> "Thin"
}

private fun quartileAccent(quartile: Int) = when (quartile) {
    4 -> AccentGold
    3 -> ResultWin
    2 -> OnSurfaceMuted
    else -> OnSurfaceFaint
}

private fun finishAccent(position: Int) = when {
    position == 1 -> AccentGold
    position <= 4 -> ResultWin
    position >= 18 -> com.dreamxi.app.ui.theme.ResultLoss
    else -> OnSurfaceMuted
}

private fun ordinal(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

/**
 * The reel shown while a spin resolves — club names flicking past.
 *
 * A spin needs to feel like a wheel stopping, not like a loading spinner. The
 * names cycling are real club names from the pool, so the moment reads as
 * "it's choosing between these" rather than as generic waiting. Flat colour
 * and opacity only; the design language rules out gloss, so the energy is
 * entirely in the motion.
 */
@Composable
fun SpinningReelCard(
    names: List<String>,
    modifier: Modifier = Modifier,
    caption: String = "choosing a club and a season…",
) {
    val transition = rememberInfiniteTransition(label = "reel")
    // Drives a name index; ~11 swaps a second reads as a blur that the eye can
    // still resolve into individual clubs, which is what sells the mechanic.
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = names.size.coerceAtLeast(1).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = names.size.coerceAtLeast(1) * 90),
            repeatMode = RepeatMode.Restart,
        ),
        label = "reelIndex",
    )
    val flicker by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(180), repeatMode = RepeatMode.Reverse),
        label = "reelFlicker",
    )
    val current = names.getOrNull(progress.toInt() % names.size.coerceAtLeast(1)) ?: ""

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceRaised1)
            .border(1.dp, AccentGold.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            text = "SPINNING",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            letterSpacing = 1.4.sp,
            color = AccentGold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = current.uppercase(),
            fontFamily = DisplayFontFamily,
            fontSize = 34.sp,
            lineHeight = 36.sp,
            letterSpacing = 0.5.sp,
            color = OnSurfacePrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(flicker),
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .height(24.dp)
                .fillMaxWidth()
                .alpha(0.35f),
        ) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0D0F)
@Composable
private fun SquadRevealPreview() {
    DreamXITheme {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SquadRevealCard(spin = PreviewSpin)
            SquadRevealCard(
                spin = PreviewSpin.copy(
                    clubName = "Sheffield United",
                    seasonLabel = "2023/24",
                    finalPosition = 20,
                    strengthQuartile = 1,
                ),
            )
            SpinningReelCard(names = listOf("Getafe", "Sevilla", "Valencia", "Real Betis"))
        }
    }
}
