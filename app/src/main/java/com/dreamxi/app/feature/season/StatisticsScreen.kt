package com.dreamxi.app.feature.season

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dreamxi.app.core.ui.components.DreamXiSecondaryButton
import com.dreamxi.app.sim.SeasonAnalysis
import com.dreamxi.app.sim.SeasonProjection
import com.dreamxi.app.sim.VenueRecord
import com.dreamxi.app.ui.theme.ResultWin
import com.dreamxi.app.ui.theme.ResultLoss
import com.dreamxi.app.sim.SeasonStats
import com.dreamxi.app.sim.SimTeam
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.SurfaceBase
import com.dreamxi.app.ui.theme.SurfaceRaised1

/**
 * The season's statistics, on their own screen.
 *
 * They used to hang off the bottom of the league table in one continuous
 * scroll. Adding the user's own XI to that would have meant two full sets of
 * leaderboards below twenty table rows, which is a column nobody reaches the
 * end of. Separating them also lets the user's side lead, which is what he
 * actually came to see: the league's records are context, his are the result.
 */
@Composable
fun StatisticsScreen(
    stats: SeasonStats,
    userTeam: SimTeam?,
    analysis: SeasonAnalysis?,
    projection: SeasonProjection?,
    finalPosition: Int?,
    seasonLabel: String,
    leagueName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    Column(modifier = modifier.fillMaxSize().background(SurfaceBase)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceRaised1)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = "${leagueName.uppercase()} · $seasonLabel",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
            )
            Text(
                text = "Season statistics",
                style = MaterialTheme.typography.titleLarge,
                color = OnSurfacePrimary,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(20.dp),
        ) {
            item {
                val ours = stats.topScorers.filter { it.isUserTeam } +
                    stats.topAssists.filter { it.isUserTeam }
                if (analysis != null && analysis.played > 0) {
                    SeasonAnalysisSection(analysis, projection, finalPosition)
                    Spacer(Modifier.height(28.dp))
                }
                if (ours.isNotEmpty() || userTeam != null) {
                    OurTeamSection(stats = stats, userTeam = userTeam)
                    Spacer(Modifier.height(28.dp))
                }
                LeagueSection(stats = stats)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().background(SurfaceRaised1).padding(16.dp),
        ) {
            DreamXiSecondaryButton(
                text = "Back to the table",
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * What the season was worth against what it returned.
 *
 * This leads the screen because it is the story the user came for: finishing
 * first is a fact, but finishing first when the fixtures were only worth a
 * projected fourth is an achievement.
 */
@Composable
private fun SeasonAnalysisSection(
    analysis: SeasonAnalysis,
    projection: SeasonProjection?,
    finalPosition: Int?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeading("SEASON ANALYSIS", AccentGold)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceRaised1)
                .padding(14.dp),
        ) {
            ExpectationRow("Points", analysis.expectedPoints, analysis.actualPoints)
            Spacer(Modifier.height(12.dp))
            ExpectationRow("Goals scored", analysis.expectedGoalsFor, analysis.actualGoalsFor)
            Spacer(Modifier.height(12.dp))
            ExpectationRow(
                label = "Goals conceded",
                expected = analysis.expectedGoalsAgainst,
                actual = analysis.actualGoalsAgainst,
                // Conceding fewer than expected is the good outcome, so the
                // colour inverts here or a mean defence would read as a failure.
                lowerIsBetter = true,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = verdict(analysis),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
            )
        }

        // The projection the user was shown before kick-off, now with the
        // answer beside it. This is the payoff of having shown it at all.
        if (projection != null && finalPosition != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceRaised1)
                    .padding(14.dp),
            ) {
                Text(
                    text = "YOU FINISHED " + ordinal(finalPosition).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = AccentGold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Projected " + "%.1f".format(projection.expectedPosition) +
                        " · that finish came up in " + percent(projection.chanceOf(finalPosition)) +
                        " of " + projection.runs + " simulated seasons",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = projectionVerdict(projection, finalPosition),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfacePrimary,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VenueCard("HOME", analysis.home, Modifier.weight(1f))
            VenueCard("AWAY", analysis.away, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = venueVerdict(analysis),
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceFaint,
        )
    }
}

/**
 * Expected against actual, with the gap called out.
 *
 * The gap is described as being above or below EXPECTATION, never as
 * "finishing". This engine models no finishing skill beyond a player's npxG, so
 * out-scoring the expected goals is a Poisson draw running hot and nothing
 * else. Calling it finishing would have the screen assert something the model
 * does not know.
 */
@Composable
private fun ExpectationRow(
    label: String,
    expected: Double,
    actual: Int,
    lowerIsBetter: Boolean = false,
) {
    val gap = actual - expected
    val good = if (lowerIsBetter) gap < 0 else gap > 0
    val meaningful = kotlin.math.abs(gap) >= 0.5
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfacePrimary,
            )
            Text(
                text = "expected " + "%.1f".format(expected),
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
            )
        }
        Text(
            text = "$actual",
            style = MaterialTheme.typography.titleLarge,
            color = OnSurfacePrimary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = when {
                !meaningful -> "on par"
                gap > 0 -> "+" + "%.1f".format(gap)
                else -> "%.1f".format(gap)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                !meaningful -> OnSurfaceFaint
                good -> ResultWin
                else -> ResultLoss
            },
            textAlign = TextAlign.End,
            modifier = Modifier.width(58.dp),
        )
    }
}

