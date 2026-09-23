package com.dreamxi.app.feature.draft

import com.dreamxi.app.core.ui.playerSurname
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.core.ui.components.DreamXiSecondaryButton
import com.dreamxi.app.core.ui.components.DreamXiPrimaryButton
import com.dreamxi.app.core.ui.components.ErrorNotice
import com.dreamxi.app.core.ui.components.RatingBadge
import com.dreamxi.app.core.ui.components.RatingBadgeSize
import com.dreamxi.app.core.ui.components.RatingBadgeStyle
import com.dreamxi.app.feature.draft.components.ChangeSpinControl
import com.dreamxi.app.feature.draft.components.colorForFit
import com.dreamxi.app.feature.draft.components.FitLegend
import com.dreamxi.app.feature.draft.components.BenchRow
import com.dreamxi.app.feature.draft.components.PitchView
import com.dreamxi.app.feature.draft.components.PreviewSpin
import com.dreamxi.app.feature.draft.components.PreviewCompleteState
import com.dreamxi.app.feature.draft.components.PreviewState
import com.dreamxi.app.feature.draft.components.SpinButton
import com.dreamxi.app.feature.draft.components.SpinPhase
import com.dreamxi.app.feature.draft.components.SpinningReelCard
import com.dreamxi.app.feature.draft.components.SquadPlayerRow
import com.dreamxi.app.feature.draft.components.SquadRevealCard
import com.dreamxi.app.feature.draft.components.XiSlotRail
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DisplayFontFamily
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.SurfaceBase
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.ResultLoss
import com.dreamxi.app.ui.theme.SurfaceRaised2

/**
 * Round-by-round: SPIN -> reveal a club-season -> pick any player from it ->
 * place him anywhere on the pitch -> repeat, eleven times.
 *
 * LAYOUT. Three fixed zones and one scrolling, because all three fixed things
 * are needed WHILE scrolling a 25-player list:
 *
 *   1. Header — league, formation, running XI rating. Small; it is context.
 *   2. XI rail — which positions are still open. The single biggest input to a
 *      pick, so it must never scroll away.
 *   3. Scrolling squad list — the reveal card scrolls with it, because once
 *      you are reading players the club name has done its job.
 *   4. Action bar — pinned, thumb-reachable, one primary action at a time.
 *
 * Tapping a player raises the PITCH as a placement sheet over the whole
 * screen. That is the one place a player gets positioned, and it is a separate
 * surface for a reason: a pitch large enough to read needs ~400dp, which does
 * not coexist with a scrollable squad list on a phone. Splitting them by
 * moment rather than by space gives both the room they need.
 */
