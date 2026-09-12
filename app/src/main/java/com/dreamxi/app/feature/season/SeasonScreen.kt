package com.dreamxi.app.feature.season

import com.dreamxi.app.core.ui.playerSurname
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dreamxi.app.core.ui.components.ClubBadge
import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.core.ui.components.DreamXiPrimaryButton
import com.dreamxi.app.core.ui.components.DreamXiSecondaryButton
import com.dreamxi.app.core.ui.components.ErrorNotice
import com.dreamxi.app.sim.FixtureOutlook
import com.dreamxi.app.sim.FormResult
import com.dreamxi.app.sim.SeasonProjection
import com.dreamxi.app.sim.SeasonStats
import com.dreamxi.app.sim.SimFixture
import com.dreamxi.app.sim.SimTeam
import com.dreamxi.app.sim.TableRow
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnAccentGold
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.OutlineSubtle
import com.dreamxi.app.ui.theme.ResultDraw
import com.dreamxi.app.ui.theme.ResultLoss
import com.dreamxi.app.ui.theme.ResultWin
import com.dreamxi.app.ui.theme.SurfaceBase
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.SurfaceRaised2

/**
 * The payoff of a run: the draw for a place in a real league, then that league
 * played out a week at a time.
 *
 * Stateless, like the draft screen, so every phase can be exercised from a
 * @Preview without a backend or a completed draft behind it.
 */
@Composable
fun SeasonScreen(
    state: SeasonUiState,
    onKickOff: () -> Unit,
    onPlayWeek: () -> Unit,
    onPlayToEnd: () -> Unit,
    onStopAutoPlay: () -> Unit,
    onSkipToEnd: () -> Unit,
    onOpenStats: () -> Unit,
    onCloseStats: () -> Unit,
    onAskExit: () -> Unit,
    onDismissExit: () -> Unit,
    onRetry: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(SurfaceBase)) {
        when {
            state.error != null -> ErrorNotice(
                error = state.error,
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )

            state.phase == SeasonPhase.Loading -> LoadingSeason(
                modifier = Modifier.align(Alignment.Center),
            )

            state.phase == SeasonPhase.Drawing || state.phase == SeasonPhase.PlaceWon ->
                TakeoverDrawStage(state = state, onKickOff = onKickOff)

            else -> SeasonInProgress(
                state = state,
                onPlayWeek = onPlayWeek,
                onPlayToEnd = onPlayToEnd,
                onStopAutoPlay = onStopAutoPlay,
                onSkipToEnd = onSkipToEnd,
                onOpenStats = onOpenStats,
                onAskExit = onAskExit,
            )
        }

        // The statistics screen sits over the table rather than beside it in
        // the same scroll. Two full leaderboard sets under a twenty-row table
        // made one column of scrolling nobody would reach the bottom of.
        val stats = state.stats
        if (state.isViewingStats && stats != null) {
            StatisticsScreen(
                stats = stats,
                userTeam = state.userTeam,
                analysis = state.analysis,
                projection = state.projection,
                finalPosition = state.userRow?.position,
                seasonLabel = state.seasonLabel,
                leagueName = state.leagueName,
                onBack = onCloseStats,
            )
        }

        if (state.isConfirmingExit) {
            ExitWithoutStatsDialog(onDismiss = onDismissExit, onConfirm = onFinish)
        }
    }
}

/**
 * Asked because "Done" now leaves behind something the user may not know is
 * there. A season's statistics are the reward for playing it, and dropping
 * straight back to Setup silently discards them along with the run.
 */
@Composable
private fun ExitWithoutStatsDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceRaised2,
        title = {
            Text(
                text = "Leave without the statistics?",
                style = MaterialTheme.typography.titleLarge,
                color = OnSurfacePrimary,
            )
        },
        text = {
            Text(
                text = "The season's top scorers, assists and your own XI's record are " +
                    "on the statistics screen. This run is not saved, so they go with it.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Leave", color = ResultLoss)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Stay", color = AccentGold)
            }
        },
    )
}

@Composable
private fun LoadingSeason(modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        CircularProgressIndicator(color = AccentGold, strokeWidth = 3.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Finding a season for your XI",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
        )
    }
}

// ------------------------------------------------------------ the draw

/**
 * The draw for a place in the league.
 *
 * Three clubs went down that season and one of their places is going to the
 * user's XI. The screen shows the tally climbing rather than announcing a
 * winner, because the draw is the moment the run stops being a spreadsheet.
 */
