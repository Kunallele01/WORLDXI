package com.dreamxi.app

import com.dreamxi.app.sim.MatchEngine
import com.dreamxi.app.sim.TeamRating
import com.dreamxi.app.sim.worldcup.WcMatchEngine
import com.dreamxi.app.sim.worldcup.WcModel
import com.dreamxi.app.sim.worldcup.WcSide
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WcMatchEngineTest {

    private val average = WcSide(TeamRating(attack = 1.35, defence = 1.35, attackRaw = 1.3, defensiveRating = 78.0))
    private val strong = WcSide(TeamRating(attack = 2.2, defence = 0.8, attackRaw = 2.3, defensiveRating = 86.0))

    @Test
    fun `expected goals are the club figure mapped onto international football`() {
        val club = MatchEngine.expectedGoals(strong.rating.attack, average.rating.defence)
        val want = WcModel.INTERNATIONAL_SCALE * club.pow(WcModel.INTERNATIONAL_SHAPE)
        assertEquals(want, WcMatchEngine.expectedGoals(strong, average), 1e-12)
    }

    @Test
    fun `a host scores at the measured lift and concedes no differently`() {
        val host = strong.copy(isHost = true)
        assertEquals(
            WcMatchEngine.expectedGoals(strong, average) * WcModel.HOST_SCORING_FACTOR,
            WcMatchEngine.expectedGoals(host, average), 1e-12,
        )
        assertEquals(WcMatchEngine.expectedGoals(average, strong), WcMatchEngine.expectedGoals(average, host), 1e-12)
    }

    @Test
    fun `a knockout tie always has a winner, and each stage only happens when needed`() {
        val random = Random(11)
        repeat(20_000) {
            val s = WcMatchEngine.playKnockout(average, average, random)
            assertNotNull(s.homeWon)
            if (s.homeRegular != s.awayRegular) {
                assertTrue(!s.extraTime && s.homeExtra == 0 && s.awayExtra == 0 && s.shootout == null)
            } else {
                assertTrue(s.extraTime)
                if (s.homeExtra != s.awayExtra) assertNull(s.shootout) else assertNotNull(s.shootout)
            }
        }
    }

    /**
     * Extra time runs at 0.745 of the per-minute rate over thirty minutes, so
     * for two sides each expecting L in ninety, the chance extra time settles
     * nothing is the chance two Poisson(L * 0.745 / 3) draws are equal.
     */
    @Test
    fun `extra time scores at the measured rate`() {
        val random = Random(3)
        var level = 0
        var reached = 0
        repeat(200_000) {
            val s = WcMatchEngine.playKnockout(average, average, random)
            if (s.extraTime) {
                reached++
                if (s.homeExtra == s.awayExtra) level++
            }
        }
        val lambda = WcMatchEngine.expectedGoals(average, average) * WcModel.EXTRA_TIME_SCORING_FACTOR / 3
        val expected = (0..12).sumOf { k -> poisson(k, lambda).pow(2) }
        val observed = level.toDouble() / reached
        println("extra time still level: simulated $observed, analytic $expected over $reached ties")
        assertEquals(expected, observed, 0.01)
    }

    /**
     * The measured facts about real shootouts: the stronger side wins no more
     * often than a coin (14 of 23), and penalties go in about 61.5% of the time.
     */
    @Test
    fun `a shootout is a coin flip whoever is stronger`() {
        val random = Random(5)
        var homeWins = 0
        var scored = 0
        var taken = 0
        val n = 40_000
        repeat(n) {
            val s = WcMatchEngine.shootout(random)
            if (s.homeWon) homeWins++
            val kicks = s.homeKicks!! + s.awayKicks!!
            scored += kicks.count { it }
            taken += kicks.size
            assertTrue("a decided shootout is never level", s.homeScore != s.awayScore)
            assertTrue("nobody takes more than one extra kick than the other", abs(s.homeKicks.size - s.awayKicks.size) <= 1)
        }
        val share = homeWins.toDouble() / n
        println("home side won $share of $n shootouts; conversion ${scored.toDouble() / taken}")
        assertEquals(0.5, share, 0.01)
        assertEquals(WcModel.SHOOTOUT_CONVERSION, scored.toDouble() / taken, 0.01)
    }

    @Test
    fun `a shootout stops as soon as it is decided`() {
        val random = Random(8)
        repeat(20_000) {
            val s = WcMatchEngine.shootout(random)
            val first = if (s.homeFirst!!) s.homeKicks!! else s.awayKicks!!
            val second = if (s.homeFirst!!) s.awayKicks!! else s.homeKicks!!
            if (first.size <= WcModel.SHOOTOUT_KICKS_EACH && second.size <= WcModel.SHOOTOUT_KICKS_EACH) {
                // Replaying all but the last kick must leave it undecided.
                val a = first.count { it }
                val b = second.count { it }
                val lastWasFirst = first.size > second.size
                val (pa, pb) = if (lastWasFirst) (a - (if (first.last()) 1 else 0)) to b else a to (b - (if (second.last()) 1 else 0))
                val (ta, tb) = if (lastWasFirst) (first.size - 1) to second.size else first.size to (second.size - 1)
                val each = WcModel.SHOOTOUT_KICKS_EACH
                val decidedBefore = pa > pb + (each - tb) || pb > pa + (each - ta)
                assertTrue("kicks were taken after the shootout was already decided", !decidedBefore)
            }
        }
    }

    private fun poisson(k: Int, lambda: Double): Double {
        var f = 1.0
        for (i in 2..k) f *= i
        return exp(-lambda) * lambda.pow(k) / f
    }
}
