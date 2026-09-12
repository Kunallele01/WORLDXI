package com.dreamxi.app.sim

import kotlin.math.exp

/**
 * One player as the simulation sees him.
 *
 * Deliberately a separate, lean type rather than the draft's SquadPlayer. The
 * engine has no business knowing about club crests or scouting prose, and
 * keeping it decoupled is what lets the whole `sim` package move to the
 * standalone Ktor service later without dragging the Android app behind it —
 * nothing in this package imports anything from Android or from the UI.
 *
 * @param npxg90 non-penalty expected goals per 90. The engine's central input:
 *   summed across an XI it predicts real goals at r=+0.864.
 * @param overallRating his rating in the position he is ACTUALLY playing,
 *   after the out-of-position cost. Defence is priced on this.
 * @param naturalRole his own position, which decides how much of his attacking
 *   output survives being moved.
 * @param savePct goalkeepers only; null is treated as league-average rather
 *   than as bad, since an unknown keeper is not a poor one.
 * @param goals90 real goals per 90, INCLUDING penalties. Used only to decide
 *   who scores once the engine has decided how many, which is why it is
 *   separate from [npxg90] — a top scorer whose penalties silently vanished
 *   would read as wrong to anyone who follows the game.
 * @param assists90 real assists per 90.
 * @param yellow90 real yellow cards per 90.
 * @param red90 real red cards per 90.
 */
data class SimPlayer(
    val name: String,
    val naturalRole: String,
    val npxg90: Double,
    val overallRating: Int,
    val savePct: Double? = null,
    val goals90: Double = 0.0,
    val assists90: Double = 0.0,
    val yellow90: Double = 0.0,
    val red90: Double = 0.0,
)

/** A player in a position, which is the only form the engine can evaluate. */
data class SimSlot(
    val role: String,
    val player: SimPlayer,
    /**
     * Rating points lost to playing out of position, from the EA positional
     * grid. Zero at his own position, never positive.
     */
    val positionPenalty: Int = 0,
) {
    /** What he is worth where he is standing. */
    val effectiveRating: Int get() = (player.overallRating + positionPenalty).coerceIn(1, 99)
}

/**
 * Turns eleven players into the two numbers a match needs: how many goals this
 * side should score, and how many it should concede.
 */
object TeamStrength {

    /**
     * A player's attacking output in the position he occupies.
     *
     * The blend is the whole trick. A player at his own position contributes
     * exactly his own measured rate — that is the relationship validated at
     * r=+0.864, and it must survive untouched. As he is moved further from it,
     * his personal rate matters less and the POSITION's rate matters more,
     * because chances belong to positions rather than to people. A striker at
     * centre-back does not take his shots with him; he inherits the handful a
     * centre-back gets, scaled by whether he is better or worse than the
     * centre-backs he replaced.
     *
     * Without this, drafting eleven strikers would predict five goals a game.
     * With it, ten of them stand where a striker's chances never arrive and
     * the XI produces roughly what a normal team does.
     */
    fun attackContribution(slot: SimSlot): Double {
        if (slot.role == "GK") return 0.0
        val n = naturalness(slot)
        return n * slot.player.npxg90 +
            (1.0 - n) * positionalYield(slot.role, slot.effectiveRating)
    }

    /**
     * What a POSITION yields for a player of a given quality, in npxG/90.
     *
     * The measured median rate for the position, scaled by how far above or
     * below the positional median his rating sits. A player well above it gets
     * more out of the position than a median player would; one below gets less.
     *
     * Used for two different jobs, deliberately sharing one formula: it is the
     * far end of the out-of-position blend above, and it is the estimate for a
     * player whose real npxG is missing. Those are the same question — "what
     * would someone of this quality produce here?" — and answering it twice
     * would let the two answers drift apart.
     */
    fun positionalYield(role: String, rating: Int): Double =
        positionalYield(role, rating, SimModel.SLOT_NPXG90)

    /** As above, for any per-90 rate that varies by position. */
    fun positionalYield(role: String, rating: Int, table: Map<String, Double>): Double {
        val slotBase = table[role] ?: 0.0
        val median = SimModel.SLOT_MEDIAN_OVR[role] ?: 77
        val edge = 1.0 + (rating - median) * SimModel.RATING_EDGE_PER_POINT
        return slotBase * edge.coerceAtLeast(0.1)
    }

    /**
     * How much of a player's own rate survives being moved, 1 at his own
     * position and 0 once he is a full [SimModel.POSITION_TRANSFER_RANGE]
     * rating points adrift.
     */
    fun naturalness(slot: SimSlot): Double =
        (1.0 + slot.positionPenalty / SimModel.POSITION_TRANSFER_RANGE).coerceIn(0.0, 1.0)

