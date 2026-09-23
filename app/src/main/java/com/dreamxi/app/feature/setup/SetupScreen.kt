package com.dreamxi.app.feature.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.core.ui.components.DreamXiPrimaryButton
import com.dreamxi.app.core.ui.components.DreamXiSecondaryButton
import com.dreamxi.app.core.ui.components.ErrorNotice
import com.dreamxi.app.data.draft.League
import com.dreamxi.app.feature.draft.Formation
import com.dreamxi.app.feature.draft.Formations
import com.dreamxi.app.feature.draft.components.MiniPitch
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DisplayFontFamily
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnAccentGold
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.OutlineSubtle
import com.dreamxi.app.ui.theme.SurfaceBase
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.SurfaceRaised2

/**
 * The screen that starts a run: pick a league, pick a difficulty, go.
 *
 * This is the step that was missing — the draft was reaching straight into
 * the database and taking whichever league came back first, so a Premier
 * League run was the only run you could ever get. The league is the one
 * genuine choice at setup, so it gets the whole screen rather than a dropdown.
 *
 * Deliberately NOT here: a season picker. Every draft round spins its own
 * random (season, club) pair from the chosen league's entire loaded history,
 * and the season that gets simulated is decided after the XI is finished.
 * Fixing a season at setup would remove the cross-era mixing that is the
 * point of the game.
 */
@Composable
fun SetupScreen(
    state: SetupUiState,
    onSelectLeague: (Long) -> Unit,
    onSetRerolls: (Int) -> Unit,
    onSelectFormation: (Formation) -> Unit,
    onStart: (League) -> Unit,
    onStartFreeMode: (League) -> Unit = {},
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectMode: (RunMode) -> Unit = {},
    onStartWorldCup: () -> Unit = {},
    onStartWorldCupFreeMode: () -> Unit = {},
) {
    val worldCup = state.mode == RunMode.WorldCup
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBase)
            .statusBarsPadding(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = "NEW RUN",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.6.sp,
                color = AccentGold,
            )
            Spacer(Modifier.height(8.dp))
            ModeSwitch(selected = state.mode, onSelect = onSelectMode)
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (worldCup) "WORLD CUP" else "PICK YOUR LEAGUE",
                fontFamily = DisplayFontFamily,
                fontSize = 34.sp,
                lineHeight = 38.sp,
                color = OnSurfacePrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (worldCup) {
                    "Every round spins a nation at a World Cup, any nation, any year. " +
                        "Once your XI is done, a World Cup is drawn and you take a bottom-of-group place."
                } else {
                    "Every round spins a random club and season from this league's history. " +
                        "You'll draft one player from each."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
            )
        }

        Box(Modifier.weight(1f)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AccentGold)
                }

                state.error != null -> Box(
                    Modifier.fillMaxSize().padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ErrorNotice(error = state.error, onRetry = onRetry)
                }

                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (worldCup) {
                        WorldCupCard()
                    } else {
                        state.leagues.forEach { league ->
                            LeagueCard(
                                league = league,
                                selected = league.id == state.selectedLeagueId,
                                onClick = { onSelectLeague(league.id) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    FormationPicker(selected = state.formation, onSelect = onSelectFormation)
                    Spacer(Modifier.height(18.dp))
                    ChangeSpinPicker(selected = state.rerollsAllowed, onSelect = onSetRerolls)
                    Spacer(Modifier.height(20.dp))
                }
            }
        }

        if (state.error == null && !state.isLoading) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(SurfaceRaised1)
                    .padding(horizontal = 20.dp, vertical = 14.dp)
                    .navigationBarsPadding(),
            ) {
                if (worldCup) {
                    DreamXiPrimaryButton(
                        text = "Start World Cup run",
                        onClick = onStartWorldCup,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    DreamXiSecondaryButton(
                        text = "Free mode — build any XI",
                        onClick = onStartWorldCupFreeMode,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    DreamXiPrimaryButton(
                        text = state.selectedLeague?.let { "Start ${it.name} run" } ?: "Choose a league",
                        onClick = { state.selectedLeague?.let(onStart) },
                        enabled = state.canStart,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    // The way out of the draft's constraints. Secondary because the
                    // spin draft is the game; Free Mode is the sandbox beside it.
                    DreamXiSecondaryButton(
                        text = "Free mode — build any XI",
                        onClick = { state.selectedLeague?.let(onStartFreeMode) },
                        enabled = state.canStart,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * League or World Cup. Two segments at the top of the one start screen, so the
 * formation and change-spin choices below them are shared rather than repeated.
 */
@Composable
private fun ModeSwitch(selected: RunMode, onSelect: (RunMode) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceRaised1)
            .border(1.dp, OutlineSubtle, shape)
            .padding(4.dp),
    ) {
        listOf(RunMode.League to "LEAGUE", RunMode.WorldCup to "WORLD CUP").forEach { (mode, label) ->
            val isSel = mode == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSel) AccentGold else SurfaceRaised1)
                    .clickable { onSelect(mode) }
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    letterSpacing = 1.sp,
                    color = if (isSel) OnAccentGold else OnSurfaceMuted,
                )
            }
        }
    }
}

/** What World Cup mode draws from. Fixed facts about the loaded data, not a picker. */
@Composable
private fun WorldCupCard() {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(AccentGold.copy(alpha = 0.10f))
            .border(1.5.dp, AccentGold, shape)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            text = "2006 – 2026",
            fontFamily = DisplayFontFamily,
            fontSize = 26.sp,
            lineHeight = 30.sp,
            color = OnSurfacePrimary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Six World Cups · every nation's real squad · real results and scorers",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted,
        )
    }
}

