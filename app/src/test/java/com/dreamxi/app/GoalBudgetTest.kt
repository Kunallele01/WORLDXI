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
 * Goals come out of a season budget, exactly as assists do.
 *
 * THE BUG THIS PINS, and the reason it survived a first investigation. When
 * assists were fixed, scorers were checked and cleared because their MEAN was
 * right — simulated 40.5 against Ronaldo's real 40. That compared the spread of
 * a re-run club-season against how much top scorers vary across DIFFERENT
 * seasons and clubs (sd 7.8), which is a different quantity altogether.
 *
 * Re-running one real club-season there is no distribution to match: the goals
 * were really scored. Measured on the per-goal draw, Liverpool's real 2017/18
 * gave Salah anywhere from 21 to 47 around his real 32, and Manchester City's
 * 2022/23 gave Haaland 23 to 53 around his real 36. A user reported Salah
 * finishing a run on 42 — past any Premier League season ever played.
 */
class GoalBudgetTest {

    /** Liverpool 2017/18: 84 goals, Salah 32 of them. */
    private fun liverpool() = listOf(
        ScorerWeight("Mohamed Salah", 32.0, 10.0, 0.0, 0.0, startRate = 1.0),
        ScorerWeight("Roberto Firmino", 15.0, 7.0, 0.0, 0.0, startRate = 0.95),
        ScorerWeight("Sadio Mané", 10.0, 7.0, 0.0, 0.0, startRate = 0.85),
        ScorerWeight("Philippe Coutinho", 8.0, 5.0, 0.0, 0.0, startRate = 0.5),
        ScorerWeight("Mohamed Salah's team-mates", 19.0, 20.0, 0.0, 0.0, startRate = 0.6),
    )

    private fun playSeason(seed: Int, goals: Int = 84): Map<String, Int> {
        val squad = SquadProfile.fromSeasonTotals(liverpool())
        val goalLedger = squad.newGoalLedger()
        val assistLedger = squad.newAssistLedger()
        val random = Random(seed)
        val tally = mutableMapOf<String, Int>()
        repeat(goals / 2) {
            val events = MatchReporter.report(
                "home", squad, "away", SquadProfile.EMPTY,
                MatchResult(2, 0, 2.0, 0.0), random,
                homeAssists = assistLedger,
                homeGoalsLedger = goalLedger,
            )
            events.goals.forEach { tally[it.scorer] = (tally[it.scorer] ?: 0) + 1 }
        }
        return tally
    }

    @Test
    fun `a real club's top scorer finishes on what he really scored`() {
        val totals = (0 until 30).map { playSeason(it)["Mohamed Salah"] ?: 0 }
        val mean = totals.average()
        println("Salah across 30 runs: mean $mean, range ${totals.min()}-${totals.max()} (real 32)")
        assertTrue("Salah averaged $mean against a real 32", mean in 28.0..32.0)
        assertTrue("his best run reached ${totals.max()}, beyond his real 32", totals.max() <= 32)
    }

    @Test
    fun `nobody outscores his own record`() {
        val real = liverpool().associate { it.name to it.goals.toInt() }
        repeat(20) { seed ->
            playSeason(seed).forEach { (name, scored) ->
                assertTrue(
                    "$name scored $scored, more than his real ${real[name]}",
                    scored <= (real[name] ?: 0),
                )
            }
        }
    }

    /**
     * A goal must always have a scorer. This is the one place the budget CANNOT
     * simply decline — unlike an assist, where "nobody" is a correct answer
     * roughly three times in ten.
     */
    @Test
    fun `every goal still gets a scorer, even past the budget`() {
        val squad = SquadProfile.fromSeasonTotals(liverpool())
        val ledger = squad.newGoalLedger()
        val random = Random(3)
        // Far more goals than the club ever scored, so the budget runs dry.
        var credited = 0
        repeat(80) {
            val events = MatchReporter.report(
                "home", squad, "away", SquadProfile.EMPTY,
                MatchResult(3, 0, 3.0, 0.0), random,
                homeGoalsLedger = ledger,
            )
            assertEquals("a goal went uncredited", 3, events.goals.size)
            credited += events.goals.size
        }
        assertEquals(240, credited)
    }

    @Test
    fun `a real club's budget is its players' actual goals`() {
        val squad = SquadProfile.fromSeasonTotals(liverpool())
        assertEquals(32, squad.goalBudget["Mohamed Salah"])
        assertEquals(84, squad.goalBudget.values.sum())
    }

    /** An estimated budget is scaled to the goals the side is expected to score. */
    @Test
    fun `an estimated budget follows the goals expected`() {
        val squad = SquadProfile.fromSeasonTotals(liverpool())
        val scaled = squad.newGoalLedger(expectedGoals = 42.0)
        assertEquals("half the goals should mean half the budget", 42, scaled.values.sum())
        assertTrue("the top scorer should still lead", scaled.getValue("Mohamed Salah") >= 14)
    }
}
