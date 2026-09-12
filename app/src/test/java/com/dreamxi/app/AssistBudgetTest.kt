package com.dreamxi.app

import com.dreamxi.app.sim.MatchReporter
import com.dreamxi.app.sim.MatchResult
import com.dreamxi.app.sim.ScorerWeight
import com.dreamxi.app.sim.SquadProfile
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Assists come out of a season budget rather than a fresh draw per goal.
 *
 * THE BUG THIS PINS. Every goal used to draw its own assister, weighted by
 * season assists, after zeroing the scorer and anyone unlikely to be on the
 * pitch. Independent draws are far wider than football: across the 30 loaded
 * league-seasons the top provider averages 15.3 with a standard deviation of
 * 2.5 and has NEVER passed 21, while the per-goal draw ran a spread near 4 and
 * put a quarter of seasons above 21 — a simulated Özil reached 26 in a season
 * he finished on 16. Masking inflated the squad total too (72 against a real
 * 67), so it was not merely redistributing.
 *
 * Real Madrid's 2010/11 squad, with their real figures, is the case that
 * exposed it.
 */
class AssistBudgetTest {

    private fun madrid() = listOf(
        // name, goals, assists — the real 2010/11 record.
        ScorerWeight("Cristiano Ronaldo", 40.0, 9.0, 0.0, 0.0, startRate = 1.0),
        ScorerWeight("Mesut Özil", 6.0, 16.0, 0.0, 0.0, startRate = 0.95),
        ScorerWeight("Ángel Di María", 6.0, 10.0, 0.0, 0.0, startRate = 0.85),
        ScorerWeight("Karim Benzema", 15.0, 4.0, 0.0, 0.0, startRate = 0.75),
        ScorerWeight("Gonzalo Higuaín", 10.0, 5.0, 0.0, 0.0, startRate = 0.6),
        ScorerWeight("Xabi Alonso", 0.0, 5.0, 0.0, 0.0, startRate = 0.95),
        ScorerWeight("Marcelo", 3.0, 4.0, 0.0, 0.0, startRate = 0.95),
        ScorerWeight("Kaká", 7.0, 4.0, 0.0, 0.0, startRate = 0.4),
        ScorerWeight("Sergio Ramos", 5.0, 3.0, 0.0, 0.0, startRate = 0.95),
        ScorerWeight("Squad", 10.0, 7.0, 0.0, 0.0, startRate = 0.3),
    )

    /** A club's 102 goals, reported with the season budget in play. */
    private fun playSeason(seed: Int): Map<String, Int> {
        val squad = SquadProfile.fromSeasonTotals(madrid())
        val ledger = squad.newAssistLedger()
        val random = Random(seed)
        val tally = mutableMapOf<String, Int>()
        repeat(51) {
            val events = MatchReporter.report(
                "home", squad, "away", SquadProfile.EMPTY,
                MatchResult(2, 0, 2.0, 0.0), random,
                homeAssists = ledger,
            )
            events.goals.mapNotNull { it.assist }.forEach { tally[it] = (tally[it] ?: 0) + 1 }
        }
        return tally
    }

    @Test
    fun `nobody exceeds the assists he actually had`() {
        val real = madrid().associate { it.name to it.assists.toInt() }
        repeat(30) { seed ->
            playSeason(seed).forEach { (name, given) ->
                assertTrue(
                    "$name was credited $given assists, more than his real ${real[name]}",
                    given <= (real[name] ?: 0),
                )
            }
        }
    }

    /**
     * The number that started this: the top provider must land ON his record,
     * not 60% above it. A budget cannot overshoot, so this is really a check
     * that the budget is being SPENT rather than ignored.
     */
    @Test
    fun `the top provider finishes near his real total`() {
        val totals = (0 until 30).map { playSeason(it)["Mesut Özil"] ?: 0 }
        val mean = totals.average()
        assertTrue("Özil averaged $mean against a real 16", mean in 12.0..16.0)
        assertTrue("the worst season gave him ${totals.min()}", totals.min() >= 9)
    }

    @Test
    fun `a scorer is never credited with assisting his own goal`() {
        val squad = SquadProfile.fromSeasonTotals(madrid())
        val ledger = squad.newAssistLedger()
        val random = Random(4)
        repeat(60) {
            val events = MatchReporter.report(
                "home", squad, "away", SquadProfile.EMPTY,
                MatchResult(3, 0, 3.0, 0.0), random,
                homeAssists = ledger,
            )
            events.goals.forEach { goal ->
                assertTrue("${goal.scorer} assisted himself", goal.assist != goal.scorer)
            }
        }
    }

    /** Without a ledger the old per-goal draw still runs, for one-off matches. */
    @Test
    fun `a match with no season around it still reports assists`() {
        val squad = SquadProfile.fromSeasonTotals(madrid())
        val random = Random(9)
        val assisted = (0 until 40).sumOf { _ ->
            MatchReporter.report(
                "home", squad, "away", SquadProfile.EMPTY,
                MatchResult(3, 0, 3.0, 0.0), random,
            ).goals.count { it.assist != null }
        }
        assertTrue("no assists at all were reported", assisted > 0)
    }

    @Test
    fun `a real club's budget is its players' actual assists`() {
        val squad = SquadProfile.fromSeasonTotals(madrid())
        assertEquals(16, squad.assistBudget["Mesut Özil"])
        assertEquals(9, squad.assistBudget["Cristiano Ronaldo"])
        assertEquals(67, squad.assistBudget.values.sum())
    }
}
