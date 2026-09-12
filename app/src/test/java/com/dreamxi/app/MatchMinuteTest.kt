package com.dreamxi.app

import com.dreamxi.app.sim.MatchReporter
import com.dreamxi.app.sim.MatchResult
import com.dreamxi.app.sim.ScorerWeight
import com.dreamxi.app.sim.SimModel
import com.dreamxi.app.sim.SquadProfile
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When events happen, and who can be involved in them.
 *
 * The reported failure: a substitute striker scoring in the 3rd minute, and
 * again in the 25th, on his way to 13 goals. Goal minutes were drawn uniformly
 * across the ninety whoever scored them, so a bench forward could appear on the
 * scoresheet before he could possibly have been on the pitch.
 *
 * The answer is NOT "substitutes score late". A squad player on a 44% share
 * still starts 64% of the matches he appears in, so most of his goals are
 * legitimately early — the engine simply has to decide which case it is.
 */
class MatchMinuteTest {

    /** A squad of one, so every event is attributable to a known start rate. */
    private fun soleScorer(startRate: Double) = SquadProfile(
        players = listOf(
            ScorerWeight("The Man", goals = 1.0, assists = 0.0, yellows = 1.0, reds = 0.0,
                startRate = startRate),
        ),
        yellowsPerMatch = 0.0,
        redsPerMatch = 0.0,
    )

    private fun minutes(startRate: Double, goals: Int, seed: Int = 5): List<Int> {
        val random = Random(seed)
        val squad = soleScorer(startRate)
        val out = mutableListOf<Int>()
        repeat(goals) {
            val events = MatchReporter.report(
                "us", squad, "them", SquadProfile.EMPTY,
                MatchResult(1, 0, 1.0, 0.0), random,
            )
            out += events.goals.map { it.minute }
        }
        return out
    }

    @Test
    fun `a player who never starts cannot score early`() {
        val mins = minutes(startRate = 0.0, goals = 400)
        println("never-starts: earliest goal minute ${mins.min()}, latest ${mins.max()}")
        assertTrue(
            "a substitute scored in minute ${mins.min()}",
            mins.min() > SimModel.SUB_ENTRY_EARLIEST,
        )
        assertTrue("nothing past full time", mins.max() <= 90)
    }

    @Test
    fun `an ever-present can score at any point`() {
        val mins = minutes(startRate = 1.0, goals = 600)
        println("ever-present: earliest ${mins.min()}, latest ${mins.max()}")
        assertTrue("should reach the opening minutes", mins.min() <= 5)
        assertTrue("should reach the closing minutes", mins.max() >= 86)
    }

    @Test
    fun `a rotation player scores early most of the time, but not always`() {
        // The measured case: a 44% share implies a 64% start rate, so roughly
        // two thirds of his goals should be able to come before substitutions.
        val rate = SimModel.startRate(0.44)
        val mins = minutes(startRate = rate, goals = 800)
        val early = mins.count { it < SimModel.SUB_ENTRY_EARLIEST } / mins.size.toDouble()
        println(
            "44% share -> start rate ${"%.2f".format(rate)}; " +
                "${"%.0f".format(early * 100)}% of his goals came before the hour",
        )
        assertTrue("start rate was $rate", rate in 0.60..0.70)
        assertTrue("too few early goals: $early", early > 0.25)
        assertTrue("too many early goals: $early", early < 0.60)
    }

    @Test
    fun `the fitted start rate matches the measured curve`() {
        // Spot values from etl/start_rate.py.
        assertEquals(1.00, SimModel.startRate(0.95), 0.02)
        assertEquals(0.94, SimModel.startRate(0.80), 0.03)
        assertEquals(0.71, SimModel.startRate(0.50), 0.03)
        assertEquals(0.48, SimModel.startRate(0.20), 0.03)
        // Never outside a probability.
        assertTrue(SimModel.startRate(-5.0) >= 0.0)
        assertTrue(SimModel.startRate(50.0) <= 1.0)
    }

    @Test
    fun `a substitute cannot assist a goal scored before he came on`() {
        // The same inconsistency one step removed: the scoresheet has to agree
        // with itself about who was on the pitch.
        val squad = SquadProfile(
            players = listOf(
                ScorerWeight("Ever Present", 1.0, 1.0, 0.0, 0.0, startRate = 1.0),
                ScorerWeight("Late Sub", 0.0, 4.0, 0.0, 0.0, startRate = 0.0),
            ),
            yellowsPerMatch = 0.0,
            redsPerMatch = 0.0,
        )
        val random = Random(11)
        var checked = 0
        repeat(600) {
            val events = MatchReporter.report(
                "us", squad, "them", SquadProfile.EMPTY,
                MatchResult(1, 0, 1.0, 0.0), random,
            )
            for (g in events.goals) {
                if (g.assist == "Late Sub") {
                    checked++
                    assertTrue(
                        "a substitute assisted in minute ${g.minute}",
                        g.minute >= SimModel.SUB_ENTRY_EARLIEST,
                    )
                }
            }
        }
        println("substitute assists checked: $checked")
        assertTrue("the substitute never assisted at all", checked > 0)
    }

    @Test
    fun `bookings obey the same rule as goals`() {
        val squad = SquadProfile(
            players = listOf(ScorerWeight("Late Sub", 0.0, 0.0, 1.0, 0.0, startRate = 0.0)),
            yellowsPerMatch = 1.0,
            redsPerMatch = 0.0,
        )
        val random = Random(3)
        val mins = mutableListOf<Int>()
        repeat(400) {
            val events = MatchReporter.report(
                "us", squad, "them", SquadProfile.EMPTY,
                MatchResult(0, 0, 0.0, 0.0), random,
            )
            mins += events.cards.map { it.minute }
        }
        assertTrue("no bookings drawn", mins.isNotEmpty())
        println("substitute bookings: earliest minute ${mins.min()}")
        assertTrue(
            "a substitute was booked in minute ${mins.min()}",
            mins.min() > SimModel.SUB_ENTRY_EARLIEST,
        )
    }
}