@Composable
fun DraftScreen(
    state: DraftUiState,
    onSpin: () -> Unit,
    onChangeSpin: () -> Unit,
    onSelectPlayer: (SquadPlayer?) -> Unit,
    onConfirmPick: (SquadPlayer, FormationSlot) -> Unit,
    onRetry: () -> Unit,
    onCancelPlacement: () -> Unit,
    onViewTeam: () -> Unit,
    onCloseTeam: () -> Unit,
    onHoldPlaced: (String) -> Unit,
    onMoveHeldTo: (String) -> Unit,
    onQuitRun: () -> Unit,
    onStartSeason: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var confirmQuit by remember { mutableStateOf(false) }

    // Back must not silently bin a run. Eleven spins is real work, and the
    // system back gesture is easy to trigger by accident — it used to drop
    // straight out to Setup with everything lost and no warning. While the
    // placement sheet is open, back closes that instead, because the nearest
    // dismissable thing is what a user expects back to dismiss.
    BackHandler(enabled = true) {
        when {
            state.isPlacing -> onCancelPlacement()
            state.isViewingTeam -> onCloseTeam()
            // ALWAYS confirm, including before the first pick. This used to
            // quit outright while the XI was still empty, on the reasoning that
            // there was nothing to lose — but there is: the league, the
            // formation and the reroll allowance were all chosen at Setup, and
            // an accidental back gesture on the spin screen threw the user all
            // the way out with no warning at all.
            else -> confirmQuit = true
        }
    }

    if (confirmQuit) {
        QuitRunDialog(
            picksMade = state.picks.size,
            onDismiss = { confirmQuit = false },
            onConfirm = onQuitRun,
        )
    }

    Box(modifier.fillMaxSize().background(SurfaceBase)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            DraftHeader(state = state)
            Spacer(Modifier.height(10.dp))
            XiSlotRail(formation = state.formation, picks = state.picks)
            Spacer(Modifier.height(12.dp))

            Box(Modifier.weight(1f)) {
                when {
                    // A failure with nothing on screen is blocking, so it takes
                    // the whole content area rather than being a red strip
                    // under a UI the user cannot use anyway.
                    state.error != null && state.spin == null && !state.isSpinning -> Box(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ErrorNotice(error = state.error, onRetry = onRetry)
                    }

                    state.isSpinning -> SpinningReelCard(
                        names = state.reelClubNames,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        caption = if (state.mode == DraftMode.WorldCup) {
                            "choosing a nation and a World Cup…"
                        } else {
                            "choosing a club and a season…"
                        },
                    )

                    state.isComplete -> CompletedXi(state = state)

                    state.spin == null -> AwaitingSpin(state = state)

                    else -> SquadList(state = state, onSelectPlayer = onSelectPlayer)
                }
            }

            ActionBar(
                state = state,
                onSpin = onSpin,
                onChangeSpin = onChangeSpin,
                onRetry = onRetry,
                onViewTeam = onViewTeam,
                onStartSeason = onStartSeason,
            )
        }

        val placing = state.selectedPlayer?.takeIf { state.isPlacing }
        if (placing != null || state.isViewingTeam) {
            TeamSheet(
                state = state,
                placing = placing,
                onPlace = { slot -> placing?.let { onConfirmPick(it, slot) } },
                onHoldPlaced = onHoldPlaced,
                onMoveHeldTo = onMoveHeldTo,
                onClose = if (placing != null) onCancelPlacement else onCloseTeam,
            )
        }
    }
}

@Composable
private fun DraftHeader(state: DraftUiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "${state.leagueName.uppercase()} · ${state.formation.name}",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.4.sp,
                color = AccentGold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "BUILD YOUR XI",
                fontFamily = DisplayFontFamily,
                fontSize = 26.sp,
                lineHeight = 30.sp,
                color = OnSurfacePrimary,
            )
        }
        // Running XI rating. Absent until there is a pick — an "average of
        // nothing" placeholder is worse than no number, and the first pick
        // appearing is a small reward in itself.
        state.currentXiRating?.let { rating ->
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "XI RATING",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    color = OnSurfaceFaint,
                )
                Spacer(Modifier.height(3.dp))
                RatingBadge(rating = rating, size = RatingBadgeSize.Small)
            }
        }
    }
}