@Composable
private fun VenueCard(title: String, record: VenueRecord, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised1)
            .padding(14.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.labelSmall, color = OnSurfaceFaint)
        Spacer(Modifier.height(6.dp))
        Text(
            text = record.won.toString() + "W · " + record.drawn + "D · " + record.lost + "L",
            style = MaterialTheme.typography.titleMedium,
            color = OnSurfacePrimary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = record.goalsFor.toString() + ":" + record.goalsAgainst +
                " · " + record.points + " pts",
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceMuted,
        )
    }
}

/** Plain English for the points gap. */
private fun verdict(a: SeasonAnalysis): String {
    val gap = a.pointsOverExpectation
    return when {
        gap >= 8 -> "%.1f points more than these fixtures were worth. A season that ran hot."
            .format(gap)
        gap >= 3 -> "%.1f points above what the fixtures were worth.".format(gap)
        gap <= -8 -> "You left %.1f points behind — the fixtures were worth more than you took."
            .format(-gap)
        gap <= -3 -> "%.1f points below what the fixtures were worth.".format(-gap)
        else -> "Within a few points of what the fixtures were worth."
    }
}

/** How the finish compares with what the XI was worth. */
private fun projectionVerdict(p: SeasonProjection, finished: Int): String {
    val gap = p.expectedPosition - finished
    return when {
        gap >= 2.5 -> "You massively overperformed what this XI was worth."
        gap >= 1.0 -> "A finish above what this XI was worth."
        gap <= -2.5 -> "Well below what this XI was worth. A season that never got going."
        gap <= -1.0 -> "A finish below what this XI was worth."
        else -> "Almost exactly what this XI was worth."
    }
}

private fun venueVerdict(a: SeasonAnalysis): String {
    val gap = a.home.pointsPerMatch - a.away.pointsPerMatch
    return when {
        gap >= 0.8 -> "Formidable at home, ordinary on the road."
        gap >= 0.3 -> "Stronger at home, as most sides are."
        gap <= -0.3 -> "Better away from home, which is unusual."
        else -> "Much the same wherever you played."
    }
}

/**
 * The user's own XI.
 *
 * Built by filtering the league leaderboards to his side rather than by
 * counting again, so a number here can never disagree with the same number one
 * section further down.
 */
