package com.dreamxi.app.sim

import kotlin.math.exp
import kotlin.random.Random

/** One match, as played. */
data class MatchResult(
    val homeGoals: Int,
    val awayGoals: Int,
    val homeExpected: Double,
    val awayExpected: Double,
) {
    val homePoints: Int get() = when {
        homeGoals > awayGoals -> 3
        homeGoals == awayGoals -> 1
        else -> 0
    }
    val awayPoints: Int get() = when {
        awayGoals > homeGoals -> 3
        homeGoals == awayGoals -> 1
        else -> 0
    }
    override fun toString() = "$homeGoals-$awayGoals"
}

/**
 * Plays a match between two elevens.
 *
 * HOW A SCORELINE IS ARRIVED AT. Each side's expected goals for the fixture is
 * its own attacking output, adjusted by how leaky the opponent is relative to
 * the league:
 *
 *     lambda_home = homeAttack * (awayDefence / leagueAverage) * homeAdvantage
 *
 * Then goals are drawn from a Poisson distribution on that mean. Poisson is
 * not a stylistic choice — football scorelines are close to Poisson, which is
 * why the same shape underlies every serious public model, and it is what lets
 * a 2.1-expected-goals side still lose 1-0 on a given afternoon. Removing the
 * randomness would make every fixture a foregone conclusion and the league
 * table a sorted list of ratings.
 *
 * The engine takes a [Random] rather than making one, so a run can be replayed
 * exactly. A user asking "what happened in that game?" must get the same
 * answer twice.
 */
object MatchEngine {

    fun play(home: TeamRating, away: TeamRating, random: Random): MatchResult {
        val lambdaHome = expectedGoals(home.attack, away.defence) * SimModel.HOME_ADVANTAGE
        val lambdaAway = expectedGoals(away.attack, home.defence)
        return MatchResult(
            homeGoals = poisson(lambdaHome, random),
            awayGoals = poisson(lambdaAway, random),
            homeExpected = lambdaHome,
            awayExpected = lambdaAway,
        )
    }

    /**
     * Attack met by defence. Dividing the opponent's concession rate by the
     * league average makes it a MULTIPLIER: an average defence leaves the
     * attack untouched, a mean one suppresses it, a poor one inflates it.
     * Using the raw number instead would make every match a goal glut.
     */
    fun expectedGoals(attack: Double, opponentDefence: Double): Double =
        (attack * (opponentDefence / SimModel.LEAGUE_GOALS_PER_TEAM))
            .coerceIn(SimModel.MIN_EXPECTED_GOALS, SimModel.MAX_EXPECTED_GOALS)

    /**
     * A Poisson draw by inversion — repeated multiplication until the product
     * falls below e^-lambda. Exact for the small means football produces, and
     * it needs nothing but a uniform random, which keeps the engine free of
     * any statistics dependency.
     *
     * Capped at 15 so a pathological lambda cannot spin forever.
     */
    fun poisson(lambda: Double, random: Random): Int {
        val limit = exp(-lambda)
        var product = random.nextDouble()
        var goals = 0
        while (product > limit && goals < 15) {
            goals++
            product *= random.nextDouble()
        }
        return goals
    }

    /**
     * The scoreline this fixture would produce on average, with no randomness.
     * For previews and explanations, never for a result the user is told is a
     * real match.
     */
    fun expectedScoreline(home: TeamRating, away: TeamRating): Pair<Double, Double> =
        expectedGoals(home.attack, away.defence) * SimModel.HOME_ADVANTAGE to
            expectedGoals(away.attack, home.defence)

    /**
     * Points BOTH sides would average from this fixture, over every plausible
     * scoreline rather than by simulating.
     *
     * Returns the pair on purpose. The away figure is NOT three minus the home
     * figure, and assuming it is quietly inflates a league table: a draw pays
     * one point to each side, so the two totals sum to 3 only when the match
     * cannot be drawn. Computing away points as `3 - homePoints` credited an
     * extra point for every draw and pushed a simulated season about four
     * points too high — small enough to look like model error rather than
     * arithmetic, which is exactly why it is worth having a single correct
     * function instead of the subtraction at each call site.
     */
    fun expectedPointsBoth(home: TeamRating, away: TeamRating): Pair<Double, Double> {
        val (lh, la) = expectedScoreline(home, away)
        var homeWin = 0.0
        var awayWin = 0.0
        var draw = 0.0
        for (h in 0..8) {
            for (a in 0..8) {
                val p = pmf(h, lh) * pmf(a, la)
                when {
                    h > a -> homeWin += p
                    h < a -> awayWin += p
                    else -> draw += p
                }
            }
        }
        return (3 * homeWin + draw) to (3 * awayWin + draw)
    }

    /** Points the HOME side would average from this fixture. */
    fun expectedPoints(home: TeamRating, away: TeamRating): Double =
        expectedPointsBoth(home, away).first

    private fun pmf(k: Int, lambda: Double): Double {
        var f = 1.0
        for (i in 2..k) f *= i
        var pow = 1.0
        repeat(k) { pow *= lambda }
        return exp(-lambda) * pow / f
    }
}
