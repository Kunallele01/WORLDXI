package com.dreamxi.app.feature.worldcup

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamxi.app.core.ui.components.ClubBadge
import com.dreamxi.app.core.ui.components.DreamXiPrimaryButton
import com.dreamxi.app.core.ui.components.DreamXiSecondaryButton
import com.dreamxi.app.core.ui.components.ErrorNotice
import com.dreamxi.app.feature.season.surname
import com.dreamxi.app.sim.worldcup.GROUP_ROUND
import com.dreamxi.app.sim.worldcup.GoalKind
import com.dreamxi.app.sim.worldcup.THIRD_PLACE_ROUND
import com.dreamxi.app.sim.worldcup.WcGoal
import com.dreamxi.app.sim.worldcup.WcGroupRow
import com.dreamxi.app.sim.worldcup.WcPlayedMatch
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DisplayFontFamily
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
 * A World Cup, from the draw for a place to the final.
 *
 * Stateless, like the season screen, so every phase can be previewed without a
 * backend or a finished draft behind it.
 */
@Composable
fun WcTournamentScreen(
    state: WcTournamentUiState,
    onKickOff: () -> Unit,
    onPlayStage: () -> Unit,
    onPlayToEnd: () -> Unit,
    onStopAutoPlay: () -> Unit,
    onSkipToEnd: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(SurfaceBase).statusBarsPadding()) {
        when {
            state.error != null -> ErrorNotice(
                error = state.error,
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )

            state.phase == WcPhase.Loading -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center),
            ) {
                CircularProgressIndicator(color = AccentGold, strokeWidth = 3.dp)
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Finding a World Cup for your XI",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted,
                )
            }

            state.phase == WcPhase.Drawing || state.phase == WcPhase.PlaceWon ->
                DrawStage(state = state, onKickOff = onKickOff)

            else -> TournamentInProgress(
                state = state,
                onPlayStage = onPlayStage,
                onPlayToEnd = onPlayToEnd,
                onStopAutoPlay = onStopAutoPlay,
                onSkipToEnd = onSkipToEnd,
                onDone = onDone,
            )
        }
    }
}

// ---------------------------------------------------------------- the draw

/**
 * The draw for a place at the World Cup. Every nation that finished bottom of
 * its group is in it — eight, or twelve in 2026 — and the tally climbs the way
 * the club draw's does, because this is the moment the run stops being a list.
 */
