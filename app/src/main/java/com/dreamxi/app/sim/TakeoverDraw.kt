package com.dreamxi.app.sim

import kotlin.random.Random

/**
 * How many draws are made between the relegated clubs before one is settled on.
 *
 * The outcome is a one-in-three chance however many draws are made — see
 * [TakeoverDraw] for why that stays exactly true. The number is large because
 * the draw is something the user WATCHES: three names tumbling, a tally
 * climbing, a lead changing hands several times before it settles. One
 * instant pick would be the same result with none of that.
 */
const val TAKEOVER_DRAWS = 300

/** One draw in the sequence: which club came out, and the tally after it. */
data class DrawTick(
    val index: Int,
    val drawnTeamId: String,
    val tallies: Map<String, Int>,
) {
    /** Who is ahead at this point. Empty ids share the lead. */
    val leaders: List<String>
        get() {
            val best = tallies.values.maxOrNull() ?: return emptyList()
            return tallies.filterValues { it == best }.keys.sorted()
        }
}

/**
 * The finished draw.
 *
 * @param replacedTeamId the club whose place the user takes.
 * @param ticks every draw in order, so the screen can play it out.
 * @param suddenDeathFrom the tick index at which the main draws ended level
 *   and extra draws began, or null if it was settled inside the main run.
 */
data class TakeoverResult(
    val replacedTeamId: String,
    val finalTallies: Map<String, Int>,
    val ticks: List<DrawTick>,
    val suddenDeathFrom: Int?,
    val seed: Long,
) {
    /** How many times each club came out, highest first. */
    val standings: List<Pair<String, Int>>
        get() = finalTallies.toList().sortedByDescending { it.second }
    val leadChanges: Int
        get() = ticks.map { it.leaders }.zipWithNext().count { (a, b) -> a != b }
}

/**
 * Picks which of a season's real relegated clubs the user's XI replaces.
 *
 * WHY IT IS A LONG DRAW AND NOT ONE PICK. The club must be chosen at random —
 * each of the three genuinely equally likely — but a single call to a random
 * number generator gives the user nothing to look at and nothing to believe
 * in. So the three are drawn against each other hundreds of times and the one
 * drawn most often is settled on.
 *
 * WHY THAT IS STILL EXACTLY ONE IN THREE. Every draw is uniform over the
 * clubs, and nothing about the procedure distinguishes them: relabel the three
 * clubs and the distribution of tallies is unchanged. So each is equally
 * likely to end up with the highest count, whatever the number of draws.
 *
 * The one place that symmetry could be broken is a tie for the lead, and it is
 * broken by exactly the trap this is designed to avoid: resolving a tie by
 * list order, or by which club was drawn first, would quietly hand the place
 * to whoever the query happened to return first. Ties are instead settled by
 * drawing again among only the tied clubs until one leads alone — which is
 * itself symmetric, so the odds survive. A test asserts all three land near a
 * third over 6,000 draws, and a second one asserts that passing the clubs in
 * reversed order changes nothing.
 *
 * The seed is scrambled before use — see [mixSeed]. Without that the draw was
 * measurably skewed, because run ids are sequential and this generator
 * correlates across neighbouring seeds.
 *
 * The whole thing runs off one seed, so a run replays identically.
 */
object TakeoverDraw {

    fun draw(candidateTeamIds: List<String>, seed: Long, draws: Int = TAKEOVER_DRAWS): TakeoverResult {
        require(candidateTeamIds.isNotEmpty()) { "nobody to draw between" }
        require(candidateTeamIds.toSet().size == candidateTeamIds.size) { "duplicate clubs in the draw" }

        // Scrambled, because seeds here are sequential run ids and feeding
        // those in raw measurably skewed the draw. See mixSeed.
        val random = Random(mixSeed(seed))
        val tally = candidateTeamIds.associateWith { 0 }.toMutableMap()
        val ticks = mutableListOf<DrawTick>()

        // A single candidate needs no draw, but still produces a tick so the
        // screen has something to show rather than a blank.
        if (candidateTeamIds.size == 1) {
            tally[candidateTeamIds.first()] = 1
            ticks += DrawTick(0, candidateTeamIds.first(), tally.toMap())
            return TakeoverResult(candidateTeamIds.first(), tally.toMap(), ticks, null, seed)
        }

        repeat(draws) { i ->
            val drawn = candidateTeamIds[random.nextInt(candidateTeamIds.size)]
            tally[drawn] = tally.getValue(drawn) + 1
            ticks += DrawTick(i, drawn, tally.toMap())
        }

        // Sudden death: keep drawing between only the tied leaders. Uniform
        // over a symmetric set, so it cannot favour anyone.
        var suddenDeathFrom: Int? = null
        var leaders = leadersOf(tally)
        while (leaders.size > 1) {
            if (suddenDeathFrom == null) suddenDeathFrom = ticks.size
            val drawn = leaders[random.nextInt(leaders.size)]
            tally[drawn] = tally.getValue(drawn) + 1
            ticks += DrawTick(ticks.size, drawn, tally.toMap())
            leaders = leadersOf(tally)
        }

        return TakeoverResult(leaders.first(), tally.toMap(), ticks, suddenDeathFrom, seed)
    }

    private fun leadersOf(tally: Map<String, Int>): List<String> {
        val best = tally.values.max()
        return tally.filterValues { it == best }.keys.toList()
    }
}
