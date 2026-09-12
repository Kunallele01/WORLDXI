package com.dreamxi.app.sim

/** One player's claim on his side's goals, assists and cards. */
data class ScorerWeight(
    val name: String,
    val goals: Double,
    val assists: Double,
    val yellows: Double,
    val reds: Double,
    /**
     * How often he is on the pitch from kick-off. Decides whether an event can
     * be credited to him early in a match — see SimModel.startRate.
     */
    val startRate: Double = 1.0,
)

/**
 * Who gets credited when a side scores or gets booked.
 *
 * Deliberately separate from the XI that decides how GOOD a side is. The
 * eleven on the pitch determine how many goals a club scores; this determines
 * which name appears beside them, and the two are not the same question.
 */
data class SquadProfile(
    val players: List<ScorerWeight>,
    val yellowsPerMatch: Double,
    val redsPerMatch: Double,
    /**
     * How many assists each man has to give over the whole season.
     *
     * WHY A BUDGET RATHER THAN A DRAW PER GOAL. Assists used to be drawn
     * independently for each of a club's ~100 goals, and independent draws are
     * far wider than football: measured over the 30 loaded league-seasons, the
     * top provider averages 15.3 with a standard deviation of 2.5 and has NEVER
     * exceeded 21, while the per-goal draw produced a spread of about 4 and put
     * a quarter to a half of seasons above 21 — which is how a simulated Özil
     * reached 26 in a season he really finished on 16.
     *
     * A playmaker's tally is anchored by his role — he takes the corners, he
     * plays the final ball — not resampled every time anyone scores. So the
     * season's assists are allotted up front and dealt out as goals arrive.
     *
     * For a real club these are its players' ACTUAL assists, which in a
     * counterfactual is simply the truth: 342 of the 380 matches really
     * happened. For the user's XI, whose season never happened, they are his
     * per-match rates over a full season.
     */
    val assistBudget: Map<String, Int> = emptyMap(),
    /**
     * How many goals each man has to score over the whole season.
     *
     * The same argument as [assistBudget], and it applies just as hard. Drawing
     * a scorer afresh for each of a club's goals reproduces the right MEAN and
     * a wildly wrong spread: re-running Liverpool's real 2017/18, Salah came
     * out anywhere between 21 and 47 around his real 32, and Haaland's real 36
     * ranged from 23 to 53. A user reported Salah finishing a 2017/18 run on
     * 42 — beyond any Premier League season ever played.
     *
     * This was missed the first time round because the simulated spread was
     * compared against how much top scorers vary ACROSS seasons and clubs
     * (sd 7.8), which is a different quantity. Re-running one real club-season,
     * the answer is not a distribution at all: those goals were really scored.
     */
    val goalBudget: Map<String, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = players.isEmpty()

    /**
     * A fresh, mutable budget for one season's bookkeeping.
     *
     * @param expectedGoals what this side is expected to score over the season,
     *   or null to use the budget as it stands.
     *
     * WHY THE GOALS MATTER. A real club's budget is its players' actual assists
     * and needs no scaling: those goals were really scored and really assisted.
     * The user's XI is different — its budget comes from per-match rates over 38
     * matches, while the match engine decides the goals separately, and the two
     * disagree. Measured: a fixture XI budgeted 25 assists while its clubs
     * scored 29-51 goals, so 500 credits had to cover 854 goals and the league's
     * assisted share collapsed to 58.5% against a real 70.4% — every goal after
     * a club's credits ran out went down as unassisted.
     *
     * So an estimated budget is rescaled to the goals actually expected, and
     * shared out by LARGEST REMAINDER rather than rounding each player
     * separately: rounding individually both loses the total and silently bars
     * anyone whose rate rounds to zero — two centre-backs and a keeper could
     * never record an assist all season.
     */
    fun newAssistLedger(expectedGoals: Double? = null): MutableMap<String, Int> =
        ledgerFor(assistBudget, expectedGoals?.let { it * SimModel.ASSISTED_GOAL_RATE })

    /**
     * A fresh, mutable goal budget for one season.
     *
     * @param expectedGoals what this side is expected to score, or null to use
     *   the budget as it stands — which is what a real club wants, because its
     *   goals are a record rather than a forecast.
     */
    fun newGoalLedger(expectedGoals: Double? = null): MutableMap<String, Int> =
        ledgerFor(goalBudget, expectedGoals)

    private fun ledgerFor(budget: Map<String, Int>, target: Double?): MutableMap<String, Int> {
        val current = budget.values.sum()
        if (target == null || current <= 0) return budget.toMutableMap()

        val scale = target / current
        val scaled = budget.mapValues { (_, v) -> v * scale }
        val floors = scaled.mapValues { (_, v) -> v.toInt() }.toMutableMap()
        // Largest remainder: hand the leftover credits to whoever was rounded
        // down hardest, so the total is exactly the target.
        var left = Math.round(target).toInt() - floors.values.sum()
        scaled.entries
            .sortedByDescending { (name, v) -> v - (floors[name] ?: 0) }
            .forEach { (name, _) ->
                if (left > 0) {
                    floors[name] = (floors[name] ?: 0) + 1
                    left--
                }
            }
        return floors
    }

    companion object {
        val EMPTY = SquadProfile(emptyList(), 0.0, 0.0)

        /**
         * A real club's squad, from what its players actually did that season.
         *
         * WHY SEASON TOTALS RATHER THAN PER-90 RATES, which is the bug this
         * function exists to fix. Attribution used to weight players by goals
         * per 90 and then hand every one of them a full 38-match season. Two
         * things went wrong at once, and they multiplied:
         *
         *   - A top-eleven player really plays 69.6% of a season. Giving him
         *     every minute turned Alexander Isak's 21 real goals into 32.
         *   - A real top eleven scores only 68.9% of its club's goals; the rest
         *     come off the bench. Crediting all of them to eleven men turned
         *     Rodrigo Muniz's 9 into 30.
         *
         * A season total already contains the games a player missed and the
         * minutes he did not play, so weighting by it reproduces the real
         * distribution: a club that scored 85 goals shares them out the way it
         * really shared them. And because the whole squad is here rather than
         * the starting eleven, the bench gets its 31% back.
         *
         * [scale] rebalances when the simulated club scores more or fewer goals
         * than the real one did — the shares stay the same, the totals move.
         */
        fun fromSeasonTotals(players: List<ScorerWeight>, matches: Int = 38): SquadProfile {
            if (players.isEmpty()) return EMPTY
            return SquadProfile(
                players = players,
                yellowsPerMatch = players.sumOf { it.yellows } / matches,
                redsPerMatch = players.sumOf { it.reds } / matches,
                // Already season totals, so the budgets ARE the real record.
                assistBudget = players.associate { it.name to it.assists.toInt() },
                goalBudget = players.associate { it.name to it.goals.toInt() },
            )
        }

        /**
         * The user's drafted eleven.
         *
         * Rates per 90 rather than season totals, and that asymmetry with
         * [fromSeasonTotals] is the point rather than an inconsistency. A real
         * club's forward misses games; a drafted XI has no bench, no rotation
         * and no injuries, so its eleven genuinely do play every minute of
         * every match. Haaland scoring 36 for a side that never rests him is
         * correct. Haaland scoring 36 for Manchester City, who rested him, was
         * the bug.
         */
        fun fromXi(xi: List<SimSlot>): SquadProfile = fromSquad(xi, emptyList())

        /**
         * The user's side, starters and substitutes, with minutes shared out.
         *
         * THE ROTATION IS WHY THIS EXISTS. Eleven men playing every minute of
         * all 38 took every goal their club scored, so a drafted forward
         * out-scored his real self by about half. Real squads give the top
         * eleven 69.6% of the minutes and the rest to a bench that takes 27.7%
         * of the goals, and reproducing that puts individual totals back where
         * real ones sit.
         *
         * Minutes are graded rather than flat. Starters get their POSITION's
         * measured share — keepers are barely rotated, forwards most — and the
         * bench follows the real rank-12-to-19 curve, which runs 44% of a season
         * down to 21%. Flat shares gave eight substitutes eight near-identical
         * tallies, and a squad list never looks like that.
         *
         * Rotation deliberately affects WHO SCORES and nothing else. Team
         * strength is still the starting XI, because a bench that quietly made
         * the side worse would be a difficulty change nobody asked for.
         */
        fun fromSquad(xi: List<SimSlot>, bench: List<SimSlot>): SquadProfile {
            if (xi.isEmpty()) return EMPTY

            // Best substitutes first, so the graded curve hands the most
            // minutes to the man most likely to be called on. Quality is only
            // a moderate predictor of minutes inside a starting XI (r = +0.36),
            // but for a bench it is the only ordering available and it beats
            // the arbitrary order they happened to be picked in.
            val rankedBench = bench.sortedByDescending { it.effectiveRating }
            val minutes = SimModel.squadMinuteShares(xi.map { it.role }, rankedBench.size)

            fun weigh(slot: SimSlot, share: Double) = ScorerWeight(
                startRate = SimModel.startRate(share),
                name = slot.player.name,
                goals = TeamStrength.eventWeight(
                    slot, slot.player.goals90, SimModel.SLOT_GOALS90,
                ) * share,
                assists = TeamStrength.eventWeight(
                    slot, slot.player.assists90, SimModel.SLOT_ASSISTS90,
                ) * share,
                // Cards are already per-match rates carried on the player, and
                // they do not scale linearly with minutes, so the share is
                // applied through the same exponent the rest of the app uses.
                //
                // NO POSITIONAL FALLBACK HERE ANY MORE. Substituting the
                // position's average for a player with no cards double counted
                // everyone who had them — the average is made OF those players
                // — and shipped a drafted side 7.1 red cards a season against a
                // real 3.4. The average is now blended into the player's own
                // rate where the raw counts still exist, in
                // SimModel.cardsPerMatch, which uses both without counting
                // either twice.
                yellows = slot.player.yellow90 *
                    Math.pow(share, SimModel.CARD_MINUTES_EXPONENT),
                reds = slot.player.red90 *
                    Math.pow(share, SimModel.CARD_MINUTES_EXPONENT),
            )

            val players = xi.mapIndexed { index, slot ->
                // With no bench there is no rotation to model, so the eleven
                // play everything — which is what a squad with nobody to bring
                // on would actually do.
                val share = if (bench.isEmpty()) 1.0 else minutes.starters.getOrElse(index) { 1.0 }
                weigh(slot, share)
            } + rankedBench.mapIndexed { index, slot ->
                weigh(slot, minutes.bench.getOrElse(index) { 0.0 })
            }

            return SquadProfile(
                players = players,
                yellowsPerMatch = players.sumOf { it.yellows },
                redsPerMatch = players.sumOf { it.reds },
                // Per-match rates here, so a season's worth is the rate times
                // the matches. This side of the asymmetry is the invented
                // season: there is no real record to deal out.
                assistBudget = players.associate {
                    it.name to Math.round(it.assists * SimModel.MATCHES_PER_SEASON).toInt()
                },
                goalBudget = players.associate {
                    it.name to Math.round(it.goals * SimModel.MATCHES_PER_SEASON).toInt()
                },
            )
        }
    }
}
