package com.dreamxi.app.sim

/** A side's record at one venue. */
data class VenueRecord(
    val played: Int = 0,
    val won: Int = 0,
    val drawn: Int = 0,
    val lost: Int = 0,
    val goalsFor: Int = 0,
    val goalsAgainst: Int = 0,
) {
    val points: Int get() = won * 3 + drawn
    val goalDifference: Int get() = goalsFor - goalsAgainst
    /** Points per match, for comparing two venues with different game counts. */
    val pointsPerMatch: Double get() = if (played > 0) points / played.toDouble() else 0.0
}

/**
 * What a season was worth against what it actually returned.
 *
 * The point of the whole thing: finishing first is a fact, but finishing first
 * when the fixtures were worth a projected fourth is a story. Everything here
 * is measured against the same engine that produced the season, so the two
 * numbers are always talking about the same thing.
 */
data class SeasonAnalysis(
    val expectedPoints: Double,
    val actualPoints: Int,
    val expectedGoalsFor: Double,
    val actualGoalsFor: Int,
    val expectedGoalsAgainst: Double,
    val actualGoalsAgainst: Int,
    val home: VenueRecord,
    val away: VenueRecord,
) {
    val pointsOverExpectation: Double get() = actualPoints - expectedPoints
    val goalsOverExpectation: Double get() = actualGoalsFor - expectedGoalsFor
    /** Positive means the defence conceded FEWER than the chances warranted. */
    val goalsPreventedOverExpectation: Double get() = expectedGoalsAgainst - actualGoalsAgainst

    val played: Int get() = home.played + away.played
    val strongerAtHome: Boolean get() = home.pointsPerMatch >= away.pointsPerMatch
}

/**
 * Measures a completed season against what it should have produced.
 *
 * WHY NONE OF THIS IS SIMULATED. Expected points come from
 * [MatchEngine.expectedPointsBoth], which sums over every plausible scoreline
 * analytically — a whole 38-match season costs about a tenth of a millisecond.
 * Expected goals are the lambdas the engine already used to produce the
 * scorelines, carried on [MatchResult], so they are the exact numbers the
 * season was drawn from rather than a re-derivation that could drift.
 *
 * ONE HONEST LABEL. Beating expected goals here is variance and nothing else.
 * In real football, out-scoring your xG is partly finishing ability; this
 * engine models no finishing skill beyond a player's npxG, so the gap is a
 * Poisson draw running hot. Anything on screen must say "above expectation",
 * never "finishing" — the second one would have the UI claim knowledge the
 * model does not have.
 */
object SeasonAnalyst {

    /**
     * Analyses [team]'s season from [fixtures].
     *
     * Only matches the team actually played are considered, and only simulated
     * ones carry usable expected values — a real historical result stores its
     * own scoreline in place of a lambda, so including one would compare a
     * number against itself.
     */
    fun analyse(fixtures: List<SimFixture>, team: SimTeam): SeasonAnalysis {
        var xPoints = 0.0
        var xFor = 0.0
        var xAgainst = 0.0
        var home = VenueRecord()
        var away = VenueRecord()

        for (f in fixtures) {
            val isHome = f.home.id == team.id
            val isAway = f.away.id == team.id
            if (!isHome && !isAway) continue

            val gf = f.goalsFor(team) ?: continue
            val ga = f.goalsAgainst(team) ?: continue

            if (f.simulated) {
                val (h, a) = MatchEngine.expectedPointsBoth(f.home.rating, f.away.rating)
                xPoints += if (isHome) h else a
                xFor += if (isHome) f.result.homeExpected else f.result.awayExpected
                xAgainst += if (isHome) f.result.awayExpected else f.result.homeExpected
            }

            val record = if (isHome) home else away
            val updated = record.copy(
                played = record.played + 1,
                won = record.won + if (gf > ga) 1 else 0,
                drawn = record.drawn + if (gf == ga) 1 else 0,
                lost = record.lost + if (gf < ga) 1 else 0,
                goalsFor = record.goalsFor + gf,
                goalsAgainst = record.goalsAgainst + ga,
            )
            if (isHome) home = updated else away = updated
        }

        return SeasonAnalysis(
            expectedPoints = xPoints,
            actualPoints = home.points + away.points,
            expectedGoalsFor = xFor,
            actualGoalsFor = home.goalsFor + away.goalsFor,
            expectedGoalsAgainst = xAgainst,
            actualGoalsAgainst = home.goalsAgainst + away.goalsAgainst,
            home = home,
            away = away,
        )
    }
}
