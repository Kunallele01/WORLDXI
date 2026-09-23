package com.dreamxi.app.feature.freemode

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dreamxi.app.core.ui.clubDisplayName
import com.dreamxi.app.core.ui.clubGroupLetter
import com.dreamxi.app.core.ui.clubSortKey
import com.dreamxi.app.core.ui.components.ClubBadge
import com.dreamxi.app.core.ui.components.DreamXiPrimaryButton
import com.dreamxi.app.core.ui.components.DreamXiSecondaryButton
import com.dreamxi.app.core.ui.components.ErrorNotice
import com.dreamxi.app.core.ui.components.RatingBadge
import com.dreamxi.app.feature.draft.FormationSlot
import com.dreamxi.app.feature.draft.SquadPlayer
import com.dreamxi.app.feature.draft.components.BenchRow
import com.dreamxi.app.feature.draft.components.PitchView
import com.dreamxi.app.feature.draft.fitAt
import com.dreamxi.app.feature.draft.ratingAt
import com.dreamxi.app.feature.draft.components.colorForFit
import com.dreamxi.app.feature.draft.sidedRoleLabel
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.OutlineSubtle
import com.dreamxi.app.ui.theme.ResultLoss
import com.dreamxi.app.ui.theme.SurfaceBase
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.SurfaceRaised2

/**
 * Free Mode: tap a position, choose a club and a season, take whoever you want.
 *
 * No spins, no rerolls, no scarcity — that is the whole point, and it is why
 * this screen has no Spin button and no round counter. What it does keep is the
 * pitch, so the XI is assembled in the same place it will be played from, and
 * the same out-of-position pricing, so a decision that would be silly in the
 * draft is still visibly silly here.
 */