    /**
     * A player's expected share of some countable event — a goal, an assist —
     * in the position he is standing in.
     *
     * The same blend the attack uses, and for the same reason: a striker moved
     * to centre-back does not take his goals with him, he inherits the handful
     * a centre-back gets, scaled by the fact that he is a better player than
     * the centre-backs around him.
     */
    fun eventWeight(slot: SimSlot, own: Double, table: Map<String, Double>): Double {
        if (slot.role == "GK") return 0.0
        val n = naturalness(slot)
        return n * own + (1.0 - n) * positionalYield(slot.role, slot.effectiveRating, table)
    }

    /**
     * Summed attacking output of the XI, in npxG per match, saturated beyond
     * the range the fit was validated on.
     *
     * Below the highest real XI ever measured the sum is used untouched — that
     * is the r=+0.864 relationship and it must not be distorted. Above it the
     * curve bends toward an asymptote, because there is no evidence the linear
     * fit continues and good reason to think it cannot: chances per match are
     * finite regardless of who is on the pitch.
     */
    fun attackRating(xi: List<SimSlot>): Double = saturate(xi.sumOf { attackContribution(it) })

    /** Linear up to the observed maximum, asymptotic past it. */
    fun saturate(raw: Double): Double {
        val cap = SimModel.ATTACK_LINEAR_MAX
        if (raw <= cap) return raw
        val headroom = SimModel.ATTACK_SATURATION_ASYMPTOTE - cap
        return cap + headroom * (1.0 - exp(-(raw - cap) / headroom))
    }

    /**
     * The XI's defensive rating: a mean of effective ratings weighted by how
     * much defending each position actually does.
     *
     * Weighted rather than flat because a striker's rating says almost nothing
     * about whether his team concedes — the flat mean fits noticeably worse
     * (r = -0.79 against -0.83). It also means a midfield stuffed with
     * forwards is properly punished instead of being averaged into
     * respectability.
     */
    fun defensiveRating(xi: List<SimSlot>): Double {
        var num = 0.0
        var den = 0.0
        for (slot in xi) {
            val w = SimModel.DEFENSIVE_WEIGHT[slot.role] ?: 0.4
            num += w * slot.effectiveRating
            den += w
        }
        return if (den > 0) num / den else 0.0
    }

    /** Goals this XI should score per match, before the opponent is considered. */
    fun expectedGoalsFor(xi: List<SimSlot>): Double =
        (SimModel.ATTACK_SLOPE * attackRating(xi) + SimModel.ATTACK_INTERCEPT)
            .coerceIn(SimModel.MIN_EXPECTED_GOALS, SimModel.MAX_EXPECTED_GOALS)

    /**
     * Goals this XI should concede per match, before the opponent is
     * considered.
     *
     * MULTIPLICATIVE THROUGHOUT — see [SimModel.DEFENCE_LOG_SLOPE] for why the
     * straight line it replaced was both a worse fit and, past about a 94
     * defensive rating, a promise to concede a negative number of goals. Each
     * rating point takes a percentage off rather than a fixed amount, so the
     * curve flattens as it approaches zero instead of crossing it, and no
     * clamp is doing the work.
     *
     * The keeper is a factor on the result rather than a term added to it, for
     * the same reason: a keeper who prevents 11% of goals should prevent 11%
     * of a great defence's handful and 11% of a poor one's flood, not the same
     * fixed number from each.
     */
    fun expectedGoalsAgainst(xi: List<SimSlot>): Double {
        val base = exp(
            SimModel.DEFENCE_LOG_SLOPE * defensiveRating(xi) + SimModel.DEFENCE_LOG_INTERCEPT,
        )
        val keeper = xi.firstOrNull { it.role == "GK" }?.player
        val save = keeper?.savePct ?: SimModel.LEAGUE_AVERAGE_SAVE_PCT
        val keeperFactor = exp(
            SimModel.KEEPER_SAVE_LOG_SLOPE * (save - SimModel.LEAGUE_AVERAGE_SAVE_PCT),
        )
        return (base * keeperFactor)
            .coerceIn(SimModel.MIN_EXPECTED_GOALS, SimModel.MAX_EXPECTED_GOALS)
    }

    /** Both numbers at once, which is all a fixture needs. */
    fun rate(xi: List<SimSlot>): TeamRating = TeamRating(
        attack = expectedGoalsFor(xi),
        defence = expectedGoalsAgainst(xi),
        attackRaw = attackRating(xi),
        defensiveRating = defensiveRating(xi),
    )
}

/**
 * A side reduced to what a match cares about.
 *
 * @param attack goals per match this side should score against average opposition
 * @param defence goals per match it should concede against average opposition
 */
data class TeamRating(
    val attack: Double,
    val defence: Double,
    val attackRaw: Double,
    val defensiveRating: Double,
) {
    /** Rough single number for display; not used by the engine itself. */
    val goalDifferencePerMatch: Double get() = attack - defence
}
