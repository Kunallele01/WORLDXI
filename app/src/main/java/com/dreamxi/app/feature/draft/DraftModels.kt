package com.dreamxi.app.feature.draft

import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.ui.theme.PlayerPosition

/**
 * Transfermarkt's per-season role -> that player's natural key in the EA
 * positional grid. Null for keepers, whose grid we do not store (see
 * [deltaAt]).
 */
fun gridKeyForRole(role: String): String? = when (role) {
    "GK" -> null
    "CB" -> "cb"
    "FB" -> "fb"
    "DM" -> "dm"
    "CM" -> "cm"
    "CAM" -> "cam"
    "Winger" -> "winger"
    "ST" -> "st"
    else -> null
}

/**
 * How natural a player is in a position. The bands the pitch colours by: a
 * drop of 3 or less is nothing, 4-10 is a real but survivable cost, and more
 * than 10 means he is somewhere he does not belong.
 *
 * The boundaries come from the measured population, not from taste. Adjacent
 * roles cluster at 1-6 points (a striker loses 3 at winger, 4 at CAM); alien
 * ones at 18-25 (that same striker loses 22 at defensive midfield, 25 at
 * centre-back). Ten points is the empty gap between those two clusters.
 */
enum class PositionFit { Natural, Comfortable, Stretch, Alien }

fun fitForDelta(delta: Int): PositionFit = when {
    delta >= 0 -> PositionFit.Natural
    delta >= -3 -> PositionFit.Comfortable
    delta >= -10 -> PositionFit.Stretch
    else -> PositionFit.Alien
}

/**
 * Cost of putting a keeper outfield or an outfielder in goal. Measured, not
 * guessed: Courtois's overall is 90 against outfield ratings of 29-32, and Ben
 * Foster's is 78 against 28-32 — roughly 50 either way. A constant rather than
 * a lookup because the pairing is blocked at selection, so this exists only to
 * keep an impossible answer visibly impossible.
 */
private const val GOALKEEPER_BOUNDARY_PENALTY = 50

/**
 * Cost of playing someone on the wrong flank.
 *
 * UNLIKE every other number in this file, these are NOT measured — they are a
 * design choice, and they are the only place in the rating model where that is
 * true. EA's dataset records which flank a player belongs to (his position
 * list says LW, not RW) but its rating columns are IDENTICAL left and right
 * for every player in the file, so there is nothing in the source to derive a
 * magnitude from. Treating that silence as "zero" was the first version, and
 * it was wrong for an obvious reason: it let Vinícius Júnior, a left winger
 * who has essentially never played on the right, take the RW position at his
 * full rating.
 *
 * The two values differ because the two cases differ in football:
 *  - A winger on the wrong flank becomes an inverted winger, cutting inside
 *    onto his stronger foot. Extremely common, often deliberate, sometimes an
 *    improvement — so the cost is small.
 *  - A full-back on the wrong flank is rarer and more awkward: crossing,
 *    first touch and defensive body shape all point the wrong way.
 *
 * Players EA lists on BOTH flanks (21% of full-backs, 28% of wingers) pay
 * nothing, and neither do players whose side we could not resolve — an unknown
 * side is not evidence of a wrong one.
 */
private const val WRONG_SIDE_WINGER_PENALTY = 2
private const val WRONG_SIDE_FULLBACK_PENALTY = 4

/** Sided display label for a role, used where EA gives us no explicit side. */
fun sidedRoleLabel(role: String): String = when (role) {
    "FB" -> "LB/RB"
    "Winger" -> "LW/RW"
    else -> role
}

/** A player as offered by a spun squad. */
data class SquadPlayer(
    val playerSeasonStatId: Long,
    val playerId: Long,
    val fullName: String,
    val role: String,
    val group: PlayerPosition,
    val overallRating: Int,
    val minutes: Int,
    val goals: Int,
    val assists: Int,
    val appearances: Int = 0,
    val shots: Int = 0,
    val shotsOnTarget: Int = 0,
    val tackles: Int = 0,
    val yellowCards: Int = 0,
    val redCards: Int = 0,
    /** Keepers only; null for outfielders. */
    val savePct: Double? = null,
    val cleanSheets: Int = 0,
    /**
     * Non-penalty expected goals per 90. What the simulation actually scores
     * off. Null for ~5% of regulars — the Understat mid-season transfers —
     * who are given their position's rate instead when the XI is simulated.
     */
    val npxg90: Double? = null,
    val nationality: String? = null,
    /** 'L', 'R' or 'B' (both flanks), from EA's position list. See [sidePenaltyAt]. */
    val side: String? = null,
    /**
     * EA's rating for this player at each position key. Empty when no EA grid
     * could be resolved (27 of 5,723 player-seasons), in which case he carries
     * no positional penalty at all rather than being guessed at.
     */
    val positionRatings: Map<String, Int> = emptyMap(),
    /**
     * True when this exact person is already in the XI from an earlier round,
     * possibly under a different club and season. Identity is unique across the
     * whole draft, so he is shown but not selectable — hiding him invites
     * "where did he go?", showing him greyed explains itself.
     */
    val alreadyDrafted: Boolean = false,
)