@Composable
private fun AwaitingSpin(state: DraftUiState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
    ) {
        Text(
            text = "ROUND ${state.round}",
            fontFamily = DisplayFontFamily,
            fontSize = 44.sp,
            lineHeight = 46.sp,
            color = OnSurfaceFaint,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (state.mode == DraftMode.WorldCup) {
                "Spin for a nation and a World Cup. Any player from that squad can go " +
                    "anywhere on the pitch, but playing him out of position costs him."
            } else {
                "Spin for a club and a season. Any player from that squad can go " +
                    "anywhere on the pitch, but playing him out of position costs him."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The finished XI, on the pitch.
 *
 * Eleven rounds of decisions end here, so this is a payoff screen and it has to
 * show the actual team rather than announce that one exists. The first version
 * said "XI COMPLETE - all positions filled", which is the least interesting
 * true sentence available: the shape, the clubs each player came from, which of
 * them are out of position and what that cost were all already computed and
 * simply never drawn.
 */
@Composable
private fun CompletedXi(state: DraftUiState, modifier: Modifier = Modifier) {
    val outOfPosition = state.picks.values.count { it.fit != PositionFit.Natural }
    val clubs = state.picks.values.map { it.clubName }.distinct().size
    val natural = state.currentXiNaturalRating
    val effective = state.currentXiRating

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "YOUR XI",
                    fontFamily = DisplayFontFamily,
                    fontSize = 32.sp,
                    lineHeight = 34.sp,
                    color = OnSurfacePrimary,
                )
                Text(
                    text = if (state.mode == DraftMode.WorldCup) {
                        "${state.formation.name} · $clubs national squads"
                    } else {
                        "${state.formation.name} · $clubs club-seasons"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                )
            }
            effective?.let {
                RatingBadge(rating = it, size = RatingBadgeSize.Large, style = RatingBadgeStyle.Solid)
            }
        }
        Spacer(Modifier.height(10.dp))

        // If out-of-position picks cost the XI anything, say how much rather
        // than quietly averaging it away.
        if (outOfPosition > 0 && natural != null && effective != null) {
            Text(
                text = "$outOfPosition of 11 out of position, costing ${natural - effective} " +
                    "rating points. This XI would be $natural if everyone played his own role.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
            )
            Spacer(Modifier.height(10.dp))
        }

        PitchView(
            formation = state.formation,
            picks = state.picks,
            modifier = Modifier.height(520.dp),
        )
        Spacer(Modifier.height(14.dp))
        BenchRow(bench = state.bench)
        Spacer(Modifier.height(6.dp))
        SectionLabel("The squad")
        Spacer(Modifier.height(4.dp))
        state.formation.slots.forEach { slot ->
            state.picks[slot.id]?.let { pick -> CompletedXiRow(pick) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** One line of the finished XI: position, player, where he came from, rating. */
@Composable
private fun CompletedXiRow(pick: DraftedPlayer) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
    ) {
        Text(
            text = pick.slot.label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            letterSpacing = 0.6.sp,
            color = OnSurfaceFaint,
            modifier = Modifier.width(38.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = pick.player.fullName,
                style = MaterialTheme.typography.titleSmall,
                color = OnSurfacePrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append("${pick.clubName} ${pick.seasonLabel}")
                    if (pick.fit != PositionFit.Natural) {
                        append(" · ${pick.player.role} out of position")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                color = if (pick.fit == PositionFit.Natural) OnSurfaceMuted else colorForFit(pick.fit),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        RatingBadge(rating = pick.effectiveRating, size = RatingBadgeSize.Small)
    }
}

@Composable
private fun SquadList(state: DraftUiState, onSelectPlayer: (SquadPlayer?) -> Unit) {
    val spin = state.spin ?: return

    // Any outfielder can fill any open outfield position now, so "eligible"
    // only excludes players already drafted, and keepers/outfielders when the
    // goalkeeper slot no longer matches. Ranked by the BEST rating each player
    // could reach in a position that is actually open, not by raw overall — a
    // 90-rated striker is worth less than an 80-rated centre-back when
    // centre-back is what is missing, and the order should say so.
    val (eligible, ineligible) = spin.players
        .partition { !it.alreadyDrafted && state.eligibleSlotsFor(it).isNotEmpty() }
    val ranked = eligible.sortedByDescending { player ->
        state.eligibleSlotsFor(player).maxOfOrNull { player.ratingAt(it) } ?: 0
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "reveal") {
            SquadRevealCard(spin = spin)
            Spacer(Modifier.height(6.dp))
        }
        item(key = "hdr-eligible") {
            if (ranked.isEmpty()) {
                // Reachable only when the sole remaining position is goalkeeper
                // and this squad has no keeper with enough minutes. No loaded
                // squad is like that, but a new league could be, and a screen
                // where nothing is tappable and nothing says why is the worst
                // possible way to find out.
                Text(
                    text = "No one here can fill your last open position. " +
                        "Use a change spin to draw a different squad.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                SectionLabel("${ranked.size} available · best fit first")
            }
        }
        items(ranked, key = { it.playerSeasonStatId }) { player ->
            SquadPlayerRow(
                player = player,
                selected = false,
                selectable = true,
                onClick = { onSelectPlayer(player) },
                defence = spin.defence,
                worldCup = state.mode == DraftMode.WorldCup,
            )
        }
        if (ineligible.isNotEmpty()) {
            item(key = "hdr-ineligible") {
                Spacer(Modifier.height(6.dp))
                SectionLabel("${ineligible.size} unavailable")
            }
            items(ineligible, key = { it.playerSeasonStatId }) { player ->
                SquadPlayerRow(
                    player = player, selected = false, selectable = false,
                    onClick = {}, defence = spin.defence,
                    worldCup = state.mode == DraftMode.WorldCup,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        letterSpacing = 1.sp,
        color = OnSurfaceFaint,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

/**
 * The placement sheet: the pitch, sized properly, with one player in hand.
 *
 * Every open position shows what THIS player would be worth in it, coloured by
 * fit, and is tappable. Tapping places him and ends the round. This is the
 * squad-builder move the horizontal chooser could never be — you see the whole
 * shape re-price itself around one player and decide where he actually helps.
 */
/**
 * The pitch, full size, with one job at a time.
 *
 * PLACING ([placing] non-null): a player from the pool is in hand and every
 * legal position shows what he would be worth there.
 *
 * EDITING ([placing] null): no one is in hand. Tap a placed player to pick him
 * up, then tap anywhere to move or swap him. Reachable from the View Team
 * button at any point in a run, which is the fix for the trap where a
 * misplacement in round 3 was permanent — the pitch used to appear only as a
 * side effect of choosing a new player, so a slot you regretted could only be
 * revisited by drafting over it, which you cannot do.
 *
 * Ratings update live and need no recomputation step: a player's effective
 * rating is derived from the position he occupies, so dragging Benzema from ST
 * to LW makes him an 89 and dragging him back makes him a 91 again.
 */
@Composable
private fun TeamSheet(
    state: DraftUiState,
    placing: SquadPlayer?,
    onPlace: (FormationSlot) -> Unit,
    onHoldPlaced: (String) -> Unit,
    onMoveHeldTo: (String) -> Unit,
    onClose: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val held = state.heldPick
    val inHand = placing ?: held?.player

    // Why a tap did nothing, when it did nothing. An illegal tap used to fall
    // through to the scrim and dismiss the whole sheet, so trying to put a left
    // winger where one already stood looked like the app losing your pick.
    var blocked by remember(placing?.playerSeasonStatId, state.heldSlotId) {
        mutableStateOf<String?>(null)
    }
    LaunchedEffect(blocked) {
        if (blocked != null) {
            kotlinx.coroutines.delay(2200)
            blocked = null
        }
    }

    fun reject(message: String) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        blocked = message
    }

    Column(
        Modifier
            .fillMaxSize()
            // The scrim swallows taps so the list underneath cannot be used
            // while the sheet is open. The pitch and its tokens consume their
            // own taps, so tapping to close only works in the margins.
            //
            // indication = null because the default ripple spread a grey wash
            // across the whole sheet on any press-and-hold, which read as the
            // overlay glitching rather than as a button.
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            )
            .statusBarsPadding()
            .padding(horizontal = 12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SheetHeader(state = state, inHand = inHand, held = held, blocked = blocked)
        Spacer(Modifier.height(10.dp))
        FitLegend()
        Spacer(Modifier.height(8.dp))
        PitchView(
            formation = state.formation,
            picks = state.picks,
            candidate = placing,
            heldSlotId = state.heldSlotId,
            onSlotClick = { slot ->
                val occupant = state.picks[slot.id]
                val keeperSlot = slot.isGoalkeeper
                when {
                    // --- a pool player is in hand: place him
                    placing != null -> when {
                        occupant != null -> reject(
                            "${slot.label} is already taken by " +
                                "${playerSurname(occupant.player.fullName)}. " +
                                "Use View Team to rearrange.",
                        )
                        keeperSlot != (placing.role == "GK") && keeperSlot ->
                            reject("Only a goalkeeper can go in goal.")
                        keeperSlot != (placing.role == "GK") ->
                            reject("A goalkeeper can only play in goal.")
                        else -> onPlace(slot)
                    }

                    // --- a placed player is in hand: move or swap him
                    held != null -> {
                        val moverIsKeeper = held.player.role == "GK"
                        val displacedIsKeeper = occupant?.player?.role == "GK"
                        val fromIsKeeperSlot = held.slot.isGoalkeeper
                        when {
                            slot.id == held.slot.id -> onHoldPlaced(slot.id)
                            moverIsKeeper != keeperSlot ->
                                reject("A goalkeeper can only play in goal.")
                            occupant != null && displacedIsKeeper != fromIsKeeperSlot ->
                                reject("That swap would move a goalkeeper outfield.")
                            else -> onMoveHeldTo(slot.id)
                        }
                    }

                    // --- nothing in hand: pick someone up
                    occupant != null -> onHoldPlaced(slot.id)
                    else -> reject("Nothing here yet. Spin to fill ${slot.label}.")
                }
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(10.dp))
        DreamXiSecondaryButton(
            text = when {
                placing != null -> "Pick someone else"
                held != null -> "Leave him where he is"
                else -> "Done"
            },
            onClick = { if (held != null && placing == null) onHoldPlaced(held.slot.id) else onClose() },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun SheetHeader(
    state: DraftUiState,
    inHand: SquadPlayer?,
    held: DraftedPlayer?,
    blocked: String?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised2)
            .padding(10.dp),
    ) {
        if (inHand != null) {
            RatingBadge(
                rating = inHand.overallRating,
                size = RatingBadgeSize.Medium,
                style = RatingBadgeStyle.Solid,
            )
        } else {
            state.currentXiRating?.let {
                RatingBadge(rating = it, size = RatingBadgeSize.Medium, style = RatingBadgeStyle.Solid)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = inHand?.fullName ?: "Your XI · ${state.formation.name}",
                style = MaterialTheme.typography.titleMedium,
                color = OnSurfacePrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = blocked ?: when {
                    inHand != null && held != null ->
                        "Moving from ${held.slot.label} · tap a position to drop him"
                    inHand != null -> "${sidedRoleLabel(inHand.role)} · tap a position to place him"
                    state.picks.isEmpty() -> "Nothing drafted yet."
                    else -> "Tap a player to pick him up, then tap where he should go."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (blocked != null) ResultLoss else OnSurfaceMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Opens the pitch for review or rearrangement. Sits directly above Spin, in the
 * muted secondary treatment so it never competes with the round's main action,
 * and carries the count of players out of position — the single most useful
 * reason to open it.
 *
 * The label changes once the XI is complete. "View team" is wrong there: the
 * completion screen already has the pitch on it, so the button is not offering
 * to show you anything — it is offering the last chance to change it before the
 * season is simulated.
 */
@Composable
private fun ViewTeamButton(state: DraftUiState, onClick: () -> Unit) {
    val misplaced = state.picks.values.count { it.fit != PositionFit.Natural }
    DreamXiSecondaryButton(
        text = when {
            state.isComplete && misplaced > 0 -> "Final edit · $misplaced out of position"
            state.isComplete -> "Final edit"
            misplaced > 0 -> "View team · $misplaced out of position"
            else -> "View team · ${state.picks.size}/${state.totalRounds}"
        },
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Guards an in-progress run against an accidental back gesture. */
@Composable
private fun QuitRunDialog(picksMade: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceRaised1,
        title = {
            Text(
                text = "QUIT THIS RUN?",
                fontFamily = DisplayFontFamily,
                fontSize = 24.sp,
                color = OnSurfacePrimary,
            )
        },
        text = {
            Text(
                text = if (picksMade == 0) {
                    "Nobody is drafted yet, but leaving goes back to setup " +
                        "and starts over."
                } else {
                    "You have drafted $picksMade of 11. Leaving now discards them — " +
                        "runs are not saved yet, so there is nothing to come back to."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Quit", color = ResultLoss)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Keep drafting", color = AccentGold)
            }
        },
    )
}

/** One primary action at a time; which one depends on where the round is. */
@Composable
private fun ActionBar(
    state: DraftUiState,
    onSpin: () -> Unit,
    onChangeSpin: () -> Unit,
    onRetry: () -> Unit,
    onViewTeam: () -> Unit,
    onStartSeason: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceRaised1)
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 12.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Non-blocking failures only — the blocking case already owns the
        // content area above, and showing it twice would be noise.
        if (state.error != null && state.spin != null) {
            ErrorNotice(error = state.error, onRetry = onRetry, compact = true)
        }

        // Always reachable once there is anything to look at. The pitch used
        // to be a side effect of selecting a player, which meant reviewing or
        // fixing the XI required drafting someone you did not want.
        if (state.picks.isNotEmpty() && !state.isSpinning) {
            ViewTeamButton(state = state, onClick = onViewTeam)
        }

        when {
            state.spin != null && !state.isSpinning -> {
                Text(
                    text = "Tap a player to place him on the pitch",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                ChangeSpinControl(
                    remaining = state.rerollsRemaining,
                    allowance = state.rerollsAllowed,
                    onChangeSpin = onChangeSpin,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Once the XI is done there is nothing left to spin for. This
            // strip is the most valuable on the screen, so it carries the only
            // action left worth taking rather than a disabled Spin button.
            state.isComplete -> DreamXiPrimaryButton(
                text = if (state.mode == DraftMode.WorldCup) "Play the World Cup" else "Play the season",
                onClick = onStartSeason,
                modifier = Modifier.fillMaxWidth(),
            )

            else -> SpinButton(
                phase = if (state.isSpinning) SpinPhase.Spinning else SpinPhase.Idle,
                round = state.round,
                totalRounds = state.totalRounds,
                onSpin = onSpin,
                enabled = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------- previews

private val noop: (SquadPlayer, FormationSlot) -> Unit = { _, _ -> }

@Preview(name = "Squad revealed", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 900)
@Composable
private fun DraftScreenPreview() {
    DreamXITheme {
        DraftScreen(
            state = PreviewState,
            onSpin = {}, onChangeSpin = {}, onSelectPlayer = {},
            onConfirmPick = noop, onRetry = {}, onCancelPlacement = {}, onViewTeam = {},
            onCloseTeam = {}, onHoldPlaced = {}, onMoveHeldTo = {}, onQuitRun = {},
        )
    }
}

@Preview(name = "Placing a player", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 900)
@Composable
private fun DraftScreenPlacingPreview() {
    DreamXITheme {
        DraftScreen(
            state = PreviewState.copy(selectedPlayer = PreviewSpin.players[12], isPlacing = true),
            onSpin = {}, onChangeSpin = {}, onSelectPlayer = {},
            onConfirmPick = noop, onRetry = {}, onCancelPlacement = {}, onViewTeam = {},
            onCloseTeam = {}, onHoldPlaced = {}, onMoveHeldTo = {}, onQuitRun = {},
        )
    }
}

@Preview(name = "Awaiting spin", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 900)
@Composable
private fun DraftScreenAwaitingPreview() {
    DreamXITheme {
        DraftScreen(
            state = PreviewState.copy(spin = null, round = 6),
            onSpin = {}, onChangeSpin = {}, onSelectPlayer = {},
            onConfirmPick = noop, onRetry = {}, onCancelPlacement = {}, onViewTeam = {},
            onCloseTeam = {}, onHoldPlaced = {}, onMoveHeldTo = {}, onQuitRun = {},
        )
    }
}

@Preview(name = "XI complete", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 1500)
@Composable
private fun DraftScreenCompletePreview() {
    DreamXITheme {
        DraftScreen(
            state = PreviewCompleteState,
            onSpin = {}, onChangeSpin = {}, onSelectPlayer = {},
            onConfirmPick = noop, onRetry = {}, onCancelPlacement = {}, onViewTeam = {},
            onCloseTeam = {}, onHoldPlaced = {}, onMoveHeldTo = {}, onQuitRun = {},
        )
    }
}

@Preview(name = "Offline", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 900)
@Composable
private fun DraftScreenOfflinePreview() {
    DreamXITheme {
        DraftScreen(
            state = PreviewState.copy(spin = null, error = DreamXiError.Offline),
            onSpin = {}, onChangeSpin = {}, onSelectPlayer = {},
            onConfirmPick = noop, onRetry = {}, onCancelPlacement = {}, onViewTeam = {},
            onCloseTeam = {}, onHoldPlaced = {}, onMoveHeldTo = {}, onQuitRun = {},
        )
    }
}