@Composable
private fun OurTeamSection(
    stats: SeasonStats,
    userTeam: SimTeam?,
    modifier: Modifier = Modifier,
) {
    val name = userTeam?.name ?: "Your XI"
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeading(name.uppercase(), AccentGold)

        // From the XI's OWN rankings, not filtered out of the league's top ten
        // — that is what limited this to a single name.
        StatBoard(
            title = "Top scorers",
            rows = stats.userScorers.take(5)
                .map { StatLine(it.player, perMatch(it.goals), "${it.goals}") },
        )
        StatBoard(
            title = "Top assists",
            rows = stats.userAssists.take(5)
                .map { StatLine(it.player, perMatch(it.assists), "${it.assists}") },
        )
        StatBoard(
            title = "Goals + assists",
            rows = stats.userInvolvements.take(5)
                .map { StatLine(it.player, "${it.goals} goals, ${it.assists} assists", "${it.involvements}") },
        )

        // The keeper gets his own block: clean sheets are a team record but
        // they are the number a goalkeeper is judged on, and nothing else on
        // this screen would ever mention him.
        val keeper = userTeam?.xi?.firstOrNull { it.role == "GK" }?.player?.name
        val record = stats.cleanSheets.firstOrNull { it.isUserTeam }
        if (record != null) {
            StatBoard(
                title = "In goal",
                rows = listOfNotNull(
                    keeper?.let { StatLine(it, "clean sheets", "${record.cleanSheets}") },
                    StatLine("Discipline", "yellow cards", "${record.yellows}"),
                    if (record.reds > 0) StatLine("Discipline", "red cards", "${record.reds}") else null,
                ),
            )
        }

        val booked = stats.userBooked.take(5)
        if (booked.isNotEmpty()) {
            StatBoard(
                title = "Most booked",
                rows = booked.map {
                    StatLine(
                        it.player,
                        "",
                        if (it.reds > 0) "${it.yellows}Y ${it.reds}R" else "${it.yellows}Y",
                    )
                },
            )
        }
    }
}

@Composable
private fun LeagueSection(stats: SeasonStats, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeading("THE LEAGUE", OnSurfaceFaint)

        StatBoard(
            title = "Top scorers",
            rows = stats.topScorers.take(10).map {
                StatLine(it.player, it.teamName, "${it.goals}", it.isUserTeam)
            },
        )
        StatBoard(
            title = "Top assists",
            rows = stats.topAssists.take(10).map {
                StatLine(it.player, it.teamName, "${it.assists}", it.isUserTeam)
            },
        )
        StatBoard(
            title = "Goals + assists",
            rows = stats.topInvolvements.take(10).map {
                StatLine(it.player, it.teamName, "${it.involvements}", it.isUserTeam)
            },
        )
        StatBoard(
            title = "Clean sheets",
            rows = stats.cleanSheets.take(10).map {
                StatLine(it.teamName, "", "${it.cleanSheets}", it.isUserTeam)
            },
        )
        StatBoard(
            title = "Most booked",
            rows = stats.mostBooked.take(10).map {
                StatLine(
                    it.player,
                    it.teamName,
                    if (it.reds > 0) "${it.yellows}Y ${it.reds}R" else "${it.yellows}Y",
                    it.isUserTeam,
                )
            },
        )

        Text(
            text = "${stats.totalGoals} goals · ${stats.totalYellows} yellows · " +
                "${stats.totalReds} reds across the season",
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceFaint,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SectionHeading(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

/**
 * Per-match rate beside a total.
 *
 * Context rather than correction. A drafted XI plays every minute of all 38
 * matches, so its totals sit above what rotated real players manage; showing
 * the rate lets those totals be compared with a real player's without altering
 * a single number the season actually produced.
 */
private fun perMatch(total: Int): String =
    "%.2f a game".format(total / 38.0)

private data class StatLine(
    val name: String,
    val subtitle: String,
    val value: String,
    val isUser: Boolean = false,
)

@Composable
private fun StatBoard(title: String, rows: List<StatLine>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised1)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = OnSurfacePrimary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        rows.forEachIndexed { index, row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceFaint,
                    modifier = Modifier.width(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (row.isUser) AccentGold else OnSurfacePrimary,
                        fontWeight = if (row.isUser) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                    if (row.subtitle.isNotBlank()) {
                        Text(
                            text = row.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceFaint,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = row.value,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (row.isUser) AccentGold else OnSurfacePrimary,
                )
            }
        }
    }
}

@Preview(name = "Statistics", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 1400)
@Composable
private fun StatisticsPreview() {
    DreamXITheme {
        val state = previewSeasonState(matchday = 38)
        StatisticsScreen(
            stats = com.dreamxi.app.sim.SeasonStatsBuilder.build(state.fixtures),
            userTeam = state.userTeam,
            analysis = state.userTeam?.let {
                com.dreamxi.app.sim.SeasonAnalyst.analyse(state.fixtures, it)
            },
            projection = null,
            finalPosition = state.table.firstOrNull { it.team.isUserTeam }?.position,
            seasonLabel = "2023/24",
            leagueName = "Premier League",
            onBack = {},
        )
    }
}