/**
 * Rating points lost playing [slot] instead of this player's natural position.
 * Never positive; zero at his own position and whenever no grid is available.
 *
 * A DELTA against his own natural-position grid value, not the raw grid value,
 * because EA's positional ratings sit on a slightly different scale from
 * overall: Benzema's FIFA 23 overall is 91 while his `st` positional rating is
 * 89. Using the grid directly would dock a striker two points for playing
 * striker. Anchoring to his own natural value cancels that offset exactly, so
 * a player at his own position always shows precisely his overall.
 */
fun SquadPlayer.deltaAt(slot: FormationSlot): Int {
    // Crossing the goalkeeper boundary is never draftable, but it must not
    // return a plausible-looking number if anything ever asks. The stored grid
    // holds OUTFIELD columns only, so a keeper's reference below would fall
    // back to his best outfield value — Courtois's outfield ratings are 29-32,
    // and taking 32 as his baseline made him read as an 87-rated centre-back.
    if ((role == "GK") != slot.isGoalkeeper) return -GOALKEEPER_BOUNDARY_PENALTY

    val target = slot.gridKey?.let { positionRatings[it] } ?: return -sidePenaltyAt(slot)
    val reference = gridKeyForRole(role)?.let { positionRatings[it] }
        ?: positionRatings.values.maxOrNull()
        ?: return -sidePenaltyAt(slot)
    return minOf(0, target - reference) - sidePenaltyAt(slot)
}

/**
 * Points lost for being on the wrong flank, or 0. See the constants above for
 * why this is a judgement call rather than a measurement.
 */
fun SquadPlayer.sidePenaltyAt(slot: FormationSlot): Int {
    val slotSide = slot.side ?: return 0
    // "B" means EA lists him on both flanks; null means we could not resolve
    // one. Neither is evidence that this flank is wrong for him.
    val playerSide = side?.takeIf { it == "L" || it == "R" } ?: return 0
    if (playerSide == slotSide) return 0
    return if (role == "FB") WRONG_SIDE_FULLBACK_PENALTY else WRONG_SIDE_WINGER_PENALTY
}

/** True when this player is being asked to switch flanks. */
fun SquadPlayer.isWrongSideAt(slot: FormationSlot): Boolean = sidePenaltyAt(slot) > 0

/** This player's effective rating in [slot], after the out-of-position cost. */
fun SquadPlayer.ratingAt(slot: FormationSlot): Int =
    (overallRating + deltaAt(slot)).coerceIn(1, 99)

fun SquadPlayer.fitAt(slot: FormationSlot): PositionFit = fitForDelta(deltaAt(slot))

/**
 * How this club-season's defence performed, for describing defenders.
 *
 * Goals and assists say almost nothing about a centre-back, and the columns
 * that WOULD describe defending — interceptions, clearances, aerials — are
 * entirely unpopulated in the loaded data. The team's defensive record is the
 * one real defensive signal available, and it is a good one: "ever-present in
 * a back line that conceded 29, fewest in the league" is a genuine description
 * of a season, where "2 goals in 31 games" is noise.
 */
data class DefensiveRecord(
    val goalsAgainst: Int,
    /** 1 = fewest conceded in that league season. */
    val rank: Int,
    val teamsInLeague: Int,
)

/** The club-season a spin landed on. */
data class SpinResult(
    val clubSeasonId: Long,
    val clubName: String,
    val seasonLabel: String,
    val finalPosition: Int?,
    val squadStrength: Double?,
    /** 1 = weakest quartile, 4 = strongest. From `club_season_draft_pool`. */
    val strengthQuartile: Int?,
    val players: List<SquadPlayer>,
    val defence: DefensiveRecord? = null,
)

