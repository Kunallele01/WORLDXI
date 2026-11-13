package com.dreamxi.app.sim.europeancup

import com.dreamxi.app.sim.MatchEngine
import com.dreamxi.app.sim.SimModel
import com.dreamxi.app.sim.TeamRating
import com.dreamxi.app.sim.worldcup.WcMatchEngine
import com.dreamxi.app.sim.worldcup.WcModel
import com.dreamxi.app.sim.worldcup.WcScore
import kotlin.math.pow
import kotlin.random.Random

/**
 * Every constant the European Cup engine adds to the club engine's. The club
 * engine's expected goals are kept; what differs is the field (thirty-two clubs
 * from eleven leagues, not twenty from one) and the knockout rules.
 */
object EcModel {
    /**
     * The club engine's home advantage, deliberately unchanged. The 2023/24
     * group stage and knockouts read 1.35 (home 1.73 goals a match, away 1.28
     * over 124 matches), but at that size the standard error is about 0.1, which
     * does not distinguish it from 1.245. Revisit once more seasons are loaded.
     */
    const val HOME_ADVANTAGE = SimModel.HOME_ADVANTAGE

    /**
     * Scales the club engine's goals so a field of European Cup clubs scores at
     * the real rate. Measured, not guessed: EuropeanCupEngineTest fails if the
     * engine's mean expected goals over the real 2023/24 fixtures drifts from the
     * real mean (1.504 goals per team per match, final excluded) by more than 3%.
     *
     * It is solved TOGETHER with [GOALS_SHAPE]: whenever the shape moves, this must
     * be re-solved, because compressing the spread also changes the mean.
     */
    const val GOALS_SCALE = 1.0275

    /**
     * Compresses the gap between strong and weak clubs: expected goals are the club
     * engine's figure raised to this power before scaling. Below 1, a stronger side's
     * edge is smaller than the raw ratings say, as the World Cup's INTERNATIONAL_SHAPE
     * does for national teams.
     *
     * TUNED ON ONE SEASON, AND TO BE RE-MEASURED. At 1.0 the real goal difference of
     * the 124 non-final matches moved only 0.71 for each 1.0 the engine predicted, so
     * the engine overstated how far apart the clubs are, and an all-star XI won the
     * competition 40% of the time (the World Cup mode's dream XI wins about 19%).
     * Measured at the same time, scale re-solved for each:
     *
     *     shape  slope  all-star title rate
     *     1.00   0.71   39.5%
     *     0.85   0.84   34.3%
     *     0.70   1.01   26.0%     <- the spread the real matches show
     *     0.65   1.09   22.3%     <- chosen: the slope is within noise of 1, the rate near 20%
     *     0.60   1.18   21.1%
     *     0.55   1.29   19.5%
     *
     * 124 matches put the standard error of the slope at about 0.2, so this is a
     * rough estimate. REVISIT once the other fourteen seasons are loaded (about 1,900
     * matches): fit the shape on all of them and replace this number.
     */
    const val GOALS_SHAPE = 0.65

    /** Thirty minutes of extra time at the rate the World Cup measured. */
    val EXTRA_TIME_SHARE: Double =
        WcModel.EXTRA_TIME_SCORING_FACTOR * WcModel.EXTRA_TIME_MINUTES / 90.0
}

/**
 * How the thirty-two clubs of a season compare with each other.
 *
 * The club engine measures a defence against the league's average concession
 * rate. The European Cup's field is stronger than any league, so that constant
 * would hand every attack an easier time than it has; here the average is the
 * field's own.
 *
 * @property averageDefence mean goals-conceded rating over the season's clubs.
 */
data class EcField(val averageDefence: Double) {
    companion object {
        fun of(ratings: Collection<TeamRating>): EcField {
            require(ratings.isNotEmpty()) { "an empty field has no average" }
            return EcField(ratings.map { it.defence }.average())
        }
    }
}

/** One side as a match sees it. */
data class EcSide(val rating: TeamRating)

/** A ninety-minute scoreline and the expectations behind it. */
data class EcNinety(val homeGoals: Int, val awayGoals: Int, val homeExpected: Double, val awayExpected: Double)

/** How a two-legged tie was settled. */
enum class TieDecision { AGGREGATE, AWAY_GOALS, PENALTIES }

/**
 * The rules that turn a tie's goals into a winner. One function serves both a
 * real tie (replayed from its stored scores) and a simulated one, so the two can
 * never disagree about who went through.
 */
object EcTieRules {
    data class Decision(val winnerIsFirst: Boolean, val decidedBy: TieDecision)

    /**
     * @param firstGoals the first-named side's goals over both legs, extra time included
     * @param secondGoals likewise for the second
     * @param firstAway goals the first side scored away from home (in leg 2)
     * @param secondAway goals the second side scored away (in leg 1)
     * @param awayGoalsRule see [EcRules.awayGoalsApply]; when false it is ignored
     *   entirely, and a tie level on aggregate goes to penalties
     * @param firstWonShootout who won the shootout, when there was one
     */
    fun decide(
        firstGoals: Int,
        secondGoals: Int,
        firstAway: Int,
        secondAway: Int,
        awayGoalsRule: Boolean,
        firstWonShootout: Boolean?,
    ): Decision = when {
        firstGoals != secondGoals -> Decision(firstGoals > secondGoals, TieDecision.AGGREGATE)
        awayGoalsRule && firstAway != secondAway -> Decision(firstAway > secondAway, TieDecision.AWAY_GOALS)
        else -> Decision(
            requireNotNull(firstWonShootout) { "a tie level on every criterion needs a shootout winner" },
            TieDecision.PENALTIES,
        )
    }
}

