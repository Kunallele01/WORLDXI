package com.dreamxi.app.feature.draft

import com.dreamxi.app.sim.SimModel

/** A substitute, with the position he provides cover for. */
data class BenchPlayer(
    val player: SquadPlayer,
    /** The role he is on the bench to cover. */
    val role: String,
    val clubName: String,
    val seasonLabel: String,
) {
    /** What he is worth in the position he would come on in. */
    val effectiveRating: Int
        get() = (player.overallRating + coverDelta(player, role)).coerceIn(1, 99)
}

/** A squad the draft passed through, kept so a bench can be filled from it. */
data class VisitedSquad(
    val clubName: String,
    val seasonLabel: String,
    val players: List<SquadPlayer>,
)

/**
 * Fills a bench automatically from the squads a run passed through.
 *
 * WHY THERE IS A BENCH AT ALL. Eleven players taking every minute of all 38
 * matches took every goal their side scored, so a drafted forward out-scored
 * his real self by roughly half. Real squads give the top eleven 69.6% of the
 * minutes and the remainder to a bench that takes 27.7% of the goals. Restoring
 * that puts individual totals back where real ones sit — see
 * [com.dreamxi.app.sim.SquadProfile.fromSquad].
 *
 * WHY IT IS AUTOMATIC. The tension of a draft is the eleven decisions. Eight
 * more rounds of picking players nobody cares about would tax the part that
 * works to fix a presentational problem, so the bench is chosen for the user
 * out of squads he has already seen. Every name on it is a real player from a
 * club he actually visited; nothing is invented.
 *
 * NO GOALKEEPER. A backup keeper scores nothing and is booked about once every
 * sixteen games, so he would occupy a slot and contribute nothing to the only
 * thing the bench exists for.
 */
object BenchSelection {

    /**
     * Which positions the bench covers, most-used in the formation first.
     *
     * Derived from the shape rather than a fixed list, so a 3-5-2 gets
     * wing-back cover and a 4-2-3-1 gets a second holding midfielder. A fixed
     * list would be right for a 4-3-3 and wrong for the other eighteen shapes.
     *
     * Once every outfield role in the formation is covered the list cycles, so
     * the most-fielded positions get a second body before the rarest get one.
     */
    fun benchRoles(formation: Formation, size: Int = SimModel.BENCH_SIZE): List<String> {
        val outfield = formation.slots.filterNot { it.isGoalkeeper }
        if (outfield.isEmpty()) return emptyList()
        val byUse = outfield
            .groupingBy { it.role }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
        return List(size) { byUse[it % byUse.size] }
    }

    /**
     * Picks the bench.
     *
     * For each role in turn, the best remaining player AT THAT POSITION — using
     * EA's positional grid rather than raw overall, so "best centre-back" means
     * best as a centre-back rather than the highest-rated player who happens to
     * be listed there.
     *
     * Anyone already in the XI is excluded, and nobody appears twice: identity
     * is unique across a whole run, bench included. If a role has nobody left,
     * the slot falls back to the best remaining outfielder rather than being
     * left empty, which is reachable in a run that spun eleven thin squads.
     */
    fun pick(
        visited: List<VisitedSquad>,
        xi: List<DraftedPlayer>,
        formation: Formation,
        size: Int = SimModel.BENCH_SIZE,
    ): List<BenchPlayer> {
        val taken = xi.map { it.player.playerId }.toMutableSet()
        val pool = visited
            .flatMap { squad -> squad.players.map { squad to it } }
            // Outfielders only, and each real person once however many of the
            // visited squads he turned up in.
            .filter { (_, player) -> player.role != "GK" }
            .distinctBy { (_, player) -> player.playerId }

        val bench = mutableListOf<BenchPlayer>()
        for (role in benchRoles(formation, size)) {
            val choice = pool
                .filterNot { (_, player) -> player.playerId in taken }
                // A PLAYER WHO ACTUALLY PLAYS THERE WINS, ALWAYS.
                //
                // Ranking purely on adjusted rating sent 29% of bench slots to
                // a converted player, and produced picks nobody would defend:
                // Lewandowski as winger cover, Valverde at full-back, Carvajal
                // at defensive midfield. EA's positional grid is far too gentle
                // about adjacent roles to stop it — a 90-rated striker is rated
                // 85 as a winger, so a four-point penalty never overcomes a
                // six-point rating gap when the pool is eleven elite squads.
                //
                // It also makes the bench immune to a bad grid, which matters:
                // about 1.2% of player-seasons carry a positional grid matched
                // to the wrong person, and those rows cluster among exactly the
                // highly-rated players an auto-pick reaches for. Carvajal's
                // corrupt grid rated him 64 at full-back and 83 at attacking
                // midfield, which is what put a right-back on the bench as a
                // defensive midfielder.
                //
                // Conversions are still allowed, but only once nobody who plays
                // the position is left — a thin pool must not leave a hole.
                .maxWithOrNull(
                    compareBy<Pair<VisitedSquad, SquadPlayer>> { (_, player) ->
                        if (player.role == role) 1 else 0
                    }.thenBy { (_, player) ->
                        player.overallRating + coverDelta(player, role)
                    },
                )
                ?: break
            val (squad, player) = choice
            taken += player.playerId
            bench += BenchPlayer(
                player = player,
                role = role,
                clubName = squad.clubName,
                seasonLabel = squad.seasonLabel,
            )
        }
        return bench
    }
}

/**
 * What a player loses covering [role], from EA's positional grid.
 *
 * The same delta the pitch shows for a starter, so a bench centre-back is
 * priced exactly as he would be if he were placed there — never positive, and
 * zero when no grid could be resolved for him.
 */
fun coverDelta(player: SquadPlayer, role: String): Int {
    val key = gridKeyForRole(role) ?: return 0
    val target = player.positionRatings[key] ?: return 0
    val reference = gridKeyForRole(player.role)?.let { player.positionRatings[it] }
        ?: player.positionRatings.values.maxOrNull()
        ?: return 0
    return minOf(0, target - reference)
}
