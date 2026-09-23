package com.dreamxi.app.sim.worldcup

import com.dreamxi.app.sim.Hungarian
import com.dreamxi.app.sim.SimModel
import com.dreamxi.app.sim.WingBackCost
import com.dreamxi.app.sim.SimPlayer
import com.dreamxi.app.sim.SimSlot
import com.dreamxi.app.sim.SquadProfile
import com.dreamxi.app.sim.TeamRating
import com.dreamxi.app.sim.TeamStrength
import kotlin.math.roundToInt

/**
 * One player in a World Cup squad: a rating and the positions EA lists for him,
 * and nothing else. See [WcModel] for why that is all there is.
 *
 * @param rating on the FIFA 17+ scale, as wc_squad_players stores it.
 * @param positions EA positions, primary first ("CB", "RB").
 * @param grid EA's positional grid for this person, as the change from his
 *   primary role to each role ("FB" to -7). His own edition's, or borrowed from
 *   the same person at most two editions away; null when there is neither, and
 *   he is priced from the rating-banded fallback [WcPositionCosts].
 * @param gridEdition the FIFA edition [grid] came from.
 */
data class WcPlayer(
    val id: String,
    val name: String,
    val rating: Double,
    val positions: List<String>,
    val grid: Map<String, Int>? = null,
    val gridEdition: String? = null,
) {
    val primaryRole: String get() = positions.firstOrNull()?.let { WcModel.EA_ROLE[it] } ?: "CM"
    val isKeeper: Boolean get() = primaryRole == "GK"
    val overall: Int get() = rating.roundToInt()

    /**
     * "L" or "R" for a full-back or winger fixed to one flank, "B" for one EA
     * lists on both, null for everyone else — the club draft's side rules.
     */
    val side: String?
        get() {
            val family = WcModel.FLANK_OF[primaryRole] ?: return null
            val sides = positions.mapNotNull { family[it] }.toSet()
            return when (sides.size) {
                2 -> "B"
                1 -> sides.first()
                else -> null
            }
        }

    /** The roles of his listed positions after the first, without repeats. */
    val secondaryRoles: Set<String>
        get() = positions.drop(1).mapNotNull { WcModel.EA_ROLE[it] }.toSet() - primaryRole
}

/**
 * A position in a formation as the engine sees it: the role, and for full-backs
 * and wingers which flank ("L"/"R"), as FormationSlot.side gives it.
 */
/**
 * A position in a national side's shape.
 *
 * [wingBack] is carried separately from [role] because a wing-back IS the
 * full-back role — EA simply rates it differently, and the stored grid has no
 * column for it. See [WingBackCost].
 */
data class WcSlot(val role: String, val side: String? = null, val wingBack: Boolean = false)

/** A side picked and rated: the shape, who stands where, and what it is worth. */
data class NationalLineup(
    val formationIndex: Int,
    val slots: List<SimSlot>,
    val players: List<WcPlayer>,
) {
    val rating: TeamRating by lazy { TeamStrength.rate(slots) }

    /** Who scores for this side in a simulated match. The eleven play every minute. */
    val scorers: SquadProfile by lazy { SquadProfile.fromXi(slots) }
}

/**
 * Builds a national side the way the club engine can read it.
 *
 * PRICING A PLAYER OUT OF POSITION. Each player carries EA's own positional
 * grid where one exists for him within two editions (68% of outfield squad
 * players); the rest are priced from [WcPositionCosts], medians by rating band.
 * A single median table used to price everyone, and put a 90-rated Rodri at
 * left-back for 2 points when his own grid says 7. On top of either, the club
 * draft's wrong-flank cost.
 */
object NationalSide {

    /**
     * Rating points lost in [role] on flank [side]: from his own EA grid where he
     * has one, the banded fallback where he does not, plus the wrong-flank cost.
     * Never positive. Null across the goalkeeper boundary, which is never
     * crossed — the same absolute rule the draft enforces.
     */
    fun positionCost(
        player: WcPlayer,
        role: String,
        side: String? = null,
        wingBack: Boolean = false,
    ): Int? {
        val keeperSlot = role == "GK"
        if (player.isKeeper != keeperSlot) return null
        if (keeperSlot) return 0
        // A wing-back is the full-back role played further up, and EA rates it
        // separately — the stored grid keeps no column for it. Same adjustment,
        // same table, as the club draft's SquadPlayer.deltaAt, so the rating on
        // the pitch is the rating that plays.
        val pushed = if (wingBack) WingBackCost[player.primaryRole] ?: 0 else 0
        return minOf(0, rawCost(player, role) - pushed) - flankCost(player, side)
    }

