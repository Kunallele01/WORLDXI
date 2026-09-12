package com.dreamxi.app.feature.draft.components

import com.dreamxi.app.core.ui.playerSurname
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamxi.app.feature.draft.DraftedPlayer
import com.dreamxi.app.feature.draft.Formation
import com.dreamxi.app.feature.draft.FormationSlot
import com.dreamxi.app.feature.draft.PositionFit
import com.dreamxi.app.feature.draft.formationById
import com.dreamxi.app.ui.theme.DisplayFontFamily
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OutlineSubtle
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.colorForPosition
import com.dreamxi.app.ui.theme.colorForRating

/**
 * The XI as a compact horizontal rail, in the formation's own pitch order.
 *
 * This is the between-picks progress view: it survives while the squad list is
 * scrolling, and answers "what am I still missing" without costing the 400dp a
 * pitch needs. Placement itself happens on the real pitch ([PitchView]) — this
 * rail deliberately does no previewing, so there is exactly one surface where
 * a player gets positioned and no chance of the two disagreeing.
 *
 * Empty positions stay drawn as outlines: "what is left" has to read as
 * negative space, which it cannot do if the gaps are simply absent.
 */
@Composable
fun XiSlotRail(
    formation: Formation,
    picks: Map<String, DraftedPlayer>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = contentPadding,
    ) {
        items(formation.slots, key = { it.id }) { slot ->
            SlotChip(slot = slot, pick = picks[slot.id])
        }
    }
}

private val ChipShape = RoundedCornerShape(10.dp)

@Composable
private fun SlotChip(slot: FormationSlot, pick: DraftedPlayer?) {
    val positionColor = colorForPosition(slot.group)
    val accent = pick?.let { colorForRating(it.effectiveRating) } ?: positionColor

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(62.dp)
            .clip(ChipShape)
            .background(SurfaceRaised1)
            .border(
                width = 1.dp,
                color = if (pick != null) accent.copy(alpha = 0.5f) else OutlineSubtle,
                shape = ChipShape,
            )
            .padding(vertical = 7.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(5.dp).background(positionColor, CircleShape))
            Text(
                text = slot.label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                letterSpacing = 0.8.sp,
                color = if (pick != null) OnSurfaceMuted else positionColor,
            )
        }
        Spacer(Modifier.height(3.dp))
        if (pick != null) {
            Text(
                text = pick.effectiveRating.toString(),
                fontFamily = DisplayFontFamily,
                fontSize = 24.sp,
                lineHeight = 24.sp,
                letterSpacing = (-0.5).sp,
                color = accent,
            )
            Text(
                text = playerSurname(pick.player.fullName),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                lineHeight = 12.sp,
                // An out-of-position pick keeps its warning colour after the
                // fact: it is a standing property of the XI, not a hint that
                // expires once the pick is made.
                color = if (pick.fit == PositionFit.Natural) OnSurfaceMuted else colorForFit(pick.fit),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // A dash where the number goes, so every chip is the same height
            // and the rail does not reflow as it fills up.
            Text(
                text = "–",
                fontFamily = DisplayFontFamily,
                fontSize = 24.sp,
                lineHeight = 24.sp,
                color = OnSurfaceFaint,
            )
            Text(
                text = "OPEN",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                lineHeight = 12.sp,
                letterSpacing = 0.8.sp,
                color = OnSurfaceFaint,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0D0F)
@Composable
private fun XiSlotRailPreview() {
    DreamXITheme {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            XiSlotRail(formation = formationById("4-3-3"), picks = PreviewPitchPicks)
            XiSlotRail(formation = formationById("3-5-2"), picks = emptyMap())
        }
    }
}
