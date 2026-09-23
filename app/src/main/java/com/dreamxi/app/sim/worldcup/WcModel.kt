package com.dreamxi.app.sim.worldcup

/**
 * Every number the World Cup engine adds on top of the club engine.
 *
 * THE RULE OF THE SIM PACKAGE STILL HOLDS: nothing here was chosen. Each value
 * was measured by `etl/wc_calibration.py` against the real 2006-2026 World
 * Cups, and carries its fit. Run that script to reproduce any of them.
 *
 * WHY THE CLUB ENGINE CANNOT BE USED UNCHANGED. World Cup squads are
 * RATING-ONLY: a national pool comes from EA's ratings and positions, with no
 * season of npxG, goals or save percentage behind it, and FIFA 07, 11 and 26
 * carry no positional grid either. So the club engine is fed through its own
 * documented estimator for missing output (TeamStrength.positionalYield), and
 * its result is then mapped onto international football, which is measurably
 * lower-scoring and closer-fought than a club league.
 */
object WcModel {

    // ------------------------------------------------------------- scoring
    /**
     * International football, from the club engine's per-side expected goals:
     *
     *     real goals = 0.9147 * clubExpected ^ 0.7330
     *
     * Poisson log-link fit over 300 group matches (every one where both sides
     * could be rated), each side's goals as an observation. Two findings in it:
     *   - the raw club model over-predicts World Cup scoring, 1.61 goals a side
     *     against a real 1.29;
     *   - the exponent below 1 says it also over-SEPARATES sides: World Cup
     *     mismatches are closer than their ratings suggest. The shape term is
     *     significant (deviance gain 14.69 against a chi-squared 5% of 3.84), so
     *     it is kept rather than folded into a single scale.
     *
     * What the mapped model is worth: win/draw/loss log-loss 0.939 against
     * 1.079 for treating every side as equal; goal difference r = +0.53;
     * draw rate 23.8% against a real 23.7%.
     *
     * Refitted 2026-09-18 with [WcOutput], which replaced the club engine's
     * linear rating-to-output fallback for World Cup players. That fixed a side
     * of 92s rating no better in attack than a real England XI, and improved the
     * fit on real results at the same time (0.9500 -> 0.9386).
     */
    const val INTERNATIONAL_SCALE = 0.9147
    const val INTERNATIONAL_SHAPE = 0.7330

    /**
     * A host nation scores x1.468 what the model expects (35 host matches
     * decided in ninety minutes; likelihood ratio 8.46, significant). It
     * concedes x1.17, which is NOT significant (LR 1.11) and is not applied.
     * Above the club home advantage of x1.245, which is the known shape of
     * World Cup hosting.
     */
    const val HOST_SCORING_FACTOR = 1.468

    /**
     * Extra time scores at 0.685 of the per-minute rate of the ninety before
     * it (37 real ties 2006-2026 that went to extra time, 23 of them still
     * level after it; 95% interval 0.36-1.25). Tired legs and a shootout to
     * hide behind. Fitted per match on each tie's own expected goals, so it is
     * not confounded by which sides reach extra time.
     */
    const val EXTRA_TIME_SCORING_FACTOR = 0.685
    const val EXTRA_TIME_MINUTES = 30

    /**
     * NO KNOCKOUT FACTOR, and the reason matters. A likelihood fit on the
     * knockout matches finds a significant x1.2 — but it comes entirely from
     * scorelines in ties decided inside ninety minutes, and applying it pushes
     * the share of ties level at ninety from 28% toward 25% against a real 33%.
     * (Refitted with per-player grids and WcOutput: same conclusion.)
     * Real knockouts are BOTH drawier and higher-scoring once someone scores,
     * because a side that falls behind has to attack; one multiplier cannot do
     * both, and the thing that decides who goes through is how often a tie is
     * level. So knockout matches use the same ninety-minute model as groups,
     * and decided knockout scorelines read about half a goal low.
     */
    @Suppress("unused")
    private const val KNOCKOUT_FACTOR_REJECTED = 1.196

    // ------------------------------------------------------------ shootouts
    /**
     * Shootouts are a coin flip between the sides: the model's stronger side
     * won 14 of 23 real shootouts 2006-2026 (two-sided p = 0.40). So both sides
     * convert at the same rate and neither is favoured.
     *
     * The rate only shapes the SCORE of a shootout, never who wins it: 0.655
     * by maximum likelihood over all 23 shootout scores 2006-2026 (95% interval
     * 0.585-0.720). An earlier fit on the nine the fixture sources recorded gave
     * 0.615 with an interval twice as wide; the other fourteen scores came from
     * Wikipedia's match pages, each checked against the recorded winner.
     */
    const val SHOOTOUT_CONVERSION = 0.655
    const val SHOOTOUT_KICKS_EACH = 5

    // ---------------------------------------------------- building an XI
    /**
     * A goalkeeper for a nation with none rated. Every real side HAD a keeper;
     * EA simply did not license his league, so ten real nation-entries have
     * none (Tunisia 2026 has thirty rated players and no keeper). Four of them
     * are opponents a user can meet.
     *
     *     keeper = 1.0219 * mean(best ten outfield ratings) - 2.493
     *
     * over the 194 nation-entries that do have a keeper; r = +0.73, residual
     * sd 4.47. A keeper is about as good as the side around him.
     */
    const val STAND_IN_KEEPER_SLOPE = 1.0219
    const val STAND_IN_KEEPER_INTERCEPT = -2.493

    /** EA positions onto the engine's eight roles, as etl/roles.py groups them. */
    val EA_ROLE: Map<String, String> = mapOf(
        "GK" to "GK", "CB" to "CB",
        "LB" to "FB", "RB" to "FB", "LWB" to "FB", "RWB" to "FB",
        "CDM" to "DM", "CM" to "CM", "CAM" to "CAM",
        "LM" to "Winger", "RM" to "Winger", "LW" to "Winger", "RW" to "Winger",
        "CF" to "ST", "LF" to "ST", "RF" to "ST", "ST" to "ST",
    )

    /**
     * Rating points lost on the WRONG FLANK: a full-back 4, a winger 2 — the
     * club draft's own values (DraftModels.kt), so both modes price it the
     * same. A JUDGEMENT, not a measurement: EA rates left and right identically
     * for every player, so there is nothing to fit. Checked against real World
     * Cup results it does no harm (log-loss 0.9456 without, 0.9450 with).
     * Applied to full-backs and wingers whose EA positions put them on one side.
     */
    const val WRONG_SIDE_FULLBACK_PENALTY = 4
    const val WRONG_SIDE_WINGER_PENALTY = 2

    /** EA positions that fix a full-back or winger to a flank. */
    val FLANK_OF: Map<String, Map<String, String>> = mapOf(
        "FB" to mapOf("LB" to "L", "LWB" to "L", "RB" to "R", "RWB" to "R"),
        "Winger" to mapOf("LM" to "L", "LW" to "L", "RM" to "R", "RW" to "R"),
    )
}
