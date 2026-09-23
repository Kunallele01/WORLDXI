package com.dreamxi.app.sim.worldcup

import kotlin.random.Random

/**
 * How a World Cup group separates teams level on points. The order CHANGED in
 * 2026, and applying the wrong one can put the wrong side through.
 */
enum class TiebreakRules {
    /**
     * 2006-2022: goal difference and goals scored in ALL group matches, then
     * points, goal difference and goals in the matches between the teams still
     * level; then fair play (from 2018) and drawing of lots.
     */
    OVERALL_FIRST,

    /**
     * 2026: points, goal difference and goals in the matches BETWEEN the level
     * teams first, re-applied to any subset still level; only then overall goal
     * difference and goals; then fair play and FIFA ranking.
     */
    HEAD_TO_HEAD_FIRST,
    ;

    companion object {
        fun forYear(year: Int): TiebreakRules = if (year >= 2026) HEAD_TO_HEAD_FIRST else OVERALL_FIRST
    }
}

/** One result the table is built from. */
data class GroupResult(val homeId: String, val awayId: String, val homeGoals: Int, val awayGoals: Int)

/** A line in a group table. */
data class WcGroupRow(
    val teamId: String,
    val played: Int,
    val won: Int,
    val drawn: Int,
    val lost: Int,
    val goalsFor: Int,
    val goalsAgainst: Int,
) {
    val points: Int get() = 3 * won + drawn
    val goalDifference: Int get() = goalsFor - goalsAgainst
}

/**
 * Orders a World Cup group.
 *
 * FAIR PLAY AND FIFA RANKING ARE REPLACED BY LOTS. Neither exists for these
 * matches — real ones carry no card data here and a simulated one has no
 * ranking — so a tie that survives every football criterion is settled by a
 * seeded draw, which is what the regulations fall back to anyway. It is rare:
 * of the real ties that decided a place, only Japan over Senegal (2018)
 * went that far, and a group the user did not play in keeps its REAL order,
 * so real history is never re-drawn.
 */
object WcGroupTable {

    fun rows(teamIds: List<String>, results: List<GroupResult>): Map<String, WcGroupRow> {
        val acc = teamIds.associateWith { IntArray(6) }
        for (r in results) {
            val h = acc[r.homeId] ?: continue
            val a = acc[r.awayId] ?: continue
            h[0]++; a[0]++
            h[4] += r.homeGoals; h[5] += r.awayGoals
            a[4] += r.awayGoals; a[5] += r.homeGoals
            when {
                r.homeGoals > r.awayGoals -> { h[1]++; a[3]++ }
                r.homeGoals < r.awayGoals -> { a[1]++; h[3]++ }
                else -> { h[2]++; a[2]++ }
            }
        }
        return acc.mapValues { (id, v) -> WcGroupRow(id, v[0], v[1], v[2], v[3], v[4], v[5]) }
    }

    /**
     * The group in finishing order.
     *
     * @param random used only for drawing lots; teams are sorted by id before
     *   any draw, so the answer does not depend on the order they were passed in.
     */
    fun rank(
        teamIds: List<String>,
        results: List<GroupResult>,
        rules: TiebreakRules,
        random: Random,
    ): List<WcGroupRow> {
        val all = rows(teamIds, results)
        val byPoints = all.values.groupBy { it.points }.toSortedMap(reverseOrder())
        return byPoints.values.flatMap { level ->
            if (level.size == 1) level
            else when (rules) {
                TiebreakRules.OVERALL_FIRST -> overallFirst(level, results, random)
                TiebreakRules.HEAD_TO_HEAD_FIRST -> headToHeadFirst(level, results, random)
            }
        }
    }

    private fun overallFirst(level: List<WcGroupRow>, results: List<GroupResult>, random: Random): List<WcGroupRow> =
        partition(level) { listOf(it.goalDifference, it.goalsFor) }.flatMap { tied ->
            if (tied.size == 1) return@flatMap tied
            val mini = rows(tied.map { it.teamId }, between(tied, results))
            partition(tied) { key(mini.getValue(it.teamId)) }.flatMap { lots(it, random) }
        }

    private fun headToHeadFirst(level: List<WcGroupRow>, results: List<GroupResult>, random: Random): List<WcGroupRow> {
        val mini = rows(level.map { it.teamId }, between(level, results))
        return partition(level) { key(mini.getValue(it.teamId)) }.flatMap { part ->
            when {
                part.size == 1 -> part
                // Head-to-head separated some of them: re-apply it to just these.
                part.size < level.size -> headToHeadFirst(part, results, random)
                // It separated nobody: overall goal difference and goals, then lots.
                else -> partition(part) { listOf(it.goalDifference, it.goalsFor) }.flatMap { lots(it, random) }
            }
        }
    }

    private fun key(row: WcGroupRow): List<Int> = listOf(row.points, row.goalDifference, row.goalsFor)

    private fun between(teams: List<WcGroupRow>, results: List<GroupResult>): List<GroupResult> {
        val ids = teams.map { it.teamId }.toSet()
        return results.filter { it.homeId in ids && it.awayId in ids }
    }

    /** Runs of equal key, best first; keys compare element by element. */
    private fun partition(rows: List<WcGroupRow>, key: (WcGroupRow) -> List<Int>): List<List<WcGroupRow>> =
        rows.groupBy(key).entries
            .sortedWith { a, b -> compareKeys(b.key, a.key) }
            .map { it.value }

    private fun compareKeys(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return 0
    }

    private fun lots(tied: List<WcGroupRow>, random: Random): List<WcGroupRow> =
        if (tied.size == 1) tied else tied.sortedBy { it.teamId }.shuffled(random)
}
