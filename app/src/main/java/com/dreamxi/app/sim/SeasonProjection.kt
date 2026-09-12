package com.dreamxi.app.sim

import kotlin.random.Random

/** How a season is likely to go, before it is played. */
data class SeasonProjection(
    val runs: Int,
    /** How often the user finished in each position, indexed by position. */
    val positionCounts: Map<Int, Int>,
    val expectedPosition: Double,
    val expectedPoints: Double,
    val leagueSize: Int,
) {
    fun chanceOf(position: Int): Double = (positionCounts[position] ?: 0) / runs.toDouble()

    fun chanceOfTop(n: Int): Double =
        (1..n).sumOf { positionCounts[it] ?: 0 } / runs.toDouble()

    /** The bottom three, as every league in the data relegates three. */
    val relegationChance: Double
        get() = ((leagueSize - 2)..leagueSize).sumOf { positionCounts[it] ?: 0 } / runs.toDouble()

    val titleChance: Double get() = chanceOf(1)

    /**
     * A CONTIGUOUS band of finishing positions around the most likely one.
     *
     * Contiguous is the whole point. This used to take the most probable
     * positions and sort them, which produced readouts like 2nd, 5th, 6th, 7th
     * — and a reader can only conclude that third and fourth are impossible.
     * Sometimes they nearly are, which is worth showing; usually they were just
     * fifth and sixth most likely, which is not. Either way, silently omitting
     * a row in the middle of a range is the one thing the display must not do.
     *
     * The band grows outward from the mode, always taking the more likely of
     * the two neighbours, so it stays centred on where the season is actually
     * heading.
     */
    fun likelyBand(maxRows: Int = 5): List<Pair<Int, Double>> {
        if (positionCounts.isEmpty() || leagueSize <= 0) return emptyList()
        val mode = positionCounts.maxByOrNull { it.value }?.key ?: return emptyList()
        var lo = mode
        var hi = mode
        while (hi - lo + 1 < maxRows && (lo > 1 || hi < leagueSize)) {
            val above = if (lo > 1) chanceOf(lo - 1) else -1.0
            val below = if (hi < leagueSize) chanceOf(hi + 1) else -1.0
            if (above < 0 && below < 0) break
            if (above >= below) lo-- else hi++
        }
        return (lo..hi).map { it to chanceOf(it) }
    }

    /**
     * True when some position inside the band is all but unreachable.
     *
     * A real property of a counterfactual rather than a rounding artefact: the
     * other clubs' points are fixed history, so a place only exists to be taken
     * if there is a gap between two of them to land in. Two clubs level on
     * points — Chelsea and Manchester United both on 66 in 2019/20, Atlético
     * and Sevilla both on 70 in La Liga the same year — leave no gap at all,
     * and that place cannot be finished in whatever the XI does.
     */
    fun hasUnreachablePlace(band: List<Pair<Int, Double>>): Boolean =
        band.any { it.second < 0.005 }
}

/**
 * Runs the same XI through the season many times to find out what it is
 * actually worth, as opposed to what happened to it once.
 *
 * WHY THIS IS CHEAP ENOUGH TO DO ON A PHONE. In a counterfactual season only
 * the user's own 38 matches are simulated; the other 342 really happened. So
 * every opponent's record from matches NOT involving the user is identical in
 * every run and can be computed once, leaving each additional season at 38
 * Poisson draws and a sort of twenty rows. Measured on the naive path — which
 * rebuilt all 380 fixtures and generated scorers and bookings for each — 1,000
 * seasons took 1.2 seconds on a desktop JVM; this path does a fraction of that
 * work and skips match reports entirely, since a projection needs scorelines
 * and nothing else.
 *
 * It is also seeded off the run, so the projection a user is shown before kick
 * off is the same one they see again at the end.
 */
object SeasonProjector {

    const val DEFAULT_RUNS = 1000

    private class Record(var points: Int = 0, var goalsFor: Int = 0, var goalsAgainst: Int = 0) {
        val goalDifference get() = goalsFor - goalsAgainst
        fun add(gf: Int, ga: Int) {
            goalsFor += gf
            goalsAgainst += ga
            points += if (gf > ga) 3 else if (gf == ga) 1 else 0
        }
        fun copy() = Record(points, goalsFor, goalsAgainst)
    }

    fun project(
        real: List<RealFixture>,
        teams: List<SimTeam>,
        user: SimTeam,
        replacedTeamId: String,
        seed: Long,
        runs: Int = DEFAULT_RUNS,
    ): SeasonProjection? {
        val opponents = teams.filterNot { it.id == replacedTeamId }
        if (opponents.isEmpty()) return null
        val byId = opponents.associateBy { it.id }

        // Everything that does not involve the club being replaced is fixed
        // history and identical in every run, so it is accumulated once.
        val base = opponents.associate { it.id to Record() }
        for (f in real) {
            if (f.homeTeamId == replacedTeamId || f.awayTeamId == replacedTeamId) continue
            base[f.homeTeamId]?.add(f.homeGoals, f.awayGoals)
            base[f.awayTeamId]?.add(f.awayGoals, f.homeGoals)
        }

        // The fixtures the user inherits, reduced to what a simulation needs.
        val userFixtures = real
            .filter { it.homeTeamId == replacedTeamId || it.awayTeamId == replacedTeamId }
            .mapNotNull { f ->
                val userAtHome = f.homeTeamId == replacedTeamId
                val opponent = byId[if (userAtHome) f.awayTeamId else f.homeTeamId]
                    ?: return@mapNotNull null
                userAtHome to opponent
            }
        if (userFixtures.isEmpty()) return null

        val random = Random(mixSeed(seed) + 1)
        val counts = HashMap<Int, Int>()
        var pointsTotal = 0L
        var positionTotal = 0L

        repeat(runs) {
            val table = base.mapValues { it.value.copy() }
            val mine = Record()

            for ((userAtHome, opponent) in userFixtures) {
                val result = if (userAtHome) {
                    MatchEngine.play(user.rating, opponent.rating, random)
                } else {
                    MatchEngine.play(opponent.rating, user.rating, random)
                }
                val (myGoals, theirGoals) = if (userAtHome) {
                    result.homeGoals to result.awayGoals
                } else {
                    result.awayGoals to result.homeGoals
                }
                mine.add(myGoals, theirGoals)
                table[opponent.id]?.add(theirGoals, myGoals)
            }

            // Where that lands. Football's order: points, then goal difference,
            // then goals scored. Anything still level counts as ahead of the
            // user, which is the pessimistic reading and the honest one — a
            // coin-flip tiebreak would flatter the projection.
            var position = 1
            for ((id, record) in table) {
                if (id == user.id) continue
                val better = record.points > mine.points ||
                    (record.points == mine.points && record.goalDifference > mine.goalDifference) ||
                    (record.points == mine.points && record.goalDifference == mine.goalDifference &&
                        record.goalsFor >= mine.goalsFor)
                if (better) position++
            }

            counts[position] = (counts[position] ?: 0) + 1
            pointsTotal += mine.points
            positionTotal += position
        }

        return SeasonProjection(
            runs = runs,
            positionCounts = counts,
            expectedPosition = positionTotal.toDouble() / runs,
            expectedPoints = pointsTotal.toDouble() / runs,
            leagueSize = opponents.size + 1,
        )
    }
}
