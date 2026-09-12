package com.dreamxi.app.sim

/** One of a side's recent results, from its own point of view. */
enum class FormResult { Win, Draw, Loss }

/** What a user can know about a fixture before it is played. */
data class FixtureOutlook(
    val matchday: Int,
    val opponent: SimTeam,
    val isHome: Boolean,
    /** The opponent's league position as things stand, or null before kick-off. */
    val opponentPosition: Int?,
    /** Their last five, oldest first. Real history until they have played you. */
    val opponentForm: List<FormResult>,
    /** What this fixture is worth to the user, over every plausible scoreline. */
    val expectedPoints: Double,
    /** 1 (routine) to 5 (brutal), relative to the user's own fixture list. */
    val difficulty: Int,
)

/**
 * Turns the fixtures a user has not played yet into something worth reading
 * before pressing the button.
 *
 * NOTHING HERE IS INVENTED. The opponent's strength is the rating the engine
 * will actually use, their position comes from the live table, and their form
 * is real history — until they have played the user, every one of their results
 * is the one that really happened that season.
 *
 * The whole season is already simulated when this runs, so the one hard rule is
 * that an outlook must never carry a result. It describes the fixture, not the
 * outcome.
 */
object FixtureOutlooks {

    /** How many bands the difficulty stars are spread over. */
    private const val BANDS = 5

    /**
     * Cut points that split the user's own fixtures into five difficulty bands.
     *
     * DELIBERATELY RELATIVE TO THIS USER'S SEASON rather than to fixed
     * thresholds. A drafted XI of 90-rated players finds nothing in a league
     * hard, and a poor one finds everything hard; absolute bands would print
     * five stars against all 38 fixtures in one case and one star against all
     * 38 in the other, which tells the user nothing. Quintiles always spread,
     * and they answer the question actually being asked: is this one of MY
     * tougher games?
     *
     * Computed once for the season, since the ratings do not change.
     */
    fun difficultyThresholds(fixtures: List<SimFixture>, team: SimTeam): List<Double> {
        val values = fixtures
            .filter { it.home.id == team.id || it.away.id == team.id }
            .map { expectedPointsFor(it, team) }
            .sorted()
        if (values.size < BANDS) return emptyList()
        return (1 until BANDS).map { i -> values[values.size * i / BANDS] }
    }

    /**
     * The next [count] fixtures for [team] after [afterMatchday].
     */
    fun next(
        fixtures: List<SimFixture>,
        team: SimTeam,
        table: List<TableRow>,
        afterMatchday: Int,
        thresholds: List<Double>,
        count: Int = 4,
    ): List<FixtureOutlook> {
        val positions = table.associate { it.team.id to it.position }
        return fixtures
            .filter { (it.home.id == team.id || it.away.id == team.id) && it.matchday > afterMatchday }
            .sortedBy { it.matchday }
            .take(count)
            .map { f ->
                val isHome = f.home.id == team.id
                val opponent = if (isHome) f.away else f.home
                val xPoints = expectedPointsFor(f, team)
                FixtureOutlook(
                    matchday = f.matchday,
                    opponent = opponent,
                    isHome = isHome,
                    opponentPosition = positions[opponent.id],
                    opponentForm = formOf(fixtures, opponent, afterMatchday),
                    expectedPoints = xPoints,
                    difficulty = difficultyOf(xPoints, thresholds),
                )
            }
    }

    /**
     * The opponent's last five results as things stand.
     *
     * Their matches against everyone else are real history, so this is genuine
     * form rather than something the simulation made up — which is a nicer
     * property than it first appears: the side the user is about to face really
     * did win their last four that season.
     */
    private fun formOf(
        fixtures: List<SimFixture>,
        team: SimTeam,
        upToMatchday: Int,
        length: Int = 5,
    ): List<FormResult> = fixtures
        .filter {
            (it.home.id == team.id || it.away.id == team.id) && it.matchday <= upToMatchday
        }
        .sortedBy { it.matchday }
        .takeLast(length)
        .mapNotNull { f ->
            val gf = f.goalsFor(team) ?: return@mapNotNull null
            val ga = f.goalsAgainst(team) ?: return@mapNotNull null
            when {
                gf > ga -> FormResult.Win
                gf == ga -> FormResult.Draw
                else -> FormResult.Loss
            }
        }

    /** What the fixture is worth to [team], from every plausible scoreline. */
    fun expectedPointsFor(fixture: SimFixture, team: SimTeam): Double {
        val (home, away) = MatchEngine.expectedPointsBoth(fixture.home.rating, fixture.away.rating)
        return if (fixture.home.id == team.id) home else away
    }

    /** Fewer expected points means a harder game, so the scale is inverted. */
    private fun difficultyOf(expectedPoints: Double, thresholds: List<Double>): Int {
        if (thresholds.isEmpty()) return 3
        val band = thresholds.count { expectedPoints >= it }
        return (BANDS - band).coerceIn(1, BANDS)
    }
}