/** A completed pick. */
data class DraftedPlayer(
    val slot: FormationSlot,
    val player: SquadPlayer,
    val clubName: String,
    val seasonLabel: String,
) {
    /** What he is actually worth where he has been put. */
    val effectiveRating: Int get() = player.ratingAt(slot)
    val fit: PositionFit get() = player.fitAt(slot)
}

/**
 * Everything the Draft screen renders. Deliberately a single immutable
 * snapshot: the screen has several interlocking states (spinning, revealed,
 * player selected, placement sheet open) and threading them as separate flags
 * is how a draft ends up somewhere impossible like "spinning while placing".
 */
data class DraftUiState(
    val leagueName: String = "",
    val formation: Formation = DefaultFormation,
    val round: Int = 1,
    /** Picks keyed by [FormationSlot.id] — slots are data now, not an enum. */
    val picks: Map<String, DraftedPlayer> = emptyMap(),
    val spin: SpinResult? = null,
    val isSpinning: Boolean = false,
    /** Names cycling on the reel while a spin resolves. */
    val reelClubNames: List<String> = emptyList(),
    val rerollsAllowed: Int = 2,
    val rerollsUsed: Int = 0,
    val selectedPlayer: SquadPlayer? = null,
    /** True while the pitch is up as a placement sheet for [selectedPlayer]. */
    val isPlacing: Boolean = false,
    /**
     * True while the pitch is up for reviewing and rearranging the XI, with no
     * player in hand from the pool. Reachable at any point in a run, which
     * matters because a misplacement made in round 3 was otherwise permanent:
     * the pitch only ever appeared as a side effect of picking someone new.
     */
    val isViewingTeam: Boolean = false,
    /**
     * The position whose player has been picked up for moving, if any. Holding
     * a placed player and tapping elsewhere moves him there, or swaps the two.
     */
    val heldSlotId: String? = null,
    /**
     * Eight substitutes, filled automatically once the XI is complete from the
     * squads this run passed through. They change WHO SCORES over a season, not
     * how good the side is. See BenchSelection.
     */
    val bench: List<BenchPlayer> = emptyList(),
    val error: DreamXiError? = null,
) {
    val totalRounds: Int get() = formation.slots.size
    val rerollsRemaining: Int get() = (rerollsAllowed - rerollsUsed).coerceAtLeast(0)
    val openSlots: List<FormationSlot> get() = formation.slots.filter { it.id !in picks }
    val isComplete: Boolean get() = picks.size == formation.slots.size

    /**
     * Positions [player] may fill.
     *
     * Any outfielder may fill any open outfield position; the cost of playing
     * him somewhere unnatural is priced into his rating there ([ratingAt])
     * rather than forbidden. Two reasons the old role lock had to go: 22% of
     * loaded squads contain no CAM at all and 7% no DM, so those positions were
     * routinely unfillable from the squad in front of you; and a locked draft
     * failed to complete an XI in 2.1%-6.2% of simulated runs with no rerolls.
     *
     * Goalkeeper is the exception and is locked both ways. EA rates outfielders
     * roughly 50 points below their overall in goal and keepers similarly
     * outfield, so allowing it would only add an option nobody would take
     * deliberately and some would take by accident.
     *
     * Sorted best-fit first so the obvious choice leads, without removing the
     * freedom to do something odd on purpose.
     */
    fun eligibleSlotsFor(player: SquadPlayer): List<FormationSlot> {
        val keeper = player.role == "GK"
        return openSlots
            .filter { it.isGoalkeeper == keeper }
            .sortedByDescending { player.ratingAt(it) }
    }

    /**
     * XI rating so far, averaging EFFECTIVE ratings rather than natural ones.
     *
     * A striker parked at centre-back has to drag this down the moment he is
     * placed, or the header would advertise an 88 XI that simulates like an 80.
     */
    val currentXiRating: Int?
        get() = picks.values.map { it.effectiveRating }
            .takeIf { it.isNotEmpty() }
            ?.let { Math.round(it.average()).toInt() }

    /** The player currently picked up off the pitch for repositioning. */
    val heldPick: DraftedPlayer? get() = heldSlotId?.let { picks[it] }

    /** Natural-rating average, for showing what the out-of-position cost is. */
    val currentXiNaturalRating: Int?
        get() = picks.values.map { it.player.overallRating }
            .takeIf { it.isNotEmpty() }
            ?.let { Math.round(it.average()).toInt() }
}
