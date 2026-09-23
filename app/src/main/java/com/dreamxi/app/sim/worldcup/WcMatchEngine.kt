package com.dreamxi.app.sim.worldcup

import com.dreamxi.app.sim.MatchEngine
import com.dreamxi.app.sim.SimModel
import com.dreamxi.app.sim.TeamRating
import kotlin.math.pow
import kotlin.random.Random

/** One side as a World Cup match sees it. */
data class WcSide(val rating: TeamRating, val isHost: Boolean = false)

/**
 * A penalty shootout.
 *
 * The WINNER is carried explicitly rather than read off the score, because a
 * winner is always known and a score is not guaranteed: the 2006-2018 fixture
 * source records who won a shootout but not by how much, and a shootout read
 * as "no score, so no winner" once turned Italy's 2006 final into a draw.
 *
 * A simulated shootout carries every kick in order, so a screen can play it
 * out. A real one carries its score but not its kicks, which are null rather
 * than invented.
 */
data class Shootout(
    val homeWon: Boolean,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val homeKicks: List<Boolean>? = null,
    val awayKicks: List<Boolean>? = null,
    val homeFirst: Boolean? = null,
) {
    init {
        if (homeScore != null && awayScore != null) {
            require((homeScore > awayScore) == homeWon) { "score $homeScore-$awayScore contradicts the winner" }
        }
    }
}

/**
 * A World Cup scoreline: ninety minutes, then whatever extra time added, then
 * any shootout. Kept apart because every screen reports them apart ("1-1,
 * 2-1 after extra time") and because a shootout is not a goal.
 */
data class WcScore(
    val homeRegular: Int,
    val awayRegular: Int,
    val homeExtra: Int = 0,
    val awayExtra: Int = 0,
    val extraTime: Boolean = false,
    val shootout: Shootout? = null,
    val homeExpected: Double = 0.0,
    val awayExpected: Double = 0.0,
) {
    val homeGoals: Int get() = homeRegular + homeExtra
    val awayGoals: Int get() = awayRegular + awayExtra

    /** True/false for a decided match, null for a drawn group match. */
    val homeWon: Boolean?
        get() = when {
            homeGoals != awayGoals -> homeGoals > awayGoals
            shootout != null -> shootout.homeWon
            else -> null
        }

    override fun toString(): String = buildString {
        append("$homeGoals-$awayGoals")
        if (extraTime) append(" aet")
        shootout?.let {
            if (it.homeScore != null && it.awayScore != null) append(" (${it.homeScore}-${it.awayScore} pens)")
            else append(if (it.homeWon) " (home on pens)" else " (away on pens)")
        }
    }
}

/**
 * Plays World Cup matches: the club engine's expected goals, mapped onto
 * international football, with extra time and penalties for knockout ties.
 * Every number comes from [WcModel].
 */
object WcMatchEngine {

    /**
     * Expected goals for [attacker] over ninety minutes against [defender].
     *
     * The club engine's figure (attack met by defence, relative to the league)
     * with no home advantage — World Cup matches are at neutral grounds —
     * mapped onto international scoring, then lifted for a host.
     */
    fun expectedGoals(attacker: WcSide, defender: WcSide): Double {
        val club = MatchEngine.expectedGoals(attacker.rating.attack, defender.rating.defence)
        val international = WcModel.INTERNATIONAL_SCALE * club.pow(WcModel.INTERNATIONAL_SHAPE)
        val host = if (attacker.isHost) WcModel.HOST_SCORING_FACTOR else 1.0
        return (international * host).coerceIn(SimModel.MIN_EXPECTED_GOALS, SimModel.MAX_EXPECTED_GOALS)
    }

    /** A group match: ninety minutes, and a draw is a result. */
    fun playGroup(home: WcSide, away: WcSide, random: Random): WcScore {
        val lh = expectedGoals(home, away)
        val la = expectedGoals(away, home)
        return WcScore(
            homeRegular = MatchEngine.poisson(lh, random),
            awayRegular = MatchEngine.poisson(la, random),
            homeExpected = lh,
            awayExpected = la,
        )
    }

    /**
     * A knockout tie, played to a finish: ninety minutes, then thirty of extra
     * time at the measured slower rate, then a shootout.
     */
    fun playKnockout(home: WcSide, away: WcSide, random: Random): WcScore {
        val ninety = playGroup(home, away, random)
        if (ninety.homeRegular != ninety.awayRegular) return ninety

        val share = WcModel.EXTRA_TIME_SCORING_FACTOR * WcModel.EXTRA_TIME_MINUTES / 90.0
        val homeExtra = MatchEngine.poisson(ninety.homeExpected * share, random)
        val awayExtra = MatchEngine.poisson(ninety.awayExpected * share, random)
        val afterExtra = ninety.copy(homeExtra = homeExtra, awayExtra = awayExtra, extraTime = true)
        if (homeExtra != awayExtra) return afterExtra
        return afterExtra.copy(shootout = shootout(random))
    }

    /**
     * Five kicks each, stopping as soon as one side cannot be caught, then
     * sudden death a pair at a time. Both sides convert at the same measured
     * rate and the first kicker is a coin toss, so the winner is exactly a coin
     * flip — which is what the real shootouts say.
     */
    fun shootout(random: Random): Shootout {
        val homeFirst = random.nextBoolean()
        val first = mutableListOf<Boolean>()
        val second = mutableListOf<Boolean>()
        fun kick() = random.nextDouble() < WcModel.SHOOTOUT_CONVERSION
        fun goals(kicks: List<Boolean>) = kicks.count { it }
        val each = WcModel.SHOOTOUT_KICKS_EACH

        while (first.size < each) {
            first += kick()
            if (decided(goals(first), first.size, goals(second), second.size, each)) break
            second += kick()
            if (decided(goals(first), first.size, goals(second), second.size, each)) break
        }
        while (goals(first) == goals(second)) {
            first += kick()
            second += kick()
        }
        val (home, away) = if (homeFirst) first to second else second to first
        return Shootout(
            homeWon = goals(home) > goals(away),
            homeScore = goals(home),
            awayScore = goals(away),
            homeKicks = home,
            awayKicks = away,
            homeFirst = homeFirst,
        )
    }

    /** Whether either side has more goals than the other can still reach. */
    private fun decided(aGoals: Int, aTaken: Int, bGoals: Int, bTaken: Int, each: Int): Boolean =
        aGoals > bGoals + (each - bTaken) || bGoals > aGoals + (each - aTaken)
}