@Composable
private fun DrawStage(state: WcTournamentUiState, onKickOff: () -> Unit) {
    val settled = state.phase == WcPhase.PlaceWon
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "WORLD CUP ${state.year ?: ""}",
            fontFamily = DisplayFontFamily,
            fontSize = 34.sp,
            color = AccentGold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        if (state.hosts.isNotEmpty()) {
            Text(
                text = "Hosted by ${state.hosts.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (settled) "You take their place" else "The draw",
            style = MaterialTheme.typography.headlineMedium,
            color = OnSurfacePrimary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${state.drawCandidates.size} nations finished bottom of their group. One place is yours.",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        val tallies = state.drawTallies
        val leading = tallies.maxOfOrNull { it.value } ?: 0
        for (id in state.drawCandidates) {
            val count = tallies[id] ?: 0
            val chosen = settled && id == state.takeover?.replacedTeamId
            DrawRow(
                name = state.name(id),
                count = count,
                share = if (leading > 0) count / leading.toFloat() else 0f,
                highlighted = chosen,
                dimmed = settled && !chosen,
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                settled -> "${state.replacedId?.let(state::name)} finished bottom of Group " +
                    "${state.userGroup}. Their fixtures are yours."
                state.inSuddenDeath -> "Level. Drawing again…"
                else -> "Draw ${state.drawTick} of ${state.takeover?.ticks?.size ?: 0}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (settled) OnSurfacePrimary else OnSurfaceFaint,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        if (settled) {
            Spacer(Modifier.height(20.dp))
            DreamXiPrimaryButton(text = "Kick off", onClick = onKickOff, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DrawRow(name: String, count: Int, share: Float, highlighted: Boolean, dimmed: Boolean) {
    val width by animateFloatAsState(share.coerceIn(0f, 1f), tween(180), label = "wcDrawBar")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceRaised1)
            .border(1.dp, if (highlighted) AccentGold else OutlineSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        ClubBadge(clubName = name, size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                color = if (dimmed) OnSurfaceFaint else OnSurfacePrimary,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(SurfaceRaised2)) {
                Box(
                    Modifier.fillMaxWidth(width).height(5.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (highlighted) AccentGold else if (dimmed) SurfaceRaised1 else OnSurfaceMuted),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.titleMedium,
            color = if (highlighted) AccentGold else if (dimmed) OnSurfaceFaint else OnSurfaceMuted,
        )
    }
}

// ---------------------------------------------------------- the tournament

@Composable
private fun TournamentInProgress(
    state: WcTournamentUiState,
    onPlayStage: () -> Unit,
    onPlayToEnd: () -> Unit,
    onStopAutoPlay: () -> Unit,
    onSkipToEnd: () -> Unit,
    onDone: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Back to the top on every stage: the result is what the tap was for.
    LaunchedEffect(state.visibleStage) {
        if (state.visibleStage >= 0) listState.animateScrollToItem(0)
    }

    Column(Modifier.fillMaxSize()) {
        Header(state)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (state.isOver) {
                item(key = "summary") { FinalSummary(state) }
            }
            if (state.visibleStage >= 0) {
                item(key = "stage") { StageResults(state) }
            }
            item(key = "group") { GroupTable(state) }
            if (state.knockoutSoFar.isNotEmpty()) {
                item(key = "bracket") { Bracket(state) }
            }
            item(key = "credit") {
                Text(
                    text = "✦ never happened: your run changed who played.\n" +
                        "Real results and scorers: Wikipedia, CC BY-SA 4.0.",
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceFaint,
                )
            }
        }
        Controls(state, onPlayStage, onPlayToEnd, onStopAutoPlay, onSkipToEnd, onDone)
    }
}

@Composable
private fun Header(state: WcTournamentUiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(SurfaceRaised1).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "WORLD CUP ${state.year ?: ""} · GROUP ${state.userGroup.orEmpty()}" +
                    if (state.isFreeMode) "  ·  FREE MODE" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (state.isFreeMode) AccentGold else OnSurfaceFaint,
            )
            Text(
                text = state.currentStage?.title ?: "Ready to start",
                style = MaterialTheme.typography.titleLarge,
                color = OnSurfacePrimary,
            )
        }
        Text(
            text = state.userStatus,
            style = MaterialTheme.typography.labelLarge,
            color = AccentGold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(150.dp),
        )
    }
}

@Composable
private fun StageResults(state: WcTournamentUiState) {
    Column(Modifier.fillMaxWidth()) {
        val mine = state.userMatch
        if (mine != null) {
            UserMatchCard(mine, state)
            Spacer(Modifier.height(14.dp))
        }
        val others = state.currentMatches.filterNot { it.involves(WC_USER_ID) }
            .sortedWith(compareBy({ it.group ?: "" }, { it.round }))
        if (others.isNotEmpty()) {
            SectionLabel(if (mine == null) "THIS ROUND" else "ELSEWHERE")
            for (m in others) MatchRow(m, state)
        }
    }
}

@Composable
private fun UserMatchCard(m: WcPlayedMatch, state: WcTournamentUiState) {
    val userHome = m.homeId == WC_USER_ID
    val won = m.winnerId == WC_USER_ID
    val accent = when {
        m.winnerId == null -> ResultDraw
        won -> ResultWin
        else -> ResultLoss
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceRaised1)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            text = (if (m.round == GROUP_ROUND) "GROUP ${m.group}" else m.round.uppercase()),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.name(m.homeId),
                style = MaterialTheme.typography.titleMedium,
                color = OnSurfacePrimary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                fontWeight = if (userHome) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = "  ${m.score.homeGoals} - ${m.score.awayGoals}  ",
                style = MaterialTheme.typography.headlineMedium,
                color = accent,
            )
            Text(
                text = state.name(m.awayId),
                style = MaterialTheme.typography.titleMedium,
                color = OnSurfacePrimary,
                modifier = Modifier.weight(1f),
                fontWeight = if (!userHome) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        scoreNote(m, state)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = TextAlign.Center,
            )
        }
        if (m.goals.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            GoalSheet(m)
        }
    }
}

