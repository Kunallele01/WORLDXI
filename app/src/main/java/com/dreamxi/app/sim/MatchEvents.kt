package com.dreamxi.app.sim

import kotlin.random.Random

/** A goal, as it would appear on a scoresheet. */
data class GoalEvent(
    val minute: Int,
    val teamId: String,
    val scorer: String,
    val assist: String?,
)

/** A booking. */
data class CardEvent(
    val minute: Int,
    val teamId: String,
    val player: String,
    val isRed: Boolean,
)

/** Everything that happened in a match beyond the scoreline. */
data class MatchEvents(
    val goals: List<GoalEvent> = emptyList(),
    val cards: List<CardEvent> = emptyList(),
) {
    fun goalsFor(teamId: String) = goals.filter { it.teamId == teamId }
    fun cardsFor(teamId: String) = cards.filter { it.teamId == teamId }
    val hasAny: Boolean get() = goals.isNotEmpty() || cards.isNotEmpty()
}

/**
 * Turns a scoreline into a match report: who scored, who set it up, who was
 * booked.
 *
 * WHAT THIS IS AND IS NOT. Something else decides HOW MANY goals a side scores
 * — the engine for the user's own matches, real history for everyone else's.
 * This only decides WHO, and it never changes a scoreline by a single goal.
 *
 * Because it works from the scoreline rather than producing it, it runs just
 * as well on a real 2021/22 result as on a simulated one, which is what lets a
 * counterfactual season still carry a full league-wide top-scorer list.
 *
 * THE ONE UNMEASURED CHOICE is the minute. Goals are placed uniformly across
 * the 90, which is not quite how football works — real goals lean late — but
 * nothing in the loaded data records a minute, so a lean would be invention.
 * Uniform is the assumption that adds least.
 */
object MatchReporter {

    /**
     * @param homeAssists,awayAssists each club's remaining assist budget for the
     *   season, spent as goals are reported. Null keeps the old per-goal draw,
     *   which is what a one-off match with no season around it wants.
     */
    fun report(
        homeId: String,
        homeSquad: SquadProfile,
        awayId: String,
        awaySquad: SquadProfile,
        result: MatchResult,
        random: Random,
        homeAssists: MutableMap<String, Int>? = null,
        awayAssists: MutableMap<String, Int>? = null,
        homeGoalsLedger: MutableMap<String, Int>? = null,
        awayGoalsLedger: MutableMap<String, Int>? = null,
    ): MatchEvents {
        val goals = mutableListOf<GoalEvent>()
        goals += scorers(homeId, homeSquad, result.homeGoals, random, homeAssists, homeGoalsLedger)
        goals += scorers(awayId, awaySquad, result.awayGoals, random, awayAssists, awayGoalsLedger)

        val cards = mutableListOf<CardEvent>()
        cards += bookings(homeId, homeSquad, random)
        cards += bookings(awayId, awaySquad, random)

        return MatchEvents(
            goals = goals.sortedBy { it.minute },
            cards = cards.sortedBy { it.minute },
        )
    }

    /** Draws [count] scorers, and an assister for each unless it was unassisted. */
    private fun scorers(
        teamId: String,
        squad: SquadProfile,
        count: Int,
        random: Random,
        ledger: MutableMap<String, Int>? = null,
        goalLedger: MutableMap<String, Int>? = null,
    ): List<GoalEvent> {
        // No squad, no match report. Degrades to "no scorers listed" rather
        // than taking the season down: a weighted draw cannot pick from nobody.
        if (count <= 0 || squad.isEmpty) return emptyList()

        val scoringWeights = squad.players.map { it.goals }
        val assistWeights = squad.players.map { it.assists }

        return (0 until count).map {
            // Spent from the season's goal budget where there is one, so a real
            // club's scorers finish on what they really scored. A goal MUST have
            // a scorer, so an exhausted budget falls back to the weighted draw
            // rather than leaving the scoresheet blank — unlike an assist, where
            // "nobody" is a correct answer.
            val scorerIndex = goalLedger
                ?.let { led -> pickOrNull(squad.players.map { (led[it.name] ?: 0).toDouble() }, random) }
                ?.also { index ->
                    val name = squad.players[index].name
                    goalLedger[name] = (goalLedger[name] ?: 1) - 1
                }
                ?: pick(scoringWeights, random)
            val scorer = squad.players[scorerIndex]
            val minute = minuteFor(scorer, random)

            // Drawn from the squad minus the scorer, and minus anyone who could
            // not have been on the pitch yet: a player cannot assist his own
            // goal, and a substitute cannot assist one scored before he came on.
            val assist = if (ledger != null) {
                // SPENT FROM THE SEASON'S BUDGET, not drawn afresh. Whoever
                // still has assists left is eligible, weighted by how many —
                // so a man finishes the season on the number his record says,
                // and the league's top provider stops drifting past totals no
                // real season has ever reached. See SquadProfile.assistBudget.
                val remaining = squad.players.mapIndexed { i, p ->
                    if (i == scorerIndex) 0.0
                    else (ledger[p.name] ?: 0).toDouble() * onPitchAt(p, minute)
                }
                pickOrNull(remaining, random)?.let { index ->
                    val name = squad.players[index].name
                    ledger[name] = (ledger[name] ?: 1) - 1
                    name
                }
            } else if (random.nextDouble() < SimModel.ASSISTED_GOAL_RATE) {
                val masked = assistWeights.mapIndexed { i, w ->
                    if (i == scorerIndex) 0.0 else w * onPitchAt(squad.players[i], minute)
                }
                // No eligible provider means the goal was UNASSISTED, not that
                // somebody ineligible gets the credit. The uniform fallback used
                // for scorers is wrong here: a goal must have a scorer, but
                // roughly three in ten have no assist at all, so "nobody" is
                // always an available and correct answer. Without this the
                // masking above was defeated entirely — a substitute could still
                // be credited with assisting a goal scored before he came on.
                pickOrNull(masked, random)?.let { squad.players[it].name }
            } else {
                null
            }
            GoalEvent(minute, teamId, scorer.name, assist)
        }
    }