@Composable
private fun TakeoverDrawStage(
    state: SeasonUiState,
    onKickOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settled = state.phase == SeasonPhase.PlaceWon
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = state.seasonLabel.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = AccentGold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (settled) "You take their place" else "The draw",
            style = MaterialTheme.typography.headlineMedium,
            color = OnSurfacePrimary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (settled) {
                "Thirty-eight games in ${state.leagueName}, ${state.seasonLabel}."
            } else {
                "Three clubs went down. One place is yours."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        val tallies = state.drawTallies
        val leading = tallies.maxOfOrNull { it.value } ?: 0
        for (club in state.drawCandidates) {
            val count = tallies[club.id] ?: 0
            val isChosen = settled && club.id == state.takeover?.replacedTeamId
            val isDimmed = settled && !isChosen
            DrawCandidateRow(
                club = club,
                count = count,
                share = if (leading > 0) count / leading.toFloat() else 0f,
                highlighted = isChosen,
                dimmed = isDimmed,
            )
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = when {
                settled -> state.replacedClubRealPosition
                    ?.let { "${state.replacedClub?.name} finished ${ordinal(it)}. Their fixtures are yours." }
                    ?: "${state.replacedClub?.name} makes way."
                state.inSuddenDeath -> "Level. Drawing again…"
                else -> "Draw ${state.drawTick} of ${state.takeover?.ticks?.size ?: 0}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (settled) OnSurfacePrimary else OnSurfaceFaint,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        // Only once the place is settled: before that the user does not yet
        // know whose fixtures they are inheriting, so a projection would be
        // answering a question they have not been asked.
        if (settled) {
            state.projection?.let { ProjectedFinish(it) }
            Spacer(Modifier.height(20.dp))
            DreamXiPrimaryButton(
                text = "Kick off",
                onClick = onKickOff,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DrawCandidateRow(
    club: SimTeam,
    count: Int,
    share: Float,
    highlighted: Boolean,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
) {
    // Animated so the bar slides rather than jumping, which at six draws a
    // frame would otherwise read as flicker.
    val width by animateFloatAsState(
        targetValue = share.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 180),
        label = "drawBar",
    )
    val face = when {
        highlighted -> AccentGold
        dimmed -> SurfaceRaised1
        else -> SurfaceRaised2
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised1)
            .border(
                width = 1.dp,
                color = if (highlighted) AccentGold else OutlineSubtle,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(12.dp),
    ) {
        ClubBadge(clubName = club.name, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = club.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (dimmed) OnSurfaceFaint else OnSurfacePrimary,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(SurfaceRaised2),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(width)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(face),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.titleLarge,
            color = if (highlighted) AccentGold else if (dimmed) OnSurfaceFaint else OnSurfaceMuted,
        )
    }
}

// -------------------------------------------------------- the season

@Composable
private fun SeasonInProgress(
    state: SeasonUiState,
    onPlayWeek: () -> Unit,
    onPlayToEnd: () -> Unit,
    onStopAutoPlay: () -> Unit,
    onSkipToEnd: () -> Unit,
    onOpenStats: () -> Unit,
    onAskExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // One scroll state shared by the header and every row, so the numeric
    // columns move as a single grid instead of shearing apart.
    val tableScroll = rememberScrollState()
    // Keep the user's own row in view as the table churns; without this the
    // one line they care about scrolls off and they have to hunt for it.
    // Snap back to the match card whenever a week is played.
    //
    // This used to scroll to the user's row in the TABLE, which meant every tap
    // threw the screen to a different place depending on where the side sat —
    // so the result, the thing the tap was actually for, was never what ended
    // up on screen. The scoresheet is the event; the table is the consequence,
    // and the user can scroll down to it whenever he wants.
    LaunchedEffect(state.visibleMatchday) {
        if (state.visibleMatchday > 0) listState.animateScrollToItem(0)
    }

    Column(modifier = modifier.fillMaxSize()) {
        SeasonHeader(state = state)

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, bottom = 16.dp,
            ),
        ) {
            if (state.visibleMatchday > 0) {
                item {
                    ResultsBlock(state = state)
                    Spacer(Modifier.height(20.dp))
                }
            }
            // The run ahead. Below the result once there is one, because the
            // result is what the last tap was for; first on the screen before
            // kick-off, when there is nothing behind you yet.
            state.upcoming.takeIf { it.isNotEmpty() }?.let { outlooks ->
                item {
                    ComingUp(outlooks)
                    Spacer(Modifier.height(20.dp))
                }
            }
            item {
                Text(
                    text = if (state.isSeasonOver) "FINAL TABLE" else "TABLE",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceFaint,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                TableHeader(tableScroll)
            }
            items(state.table, key = { it.team.id }) { row -> TableRowView(row, tableScroll) }
        }

        SeasonControls(
            state = state,
            onPlayWeek = onPlayWeek,
            onPlayToEnd = onPlayToEnd,
            onStopAutoPlay = onStopAutoPlay,
            onSkipToEnd = onSkipToEnd,
            onOpenStats = onOpenStats,
            onAskExit = onAskExit,
        )
    }
}

@Composable
private fun SeasonHeader(state: SeasonUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceRaised1)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // A Free Mode side is labelled wherever the season is shown.
                    // A 90-rated XI winning a league is a different achievement
                    // from a drafted one doing it, and the two must never be
                    // mistaken for each other.
                    text = "${state.leagueName.uppercase()} · ${state.seasonLabel}" +
                        if (state.isFreeMode) "  ·  FREE MODE" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.isFreeMode) AccentGold else OnSurfaceFaint,
                )
                Text(
                    text = if (state.visibleMatchday == 0) {
                        "Ready to start"
                    } else {
                        "Matchday ${state.visibleMatchday} of ${state.totalMatchdays}"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = OnSurfacePrimary,
                )
            }
            state.userRow?.let { row ->
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = ordinal(row.position),
                        style = MaterialTheme.typography.headlineMedium,
                        color = AccentGold,
                    )
                    Text(
                        text = "${row.points} pts",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceMuted,
                    )
                }
            }
        }
    }
}

