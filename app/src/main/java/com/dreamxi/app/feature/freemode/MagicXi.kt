package com.dreamxi.app.feature.freemode

import com.dreamxi.app.feature.draft.Formation
import com.dreamxi.app.feature.draft.FormationSlot
import com.dreamxi.app.feature.draft.SquadPlayer
import com.dreamxi.app.feature.draft.coverDelta
import kotlin.random.Random

/**
 * One candidate for the magic XI: a player in one particular season.
 *
 * The same person appears once per season he played, and the solver treats
 * them as one PERSON — picking whichever season serves the slot best. Messi
 * is not eleven different candidates.
 */
data class MagicCandidate(
    val player: SquadPlayer,
    val clubSeasonId: Long,
    val clubName: String,
    val seasonLabel: String,
)

/** A filled slot, in the order the reveal should play it. */
data class MagicPick(
    val slot: FormationSlot,
    val candidate: MagicCandidate,
    /** What he is worth standing here — his rating after any out-of-position cost. */
    val effectiveRating: Int,
)

/**
 * Builds the strongest eleven available in a whole league.
 *
 * WHY THE ELEVEN ARE CHOSEN TOGETHER, not slot by slot. Filling the shirts in
 * order — best keeper, then best centre-backs, then the midfield — reads like
 * the obvious approach and is measurably worse. Run greedily over La Liga it
 * takes Ronaldo for the first winger slot, finds Messi has nowhere better left
 * than central midfield, and pays a four-point out-of-position cost to put him
 * there. Solved as one assignment, both play on the wing and Suarez leads the
 * line — a stronger side that also looks like a football team. The reveal
 * animation still fills the shirts in order; only the arithmetic happens up
 * front.
 *
 * WHY IT IS NOT THE SAME ELEVEN EVERY TIME. A pure optimum is deterministic:
 * one league and one formation would always return one fixed side, and there
 * would be no reason ever to press the button twice. Each candidate's value
 * gets a small random nudge (< [JITTER] of a rating point) before solving, so
 * players who are effectively equal trade places between runs — Kroos and
 * Modric one time, Xavi and Iniesta the next — while the side as a whole stays
 * within a point of the true best.
 */
object MagicXi {

    /**
     * How much a candidate's value may be nudged, in rating points. One point:
     * large enough that genuine near-equals swap, small enough that a clearly
     * better player never loses his place.
     */
    const val JITTER = 1.0

    /** A keeper is never an outfielder and an outfielder is never a keeper. */
    private const val IMPOSSIBLE = Double.NEGATIVE_INFINITY

    /**
     * @param keptSlotIds shirts the user filled himself, left exactly as they are.
     * @param keptPlayerIds the men standing in them, who must not be offered again.
     *
     * KEEPING SHIRTS IS PART OF THE SOLVE, not a filter afterwards. The eleven
     * are chosen together, so telling the solver which shirts are already taken
     * lets it pick the best side AROUND them — the remaining places are filled
     * knowing a left-footed centre-back is already there. Solving all eleven and
     * then overwriting some would throw away that whole advantage.
     */
    fun build(
        candidates: List<MagicCandidate>,
        formation: Formation,
        random: Random = Random.Default,
        keptSlotIds: Set<String> = emptySet(),
        keptPlayerIds: Set<Long> = emptySet(),
    ): List<MagicPick> {
        if (candidates.isEmpty()) return emptyList()

        // A kept man is unavailable everywhere else: without this the solver
        // would happily hand back a second copy of the user's own centre-back
        // for the other centre-back shirt.
        val slots = formation.slots.filterNot { it.id in keptSlotIds }
        if (slots.isEmpty()) return emptyList()

        // Collapse to one column per PERSON. A player occupies one shirt, so
        // his best season for a given slot is all the solver needs; the season
        // that produced it is recovered afterwards.
        val byPerson: Map<Long, List<MagicCandidate>> =
            candidates.filterNot { it.player.playerId in keptPlayerIds }
                .groupBy { it.player.playerId }
        if (byPerson.isEmpty()) return emptyList()
        val people: List<Long> = byPerson.keys.toList()

        // value[slot][person], already nudged. Kept alongside the candidate
        // that produced it so the season is not recomputed later.
        val best = Array(slots.size) { arrayOfNulls<MagicCandidate>(people.size) }
        val value = Array(slots.size) { DoubleArray(people.size) { IMPOSSIBLE } }
        for ((s, slot) in slots.withIndex()) {
            for ((p, personId) in people.withIndex()) {
                var bestValue = IMPOSSIBLE
                var bestCandidate: MagicCandidate? = null
                for (c in byPerson.getValue(personId)) {
                    val rating = ratingAt(c.player, slot) ?: continue
                    if (rating > bestValue) {
                        bestValue = rating.toDouble()
                        bestCandidate = c
                    }
                }
                if (bestCandidate != null) {
                    value[s][p] = bestValue + random.nextDouble() * JITTER
                    best[s][p] = bestCandidate
                }
            }
        }

        val assignment = Hungarian.maximise(value)
        return slots.indices.mapNotNull { s ->
            val p = assignment[s]
            val candidate = if (p >= 0) best[s][p] else null
            candidate?.let {
                MagicPick(slots[s], it, ratingAt(it.player, slots[s]) ?: it.player.overallRating)
            }
        }
    }

