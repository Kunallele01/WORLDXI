package com.dreamxi.app.feature.season

import com.dreamxi.app.sim.SeasonSimulator
import com.dreamxi.app.sim.ScorerWeight
import com.dreamxi.app.sim.SimTeam
import com.dreamxi.app.sim.SquadProfile
import com.dreamxi.app.sim.TakeoverDraw
import com.dreamxi.app.sim.TeamRating

/**
 * Fixed data for the @Previews, so every phase of the season can be looked at
 * in Android Studio without a backend, a network or a completed draft.
 *
 * Real club names on purpose: a preview full of "Team A" hides exactly the
 * problems previews are for — a long name overflowing its row, a missing
 * crest, a table column collapsing.
 */
private fun team(id: String, name: String, attack: Double, defence: Double, user: Boolean = false) =
    SimTeam(
        id = id,
        name = name,
        season = "2023/24",
        rating = TeamRating(attack, defence, attack, 78.0),
        isUserTeam = user,
        // A plausible scoring squad, so the previews show a real-looking
        // scoresheet and stats section rather than empty ones.
        squad = SquadProfile.fromSeasonTotals(
            listOf(
                ScorerWeight("$name Striker", 18.0, 5.0, 3.0, 0.0),
                ScorerWeight("$name Winger", 11.0, 9.0, 4.0, 0.0),
                ScorerWeight("$name Playmaker", 6.0, 12.0, 6.0, 1.0),
                ScorerWeight("$name Midfielder", 4.0, 6.0, 8.0, 0.0),
                ScorerWeight("$name Centre-back", 3.0, 1.0, 9.0, 1.0),
                ScorerWeight("$name Full-back", 1.0, 7.0, 7.0, 0.0),
                ScorerWeight("$name Substitute", 5.0, 4.0, 2.0, 0.0),
            ),
        ),
    )

private val previewClubs = listOf(
    team("club-1", "Manchester City", 2.55, 0.87),
    team("club-2", "Arsenal", 2.32, 0.76),
    team("club-3", "Liverpool", 2.24, 1.03),
    team("club-4", "Aston Villa", 1.95, 1.42),
    team("club-5", "Tottenham Hotspur", 1.92, 1.55),
    team("club-6", "Chelsea", 1.87, 1.50),
    team("club-7", "Newcastle United", 1.98, 1.55),
    team("club-8", "Manchester United", 1.60, 1.50),
    team("club-9", "West Ham United", 1.55, 1.71),
    team("club-10", "Crystal Palace", 1.39, 1.50),
    team("club-11", "Brighton & Hove Albion", 1.55, 1.60),
    team("club-12", "Bournemouth", 1.47, 1.71),
    team("club-13", "Fulham", 1.42, 1.55),
    team("club-14", "Wolverhampton Wanderers", 1.34, 1.55),
    team("club-15", "Everton", 1.05, 1.29),
    team("club-16", "Brentford", 1.42, 1.71),
    team("club-17", "Nottingham Forest", 1.26, 1.71),
    team("club-18", "Luton Town", 1.34, 2.13),
    team("club-19", "Burnley", 1.13, 2.05),
    team("club-20", "Sheffield United", 0.92, 2.55),
)

private val previewUser = team("dream-xi", "Dream XI", 2.41, 0.95, user = true)

private val relegated = listOf("club-18", "club-19", "club-20")

fun previewDrawState(settled: Boolean): SeasonUiState {
    val takeover = TakeoverDraw.draw(relegated, seed = 12)
    val candidates = previewClubs.filter { it.id in relegated }
    val replacedId = takeover.replacedTeamId
    return SeasonUiState(
        phase = if (settled) SeasonPhase.PlaceWon else SeasonPhase.Drawing,
        leagueName = "Premier League",
        seasonLabel = "2023/24",
        drawCandidates = candidates,
        takeover = takeover,
        drawTick = if (settled) takeover.ticks.size else (takeover.ticks.size * 0.62).toInt(),
        replacedClub = candidates.find { it.id == replacedId },
        replacedClubRealPosition = 18 + relegated.indexOf(replacedId),
        userTeam = previewUser,
    )
}

fun previewSeasonState(matchday: Int = 12): SeasonUiState {
    val league = previewClubs.filterNot { it.id == "club-19" } + previewUser
    val season = SeasonSimulator.play(league, seed = 3)
    val played = season.fixtures.filter { it.matchday <= matchday }
    return SeasonUiState(
        phase = SeasonPhase.Playing,
        leagueName = "Premier League",
        seasonLabel = "2023/24",
        userTeam = previewUser,
        replacedClub = previewClubs.find { it.id == "club-19" },
        fixtures = season.fixtures,
        table = SeasonSimulator.buildTable(league, played),
        totalMatchdays = season.fixtures.maxOf { it.matchday },
        visibleMatchday = matchday,
    )
}
