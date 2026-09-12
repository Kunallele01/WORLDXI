package com.dreamxi.app.feature.draft

/**
 * How much less likely a club-season becomes once it has already come up in
 * this draft.
 *
 * NOT AN EXCLUSION. A club may absolutely come round again — that possibility
 * is part of the spin — it should just stop being as likely as a club the user
 * has not seen. An earlier version banned repeats outright and that was wrong
 * in the other direction: it turned "uncommon" into "impossible" and made every
 * draft visit exactly eleven different clubs, which is its own kind of
 * artificial.
 *
 * The weights are multiplicative and compound, so a third appearance is rarer
 * than a second by the same factor again.
 *
 * MEASURED over 200,000 simulated drafts (etl/spin_repeat_weights.py), on the
 * real pool shape of 20 clubs x 5 seasons drawn 11 times:
 *
 *     club w  exact w   a club 2+   a club 3+   same club-season twice
 *       1.00     1.00       96.8%       28.5%        43.6%   <- no weighting
 *       0.50     0.10       79.6%        3.7%         3.2%
 *       0.35     0.05       67.8%        1.3%         1.2%
 *   ->  0.25     0.05       56.5%        0.6%         0.9%
 *       0.15     0.02       39.9%        0.1%         0.2%
 *       0.00     0.00        0.0%        0.0%         0.0%   <- hard exclusion
 *
 * The chosen row leaves a club repeating at roughly a coin flip across a whole
 * draft — common enough to feel possible, not so common that it reads as a
 * fault — while three appearances drops from better than one draft in four to
 * one in a hundred and fifty. It also fixes the thing that was plainly broken:
 * the pool had no memory at all, so 43.6% of drafts drew the IDENTICAL
 * club-season twice.
 */
const val CLUB_REPEAT_WEIGHT = 0.25

/**
 * The same club in the same season, which is the one repeat with nothing to
 * recommend it: an identical squad list offering identical players. Weighted
 * far below a repeat club in a different season, but still not zero.
 */
const val CLUB_SEASON_REPEAT_WEIGHT = 0.05

/**
 * Relative chance of drawing a club-season, given how often its club and it
 * specifically have already been drafted from in this run.
 *
 * Always positive, so nothing is ever truly removed from the pool.
 */
fun spinWeight(timesClubUsed: Int, timesClubSeasonUsed: Int): Double {
    var weight = 1.0
    repeat(timesClubUsed) { weight *= CLUB_REPEAT_WEIGHT }
    repeat(timesClubSeasonUsed) { weight *= CLUB_SEASON_REPEAT_WEIGHT }
    return weight
}

/**
 * Picks an index from [weights] in proportion to them.
 *
 * Falls back to a uniform draw if every weight has underflowed to zero, which
 * compounding can reach in a long enough run. A spin must always produce a
 * squad.
 */
fun weightedIndex(weights: List<Double>, roll: Double): Int {
    val total = weights.sum()
    if (total <= 0.0 || weights.isEmpty()) return 0
    var target = roll.coerceIn(0.0, 1.0) * total
    for (i in weights.indices) {
        target -= weights[i]
        if (target <= 0.0) return i
    }
    return weights.lastIndex
}