    /**
     * The men who genuinely play this position, best first.
     *
     * The ladder a tap walks down. It is deliberately NOT re-solved: pressing a
     * shirt is a request to change that one man, so the other ten stay exactly
     * where the solver put them and only this position moves. Re-running the
     * assignment on every tap would shuffle players the user never touched,
     * which reads as the app undoing his last change.
     *
     * SPECIALISTS ONLY, which is where this differs from [build] and why. The
     * solver is allowed to play a man out of position because it is buying a
     * stronger ELEVEN and paying for it somewhere; a tap is not buying
     * anything, it is answering "who else could play here". Ranked by
     * effective rating alone the answer went wrong exactly as reported: a
     * 94-rated striker carrying a five-point penalty prices at 89 and so
     * outranks the twelfth-best centre-back, and a dozen taps into a defensive
     * shirt the app starts offering forwards. Requiring [coverDelta] to be
     * zero — EA's own grid saying he plays there, not merely that he is good
     * enough to survive there — keeps the ladder to real centre-backs, however
     * far down it goes.
     *
     * [keep] is the man the cast originally put here, held on the ladder even
     * if he was one of those out-of-position picks, so that walking the whole
     * cycle returns to the magic XI rather than stranding the user.
     *
     * Ordering is total and has no jitter in it. A tap must land on the same
     * next man every time, or tapping twice could return the player it just
     * replaced.
     */
    fun ladder(
        candidates: List<MagicCandidate>,
        slot: FormationSlot,
        exclude: Set<Long> = emptySet(),
        keep: MagicCandidate? = null,
    ): List<MagicPick> = candidates
        .asSequence()
        .filter { it.player.playerId !in exclude }
        .filter { plays(it.player, slot) || it.player.playerId == keep?.player?.playerId }
        .mapNotNull { c -> ratingAt(c.player, slot)?.let { MagicPick(slot, c, it) } }
        // One rung per PERSON, in his best season for this shirt — the same
        // collapse [build] does, for the same reason: a player is one option,
        // not one option per season he played.
        .groupBy { it.candidate.player.playerId }
        .map { (_, seasons) -> seasons.maxByOrNull { it.effectiveRating }!! }
        .sortedWith(
            // The man the cast chose sits at the head of his own cycle
            // whatever he is rated, so the first tap moves away from him and
            // the last tap comes back to him.
            compareByDescending<MagicPick> { it.candidate.player.playerId == keep?.player?.playerId }
                .thenByDescending { it.effectiveRating }
                .thenBy { it.candidate.player.fullName }
                .thenBy { it.candidate.player.playerId },
        )

    /**
     * Whether this is a position the player actually plays, as opposed to one
     * he could be asked to cover. [coverDelta] is zero only when EA's grid
     * rates him here as highly as in his own position.
     */
    private fun plays(player: SquadPlayer, slot: FormationSlot): Boolean {
        if ((player.role == "GK") != slot.isGoalkeeper) return false
        return slot.isGoalkeeper || coverDelta(player, slot.role) == 0
    }

