package com.dreamxi.app.sim

/**
 * Every number the match engine uses, in one file, each one measured.
 *
 * THE RULE FOR THIS FILE: nothing here is a coefficient someone liked the look
 * of. All of it is fitted against the 200 real club-seasons in the database,
 * and the fit quality is recorded beside it so a future change can be judged
 * rather than argued about. If a value cannot be measured, it does not belong
 * here — it belongs in a comment explaining why the engine does without it.
 *
 * The whole model is deliberately in one place so that retuning is editing
 * constants, not rewriting logic. See etl/sim_calibration.py, sim_fit.py and
 * sim_position_shares.py for the scripts that produce these.
 *
 * VALIDATION AS A WHOLE (etl/sim_fit.py and etl/sim_defence_fit.py, now 318
 * club-seasons with complete data, up from 156 when the 2014/15-2018/19
 * seasons were loaded):
 *     predicted vs actual league points   r = +0.895
 *     RMSE 7.9 points across a 38-game season
 *     mean predicted 53.9 vs actual 53.9 — unbiased
 * The defence equation changed shape (see DEFENCE_LOG_SLOPE) without moving
 * these numbers at all: the straight line it replaced scores r = +0.896 and
 * the same 7.9 RMSE on the same rows. That is the point rather than a
 * disappointment — the two shapes are indistinguishable across the range where
 * real clubs live, and differ only out past it, where one of them stopped
 * describing football.
 * Two of the six worst misses are Everton and Nottingham Forest in 2023/24,
 * both of whom served points deductions that season. No model built from
 * player statistics can see a disciplinary charge, so the true error is
 * smaller than the headline number suggests.
 *
 * WHAT THE ENGINE DELIBERATELY DOES NOT USE, and why:
 *  - Tackles, interceptions, clearances and blocks as a measure of defensive
 *    QUALITY. Verified across all ten loaded seasons: centre-backs at the
 *    best defences average FEWER tackles and interceptions per 90 than those
 *    at the worst, because good defenders position rather than lunge. The
 *    signal is real but points the wrong way, so defence is priced on rating.
 *  - Any column that does not exist for all five seasons. Clearances, aerials,
 *    dribbles, pass completion and post-shot xG stop at 2021/22, and a player
 *    from 2023/24 must not lose because his data is thinner rather than
 *    because he was worse.
 */
object SimModel {

    // ---------------------------------------------------------------- attack
    /**
     * Goals scored per match, from the summed non-penalty expected goals of
     * the eleven players on the pitch.
     *
     *     goals_for/match = 0.8828 * sum(npxG per 90) + 0.2095
     *     r = +0.864, RMSE 0.223/match (8.5 goals/season), n=156
     *
     * That attack is ADDITIVE over players is the single most important
     * finding behind this engine, and it was checked rather than assumed: the
     * mean summed npxG of a real XI is 1.33 against a real average of 1.38
     * goals per match, so the relationship is close to one-to-one.
     */
    const val ATTACK_SLOPE = 0.8828
    const val ATTACK_INTERCEPT = 0.2095

    /**
     * The highest summed npxG any real XI in the data reaches: Manchester City
     * 2019/20, who scored 102 goals. The fit stays linear all the way up to it
     * — refitting on the 21 club-seasons above 1.8 gives the same 0.883 slope —
     * so the relationship is trusted across the whole observed range.
     *
     * Above it there is NO DATA AT ALL, and a draft can easily go there: eleven
     * elite strikers sum to about 3.97, which a linear extrapolation turns into
     * 3.7 goals a match, comfortably beyond anything in the history of either
     * league. Football has a physical reason it cannot hold — a side gets
     * twelve or thirteen shots a match whoever is playing, and eleven strikers
     * cannot all take them.
     *
     * So beyond this point the model saturates rather than extrapolating. It is
     * the honest shape for "we know the answer up to here and not past it".
     */
    const val ATTACK_LINEAR_MAX = 3.00