/** This week: the user's match large, the rest of the league beneath it. */
@Composable
private fun ResultsBlock(state: SeasonUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        state.userResult?.let { fixture ->
            UserFixtureCard(fixture)
            Spacer(Modifier.height(14.dp))
        }
        val others = state.currentResults.filterNot { it.home.isUserTeam || it.away.isUserTeam }
        if (others.isNotEmpty()) {
            Text(
                text = "ELSEWHERE",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            for (f in others) OtherFixtureRow(f)
        }
    }
}

@Composable
private fun UserFixtureCard(fixture: SimFixture, modifier: Modifier = Modifier) {
    val userIsHome = fixture.home.isUserTeam
    val userGoals = if (userIsHome) fixture.result.homeGoals else fixture.result.awayGoals
    val theirGoals = if (userIsHome) fixture.result.awayGoals else fixture.result.homeGoals
    val accent = when {
        userGoals > theirGoals -> ResultWin
        userGoals == theirGoals -> ResultDraw
        else -> ResultLoss
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceRaised1)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            text = if (userIsHome) "HOME" else "AWAY",
            style = MaterialTheme.typography.labelSmall,
            color = accent,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = fixture.home.name,
                style = MaterialTheme.typography.titleMedium,
                color = OnSurfacePrimary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
            Text(
                text = "  ${fixture.result.homeGoals} - ${fixture.result.awayGoals}  ",
                style = MaterialTheme.typography.headlineMedium,
                color = accent,
            )
            Text(
                text = fixture.away.name,
                style = MaterialTheme.typography.titleMedium,
                color = OnSurfacePrimary,
                modifier = Modifier.weight(1f),
            )
        }
        if (fixture.events.hasAny) {
            Spacer(Modifier.height(16.dp))
            MatchReport(fixture)
        }
    }
}

/**
 * The scoresheet: who scored, who set it up, who was booked.
 *
 * Two columns matching the scoreline above it, so a goal sits under the side
 * that scored it and the eye never has to re-read a team name to work out who
 * a line belongs to.
 */
