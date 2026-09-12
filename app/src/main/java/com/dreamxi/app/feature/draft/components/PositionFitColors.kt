package com.dreamxi.app.feature.draft.components

import androidx.compose.ui.graphics.Color
import com.dreamxi.app.feature.draft.PositionFit
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.ResultLoss
import com.dreamxi.app.ui.theme.ResultWin

/**
 * Colour and wording for how well a player suits a slot.
 *
 * The draft lets any outfielder fill any outfield slot, priced by rating. That
 * freedom is only usable if the price is legible at a glance — nobody is going
 * to hold a seven-by-seven matrix in their head while scanning 25 players — so
 * every open slot is tinted by what the selected player would cost there.
 *
 * The bands come from the measured population spread, not from taste: adjacent
 * roles cluster at 1-6 points (a striker loses 3 at winger, 4 at CAM), genuinely
 * alien ones at 18-25 (a striker loses 22 at defensive midfield, 25 at
 * centre-back). Ten points is the gap between those two clusters.
 */
fun colorForFit(fit: PositionFit): Color = when (fit) {
    PositionFit.Natural -> AccentGold
    PositionFit.Comfortable -> ResultWin
    PositionFit.Stretch -> Color(0xFFE0A83C)
    PositionFit.Alien -> ResultLoss
}

fun labelForFit(fit: PositionFit): String = when (fit) {
    PositionFit.Natural -> "Natural"
    PositionFit.Comfortable -> "Comfortable"
    PositionFit.Stretch -> "Out of position"
    PositionFit.Alien -> "Badly out of position"
}

/** Muted variant, for slots that are filled rather than being considered. */
fun mutedFitColor(fit: PositionFit): Color =
    if (fit == PositionFit.Natural) OnSurfaceMuted else colorForFit(fit)