    /**
     * Where the saturation curve tends. A JUDGEMENT, not a fit — there is no
     * data out here by definition, which is why it sits beside
     * POSITION_TRANSFER_RANGE as one of only two chosen numbers in this file.
     *
     * Set 10% above the best real XI ever recorded here, so a freakish draft
     * can beat Manchester City's best attack but not by a fantasy margin.
     */
    const val ATTACK_SATURATION_ASYMPTOTE = 3.30

    // --------------------------------------------------------------- defence
    /**
     * Goals conceded per match, from a defensively-weighted mean of the XI's
     * ratings. MULTIPLICATIVE, not additive:
     *
     *     goals_against/match = exp(-0.07293 * defensiveRating + 5.9884)
     *     r = -0.823, RMSE 0.2059/match (7.8 goals/season), n=400
     *
     * WHY THE LOG LINK, replacing a straight line. The line was fitted on the
     * same data and it is measurably worse — every way of scoring it:
     *
     *     over all 400        linear RMSE 0.2105   log 0.2059
     *     over the strongest  linear RMSE 0.2107   log 0.1950
     *     bias on the strong  linear -0.0439       log -0.0040
     *
     * That bias line is the important one. A straight line UNDER-PREDICTS what
     * good defences concede, by 0.044 a match — a season and a half of goals —
     * and it does so systematically, because the real relationship bends and a
     * line cannot. Binned actuals show the bend plainly: the top tenth of the
     * data concedes 31.4 a season where the line says 29.2 and this says 30.9.
     *
     * AND IT DOES NOT RUN OFF A CLIFF. A line through goals conceded has to
     * reach zero and then go negative, and it got there embarrassingly early:
     * a Free Mode magic XI rates 88.9, the line gave it 15.6 goals a season and
     * about 28 clean sheets, and anything above 94.5 conceded a negative number
     * of goals. Nothing was capping it, because nothing here was ever supposed
     * to need capping.
     *
     * Multiplying instead of subtracting fixes that by construction rather than
     * by clamping. Each rating point removes a PERCENTAGE of the goals rather
     * than a fixed number, so the curve approaches zero without reaching it and
     * every rating buys less than the one before — which is both the honest
     * shape for a count that cannot go negative and, per the binned actuals,
     * the shape the real data has. There is no chosen constant anywhere in it
     * and no arbitrary floor: an XI of eleven 99-rated players behind a keeper
     * saving 90% concedes 8.9 goals a season, and that number is the model's
     * own answer rather than a limit imposed on it.
     */
    const val DEFENCE_LOG_SLOPE = -0.07293
    const val DEFENCE_LOG_INTERCEPT = 5.9884

    /**
     * How much defending each position is actually asked to do.
     *
     * Used to weight the mean rating, so that a midfield of forwards is
     * correctly punished rather than averaged away. A flat mean over the XI
     * fits worse (r = -0.79 against -0.83), because a striker's rating says
     * almost nothing about whether his team concedes.
     */
    val DEFENSIVE_WEIGHT = mapOf(
        "GK" to 1.00, "CB" to 1.00, "FB" to 0.85, "DM" to 0.70,
        "CM" to 0.45, "CAM" to 0.20, "Winger" to 0.20, "ST" to 0.10,
    )

    /**
     * What the goalkeeper is worth BEYOND his rating, as a MULTIPLIER on the
     * goals the outfield ten concede.
     *
     *     log_residual_goals_against = -0.010781 * save_pct + 0.745
     *     r = -0.354 against the residual after the rating fit, n=396
     *
     * Refitted on the log scale to match [DEFENCE_LOG_SLOPE]: a keeper on 80%
     * saves multiplies goals conceded by 0.889 against an average one, and
     * adding him drops RMSE from 0.2063 to 0.1934 a match.
     *
     * Measured on the residual on purpose. Raw save percentage correlates with
     * goals conceded at -0.545, but much of that is simply that good teams
     * have good keepers. Fitting the leftover isolates the part that is his.
     *
     * Applied relative to the league average, which is why the fitted intercept
     * does not appear here — it cancels. (It is +0.745 against a mean save
     * percentage of 69.04, so the neutral keeper's effect is exp(0.0007), i.e.
     * nothing, which is the check that the residual really was centred.)
     */
    const val KEEPER_SAVE_LOG_SLOPE = -0.010781