@Composable
fun FreeModeScreen(
    state: FreeModeUiState,
    clubOptions: () -> List<ClubOption>,
    seasonOptions: (String) -> List<SeasonOption>,
    onSelectSlot: (FormationSlot) -> Unit,
    onClosePicker: () -> Unit,
    onChooseClub: (String) -> Unit,
    onClearClub: () -> Unit,
    onSelectSeason: (String) -> Unit,
    onPlace: (SquadPlayer) -> Unit,
    onClearSlot: (String) -> Unit,
    onStartSeason: () -> Unit,
    onRetry: () -> Unit,
    onQuit: () -> Unit,
    onCastMagic: () -> Unit,
    onCycleSlot: (FormationSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmQuit by remember { mutableStateOf(false) }

    // Same guard the draft has, and for the same reason: an accidental back
    // gesture must not throw away an XI. Back closes the picker if it is open,
    // because the nearest dismissable thing is what back should dismiss; other-
    // wise it asks. This handler used to be enabled ONLY while the picker was
    // open, so backing out of the pitch discarded every pick without a word.
    BackHandler(enabled = true) {
        when {
            // Inside the picker, back retraces the steps it came forward
            // through — squad, then seasons, then clubs — rather than throwing
            // the whole sheet away from three levels down.
            state.openSlot != null && state.selectedSeason != null ->
                state.selectedClub?.let(onChooseClub) ?: onClosePicker()
            state.openSlot != null && state.selectedClub != null -> onClearClub()
            state.openSlot != null -> onClosePicker()
            else -> confirmQuit = true
        }
    }

    if (confirmQuit) {
        QuitFreeModeDialog(
            picksMade = state.filled,
            total = state.total,
            onDismiss = { confirmQuit = false },
            onConfirm = onQuit,
        )
    }

    Box(modifier = modifier.fillMaxSize().background(SurfaceBase)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header(state)

            when {
                state.error != null && state.picks.isEmpty() -> ErrorNotice(
                    error = state.error,
                    onRetry = onRetry,
                    modifier = Modifier.padding(20.dp),
                )

                state.isLoading -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) {
                    CircularProgressIndicator(color = AccentGold, strokeWidth = 3.dp)
                }

                else -> Column(
                    Modifier.weight(1f).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Once the magic has been cast a tap means "next best in
                    // this shirt" and the picker moves to a long press. Before
                    // that there is no ladder to walk, so a tap keeps its
                    // ordinary meaning and nothing is hidden behind a hold.
                    val cycling = state.magic.isCastComplete
                    PitchView(
                        formation = state.formation,
                        picks = state.picks,
                        onSlotClick = if (cycling) onCycleSlot else onSelectSlot,
                        onSlotLongClick = if (cycling) onSelectSlot else null,
                    )
                    if (state.bench.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        BenchRow(bench = state.bench)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = when (state.magic) {
                            MagicPhase.CONJURING -> "Working the magic…"
                            MagicPhase.REVEALING -> "Working the magic…"
                            MagicPhase.DONE ->
                                (if (state.worldCup) "The best eleven any World Cup has seen. " else "The best eleven this league can field. ") +
                                    "Tap a shirt for the next best — hold to choose."
                            MagicPhase.TWEAKED ->
                                "Tweaked from the magic XI. " +
                                    "Tap a shirt for the next best — hold to choose."
                            MagicPhase.IDLE -> "Tap any position to choose a player."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.magic == MagicPhase.IDLE) OnSurfaceFaint else AccentGold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .background(SurfaceRaised1)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Offered whether or not the eleven is already full: pressing it
                // again is how the user asks for a different best side, which
                // the shuffle in MagicXi makes worth doing.
                MagicButton(
                    phase = state.magic,
                    hasPicks = state.picks.isNotEmpty(),
                    onClick = onCastMagic,
                )

                if (state.isComplete) {
                    DreamXiPrimaryButton(
                        text = if (state.worldCup) "Play the World Cup" else "Play the season",
                        onClick = onStartSeason,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = "${state.filled} of ${state.total} positions filled",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceMuted,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                DreamXiSecondaryButton(
                    text = "Back",
                    // Routed through the same confirmation as the back gesture,
                    // so the two cannot behave differently.
                    onClick = { confirmQuit = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        state.openSlot?.let { slot ->
            PlayerPicker(
                state = state,
                slot = slot,
                clubOptions = clubOptions,
                seasonOptions = seasonOptions,
                onChooseClub = onChooseClub,
                onClearClub = onClearClub,
                onSelectSeason = onSelectSeason,
                onPlace = onPlace,
                onClear = { onClearSlot(slot.id) },
                onClose = onClosePicker,
            )
        }
    }
}

@Composable
private fun QuitFreeModeDialog(
    picksMade: Int,
    total: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceRaised1,
        title = {
            Text(
                text = "Leave free mode?",
                style = MaterialTheme.typography.titleLarge,
                color = OnSurfacePrimary,
            )
        },
        text = {
            Text(
                text = if (picksMade == 0) {
                    "Nothing is picked yet, but leaving goes back to setup."
                } else {
                    "You have picked $picksMade of $total. Leaving now discards them — " +
                        "runs are not saved yet, so there is nothing to come back to."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Leave", color = ResultLoss) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep building", color = AccentGold) }
        },
    )
}

@Composable
private fun Header(state: FreeModeUiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "FREE MODE · ${state.leagueName.uppercase()}",
                style = MaterialTheme.typography.labelSmall,
                color = AccentGold,
            )
            Text(
                text = state.formation.name,
                style = MaterialTheme.typography.titleLarge,
                color = OnSurfacePrimary,
            )
        }
        state.xiRating?.let { RatingBadge(rating = it) }
    }
}

/**
 * The picker: club, then season, then the whole squad.
 *
 * The squad is NOT filtered to players who suit the position. Every man is
 * listed with what he would be worth standing there, so putting Xavi at centre
 * forward is available and visibly costly rather than hidden. Free Mode is
 * about removing constraints, not about removing information.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerPicker(
    state: FreeModeUiState,
    slot: FormationSlot,
    clubOptions: () -> List<ClubOption>,
    seasonOptions: (String) -> List<SeasonOption>,
    onChooseClub: (String) -> Unit,
    onClearClub: () -> Unit,
    onSelectSeason: (String) -> Unit,
    onPlace: (SquadPlayer) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val club = state.selectedClub
    val season = state.selectedSeason
    Box(
        Modifier
            .fillMaxSize()
            .background(SurfaceBase.copy(alpha = 0.92f))
            .clickable(indication = null, interactionSource = remembered()) { onClose() },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(16.dp)
                .clickable(indication = null, interactionSource = remembered()) { },
        ) {
            // One breadcrumb, one back arrow. The step is read from the state
            // rather than kept separately: a club with no season means "choose
            // a season", and there is then no second place for it to disagree.
            PickerHeader(
                slot = slot,
                club = club,
                season = season,
                onBack = {
                    when {
                        season != null && club != null -> onChooseClub(club)
                        club != null -> onClearClub()
                        else -> onClose()
                    }
                },
            )
            Spacer(Modifier.height(12.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    club == null -> ClubList(clubOptions(), onChooseClub, state.worldCup)

                    season == null -> SeasonList(seasonOptions(club), onSelectSeason, state.worldCup)

                    state.isLoadingSquad -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = AccentGold, strokeWidth = 3.dp)
                    }

                    state.squad.isEmpty() -> Hint("No players loaded for that squad.")

                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                        items(state.squad, key = { it.playerSeasonStatId }) { player ->
                            // The goalkeeper boundary is absolute in both
                            // directions, exactly as in the draft: EA rates a
                            // keeper about 50 points below himself outfield, so
                            // offering it would only be a way to make a mistake.
                            val allowed = (player.role == "GK") == slot.isGoalkeeper
                            PlayerRow(
                                player = player,
                                slot = slot,
                                enabled = allowed,
                                onClick = { if (allowed) onPlace(player) },
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (slot.id in state.picks) {
                    DreamXiSecondaryButton(
                        text = "Empty this position",
                        onClick = onClear,
                        modifier = Modifier.weight(1f),
                    )
                }
                DreamXiSecondaryButton(
                    text = "Close",
                    onClick = onClose,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun remembered() = androidx.compose.runtime.remember {
    androidx.compose.foundation.interaction.MutableInteractionSource()
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceFaint,
            textAlign = TextAlign.Center,
        )
    }
}

/** Role, then the trail through club and season, with one back arrow. */
@Composable
private fun PickerHeader(
    slot: FormationSlot,
    club: String?,
    season: String?,
    onBack: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "←",
            style = MaterialTheme.typography.titleLarge,
            color = OnSurfacePrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = sidedRoleLabel(slot.role).uppercase() + " · " + slot.label,
                style = MaterialTheme.typography.labelSmall,
                color = AccentGold,
            )
            if (club != null) {
                Text(
                    text = clubDisplayName(club) + if (season != null) "  ·  $season" else "",
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurfacePrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Every club in the league, alphabetically, with a search box and an A-Z rail.
 *
 * This replaced a horizontal strip of 41 chips. A strip gives no sense of how
 * far through you are, no way to jump, and no record of what has already gone
 * past — the three things a list of this length has to provide. Clubs file
 * under their real name with initialisms ignored, so FC Barcelona sits under B
 * and Real Madrid under R (see ClubName).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClubList(options: List<ClubOption>, onChoose: (String) -> Unit, worldCup: Boolean = false) {
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val groups = remember(options, query) {
        options
            .filter {
                query.isBlank() ||
                    clubDisplayName(it.name).contains(query, ignoreCase = true) ||
                    it.name.contains(query, ignoreCase = true)
            }
            .sortedBy { clubSortKey(it.name) }
            .groupBy { clubGroupLetter(it.name) }
    }
    // Where each letter starts once headers are counted in, so the rail can
    // jump straight to it.
    val letterStart = remember(groups) {
        var index = 0
        groups.mapValues { (_, rows) ->
            val start = index
            index += rows.size + 1
            start
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceRaised2)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (query.isEmpty()) {
                Text(
                    text = if (worldCup) "Search nations" else "Search clubs",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceFaint,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = OnSurfacePrimary),
                cursorBrush = SolidColor(AccentGold),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))

        if (groups.isEmpty()) {
            Hint(if (worldCup) "No nation matches that." else "No club matches that.")
            return@Column
        }

        Row(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                groups.forEach { (letter, rows) ->
                    stickyHeader(key = "header-$letter") {
                        Text(
                            text = letter,
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentGold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceBase)
                                .padding(vertical = 6.dp),
                        )
                    }
                    items(rows, key = { it.name }) { option ->
                        ClubRow(option, onChoose, worldCup)
                    }
                }
            }
            // The rail. Tap a letter to jump; it lists only letters that exist,
            // so it never promises a section that isn't there.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxHeight().padding(start = 4.dp),
            ) {
                letterStart.keys.forEach { letter ->
                    Text(
                        text = letter,
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceMuted,
                        modifier = Modifier
                            .clickable {
                                scope.launch {
                                    listState.scrollToItem(letterStart.getValue(letter))
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClubRow(option: ClubOption, onChoose: (String) -> Unit, worldCup: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceRaised1)
            .clickable { onChoose(option.name) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // The badge is resolved from the STORED name, which is what the crest
        // files are keyed on; only the label uses the display name.
        ClubBadge(clubName = option.name, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = clubDisplayName(option.name),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfacePrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    worldCup -> if (option.seasons == 1) "1 World Cup" else "${option.seasons} World Cups"
                    option.seasons == 1 -> "1 season"
                    else -> "${option.seasons} seasons"
                },
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
            )
        }
    }
}

/**
 * One club's seasons, newest first.
 *
 * With where they finished and how strong the squad was, because those are the
 * two things a season is actually chosen on — "the year they won it", "their
 * best side". The old picker offered bare labels, so the choice was blind.
 */
@Composable
private fun SeasonList(options: List<SeasonOption>, onSelect: (String) -> Unit, worldCup: Boolean = false) {
    if (options.isEmpty()) {
        Hint(if (worldCup) "No World Cups loaded for that nation." else "No seasons loaded for that club.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
        items(options, key = { it.label }) { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceRaised1)
                    .clickable { onSelect(option.label) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfacePrimary,
                    modifier = Modifier.weight(1f),
                )
                option.note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceMuted,
                    )
                }
                option.finalPosition?.takeIf { option.note == null }?.let {
                    Text(
                        text = ordinal(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (it == 1) AccentGold else OnSurfaceMuted,
                    )
                }
                option.squadStrength?.let {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "squad ${"%.1f".format(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceFaint,
                    )
                }
            }
        }
    }
}

private fun ordinal(position: Int): String {
    val suffix = when {
        position % 100 in 11..13 -> "th"
        position % 10 == 1 -> "st"
        position % 10 == 2 -> "nd"
        position % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$position$suffix"
}

@Composable
private fun PlayerRow(
    player: SquadPlayer,
    slot: FormationSlot,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val here = player.ratingAt(slot)
    val moved = here != player.overallRating
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceRaised1)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = player.fullName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) OnSurfacePrimary else OnSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (!enabled) {
                    "Goalkeepers stay in goal"
                } else if (moved) {
                    "${sidedRoleLabel(player.role)} · ${player.overallRating} natural"
                } else {
                    sidedRoleLabel(player.role)
                },
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$here",
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) colorForFit(player.fitAt(slot)) else OnSurfaceFaint,
        )
    }
}

