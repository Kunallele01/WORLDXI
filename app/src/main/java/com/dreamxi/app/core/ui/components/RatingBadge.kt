package com.dreamxi.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamxi.app.ui.theme.DisplayFontFamily
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnAccentGold
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.colorForRating
import com.dreamxi.app.ui.theme.darken

/**
 * A player/XI overall rating, drawn as a compact stat tile.
 *
 * Two things were wrong with the first version and both are fixed here.
 *
 * 1. THE COLOUR CARRIED NO INFORMATION. The scale was normalised over 0-99
 *    while real ratings span 63-84 at the 5th/95th percentiles, so ~83% of
 *    players landed on the same green. See [colorForRating] — the scale is
 *    now keyed to measured rating values. That fix lives in the colour
 *    system, not here, but it is the reason a list of these now reads as a
 *    range instead of a wall.
 *
 * 2. THE NUMERAL WAS TYPESET AS BODY TEXT. It used Inter SemiBold 22sp
 *    (`titleLarge`) inside a 44dp square, so it sat as a loose blob of text
 *    in a coloured box with no internal hierarchy. The type system reserves
 *    the condensed display face for exactly this ("rating badge numerals",
 *    Type.kt) — a two-digit number in Bebas Neue is dense, tabular and
 *    unmistakably a *score*. Negative letter spacing tightens the pair, and
 *    a small OVR caption underneath gives the tile a top-to-bottom
 *    hierarchy so the number reads as labelled data rather than loose text.
 *
 * [Style.Tonal] is the default and the one used in lists: a dark tile washed
 * with the rating colour, with the numeral and rim carrying the full-strength
 * hue. Twenty-five saturated blocks in a squad list is noise; twenty-five
 * tinted tiles with vivid numerals is a scannable gradient.
 * [Style.Solid] is for hero moments only — the drafted player, the XI's
 * overall on the results board — where there is exactly one on screen.
 */
enum class RatingBadgeStyle { Tonal, Solid }

enum class RatingBadgeSize(
    internal val width: Dp,
    internal val height: Dp,
    internal val numberSize: TextUnit,
    internal val showCaption: Boolean,
) {
    /** Dense list rows — squad list, XI slot rail. */
    Small(38.dp, 42.dp, 22.sp, showCaption = false),

    /** Default: player cards, pick confirmation. */
    Medium(48.dp, 54.dp, 28.sp, showCaption = true),

    /** Hero: the just-drafted player, XI overall on the results board. */
    Large(72.dp, 82.dp, 44.sp, showCaption = true),
}

@Composable
fun RatingBadge(
    rating: Int,
    modifier: Modifier = Modifier,
    size: RatingBadgeSize = RatingBadgeSize.Medium,
    style: RatingBadgeStyle = RatingBadgeStyle.Tonal,
) {
    val accent = colorForRating(rating)
    val shape = RoundedCornerShape(if (size == RatingBadgeSize.Large) 14.dp else 9.dp)

    val fill = when (style) {
        // A wash of the rating colour over the raised surface. Keeps the tile
        // quiet in a list while still tinting warm/cool with quality.
        RatingBadgeStyle.Tonal -> accent.copy(alpha = 0.14f)
        RatingBadgeStyle.Solid -> accent
    }
    val numberColor = when (style) {
        RatingBadgeStyle.Tonal -> accent
        RatingBadgeStyle.Solid -> OnAccentGold
    }
    val rimColor = when (style) {
        RatingBadgeStyle.Tonal -> accent.copy(alpha = 0.55f)
        RatingBadgeStyle.Solid -> accent.darken(0.35f)
    }
    val captionColor = when (style) {
        RatingBadgeStyle.Tonal -> accent.copy(alpha = 0.65f)
        RatingBadgeStyle.Solid -> OnAccentGold.copy(alpha = 0.7f)
    }

    Box(
        modifier = modifier
            .size(width = size.width, height = size.height)
            .clip(shape)
            .background(SurfaceRaised1)
            .background(fill)
            .border(width = 1.dp, color = rimColor, shape = shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = rating.toString(),
                fontFamily = DisplayFontFamily,
                fontSize = size.numberSize,
                // Bebas is already condensed; pulling the pair tighter makes
                // a two-digit number read as one glyph-block, like a scoreline.
                letterSpacing = (-0.5).sp,
                lineHeight = size.numberSize,
                color = numberColor,
                textAlign = TextAlign.Center,
            )
            if (size.showCaption) {
                Text(
                    text = "OVR",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = if (size == RatingBadgeSize.Large) 10.sp else 8.sp,
                    lineHeight = 10.sp,
                    letterSpacing = 1.2.sp,
                    color = captionColor,
                )
            }
        }
    }
}

@Preview(name = "Across the real rating range", showBackground = true, backgroundColor = 0xFF0C0D0F)
@Composable
private fun RatingBadgeRangePreview() {
    DreamXITheme {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
        ) {
            // p5..p95 of the real distribution — this row is the regression
            // test for the calibration fix. If these five look alike, the
            // scale has drifted back to being decorative.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(63, 70, 76, 80, 84).forEach { RatingBadge(rating = it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(50, 65, 72, 78, 83, 88, 91).forEach {
                    RatingBadge(rating = it, size = RatingBadgeSize.Small)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RatingBadge(rating = 91, size = RatingBadgeSize.Large, style = RatingBadgeStyle.Solid)
                RatingBadge(rating = 78, size = RatingBadgeSize.Large)
                RatingBadge(rating = 66, size = RatingBadgeSize.Large, style = RatingBadgeStyle.Solid)
            }
            Box(Modifier.height(4.dp).width(1.dp))
        }
    }
}