@Composable
private fun MatchReport(fixture: SimFixture, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        SideReport(fixture, fixture.home.id, alignEnd = true, modifier = Modifier.weight(1f))
        // A visible gutter down the middle. Two columns pressed against each
        // other read as one ragged block of text rather than as two sides of a
        // scoresheet, which is what made this feel cramped.
        Spacer(Modifier.width(24.dp))
        SideReport(fixture, fixture.away.id, alignEnd = false, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SideReport(
    fixture: SimFixture,
    teamId: String,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    val align = if (alignEnd) TextAlign.End else TextAlign.Start
    val side = if (alignEnd) Alignment.End else Alignment.Start
    Column(
        modifier = modifier,
        horizontalAlignment = side,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (goal in fixture.events.goalsFor(teamId)) {
            Column(horizontalAlignment = side) {
                Text(
                    text = "⚽  ${surname(goal.scorer)}  ${goal.minute}'",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfacePrimary,
                    textAlign = align,
                )
                goal.assist?.let {
                    Text(
                        text = surname(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceFaint,
                        textAlign = align,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        for (card in fixture.events.cardsFor(teamId)) {
            Text(
                text = "${if (card.isRed) "🟥" else "🟨"}  ${surname(card.player)}  ${card.minute}'",
                style = MaterialTheme.typography.bodySmall,
                color = if (card.isRed) ResultLoss else OnSurfaceMuted,
                textAlign = align,
            )
        }
    }
}

/**
 * Surname only, the way a scoresheet reads. Full names wrap on a phone and
 * would turn a two-goal match into six lines of text.
 */
internal fun surname(full: String): String = playerSurname(full).ifBlank { full }

@Composable
private fun OtherFixtureRow(fixture: SimFixture, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
    ) {
        Text(
            text = fixture.home.name,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        Text(
            text = "  ${fixture.result.homeGoals}-${fixture.result.awayGoals}  ",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfacePrimary,
        )
        Text(
            text = fixture.away.name,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
    }
}

/**
 * Column widths for the numeric half of the table. Fixed rather than weighted
 * so every row lines up with the header, which weights cannot guarantee once a
 * club name changes length.
 */
private val ColPlayed = 30.dp
private val ColResult = 26.dp
private val ColGoals = 32.dp
private val ColDiff = 38.dp
private val ColPoints = 36.dp

/** Left-hand block width: position, crest and name. */
private val ColIdentity = 148.dp

/**
 * The league table.
 *
 * NINE COLUMNS DO NOT FIT ON A PHONE at a size anyone can read — position,
 * club, GP, W, D, L, GF, GA, GD and points need about 390dp and a typical
 * handset gives 360. Rather than shrink the type until it is unreadable or
 * drop the columns that were asked for, the identity block stays pinned on the
 * left and the numbers scroll sideways underneath a header that scrolls with
 * them.
 *
 * Every row shares ONE scroll state with the header, so dragging any row moves
 * the whole grid together and a number is always under its own heading. Giving
 * each row its own state is the obvious implementation and produces a table
 * that shears apart as soon as the user touches it.
 */
@Composable
private fun TableHeader(scroll: ScrollState, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
    ) {
        Text(
            text = "CLUB",
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceFaint,
            modifier = Modifier.width(ColIdentity),
        )
        Row(modifier = Modifier.horizontalScroll(scroll)) {
            HeadCell("GP", ColPlayed)
            HeadCell("W", ColResult)
            HeadCell("D", ColResult)
            HeadCell("L", ColResult)
            HeadCell("GF", ColGoals)
            HeadCell("GA", ColGoals)
            HeadCell("GD", ColDiff)
            HeadCell("PTS", ColPoints)
        }
    }
}

@Composable
private fun HeadCell(text: String, width: Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = OnSurfaceFaint,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun TableRowView(row: TableRow, scroll: ScrollState, modifier: Modifier = Modifier) {
    val isUser = row.team.isUserTeam
    val ink = if (isUser) OnAccentGold else OnSurfacePrimary
    val inkMuted = if (isUser) OnAccentGold else OnSurfaceMuted
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isUser) AccentGold else SurfaceRaised1)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.width(ColIdentity),
        ) {
            Text(
                text = "${row.position}",
                style = MaterialTheme.typography.bodyMedium,
                color = inkMuted,
                modifier = Modifier.width(22.dp),
            )
            ClubBadge(clubName = row.team.name, size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = row.team.name,
                style = MaterialTheme.typography.bodyMedium,
                color = ink,
                fontWeight = if (isUser) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(modifier = Modifier.horizontalScroll(scroll)) {
            StatCell("${row.played}", inkMuted, ColPlayed)
            StatCell("${row.won}", inkMuted, ColResult)
            StatCell("${row.drawn}", inkMuted, ColResult)
            StatCell("${row.lost}", inkMuted, ColResult)
            StatCell("${row.goalsFor}", inkMuted, ColGoals)
            StatCell("${row.goalsAgainst}", inkMuted, ColGoals)
            StatCell(withSign(row.goalDifference), inkMuted, ColDiff)
            StatCell("${row.points}", ink, ColPoints, bold = true)
        }
    }
}

@Composable
private fun StatCell(text: String, color: Color, width: Dp, bold: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier.width(width),
    )
}

@Composable
private fun SeasonControls(
    state: SeasonUiState,
    onPlayWeek: () -> Unit,
    onPlayToEnd: () -> Unit,
    onStopAutoPlay: () -> Unit,
    onSkipToEnd: () -> Unit,
    onOpenStats: () -> Unit,
    onAskExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceRaised1)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            state.isSeasonOver -> {
                state.userRow?.let { row ->
                    Text(
                        text = "${ordinal(row.position)} · ${row.points} points · " +
                            "W${row.won} D${row.drawn} L${row.lost} · " +
                            "${row.goalsFor}:${row.goalsAgainst}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceMuted,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                DreamXiPrimaryButton(
                    text = "Season statistics",
                    onClick = onOpenStats,
                    modifier = Modifier.fillMaxWidth(),
                )
                DreamXiSecondaryButton(
                    text = "Done",
                    onClick = onAskExit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // While the season plays itself, the two controls that matter are
            // stopping it and getting past it. Offering "play next matchday"
            // here would fight the loop for the same state.
            state.isAdvancing -> {
                DreamXiPrimaryButton(
                    text = "Stop",
                    onClick = onStopAutoPlay,
                    modifier = Modifier.fillMaxWidth(),
                )
                DreamXiSecondaryButton(
                    text = "Skip to the end",
                    onClick = onSkipToEnd,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> {
                DreamXiPrimaryButton(
                    text = if (state.visibleMatchday == 0) "Play matchday 1" else "Play next matchday",
                    onClick = onPlayWeek,
                    modifier = Modifier.fillMaxWidth(),
                )
                DreamXiSecondaryButton(
                    text = "Play the rest of the season",
                    onClick = onPlayToEnd,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun withSign(value: Int): String = if (value > 0) "+$value" else "$value"

// ------------------------------------------------------ projected finish

/**
 * What this XI is worth, before a ball is kicked.
 *
 * The point of showing it is what it does to the ENDING: finishing first is a
 * fact, but finishing first having been shown an 18% chance beforehand is a
 * story the user was part of. It is the same engine that plays the season, so
 * the numbers are a promise it has to keep rather than decoration.
 */
@Composable
private fun ProjectedFinish(projection: SeasonProjection, modifier: Modifier = Modifier) {
    val rows = projection.likelyBand(5)
    val peak = rows.maxOfOrNull { it.second } ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised1)
            .padding(14.dp),
    ) {
        Text(
            text = "PROJECTED FINISH",
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceFaint,
        )
        Spacer(Modifier.height(10.dp))
        for ((position, chance) in rows) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            ) {
                Text(
                    text = ordinal(position),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfacePrimary,
                    modifier = Modifier.width(38.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(SurfaceRaised2),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((chance / peak).toFloat().coerceIn(0f, 1f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(AccentGold),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = percent(chance),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
            }
        }
        if (projection.hasUnreachablePlace(rows)) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "A place shown at 0% is one no result can reach: two clubs " +
                    "finished level on points that season, so there is no gap to land in.",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Title ${percent(projection.titleChance)} · " +
                "Top four ${percent(projection.chanceOfTop(4))} · " +
                "Relegation ${percent(projection.relegationChance)}",
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceMuted,
        )
        Text(
            text = "Expected finish ${"%.1f".format(projection.expectedPosition)}" +
                " · ${"%.0f".format(projection.expectedPoints)} points" +
                " · over ${projection.runs} simulated seasons",
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceFaint,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Rounds to whole percent, but never to a zero that would read as impossible. */
internal fun percent(p: Double): String = when {
    p <= 0.0 -> "0%"
    p < 0.005 -> "<1%"
    else -> "${Math.round(p * 100)}%"
}

// ---------------------------------------------------------- coming up

/**
 * The fixtures ahead: who, where, how they are placed, how they are playing,
 * and how hard it should be.
 *
 * A short RUN rather than only the next match, because a run is what makes a
 * season feel like something to survive — Madrid away, then Barcelona at home,
 * then Atlético away reads very differently from one fixture at a time.
 *
 * Carries no result. The whole season is already simulated behind this screen,
 * so the one hard rule is that a preview must never leak what it knows.
 */
@Composable
private fun ComingUp(outlooks: List<FixtureOutlook>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "COMING UP",
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceFaint,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        outlooks.forEachIndexed { index, outlook -> OutlookRow(outlook, lead = index == 0) }
    }
}

@Composable
private fun OutlookRow(outlook: FixtureOutlook, lead: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (lead) SurfaceRaised2 else SurfaceRaised1)
            .padding(horizontal = 12.dp, vertical = if (lead) 12.dp else 9.dp),
    ) {
        ClubBadge(clubName = outlook.opponent.name, size = if (lead) 30.dp else 22.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = outlook.opponent.name,
                style = if (lead) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = OnSurfacePrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = buildString {
                        append(if (outlook.isHome) "Home" else "Away")
                        outlook.opponentPosition?.let { append(" · " + ordinal(it)) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceMuted,
                )
                if (outlook.opponentForm.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    FormRun(outlook.opponentForm)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = difficultyStars(outlook.difficulty),
            style = MaterialTheme.typography.labelSmall,
            color = difficultyColour(outlook.difficulty),
        )
    }
}

/** Their last five, oldest first — real history until they have played you. */
@Composable
private fun FormRun(form: List<FormResult>) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (result in form) {
            Box(
                modifier = Modifier
                    .size(width = 12.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        when (result) {
                            FormResult.Win -> ResultWin
                            FormResult.Draw -> ResultDraw
                            FormResult.Loss -> ResultLoss
                        },
                    ),
            )
        }
    }
}

private fun difficultyStars(level: Int): String =
    "★".repeat(level.coerceIn(1, 5)) + "☆".repeat((5 - level).coerceIn(0, 4))

private fun difficultyColour(level: Int): Color = when {
    level >= 5 -> ResultLoss
    level == 4 -> AccentGold
    level <= 2 -> ResultWin
    else -> OnSurfaceMuted
}

/** 1 -> 1st, 2 -> 2nd, 11 -> 11th. */
internal fun ordinal(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}

// ------------------------------------------------------------ previews

@Preview(name = "Draw in progress", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 800)
@Composable
private fun SeasonDrawPreview() {
    DreamXITheme {
        SeasonScreen(
            state = previewDrawState(settled = false),
            onKickOff = {}, onPlayWeek = {}, onPlayToEnd = {},
            onStopAutoPlay = {}, onSkipToEnd = {}, onOpenStats = {}, onCloseStats = {},
            onAskExit = {}, onDismissExit = {}, onRetry = {}, onFinish = {},
        )
    }
}

@Preview(name = "Place won", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 800)
@Composable
private fun SeasonPlaceWonPreview() {
    DreamXITheme {
        SeasonScreen(
            state = previewDrawState(settled = true),
            onKickOff = {}, onPlayWeek = {}, onPlayToEnd = {},
            onStopAutoPlay = {}, onSkipToEnd = {}, onOpenStats = {}, onCloseStats = {},
            onAskExit = {}, onDismissExit = {}, onRetry = {}, onFinish = {},
        )
    }
}

@Preview(name = "Matchday", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 900)
@Composable
private fun SeasonMatchdayPreview() {
    DreamXITheme {
        SeasonScreen(
            state = previewSeasonState(),
            onKickOff = {}, onPlayWeek = {}, onPlayToEnd = {},
            onStopAutoPlay = {}, onSkipToEnd = {}, onOpenStats = {}, onCloseStats = {},
            onAskExit = {}, onDismissExit = {}, onRetry = {}, onFinish = {},
        )
    }
}

@Preview(name = "Error", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 500)
@Composable
private fun SeasonErrorPreview() {
    DreamXITheme {
        SeasonScreen(
            state = SeasonUiState(error = DreamXiError.Offline),
            onKickOff = {}, onPlayWeek = {}, onPlayToEnd = {},
            onStopAutoPlay = {}, onSkipToEnd = {}, onOpenStats = {}, onCloseStats = {},
            onAskExit = {}, onDismissExit = {}, onRetry = {}, onFinish = {},
        )
    }
}