/**
 * The goals, in the order they went in.
 *
 * ONE LINE PER GOAL, BOTH SIDES TOGETHER. Two columns — home's goals down one,
 * away's down the other — put each side's first goal on the same line as the
 * other's, so a 5th-minute opener and a 27th-minute equaliser sat level with
 * each other. These are real minutes off Wikipedia, so reading the match down
 * the page ought to give the match as it happened.
 *
 * The side still says who scored: the half opposite each goal is left empty.
 */
@Composable
private fun GoalSheet(m: WcPlayedMatch, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (g in m.goals.sortedWith(compareBy({ it.minute }, { it.stoppage ?: 0 }))) {
            // An own goal is credited to the side that benefits, which is how it
            // is scored and how the scoreline above was built.
            val home = g.teamId == m.homeId
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (home) GoalText(g, TextAlign.End)
                }
                Spacer(Modifier.width(24.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (!home) GoalText(g, TextAlign.Start)
                }
            }
        }
    }
}

@Composable
private fun GoalText(g: WcGoal, align: TextAlign) {
    Text(
        text = "⚽  ${goalLine(g)}",
        style = MaterialTheme.typography.bodyMedium,
        color = OnSurfacePrimary,
        textAlign = align,
    )
}

private fun goalLine(g: WcGoal): String {
    val minute = g.stoppage?.let { "${g.minute}+$it'" } ?: "${g.minute}'"
    val kind = when (g.kind) {
        GoalKind.PENALTY -> " (pen)"
        GoalKind.OWN_GOAL -> " (og)"
        GoalKind.GOAL -> ""
    }
    return "${surname(g.scorer)} $minute$kind"
}

/** "After extra time", or "Portugal won 3-1 on penalties", when there is more to a score than the score. */
private fun scoreNote(m: WcPlayedMatch, state: WcTournamentUiState): String? {
    val shootout = m.score.shootout
    return when {
        shootout != null -> {
            val winner = state.name(if (shootout.homeWon) m.homeId else m.awayId)
            val score = if (shootout.homeScore != null && shootout.awayScore != null) {
                val (w, l) = if (shootout.homeWon) shootout.homeScore to shootout.awayScore
                else shootout.awayScore to shootout.homeScore
                " $w-$l"
            } else {
                ""
            }
            "$winner won$score on penalties"
        }
        m.score.extraTime -> "After extra time"
        else -> null
    }
}

@Composable
private fun MatchRow(m: WcPlayedMatch, state: WcTournamentUiState) {
    val rewritten = m.simulated && !m.involves(WC_USER_ID)
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.name(m.homeId),
                style = MaterialTheme.typography.bodyMedium,
                color = if (m.winnerId == m.homeId) OnSurfacePrimary else OnSurfaceMuted,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "  ${m.score.homeGoals}-${m.score.awayGoals}  ",
                style = MaterialTheme.typography.bodyMedium,
                color = if (rewritten) AccentGold else OnSurfacePrimary,
            )
            Text(
                text = state.name(m.awayId) + if (rewritten) "  ✦" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (m.winnerId == m.awayId) OnSurfacePrimary else OnSurfaceMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        scoreNote(m, state)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The user's group: position, nation, played, W-D-L, goal difference, points. */
@Composable
private fun GroupTable(state: WcTournamentUiState) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("GROUP ${state.userGroup.orEmpty()}")
        TableLine("#", "Nation", "P", "W-D-L", "GD", "Pts", header = true, user = false)
        state.userGroupRows.forEachIndexed { i, row ->
            TableLine(
                pos = "${i + 1}",
                name = state.name(row.teamId),
                played = "${row.played}",
                record = "${row.won}-${row.drawn}-${row.lost}",
                diff = row.goalDifference.let { if (it > 0) "+$it" else "$it" },
                points = "${row.points}",
                header = false,
                user = row.teamId == WC_USER_ID,
            )
        }
    }
}