    /** League-average save percentage, so an unknown keeper is priced neutrally. */
    const val LEAGUE_AVERAGE_SAVE_PCT = 69.04

    // ------------------------------------------------------------- positions
    /**
     * Median non-penalty expected goals per 90 by position, over players with
     * 900+ minutes.
     *
     * THIS IS WHAT STOPS ELEVEN STRIKERS SCORING FIVE A GAME. A position is a
     * share of the team's chances, not a label: a striker takes 39% of a
     * team's npxG because he stands where the chances occur. Put him at
     * centre-back and those chances simply do not arrive.
     *
     * Share of a team's npxG by position:
     *     ST 39.2%  Winger 21.4%  CAM 17.9%  CM 8.1%
     *     CB 5.2%   DM 4.4%       FB 3.7%
     */
    val SLOT_NPXG90 = mapOf(
        "GK" to 0.000, "CB" to 0.047, "FB" to 0.034, "DM" to 0.040,
        "CM" to 0.073, "CAM" to 0.161, "Winger" to 0.193, "ST" to 0.353,
    )

    // ------------------------------------------------- the match report
    /**
     * Median goals per 90 by position (etl/sim_events_calibration.py, the same
     * 4,117 regulars).
     *
     * Used to decide WHO scores once the engine has decided HOW MANY. It is a
     * separate table from [SLOT_NPXG90] on purpose: npxG is non-penalty by
     * definition, and a top scorer's total that silently dropped his penalties
     * would look wrong to anyone who follows football.
     *
     * A full-back's median is exactly zero — over half of them go a season
     * without scoring — so attribution falls back on the rating adjustment for
     * players whose position essentially never scores, rather than making it
     * impossible.
     */
    val SLOT_GOALS90 = mapOf(
        "GK" to 0.0000, "CB" to 0.0311, "FB" to 0.0000, "DM" to 0.0306,
        "CM" to 0.0707, "CAM" to 0.1640, "Winger" to 0.1745, "ST" to 0.3214,
    )

    /** Median assists per 90 by position, same source. */
    val SLOT_ASSISTS90 = mapOf(
        "GK" to 0.0000, "CB" to 0.0000, "FB" to 0.0629, "DM" to 0.0333,
        "CM" to 0.0791, "CAM" to 0.1489, "Winger" to 0.1425, "ST" to 0.1019,
    )

    /**
     * Share of goals that carry a recorded assist: 6,989 assists against 9,932
     * goals. The rest are unassisted — solo runs, rebounds, penalties, and
     * anything the source did not credit.
     */
    const val ASSISTED_GOAL_RATE = 0.704

    /**
     * Not every goal a club scores comes from its first eleven: own goals and
     * squad players account for the gap, and summing the players' goals gives
     * 97.0% of the club's real total.
     *
     * The engine has no bench, so every simulated goal is attributed to one of
     * the eleven. That overstates an XI's individual tallies by about 3%,
     * which is recorded here rather than hidden — it is the honest cost of
     * simulating an XI instead of a squad.
     */
    const val XI_SHARE_OF_CLUB_GOALS = 0.970

    /**
     * MEAN yellow and red cards per 90 by position, not median.
     *
     * The median is the right statistic almost everywhere else in this file
     * and is completely wrong here: the median red card rate is 0.00000 for
     * every position on the pitch, because most players never receive one.
     * Only 22.4% of centre-backs see a red in a season, and 7.4% of keepers.
     * Using medians would produce a league in which nobody is ever sent off.
     *
     * The shape is exactly what anyone who watches football would expect:
     * defensive midfielders are booked most (0.286 a game), keepers least
     * (0.063), and centre-backs are sent off at more than twice a winger's rate.
     */
    val SLOT_YELLOW90 = mapOf(
        "GK" to 0.0625, "CB" to 0.2197, "FB" to 0.2154, "DM" to 0.2859,
        "CM" to 0.2446, "CAM" to 0.1861, "Winger" to 0.1711, "ST" to 0.1821,
    )