    /**
     * The minute a player's event happens in.
     *
     * If he started, anywhere in the ninety. If he came on, only after he came
     * on — which is the whole point: goal minutes used to be uniform across the
     * match whoever scored, so a bench forward appeared on the scoresheet in the
     * third minute, before he could have been on the pitch.
     *
     * Note this is NOT "substitutes score late". A squad player on a 44% share
     * still starts 64% of the matches he appears in, so most of his goals are
     * legitimately early; the engine simply has to decide which case it is.
     */
    private fun minuteFor(player: ScorerWeight, random: Random): Int {
        if (random.nextDouble() < player.startRate) return random.nextInt(1, 91)
        val entry = random.nextInt(SimModel.SUB_ENTRY_EARLIEST, SimModel.SUB_ENTRY_LATEST + 1)
        return random.nextInt(entry, 91)
    }

    /**
     * Rough chance a player is on the pitch at [minute], used to keep the rest
     * of a scoresheet consistent with the man who caused it.
     *
     * Before substitutions begin, only those who started can be involved; after
     * that, everyone in the squad might be.
     */
    private fun onPitchAt(player: ScorerWeight, minute: Int): Double =
        if (minute < SimModel.SUB_ENTRY_EARLIEST) player.startRate else 1.0

    /**
     * Bookings for one side.
     *
     * The COUNT comes from the club's real disciplinary record spread across
     * the season, drawn from a Poisson so a match can produce none or three.
     * WHO collects them is then weighted by which players actually get booked.
     *
     * Drawing an independent coin for every player, as this used to, made the
     * card count depend on how many players were in the list rather than on the
     * club — which was invisible while every side was an XI and would have
     * broken the moment real squads of twenty-odd arrived.
     *
     * A red suppresses that player's yellow: one man booked and sent off in the
     * same match describes a second yellow, which is not what the red card rate
     * measures.
     */
    private fun bookings(teamId: String, squad: SquadProfile, random: Random): List<CardEvent> {
        if (squad.isEmpty) return emptyList()
        val events = mutableListOf<CardEvent>()
        val sentOff = mutableSetOf<String>()

        val redWeights = squad.players.map { it.reds }
        repeat(MatchEngine.poisson(squad.redsPerMatch, random)) {
            val p = squad.players[pick(redWeights, random)]
            if (sentOff.add(p.name)) {
                events += CardEvent(minuteFor(p, random), teamId, p.name, isRed = true)
            }
        }

        val yellowWeights = squad.players.map { it.yellows }
        val booked = mutableSetOf<String>()
        repeat(MatchEngine.poisson(squad.yellowsPerMatch, random)) {
            val p = squad.players[pick(yellowWeights, random)]
            if (p.name !in sentOff && booked.add(p.name)) {
                events += CardEvent(minuteFor(p, random), teamId, p.name, isRed = false)
            }
        }
        return events
    }

    /**
     * Weighted draw over a squad.
     *
     * Falls back to a uniform pick when every weight is zero, which is
     * genuinely reachable — an XI of eleven full-backs has a median goal rate
     * of exactly zero. Someone has to have scored the goal that was just
     * awarded, so refusing to choose is not an option.
     */
    /** As [pick], but returns null rather than inventing an answer. */
    private fun pickOrNull(weights: List<Double>, random: Random): Int? {
        if (weights.isEmpty() || weights.sum() <= 0.0) return null
        return pick(weights, random)
    }

    private fun pick(weights: List<Double>, random: Random): Int {
        val total = weights.sum()
        if (total <= 0.0) return random.nextInt(weights.size)
        var target = random.nextDouble() * total
        for (i in weights.indices) {
            target -= weights[i]
            if (target <= 0.0) return i
        }
        return weights.lastIndex
    }
}
