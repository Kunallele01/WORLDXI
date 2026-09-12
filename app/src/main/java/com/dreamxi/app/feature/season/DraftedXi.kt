package com.dreamxi.app.feature.season

import com.dreamxi.app.feature.draft.BenchPlayer
import com.dreamxi.app.feature.draft.DraftedPlayer
import com.dreamxi.app.feature.draft.coverDelta
import com.dreamxi.app.feature.draft.deltaAt
import com.dreamxi.app.sim.SimModel
import com.dreamxi.app.sim.SimPlayer
import com.dreamxi.app.sim.SimSlot
import com.dreamxi.app.sim.SimTeam
import com.dreamxi.app.sim.SquadProfile
import com.dreamxi.app.sim.TeamStrength

/** The id the user's own side carries in a simulated table. */
const val USER_TEAM_ID = "dream-xi"

/**
 * A drafted pick as the engine sees him.
 *
 * Two things cross over from the draft, and they are different numbers on
 * purpose:
 *  - [DraftedPlayer.player]'s natural role and rating decide what he really
 *    produced, which is what his npxG/90 describes;
 *  - the position he was placed in, and the rating points that cost him,
 *    decide how much of that survives.
 *
 * The penalty is the same one the pitch already shows the user. A player whose
 * card reads "-22 at DM" is worth exactly that much less to the simulation,
 * so nothing on screen is decorative.
 */
fun DraftedPlayer.toSimSlot(): SimSlot {
    val natural = player.role
    // His real rate, or what someone of his quality produces in his own
    // position when Understat never matched him. Not zero — see SeasonRepository.
    val rate = player.npxg90
        ?: TeamStrength.positionalYield(natural, player.overallRating)
    val mins = player.minutes
    fun per90(v: Int): Double = if (mins > 0) v * 90.0 / mins else 0.0

    /**
     * Cards per match: his own record shrunk toward what his position
     * averages, then projected onto a full season.
     *
     * Goals and assists above use a flat per-90 rate because they really do
     * scale with minutes. Bookings do not — a player who features more is
     * booked LESS per 90 — so treating them the same put Modric on 15 yellows
     * off a real 7. See SimModel.CARD_MINUTES_EXPONENT and, for why the
     * positional average is blended in here rather than substituted later,
     * SimModel.cardsPerMatch.
     */
    fun yellowsPerMatch(total: Int): Double =
        SimModel.cardsPerMatch(total, mins, SimModel.SLOT_YELLOW90[natural] ?: 0.0)

    fun redsPerMatch(total: Int): Double =
        SimModel.cardsPerMatch(total, mins, SimModel.SLOT_RED90[natural] ?: 0.0)
    return SimSlot(
        role = slot.role,
        player = SimPlayer(
            name = player.fullName,
            naturalRole = natural,
            npxg90 = if (natural == "GK") 0.0 else rate,
            overallRating = player.overallRating,
            savePct = player.savePct,
            goals90 = per90(player.goals),
            assists90 = per90(player.assists),
            yellow90 = yellowsPerMatch(player.yellowCards),
            red90 = redsPerMatch(player.redCards),
        ),
        positionPenalty = player.deltaAt(slot),
    )
}

/**
 * A substitute as the engine sees him: his own rates, priced in the position he
 * would come on in.
 */
fun BenchPlayer.toSimSlot(): SimSlot {
    val natural = player.role
    val mins = player.minutes
    fun per90(v: Int): Double = if (mins > 0) v * 90.0 / mins else 0.0
    val rate = player.npxg90 ?: TeamStrength.positionalYield(natural, player.overallRating)
    return SimSlot(
        role = role,
        player = SimPlayer(
            name = player.fullName,
            naturalRole = natural,
            npxg90 = if (natural == "GK") 0.0 else rate,
            overallRating = player.overallRating,
            savePct = player.savePct,
            goals90 = per90(player.goals),
            assists90 = per90(player.assists),
            yellow90 = SimModel.cardsPerMatch(
                player.yellowCards, mins, SimModel.SLOT_YELLOW90[natural] ?: 0.0,
            ),
            red90 = SimModel.cardsPerMatch(
                player.redCards, mins, SimModel.SLOT_RED90[natural] ?: 0.0,
            ),
        ),
        positionPenalty = coverDelta(player, role),
    )
}

/**
 * The user's XI as a side in a league table.
 *
 * Takes the picks in formation order rather than pick order so the eleven read
 * the way they are drawn on the pitch; the engine does not care, but anything
 * that later shows the line-up does.
 */
fun draftedTeam(
    picks: List<DraftedPlayer>,
    name: String,
    season: String,
    bench: List<BenchPlayer> = emptyList(),
): SimTeam {
    require(picks.size == 11) { "a side needs eleven players, got ${picks.size}" }
    val slots = picks.map { it.toSimSlot() }
    return SimTeam(
        id = USER_TEAM_ID,
        name = name,
        season = season,
        rating = TeamStrength.rate(slots),
        isUserTeam = true,
        xi = slots,
        // Starters and substitutes, with minutes shared out. The bench decides
        // WHO SCORES and nothing else — the rating above is the starting XI's
        // alone, so rotation cannot quietly make the side worse.
        squad = SquadProfile.fromSquad(slots, bench.map { it.toSimSlot() }),
    )
}

/**
 * Drops the user's side into a real league season.
 *
 * One real club makes way, because a 21-team league is not a season anyone
 * recognises and every fixture count and points total would be off. The club
 * replaced is passed in rather than chosen here — it is a decision the user
 * sees and the run records, not an implementation detail.
 */
fun leagueWithUser(
    opponents: List<SimTeam>,
    user: SimTeam,
    replacingTeamId: String?,
): List<SimTeam> = opponents.filterNot { it.id == replacingTeamId } + user