    /**
     * What this player is worth in this slot, or null if he cannot fill it.
     *
     * The goalkeeper boundary is absolute in both directions — the same rule
     * the draft and Free Mode already enforce when a shirt is tapped.
     */
    fun ratingAt(player: SquadPlayer, slot: FormationSlot): Int? {
        val isKeeper = player.role == "GK"
        if (isKeeper != slot.isGoalkeeper) return null
        if (isKeeper) return player.overallRating
        return (player.overallRating + coverDelta(player, slot.role)).coerceIn(1, 99)
    }
}

/**
 * Kuhn-Munkres assignment, for a rectangular matrix with at least as many
 * columns as rows.
 *
 * Written out rather than approximated because the whole point of choosing the
 * eleven together is that the answer is actually optimal; a heuristic here
 * would quietly reintroduce the greedy behaviour this exists to avoid. It runs
 * on an 11-by-a-few-hundred matrix, so cost is irrelevant.
 *
 * Cells holding NEGATIVE_INFINITY are forbidden pairings and are never chosen
 * unless a row has no legal column at all, which returns -1 for that row.
 */
internal object Hungarian {

    /** For each row, the column assigned to it, or -1 when none is possible. */
    fun maximise(value: Array<DoubleArray>): IntArray {
        if (value.isEmpty()) return IntArray(0)
        if (value[0].isEmpty()) return IntArray(value.size) { -1 }

        // A row with no legal column is dropped before solving rather than
        // handed one and filtered out afterwards. Left in, it takes a column
        // with it — and that column may have been the only legal option for a
        // row that could actually have used it.
        val live = value.indices.filter { r -> value[r].any { it != Double.NEGATIVE_INFINITY } }
        val out = IntArray(value.size) { -1 }
        if (live.isEmpty()) return out
        if (live.size < value.size) {
            val sub = solve(Array(live.size) { value[live[it]] })
            for ((i, r) in live.withIndex()) out[r] = sub[i]
            return out
        }
        return solve(value)
    }

    private fun solve(value: Array<DoubleArray>): IntArray {
        val rows = value.size
        val cols = value[0].size

        // Solved as a minimisation over costs, with forbidden pairings priced
        // far above any real one rather than at infinity, which would poison
        // the potentials.
        var span = 0.0
        for (r in 0 until rows) for (c in 0 until cols) {
            val v = value[r][c]
            if (v != Double.NEGATIVE_INFINITY) span = maxOf(span, kotlin.math.abs(v))
        }
        val forbidden = (span + 1.0) * (rows + 1)
        // Padded out to a square when candidates are scarcer than shirts, so
        // the algorithm's "every row gets a column" invariant holds; rows that
        // land on padding are reported unassigned.
        val width = maxOf(cols, rows)
        val cost = Array(rows) { r ->
            DoubleArray(width) { c ->
                val v = if (c < cols) value[r][c] else Double.NEGATIVE_INFINITY
                if (v == Double.NEGATIVE_INFINITY) forbidden else -v
            }
        }

        val u = DoubleArray(rows + 1)
        val v = DoubleArray(width + 1)
        val p = IntArray(width + 1) { -1 }   // column -> row
        val way = IntArray(width + 1) { -1 }

        for (i in 0 until rows) {
            p[width] = i
            var j0 = width
            val minv = DoubleArray(width + 1) { Double.MAX_VALUE }
            val used = BooleanArray(width + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.MAX_VALUE
                var j1 = -1
                for (j in 0 until width) {
                    if (used[j]) continue
                    val cur = cost[i0][j] - u[i0] - v[j]
                    if (cur < minv[j]) {
                        minv[j] = cur
                        way[j] = j0
                    }
                    if (minv[j] < delta) {
                        delta = minv[j]
                        j1 = j
                    }
                }
                if (j1 < 0) break
                for (j in 0..width) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != -1)
            while (j0 != width) {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            }
        }

        val result = IntArray(rows) { -1 }
        for (j in 0 until cols) {
            val r = p[j]
            if (r in 0 until rows && value[r][j] != Double.NEGATIVE_INFINITY) result[r] = j
        }
        return result
    }
}