@Composable
private fun TableLine(
    pos: String, name: String, played: String, record: String, diff: String, points: String,
    header: Boolean, user: Boolean,
) {
    val color: Color = when {
        header -> OnSurfaceFaint
        user -> AccentGold
        else -> OnSurfacePrimary
    }
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (user) AccentGold.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Text(pos, style = style, color = color, modifier = Modifier.width(22.dp))
        Text(name, style = style, color = color, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(played, style = style, color = color, modifier = Modifier.width(26.dp), textAlign = TextAlign.End)
        Text(record, style = style, color = color, modifier = Modifier.width(58.dp), textAlign = TextAlign.End)
        Text(diff, style = style, color = color, modifier = Modifier.width(38.dp), textAlign = TextAlign.End)
        Text(points, style = style, color = color, modifier = Modifier.width(38.dp), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold)
    }
}

/** Every knockout tie revealed so far, round by round. */
@Composable
private fun Bracket(state: WcTournamentUiState) {
    Column(Modifier.fillMaxWidth()) {
        SectionLabel("KNOCKOUTS")
        for ((round, ties) in state.knockoutSoFar) {
            Text(
                text = round,
                style = MaterialTheme.typography.titleSmall,
                color = OnSurfacePrimary,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            for (tie in ties) MatchRow(tie, state)
        }
    }
}

@Composable
private fun FinalSummary(state: WcTournamentUiState) {
    val result = state.result ?: return
    val userWon = result.championId == WC_USER_ID
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (userWon) AccentGold.copy(alpha = 0.12f) else SurfaceRaised1)
            .border(1.dp, if (userWon) AccentGold else OutlineSubtle, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text("CHAMPIONS", style = MaterialTheme.typography.labelSmall, color = AccentGold)
        Text(
            text = state.name(result.championId).uppercase(),
            fontFamily = DisplayFontFamily,
            fontSize = 32.sp,
            color = OnSurfacePrimary,
        )
        Text(
            text = if (userWon) "Your XI won the World Cup." else "Your XI: ${state.userStatus.replaceFirstChar { it.lowercase() }}.",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(14.dp))
        SectionLabel("TOP SCORERS")
        result.topScorers().take(6).forEach { (who, goals) ->
            val (scorer, teamId) = who
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    text = "${surname(scorer)} · ${state.name(teamId)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (teamId == WC_USER_ID) AccentGold else OnSurfacePrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("$goals", style = MaterialTheme.typography.bodyMedium, color = OnSurfacePrimary)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = OnSurfaceFaint,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Controls(
    state: WcTournamentUiState,
    onPlayStage: () -> Unit,
    onPlayToEnd: () -> Unit,
    onStopAutoPlay: () -> Unit,
    onSkipToEnd: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().background(SurfaceRaised1).padding(16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            state.isOver -> DreamXiPrimaryButton(text = "Done", onClick = onDone, modifier = Modifier.fillMaxWidth())
            state.isAdvancing -> {
                DreamXiPrimaryButton(text = "Stop", onClick = onStopAutoPlay, modifier = Modifier.fillMaxWidth())
                DreamXiSecondaryButton(text = "Skip to the end", onClick = onSkipToEnd, modifier = Modifier.fillMaxWidth())
            }
            else -> {
                DreamXiPrimaryButton(
                    text = state.nextStage?.playLabel ?: "Play on",
                    onClick = onPlayStage,
                    modifier = Modifier.fillMaxWidth(),
                )
                DreamXiSecondaryButton(
                    text = "Play the rest of the World Cup",
                    onClick = onPlayToEnd,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
