package com.dreamxi.app.feature.draft.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamxi.app.core.ui.components.RatingBadge
import com.dreamxi.app.core.ui.components.RatingBadgeSize
import com.dreamxi.app.core.ui.components.RatingBadgeStyle
import com.dreamxi.app.feature.draft.DefensiveRecord
import com.dreamxi.app.feature.draft.SquadPlayer
import com.dreamxi.app.feature.draft.nationalityLabel
import com.dreamxi.app.feature.draft.scoutLine
import com.dreamxi.app.feature.draft.sidedRoleLabel
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.OutlineSubtle
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.colorForPosition

/**
 * One selectable player from the spun squad.
 *
 * Layout is a strict three-zone scan: rating badge (is he good?), name and
 * role (who and what shape?), then a one-line scouting description of what he
 * actually did that season.
 *
 * The description ([scoutLine]) replaces a bare "2711' · 27G · 12A" stat
 * string. Raw counts require the reader to hold every role's normal range in
 * their head — 0.20 goals per 90 is a poor striker and an outstanding
 * defensive midfielder — so the line does that comparison for them against
 * measured per-role baselines. Minutes still surface in it, because a
 * 91-rated player with 168 minutes is a trap the rating alone cannot warn you
 * about and the draft explicitly allows picking him.
 *
 * A player already drafted in an earlier round is dimmed and inert rather
 * than removed: identity is unique across the whole draft, so the same person
 * can reappear via a different club-season, and silently deleting him from
 * the list reads as a data bug rather than a rule.
 */
@Composable
fun SquadPlayerRow(
    player: SquadPlayer,
    selected: Boolean,
    selectable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    defence: DefensiveRecord? = null,
) {
    val positionColor = colorForPosition(player.group)
    val shape = RoundedCornerShape(12.dp)
    val enabled = selectable && !player.alreadyDrafted

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) positionColor.copy(alpha = 0.12f) else SurfaceRaised1)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) positionColor else OutlineSubtle,
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        RatingBadge(
            rating = player.overallRating,
            size = RatingBadgeSize.Small,
            style = if (selected) RatingBadgeStyle.Solid else RatingBadgeStyle.Tonal,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = player.fullName,
                style = MaterialTheme.typography.titleSmall,
                color = OnSurfacePrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Sided label. Where EA states a side we use it ("RB"); where
                // it says both, or says nothing, we name both slots ("LB/RB")
                // rather than inventing a side. This is now load-bearing, not
                // decoration: a wrong-flank placement costs rating points (see
                // SquadPlayer.sidePenaltyAt), so the tag has to say which side
                // he is actually on.
                RoleTag(role = player.displayRole(), color = positionColor)
                Text(
                    text = statLine(player),
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val description = if (player.alreadyDrafted) {
                "Already in your XI — picked in an earlier round."
            } else {
                // Nationality leads the line: it is the fastest way to
                // recognise a player you half-remember, and it costs one clause.
                listOfNotNull(nationalityLabel(player.nationality), scoutLine(player, defence))
                    .joinToString(" · ")
                    .takeIf { it.isNotBlank() }
            }
            if (description != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = if (player.alreadyDrafted) OnSurfaceFaint else OnSurfaceMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The compact counts that sit beside the role tag. Kept alongside the prose
 * description rather than replaced by it: the sentence gives the judgement,
 * these give the raw numbers for anyone who wants to check it.
 */
/**
 * "FB" plus EA's side where it states one. 'B' (genuinely both flanks, 21% of
 * fullbacks and 28% of wingers) and unknown both fall through to naming both
 * slots, because "plays either" and "we don't know" should not be dressed up
 * as a definite side.
 */
private fun SquadPlayer.displayRole(): String = when {
    side == "L" && role == "FB" -> "LB"
    side == "R" && role == "FB" -> "RB"
    side == "L" && role == "Winger" -> "LW"
    side == "R" && role == "Winger" -> "RW"
    else -> sidedRoleLabel(role)
}

private fun statLine(p: SquadPlayer): String = buildString {
    append("${p.appearances.coerceAtLeast(1)} apps")
    if (p.goals > 0) append(" · ${p.goals}G")
    if (p.assists > 0) append(" · ${p.assists}A")
}

@Composable
private fun RoleTag(role: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Box(Modifier.size(4.dp).background(color, CircleShape))
        Text(
            text = role.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            lineHeight = 13.sp,
            letterSpacing = 0.6.sp,
            color = color,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0D0F)
@Composable
private fun SquadPlayerRowPreview() {
    DreamXITheme {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SquadPlayerRow(PreviewSquad[12], selected = false, selectable = true, onClick = {})
            SquadPlayerRow(PreviewSquad[6], selected = true, selectable = true, onClick = {})
            SquadPlayerRow(PreviewSquad[13], selected = false, selectable = true, onClick = {})
            SquadPlayerRow(PreviewSquad[15], selected = false, selectable = true, onClick = {})
            SquadPlayerRow(PreviewSquad[0], selected = false, selectable = false, onClick = {})
            Box(Modifier.height(2.dp))
        }
    }
}