@Preview(name = "Free mode", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 900)
@Composable
private fun FreeModePreview() {
    DreamXITheme {
        FreeModeScreen(
            state = FreeModeUiState(leagueName = "Premier League", clubs = listOf("Arsenal")),
            clubOptions = { listOf(ClubOption("Arsenal", 15)) },
            seasonOptions = { emptyList() },
            onSelectSlot = {}, onClosePicker = {}, onChooseClub = {}, onClearClub = {},
            onSelectSeason = {}, onCycleSlot = {},
            onPlace = {}, onClearSlot = {}, onStartSeason = {}, onRetry = {}, onQuit = {},
            onCastMagic = {},
        )
    }
}


/**
 * The magic button.
 *
 * Its own thing rather than a third ordinary button, because it is the only
 * control here that acts on the whole team at once. It says what it is doing
 * while it works — a button that goes silent for a second and a half reads as
 * broken — and it is inert while the shirts are landing, so a second tap
 * cannot restart the reveal halfway through.
 */
@Composable
private fun MagicButton(
    phase: MagicPhase,
    hasPicks: Boolean,
    onClick: () -> Unit,
) {
    val working = phase == MagicPhase.CONJURING || phase == MagicPhase.REVEALING
    val pulse = rememberInfiniteTransition(label = "magic")
    val alpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "magicAlpha",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (working) SurfaceRaised1 else AccentGold.copy(alpha = 0.14f))
            .clickable(enabled = !working, onClick = onClick)
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                working -> "Working the magic…"
                // Filling in around players the user has already chosen is a
                // different promise from starting over, so the button says so.
                hasPicks && phase == MagicPhase.IDLE -> "✦  Fill the rest with magic"
                hasPicks -> "✦  Work the magic again"
                else -> "✦  Work the magic"
            },
            style = MaterialTheme.typography.labelLarge,
            color = AccentGold.copy(alpha = if (working) alpha else 1f),
        )
    }
}