    val SLOT_RED90 = mapOf(
        "GK" to 0.00394, "CB" to 0.01354, "FB" to 0.00828, "DM" to 0.01002,
        "CM" to 0.00902, "CAM" to 0.00822, "Winger" to 0.00571, "ST" to 0.00827,
    )

    /** Minutes in a full 38-match season, and the matches themselves. */
    const val FULL_SEASON_MINUTES = 3420.0
    const val MATCHES_PER_SEASON = 38

    /**
     * How bookings scale with minutes played: yellows go as minutes^0.764, NOT
     * linearly.
     *
     * WHY THIS EXISTS. The user's XI plays every minute of every match, so a
     * drafted player's record has to be projected onto a full season. Doing
     * that linearly — his rate per 90, times 38 — overstates bookings badly:
     * Luka Modric's real 7 yellows in 1,744 minutes became 15 in a simulated
     * season, when players who actually play near-full seasons average 4.96 and
     * have never exceeded 17.
     *
     * Cards genuinely do not scale with minutes. Mean yellows per 90 FALLS
     * steadily as a player features more (0.235 in the 450-900 band down to
     * 0.151 above 2,900), because a booked player is often substituted and one
     * already on a yellow is managed carefully. Goals show no such decline —
     * their per-90 rate is flat to rising across the same bands — so this
     * correction is applied to cards only, and applying it to goals would be
     * wrong.
     *
     * MEASURED FROM THE BAND RATES, deliberately not from a log-log regression
     * on individual players. That regression conditions on a non-zero count and
     * so only sees players who were booked at all, which biases it hard toward
     * low-minute players: it returns 0.612 for yellows and a spurious 0.565 for
     * GOALS, which the unbiased band rates show to be linear. The band means
     * include every player, zeros and all.
     */
    const val CARD_MINUTES_EXPONENT = 0.764

    /**
     * How much a positional average is worth against a player's own record,
     * measured in 90s played.
     *
     * A player with no red card in 700 minutes has not proved he is
     * unbookable; one with a red in 700 minutes has not proved he is a thug.
     * Both estimates get pulled toward what his position does, by an amount
     * that fades as he plays more — at [CARD_PRIOR_90S] of 8, a man with 720
     * minutes is judged half on his own record and half on his position, and
     * one who played a full season almost entirely on his own.
     *
     * The value barely matters to the TEAM total, which is the point: shrinkage
     * moves cards between players without inventing or destroying any. It was
     * checked across 3 to 20 and the season total moved by 0.1 of a card. So it
     * is chosen for what it does to an individual, not fitted.
     */
    const val CARD_PRIOR_90S = 8.0

    /**
     * A player's expected cards per match: his own record, shrunk toward what
     * his position averages, then projected onto a full season.
     *
     * THE BUG THIS REPLACES. Card rates used to be "his own rate if he has
     * one, otherwise the positional mean". Those two are not alternatives —
     * the positional mean ALREADY CONTAINS the players who were carded, so
     * every one of them was counted twice: once in his own rate, and once
     * inside the average handed to everybody else.
     *
     * The damage scales with how rare the card is. 90.2% of players are booked
     * in a season, so yellows came out 1.20x reality. Only 14.4% see a red, so
     * reds came out 2.12x — a drafted side was shown 7.1 sendings-off a season
     * against a real 3.4, and two reds in one match arrived once every 64
     * matches instead of once every 270.
     *
     * Shrinking rather than switching uses both sources at once, weighted by
     * how much each is worth, and double counts nothing. Measured over all 400
     * club-seasons the result lands at 1.01x real for reds and 0.98x for
     * yellows.
     *
     * @param positionMean90 the [SLOT_YELLOW90] or [SLOT_RED90] entry for the
     *   position he actually plays.
     */
    fun cardsPerMatch(cards: Int, minutes: Int, positionMean90: Double): Double {
        if (minutes <= 0) return 0.0
        // The same sub-linear minutes correction as projectedSeasonCards, in
        // rate form: a full season leaves the rate alone, a partial one scales
        // it down, because a player who features more is booked less per 90.
        val played90 = minutes / 90.0
        return shrunkCardRate90(cards, minutes, positionMean90) *
            Math.pow(played90 / MATCHES_PER_SEASON, 1.0 - CARD_MINUTES_EXPONENT)
    }