    /**
     * The grid difference for [role] BEFORE it is capped at zero.
     *
     * Capping belongs at the end, once every adjustment is in. Capping here
     * instead cost a point on wing-backs whose full-back rating sits ABOVE
     * their own position's: zero minus the push is not the same number as the
     * true difference minus the push, and the draft pitch and the engine then
     * disagreed about what the same man was worth in the same shirt.
     */
    internal fun rawCost(player: WcPlayer, role: String): Int {
        val grid = player.grid
        return when {
            grid != null -> grid[role] ?: 0
            role == player.primaryRole -> 0
            role in player.secondaryRoles ->
                WcPositionCosts.LISTED[WcPositionCosts.band(player.rating)][role] ?: 0
            else -> WcPositionCosts.UNLISTED[WcPositionCosts.band(player.rating)]["${player.primaryRole}>$role"]
                ?: -SimModel.POSITION_TRANSFER_RANGE.toInt()
        }
    }

    /** The club draft's wrong-flank cost, by the same rule as SquadPlayer.sidePenaltyAt. */
    fun flankCost(player: WcPlayer, side: String?): Int {
        if (side == null) return 0
        val mine = player.side?.takeIf { it == "L" || it == "R" } ?: return 0
        if (mine == side) return 0
        return if (player.primaryRole == "FB") WcModel.WRONG_SIDE_FULLBACK_PENALTY else WcModel.WRONG_SIDE_WINGER_PENALTY
    }

    /**
     * The player as the club engine sees him. With no season of output behind
     * him, his npxG, goals and assists come from [WcOutput] — measured on real
     * club seasons, per role, growing multiplicatively with rating.
     *
     * NOT the club engine's own [TeamStrength.positionalYield] fallback, which
     * is linear at 3% a point: measured against real output it flattered weak
     * attackers and halved elite ones, so a side of 92s rated no better in
     * attack than a real England XI. Club mode still uses its own fallback,
     * unchanged, because club players have real xG and only ~5% ever need it.
     */
    fun simPlayer(player: WcPlayer): SimPlayer {
        val role = player.primaryRole
        return SimPlayer(
            name = player.name,
            naturalRole = role,
            npxg90 = WcOutput.npxg90(role, player.rating),
            overallRating = player.overall,
            savePct = null,
            goals90 = WcOutput.goals90(role, player.rating),
            assists90 = WcOutput.assists90(role, player.rating),
        )
    }

    /**
     * A goalkeeper for a squad with none rated, from the measured relationship
     * between a side's keeper and its best ten outfielders. Null when the squad
     * already has one or has too few outfielders to estimate from.
     */
    fun standInKeeper(squad: List<WcPlayer>): WcPlayer? {
        if (squad.any { it.isKeeper }) return null
        val best = squad.map { it.rating }.sortedDescending().take(10)
        if (best.size < 10) return null
        val rating = WcModel.STAND_IN_KEEPER_SLOPE * best.average() + WcModel.STAND_IN_KEEPER_INTERCEPT
        return WcPlayer(id = "stand-in-keeper", name = STAND_IN_KEEPER_NAME, rating = rating, positions = listOf("GK"))
    }

    const val STAND_IN_KEEPER_NAME = "Unrated goalkeeper"

    /**
     * The strongest side a squad can field, chosen the way a coach would: the
     * best eleven for each shape, solved as one assignment (Magic XI's reason
     * applies — greedy slot filling measurably loses), then the shape whose
     * eleven has the best expected goal margin.
     *
     * @param formations each formation's eleven slots, keeper first.
     * @return null when the squad cannot fill any formation, which only
     *   happens to a handful of bottom-of-group nations — sides a user replaces
     *   and never plays.
     */
    fun bestLineup(squad: List<WcPlayer>, formations: List<List<WcSlot>>): NationalLineup? {
        val players = squad + listOfNotNull(standInKeeper(squad))
        var best: NationalLineup? = null
        var bestMargin = Double.NEGATIVE_INFINITY
        for ((index, slots) in formations.withIndex()) {
            require(slots.size == 11) { "formation $index has ${slots.size} slots" }
            if (players.size < 11) continue
            val value = Array(slots.size) { s ->
                DoubleArray(players.size) { p ->
                    positionCost(players[p], slots[s].role, slots[s].side)?.let { players[p].rating + it }
                        ?: Double.NEGATIVE_INFINITY
                }
            }
            val assignment = Hungarian.maximise(value)
            if (assignment.any { it < 0 }) continue
            val lineup = lineup(index, slots.zip(assignment.map { players[it] }))
            val margin = lineup.rating.attack - lineup.rating.defence
            if (margin > bestMargin) {
                best = lineup
                bestMargin = margin
            }
        }
        return best
    }

    /** A given eleven in given slots — the user's own XI, or a solved one. */
    fun lineup(formationIndex: Int, placed: List<Pair<WcSlot, WcPlayer>>): NationalLineup {
        val slots = placed.map { (slot, player) ->
            val cost = requireNotNull(positionCost(player, slot.role, slot.side)) {
                "${player.name} cannot play ${slot.role}: the goalkeeper boundary is never crossed"
            }
            SimSlot(role = slot.role, player = simPlayer(player), positionPenalty = cost)
        }
        return NationalLineup(formationIndex, slots, placed.map { it.second })
    }
}
