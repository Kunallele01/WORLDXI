package com.dreamxi.app.feature.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dreamxi.app.core.ui.components.DreamXiDivider
import com.dreamxi.app.core.ui.components.DreamXiPrimaryButton
import com.dreamxi.app.core.ui.components.DreamXiSecondaryButton
import com.dreamxi.app.core.ui.components.RatingBadge
import com.dreamxi.app.core.ui.components.TraitChip
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.PositionDefender
import com.dreamxi.app.ui.theme.PositionForward
import com.dreamxi.app.ui.theme.PositionGoalkeeper
import com.dreamxi.app.ui.theme.PositionMidfielder
import com.dreamxi.app.ui.theme.ResultDraw
import com.dreamxi.app.ui.theme.ResultLoss
import com.dreamxi.app.ui.theme.ResultWin
import com.dreamxi.app.ui.theme.SurfaceRaised2

/**
 * Living reference for the §10 design system — every token and atomic
 * component in one scrollable screen so it can be visually approved before
 * real screens get built against it (§13 Milestone 4). Not part of the
 * shipped screen inventory (§10.6); pull the "design_system" route out of
 * DreamXiNavHost once approved.
 */
@Composable
fun DesignSystemScreen(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item { SectionHeader("Dream XI — Design System") }

        item {
            Section(title = "Type scale") {
                Text("76 PTS", style = MaterialTheme.typography.displayLarge)
                Text("Champions League Final", style = MaterialTheme.typography.headlineLarge)
                Text("Squad Overview", style = MaterialTheme.typography.headlineSmall)
                Text("Player Card Title", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Body copy for stat lines, descriptions, and the tactical write-up on the results screen reads here — Inter at 16sp.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text("LABEL / BUTTON TEXT", style = MaterialTheme.typography.labelLarge)
            }
        }

        item {
            Section(title = "Accent & surfaces") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Swatch(AccentGold, "Accent")
                    Swatch(SurfaceRaised2, "Surface")
                }
            }
        }

        item {
            Section(title = "Position colors") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Swatch(PositionGoalkeeper, "GK")
                    Swatch(PositionDefender, "DEF")
                    Swatch(PositionMidfielder, "MID")
                    Swatch(PositionForward, "FWD")
                }
            }
        }

        item {
            Section(title = "Result colors") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Swatch(ResultWin, "Win")
                    Swatch(ResultDraw, "Draw")
                    Swatch(ResultLoss, "Loss")
                }
            }
        }

        item {
            Section(title = "Rating badges — continuous grey → yellow → green → gold scale") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RatingBadge(rating = 40)
                    RatingBadge(rating = 52)
                    RatingBadge(rating = 63)
                    RatingBadge(rating = 71)
                    RatingBadge(rating = 79)
                    RatingBadge(rating = 87)
                    RatingBadge(rating = 95)
                }
            }
        }

        item {
            Section(title = "Trait chips") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TraitChip(label = "Playmaker")
                    TraitChip(label = "Aerial Threat")
                    TraitChip(label = "Pacy")
                }
            }
        }

        item {
            Section(title = "Buttons") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DreamXiPrimaryButton(text = "Spin", onClick = {}, modifier = Modifier.fillMaxWidth())
                    DreamXiSecondaryButton(text = "Reroll", onClick = {}, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(text = title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Normal)
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
        DreamXiDivider()
    }
}

@Composable
private fun Swatch(color: androidx.compose.ui.graphics.Color, label: String) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(color = color, shape = RoundedCornerShape(12.dp)),
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}