    /**
     * The shrinkage on its own: a player's cards per 90, pulled toward
     * [positionMean90] by an amount that fades as he plays more.
     *
     * Algebraically a weighted average of his own rate and his position's,
     * with weight played90 / (played90 + [CARD_PRIOR_90S]) on his own. Split
     * out from [cardsPerMatch] so the shrinkage can be tested without the
     * minutes projection sitting on top of it.
     */
    fun shrunkCardRate90(cards: Int, minutes: Int, positionMean90: Double): Double {
        if (minutes <= 0) return positionMean90
        val played90 = minutes / 90.0
        return (cards + CARD_PRIOR_90S * positionMean90) / (played90 + CARD_PRIOR_90S)
    }

    /**
     * What a player's card record projects to over a full season, given how
     * much of one he actually played. See [CARD_MINUTES_EXPONENT].
     */
    fun projectedSeasonCards(total: Int, minutes: Int): Double =
        projectedCards(total, fromMinutes = minutes, toMinutes = FULL_SEASON_MINUTES)

    /**
     * A card record re-scaled from the minutes it was earned in to the minutes
     * a player is about to be given. See [CARD_MINUTES_EXPONENT] for why this
     * is not a straight ratio.
     */
    fun projectedCards(total: Int, fromMinutes: Int, toMinutes: Double): Double {
        if (total <= 0 || fromMinutes <= 0 || toMinutes <= 0) return 0.0
        return total * Math.pow(toMinutes / fromMinutes, CARD_MINUTES_EXPONENT)
    }

    // ------------------------------------------------ rotation and the bench
    /**
     * The share of a season a STARTER plays once the squad has a bench.
     *
     * Measured: the median top-eleven player features for 69.6% of a season,
     * because real squads rotate, rest and lose players to injury. Before the
     * bench existed the engine gave all eleven every minute of all 38, which is
     * why a drafted forward's totals ran about 45% above a real one's.
     *
     * The goalkeeper is the exception and plays the lot — there is no backup
     * keeper on the bench, since one would score nothing, be booked once every
     * sixteen games, and occupy a slot that could hold a player who matters.
     */
    const val STARTER_MINUTE_SHARE = 0.70

    /** Substitutes on an auto-filled bench. */
    const val BENCH_SIZE = 8

    /**
     * Share of a season a STARTER plays, by position (median, 204-481 real
     * starting seasons each).
     *
     * Minutes are not flat across a starting eleven and they do not track
     * quality especially closely — the correlation between rating and minutes
     * INSIDE a starting XI is only r = +0.36. What they track is position:
     * keepers are barely rotated, forwards are rotated most. The spread is
     * modest, ±5 points around the 70% average, but it runs in the direction
     * that matters here, since the striker is usually the player whose tally is
     * most at risk of looking inflated.
     *
     * The goalkeeper is a special case. Real keepers play 89.5% because a
     * backup takes the rest; a drafted squad has no backup, so ours plays
     * everything.
     */
    val STARTER_MINUTE_SHARE_BY_ROLE = mapOf(
        "GK" to 1.000, "CB" to 0.710, "CAM" to 0.705, "DM" to 0.690,
        "CM" to 0.690, "FB" to 0.672, "Winger" to 0.651, "ST" to 0.648,
    )

    /**
     * Share of a season played by squad ranks 12 to 19 (median), which is what
     * a bench really looks like.
     *
     * A real bench is a hierarchy, not eight interchangeable names: the first
     * substitute plays 44.1% of a season and the eighth 21.1%, a 2.1x spread.
     * Giving all eight the same share produced eight near-identical tallies,
     * which is the one thing a squad list never looks like.
     *
     * Only the SHAPE is used. The level is set by what the starters leave, so
     * the squad always adds up to eleven players' worth of minutes.
     */
    val BENCH_MINUTE_CURVE = listOf(0.441, 0.409, 0.373, 0.340, 0.307, 0.272, 0.243, 0.211)