/** A two-legged tie as played: leg 1 at the first side's ground, leg 2 at the second's. */
data class EcTieResult(
    val leg1: WcScore,
    val leg2: WcScore,
    val winnerIsFirst: Boolean,
    val decidedBy: TieDecision,
) {
    val firstAggregate: Int get() = leg1.homeGoals + leg2.awayGoals
    val secondAggregate: Int get() = leg1.awayGoals + leg2.homeGoals
}

/**
 * Plays European Cup matches: the club engine's expected goals, measured against
 * the season's own field, with two-legged ties and their extra time and shootouts.
 */
object EcMatchEngine {

    /**
     * Expected goals for [attacker] over ninety minutes against [defender].
     *
     * The club engine's figure, with the defence compared against the field's
     * average, scaled to the European Cup's scoring rate, and lifted for the
     * side playing at home. A neutral ground (the final) lifts nobody.
     */
    fun expectedGoals(attacker: EcSide, defender: EcSide, field: EcField, atHome: Boolean): Double {
        val raw = attacker.rating.attack * (defender.rating.defence / field.averageDefence)
        val home = if (atHome) EcModel.HOME_ADVANTAGE else 1.0
        return (raw.pow(EcModel.GOALS_SHAPE) * EcModel.GOALS_SCALE * home).coerceIn(SimModel.MIN_EXPECTED_GOALS, SimModel.MAX_EXPECTED_GOALS)
    }

    /** Ninety minutes. [neutral] is true only for the final. */
    fun playNinety(home: EcSide, away: EcSide, field: EcField, neutral: Boolean, random: Random): EcNinety {
        val lh = expectedGoals(home, away, field, atHome = !neutral)
        val la = expectedGoals(away, home, field, atHome = false)
        return EcNinety(MatchEngine.poisson(lh, random), MatchEngine.poisson(la, random), lh, la)
    }

    /** A group match: ninety minutes, and a draw is a result. */
    fun playGroup(home: EcSide, away: EcSide, field: EcField, random: Random): WcScore {
        val n = playNinety(home, away, field, neutral = false, random = random)
        return WcScore(n.homeGoals, n.awayGoals, homeExpected = n.homeExpected, awayExpected = n.awayExpected)
    }

    /** One knockout match to a finish, which only the final is. Extra time, then penalties. */
    fun playFinal(home: EcSide, away: EcSide, field: EcField, random: Random): WcScore {
        val n = playNinety(home, away, field, neutral = true, random = random)
        val ninety = WcScore(n.homeGoals, n.awayGoals, homeExpected = n.homeExpected, awayExpected = n.awayExpected)
        if (n.homeGoals != n.awayGoals) return ninety
        val (homeExtra, awayExtra) = extraTime(n, random)
        val afterExtra = ninety.copy(homeExtra = homeExtra, awayExtra = awayExtra, extraTime = true)
        if (homeExtra != awayExtra) return afterExtra
        return afterExtra.copy(shootout = WcMatchEngine.shootout(random))
    }

    /**
     * A two-legged tie. [first] hosts leg 1 and [second] leg 2.
     *
     * After leg 2's ninety minutes: a lead on aggregate settles it; level, the
     * away-goals rule (where [awayGoalsRule] applies) settles it if the away goals
     * differ; otherwise thirty minutes of extra time are played in leg 2, then the
     * same checks again, then a shootout. From the 2021/22 season the away-goals
     * step does not exist, and a tie level on aggregate always goes to extra time.
     */
    fun playTie(first: EcSide, second: EcSide, field: EcField, awayGoalsRule: Boolean, random: Random): EcTieResult {
        val l1 = playNinety(first, second, field, neutral = false, random = random)
        val l2 = playNinety(second, first, field, neutral = false, random = random)
        val leg1 = WcScore(l1.homeGoals, l1.awayGoals, homeExpected = l1.homeExpected, awayExpected = l1.awayExpected)
        var leg2 = WcScore(l2.homeGoals, l2.awayGoals, homeExpected = l2.homeExpected, awayExpected = l2.awayExpected)

        fun aggregates(s: WcScore) = (leg1.homeGoals + s.awayGoals) to (leg1.awayGoals + s.homeGoals)
        fun settled(s: WcScore): Boolean {
            val (a, b) = aggregates(s)
            if (a != b) return true
            // Away goals: the first side's are leg 2's away goals, the second side's leg 1's.
            return awayGoalsRule && s.awayGoals != leg1.awayGoals
        }

        if (!settled(leg2)) {
            val (homeExtra, awayExtra) = extraTime(l2, random)
            leg2 = leg2.copy(homeExtra = homeExtra, awayExtra = awayExtra, extraTime = true)
            if (!settled(leg2)) leg2 = leg2.copy(shootout = WcMatchEngine.shootout(random))
        }

        val (a, b) = aggregates(leg2)
        val decision = EcTieRules.decide(
            firstGoals = a, secondGoals = b,
            firstAway = leg2.awayGoals, secondAway = leg1.awayGoals,
            awayGoalsRule = awayGoalsRule,
            // The shootout is in leg 2, whose home side is the SECOND club.
            firstWonShootout = leg2.shootout?.let { !it.homeWon },
        )
        return EcTieResult(leg1, leg2, decision.winnerIsFirst, decision.decidedBy)
    }

    private fun extraTime(ninety: EcNinety, random: Random): Pair<Int, Int> = Pair(
        MatchEngine.poisson(ninety.homeExpected * EcModel.EXTRA_TIME_SHARE, random),
        MatchEngine.poisson(ninety.awayExpected * EcModel.EXTRA_TIME_SHARE, random),
    )
}
