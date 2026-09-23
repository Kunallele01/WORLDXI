package com.dreamxi.app

import com.dreamxi.app.sim.worldcup.GroupResult
import com.dreamxi.app.sim.worldcup.TiebreakRules
import com.dreamxi.app.sim.worldcup.WcGroupTable
import com.dreamxi.app.sim.worldcup.WcThirdPlaceTable2026
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WcGroupTableTest {

    private val teams = listOf("A", "B", "C", "D")

    /**
     * A and B both finish on 6 points. B has the better overall goal difference,
     * A won the match between them. The 2006-2022 rules put B first; 2026's put
     * A first. Getting this wrong sends the wrong side through as group winner.
     */
    private val aAndBLevel = listOf(
        GroupResult("A", "B", 1, 0),
        GroupResult("A", "C", 0, 1),
        GroupResult("A", "D", 1, 0),
        GroupResult("B", "C", 5, 0),
        GroupResult("B", "D", 3, 0),
        GroupResult("C", "D", 0, 1),
    )

    @Test
    fun `the old rules take overall goal difference before head-to-head`() {
        val order = WcGroupTable.rank(teams, aAndBLevel, TiebreakRules.OVERALL_FIRST, Random(1)).map { it.teamId }
        assertEquals(listOf("B", "A"), order.take(2))
    }

    @Test
    fun `2026 takes head-to-head first`() {
        val order = WcGroupTable.rank(teams, aAndBLevel, TiebreakRules.HEAD_TO_HEAD_FIRST, Random(1)).map { it.teamId }
        assertEquals(listOf("A", "B"), order.take(2))
    }

    /**
     * A, B and C all finish on 6 points after a cycle — A beat B 1-0, B beat C
     * 2-1, C beat A 2-1 — and each beat D 1-0. Among the three, all have 3
     * points and a goal difference of zero, but C scored 3 to A's and B's 2, so
     * C is separated and A and B are STILL exactly level.
     *
     * 2026's rules then re-apply head-to-head to A and B alone, where A's 1-0
     * win decides it. Skipping that step would drop to overall goal difference,
     * where A and B are identical, and leave it to lots — B first half the time.
     * So the order must be C, A, B on every seed.
     */
    @Test
    fun `2026 re-applies head-to-head to the teams still level`() {
        val results = listOf(
            GroupResult("A", "B", 1, 0),
            GroupResult("B", "C", 2, 1),
            GroupResult("C", "A", 2, 1),
            GroupResult("A", "D", 1, 0),
            GroupResult("B", "D", 1, 0),
            GroupResult("C", "D", 1, 0),
        )
        val points = WcGroupTable.rows(teams, results)
        assertEquals(listOf(6, 6, 6, 0), teams.map { points.getValue(it).points })
        repeat(60) { seed ->
            val order = WcGroupTable.rank(teams, results, TiebreakRules.HEAD_TO_HEAD_FIRST, Random(seed)).map { it.teamId }
            assertEquals("seed $seed", listOf("C", "A", "B", "D"), order)
        }
    }

    @Test
    fun `drawing of lots is fair and does not depend on input order`() {
        val allDraws = listOf(
            GroupResult("A", "B", 1, 1), GroupResult("A", "C", 1, 1), GroupResult("A", "D", 1, 1),
            GroupResult("B", "C", 1, 1), GroupResult("B", "D", 1, 1), GroupResult("C", "D", 1, 1),
        )
        val wins = mutableMapOf<String, Int>()
        repeat(8_000) { seed ->
            val forward = WcGroupTable.rank(teams, allDraws, TiebreakRules.OVERALL_FIRST, Random(seed)).map { it.teamId }
            val reversed = WcGroupTable.rank(teams.reversed(), allDraws, TiebreakRules.OVERALL_FIRST, Random(seed)).map { it.teamId }
            assertEquals(forward, reversed)
            wins.merge(forward.first(), 1, Int::plus)
        }
        println("first place by lots over 8,000 draws: $wins")
        for (t in teams) assertEquals(2_000.0, (wins[t] ?: 0).toDouble(), 150.0)
    }

    @Test
    fun `FIFA's third-place table covers every combination once`() {
        val table = WcThirdPlaceTable2026.assignments
        assertEquals(495, table.size)
        for ((groups, assignment) in table) {
            assertEquals(8, groups.length)
            assertEquals(groups.toList().map { it.toString() }.sorted(), assignment.values.sorted())
        }
        // What really happened in 2026 (outcome #67).
        assertEquals(
            mapOf("A" to "E", "B" to "J", "D" to "B", "E" to "D", "G" to "I", "I" to "F", "K" to "L", "L" to "K"),
            table.getValue("BDEFIJKL"),
        )
        assertTrue(table.keys.all { it == it.toList().sorted().joinToString("") })
    }
}