    /** Minutes a full side plays across a season, in player-seasons. */
    const val SQUAD_PLAYER_SEASONS = 11.0

    /**
     * How often a player STARTS, given how much of a season he plays.
     *
     *     start rate = 0.3231 + 0.7662 * minutes share      (n = 4,000)
     *
     * Fitted from minutes per appearance across every squad in the data: a
     * start puts a player on for about 85 minutes and a substitute appearance
     * for about 22, so the ratio implies how often he began a match. The band
     * means track the fit closely from a 20% share upward.
     *
     * WHY THE ENGINE NEEDS THIS. A substitute cannot score in the third minute,
     * and goal minutes used to be drawn uniformly across the 90 whoever scored
     * them — so a bench forward turned up on the scoresheet before he could
     * possibly have been on the pitch. Note the answer is not "substitutes
     * score late": a player on a 44% share still STARTS 64% of the matches he
     * appears in, so most of his goals are legitimately early. The engine just
     * has to decide which case it is rather than ignoring the question.
     */
    const val START_RATE_INTERCEPT = 0.3231
    const val START_RATE_SLOPE = 0.7662

    fun startRate(minutesShare: Double): Double =
        (START_RATE_INTERCEPT + START_RATE_SLOPE * minutesShare).coerceIn(0.0, 1.0)

    /**
     * When a substitute comes on.
     *
     * JUDGEMENT, not measurement — nothing in the data records a substitution
     * minute, so this is the window real substitutions cluster in rather than a
     * figure fitted to anything. It only decides the earliest minute a
     * substitute can appear on the scoresheet.
     */
    const val SUB_ENTRY_EARLIEST = 58
    const val SUB_ENTRY_LATEST = 78

    /**
     * What each substitute gets: whatever the ten outfield starters leave.
     *
     * Real squads spread the remainder over about ten players and those players
     * take 27.7% of the club's goals. Eight subs is close enough to that shape
     * to put a top substitute near the real median of five goals; six put him at
     * the ninetieth percentile.
     */
    /** Minutes for a whole squad, as a share of a season each. */
    data class SquadMinutes(val starters: List<Double>, val bench: List<Double>)

    /**
     * Shares out a season's minutes across eleven starters and a bench.
     *
     * The SHAPE comes from the measured curves; the LEVEL comes from the
     * constraint that a side plays exactly eleven players' worth of minutes
     * over a season. Those two do not agree on their own, because the real
     * curves describe squads of twenty-five and a drafted squad is nineteen:
     * the minutes a real club gives its twentieth to twenty-fifth players have
     * to go somewhere. They are spread proportionally over everyone rather than
     * loaded onto the starters, which would re-inflate exactly the star tallies
     * the bench exists to bring down.
     *
     * Nobody can exceed a full season, so anyone the scaling pushes past 100%
     * is capped and his surplus redistributed.
     */
    fun squadMinuteShares(starterRoles: List<String>, benchSize: Int): SquadMinutes {
        if (starterRoles.isEmpty()) return SquadMinutes(emptyList(), emptyList())
        val rawStarters = starterRoles.map { STARTER_MINUTE_SHARE_BY_ROLE[it] ?: STARTER_MINUTE_SHARE }
        val rawBench = List(benchSize) { i ->
            BENCH_MINUTE_CURVE.getOrElse(i) { BENCH_MINUTE_CURVE.last() }
        }

        // The keeper is held out of the scaling entirely. He already plays a
        // full season and cannot play more, so including him only pushed his
        // surplus onto the outfielders — which quietly moved the starter/bench
        // split from the measured 72/28 towards 74/26 and handed the star
        // striker back some of the share the bench exists to take off him.
        val keeperIndices = starterRoles.withIndex().filter { it.value == "GK" }.map { it.index }
        val keeperMinutes = keeperIndices.size.toDouble()

        val scalable = rawStarters.filterIndexed { i, _ -> i !in keeperIndices } + rawBench
        val target = SQUAD_PLAYER_SEASONS - keeperMinutes
        val rawTotal = scalable.sum()
        if (rawTotal <= 0.0) return SquadMinutes(rawStarters, rawBench)
        val scale = target / rawTotal

        // Scale, then cap at a full season and give the capped surplus to
        // whoever still has room. Without the redistribution the squad quietly
        // ends up short: a side with no bench needs all ten outfielders on 100%,
        // and simply clipping the overflow left it playing 10.86 men.
        var shares = scalable.map { it * scale }
        repeat(6) {
            val capped = shares.map { it.coerceAtMost(1.0) }
            val deficit = target - capped.sum()
            if (deficit <= 1e-9) {
                shares = capped
                return@repeat
            }
            val room = capped.sumOf { 1.0 - it }
            shares = if (room <= 1e-9) capped else capped.map { it + deficit * (1.0 - it) / room }
        }
        shares = shares.map { it.coerceIn(0.0, 1.0) }

        val outfield = shares.take(rawStarters.size - keeperIndices.size).iterator()
        val starters = rawStarters.indices.map { i ->
            if (i in keeperIndices) 1.0 else outfield.next()
        }
        return SquadMinutes(starters, shares.takeLast(rawBench.size))
    }