@Composable
private fun LeagueCard(league: League, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val empty = league.seasonCount == 0
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) AccentGold.copy(alpha = 0.10f) else SurfaceRaised1)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) AccentGold else OutlineSubtle,
                shape = shape,
            )
            .clickable(enabled = !empty, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = league.name.uppercase(),
                fontFamily = DisplayFontFamily,
                fontSize = 26.sp,
                lineHeight = 30.sp,
                color = if (empty) OnSurfaceFaint else OnSurfacePrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                // Says what the league actually contains. A league with no
                // seasons loaded is the difference between "pick this" and a
                // draft that cannot spin, so it is stated up front.
                text = if (empty) {
                    "No seasons loaded yet"
                } else {
                    "${league.country.ifBlank { "—" }} · ${league.seasonCount} seasons · ${league.seasonRange}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) AccentGold else SurfaceRaised2)
                .border(1.dp, if (selected) AccentGold else OutlineSubtle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(8.dp).background(OnAccentGold, CircleShape))
            }
        }
    }
}

/**
 * The shape you commit to for the whole run.
 *
 * Every real-world formation is offered rather than a curated handful: the
 * whole point is drafting to a plan a manager would actually pick, and each
 * shape is only eleven coordinates, so breadth is cheap. It scrolls
 * horizontally in groups by defender count, because "how many at the back" is
 * the first thing anyone decides.
 *
 * Each option draws its actual shape rather than only naming it. "4-2-3-1"
 * means nothing to a casual player until they see the dots; and even for
 * someone fluent, the picture is faster than the number.
 */
@Composable
private fun FormationPicker(selected: Formation, onSelect: (Formation) -> Unit) {
    Column {
        Text(
            text = "FORMATION",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
            color = AccentGold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Locked for the whole run. ${selected.description}",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(10.dp))
        listOf(4, 3, 5).forEach { defenders ->
            val group = Formations.filter { it.defenderCount == defenders }
            if (group.isEmpty()) return@forEach
            Text(
                text = "$defenders AT THE BACK",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                color = OnSurfaceFaint,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(group, key = { it.id }) { formation ->
                    FormationCard(
                        formation = formation,
                        selected = formation.id == selected.id,
                        onClick = { onSelect(formation) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun FormationCard(formation: Formation, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(104.dp)
            .clip(shape)
            .background(if (selected) AccentGold.copy(alpha = 0.12f) else SurfaceRaised1)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) AccentGold else OutlineSubtle,
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
    ) {
        MiniPitch(
            formation = formation,
            accent = if (selected) AccentGold else OnSurfaceMuted,
            modifier = Modifier.fillMaxWidth().height(76.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = formation.id,
            fontFamily = DisplayFontFamily,
            fontSize = 20.sp,
            lineHeight = 22.sp,
            color = if (selected) AccentGold else OnSurfacePrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Difficulty dial. Change spins let a player reject a squad they hate and
 * spin again; the allowance is the whole difficulty setting, so it is set
 * before a run and then fixed for that run — changing it later would
 * retroactively rewrite how hard a finished draft was.
 *
 * Measured effect is about +0.4 XI rating per change spin, so no setting
 * trivialises a run; the labels say that honestly rather than implying
 * "easy mode".
 */
@Composable
private fun ChangeSpinPicker(selected: Int, onSelect: (Int) -> Unit) {
    Column {
        Text(
            text = "CHANGE SPINS",
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
            color = AccentGold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "How many times you can reject a squad and spin again.",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(1 to "Tough", 2 to "Standard", 3 to "Relaxed").forEach { (count, label) ->
                val isSel = count == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSel) AccentGold.copy(alpha = 0.12f) else SurfaceRaised1)
                        .border(
                            width = if (isSel) 1.5.dp else 1.dp,
                            color = if (isSel) AccentGold else OutlineSubtle,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable { onSelect(count) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        text = "$count",
                        fontFamily = DisplayFontFamily,
                        fontSize = 28.sp,
                        lineHeight = 30.sp,
                        color = if (isSel) AccentGold else OnSurfaceMuted,
                    )
                    Text(
                        text = label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        letterSpacing = 0.8.sp,
                        color = if (isSel) OnSurfacePrimary else OnSurfaceFaint,
                    )
                }
            }
        }
    }
}

private val PreviewLeagues = listOf(
    League(4, "Premier League", "England", 5, "2019/20 – 2023/24"),
    League(5, "La Liga", "Spain", 5, "2019/20 – 2023/24"),
    League(9, "Serie A", "Italy", 0, ""),
)

@Preview(showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 900)
@Composable
private fun SetupScreenPreview() {
    DreamXITheme {
        SetupScreen(
            state = SetupUiState(leagues = PreviewLeagues, selectedLeagueId = 5, isLoading = false),
            onSelectLeague = {}, onSetRerolls = {}, onSelectFormation = {}, onStart = {}, onRetry = {},
        )
    }
}

@Preview(name = "Offline", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 900)
@Composable
private fun SetupScreenOfflinePreview() {
    DreamXITheme {
        SetupScreen(
            state = SetupUiState(isLoading = false, error = DreamXiError.Offline),
            onSelectLeague = {}, onSetRerolls = {}, onSelectFormation = {}, onStart = {}, onRetry = {},
        )
    }
}