    /**
     * League-wide card rates per team per match, for validating the above.
     * A simulated season that drifts from these is wrong regardless of how
     * good the per-position numbers look.
     */
    const val TEAM_YELLOWS_PER_MATCH = 2.129
    const val TEAM_REDS_PER_MATCH = 0.0932

    /**
     * The median top scorer takes 35.2% of his XI's goals, and real Golden
     * Boot totals across the ten loaded seasons run 23-36. A simulated season
     * whose leading scorer sits far outside that is misattributing, however
     * correct the team totals are. Asserted in SeasonStatsTest.
     */
    const val TOP_SCORER_SHARE_OF_XI = 0.352

    /** Median overall rating by position, for pricing a player against his peers. */
    val SLOT_MEDIAN_OVR = mapOf(
        "GK" to 79, "CB" to 77, "FB" to 77, "DM" to 77,
        "CM" to 77, "CAM" to 80, "Winger" to 78, "ST" to 79,
    )

    /**
     * How far out of position a player must be before none of his own
     * attacking output transfers.
     *
     * The blend runs on the EA positional rating drop already used by the
     * draft. At a 25-point drop a player produces what the POSITION produces
     * rather than what HE produces, adjusted for his quality. That threshold
     * is the measured gap between adjacent roles (1-6 points) and genuinely
     * alien ones (18-25), so it separates "playing him wide" from "playing him
     * at centre-back".
     *
     * This is the one number in the file that is a judgement rather than a
     * fit, because no player in the dataset spent a season out of position —
     * there is nothing to regress against. It is isolated here so it can be
     * changed on its own.
     */
    const val POSITION_TRANSFER_RANGE = 25.0

    /** Rating points above the positional median worth 1% more output. */
    const val RATING_EDGE_PER_POINT = 0.030

    // ----------------------------------------------------------------- match
    /**
     * Home advantage, measured over 3,800 real fixtures in the database:
     * home sides scored 1.498 per match against 1.203 away.
     *
     *     multiplier = 1.2452
     *     home wins 44.3%, draws 25.1%, away wins 30.6%
     *
     * Notably below the 1.3 that gets quoted from memory, which is exactly why
     * it was counted rather than recalled.
     */
    const val HOME_ADVANTAGE = 1.2452

    /** League goals per team per match, from those same 3,800 fixtures. */
    const val LEAGUE_GOALS_PER_TEAM = 1.3505

    /** Nothing sensible happens outside this band; keeps a freak XI finite. */
    const val MIN_EXPECTED_GOALS = 0.15
    const val MAX_EXPECTED_GOALS = 5.0
}
