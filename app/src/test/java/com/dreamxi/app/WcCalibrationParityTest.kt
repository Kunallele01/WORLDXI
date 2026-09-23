package com.dreamxi.app

import com.dreamxi.app.sim.worldcup.GROUP_ROUND
import com.dreamxi.app.sim.worldcup.NationalSide
import com.dreamxi.app.sim.worldcup.WcMatchEngine
import com.dreamxi.app.sim.worldcup.WcModel
import com.dreamxi.app.sim.worldcup.WcPlayer
import com.dreamxi.app.sim.worldcup.WcSide
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The constants in WcModel were measured by etl/wc_calibration.py, which runs
 * its own PORT of the strength model. This holds the shipped Kotlin to what
 * that port measured, so a drift between the two — a role mapped differently,
 * a cost applied twice — cannot hide behind numbers that were right in Python.
 *
 * Python, on the same 300 group matches, with per-player grids, the flank cost
 * and the measured rating-to-output curves: win/draw/loss log-loss 0.9386
 * against 1.0785 for treating every side as equal; model draw rate 0.238.
 */
class WcCalibrationParityTest {

    @Test
    fun `the shipped model predicts real group matches as well as the calibration measured`() {
        var matches = 0
        var logLoss = 0.0
        var flatLoss = 0.0
        var drawModel = 0.0
        val realGoals = mutableListOf<Int>()
        for (year in WcFixtures.years) {
            val data = WcFixtures.tournaments.getValue(year)
            val entries = data.entries.associateBy { it.id }
            for (m in data.matches.filter { it.round == GROUP_ROUND }) {
                val home = entries.getValue(m.homeId)
                val away = entries.getValue(m.awayId)
                realGoals += m.homeGoals
                realGoals += m.awayGoals
                val hl = home.lineup ?: continue
                val al = away.lineup ?: continue
                val hs = WcSide(hl.rating, home.isHost)
                val aws = WcSide(al.rating, away.isHost)
                // Calibration measured the mapping BEFORE any host lift, so
                // parity is checked the same way.
                val lh = WcMatchEngine.expectedGoals(hs.copy(isHost = false), aws)
                val la = WcMatchEngine.expectedGoals(aws.copy(isHost = false), hs)
                val (ph, pd, pa) = outcome(lh, la)
                val p = when {
                    m.homeGoals > m.awayGoals -> ph
                    m.homeGoals == m.awayGoals -> pd
                    else -> pa
                }
                logLoss -= ln(p)
                drawModel += pd
                matches++
            }
        }
        val base = realGoals.average()
        for (year in WcFixtures.years) {
            for (m in WcFixtures.tournaments.getValue(year).matches.filter { it.round == GROUP_ROUND }) {
                val e = WcFixtures.tournaments.getValue(year).entries.associateBy { it.id }
                if (e.getValue(m.homeId).lineup == null || e.getValue(m.awayId).lineup == null) continue
                val (ph, pd, pa) = outcome(base, base)
                flatLoss -= ln(if (m.homeGoals > m.awayGoals) ph else if (m.homeGoals == m.awayGoals) pd else pa)
            }
        }
        println("Kotlin: $matches matches, log-loss ${logLoss / matches} (flat ${flatLoss / matches}), " +
            "draw rate ${drawModel / matches}")
        assertEquals("both sides rated in the same matches as the calibration", 300, matches)
        assertEquals(0.9386, logLoss / matches, 0.004)
        assertEquals(0.238, drawModel / matches, 0.01)
        assertTrue(logLoss < flatLoss)
    }

    @Test
    fun `stand-in keepers go to exactly the reachable keeperless nations the calibration found`() {
        val reachable = listOf(2006 to "Angola", 2026 to "South Africa", 2026 to "Egypt", 2026 to "IR Iran")
        for (key in reachable) {
            val squad = WcFixtures.squads.getValue(key)
            val keeper = NationalSide.standInKeeper(squad)
            assertNotNull("$key needs a stand-in keeper", keeper)
            val lineup = assertNotNullAndGet(WcFixtures.lineups[key], key)
            assertTrue("$key's lineup should use it", lineup.players.any { it.name == NationalSide.STAND_IN_KEEPER_NAME })
        }
        assertNull(NationalSide.standInKeeper(WcFixtures.squads.getValue(2022 to "France")))
    }

    @Test
    fun `every nation that can meet a user can field a side`() {
        for (year in WcFixtures.years) {
            for (entry in WcFixtures.tournaments.getValue(year).entries) {
                if (!entry.finishedBottom) assertNotNull("$year ${entry.id}", entry.lineup)
            }
        }
    }

    @Test
    fun `position costs come from his own grid, the banded fallback, and the flank`() {
        fun p(rating: Double, vararg pos: String, grid: Map<String, Int>? = null) =
            WcPlayer("x", "x", rating, pos.toList(), grid = grid)
        // Rodri, FC 26, carrying his FIFA 24 grid: the case that started this.
        val rodri = p(90.0, "CDM", "CM", grid = mapOf("CB" to -3, "FB" to -7, "DM" to 0, "CM" to -2, "CAM" to -7, "Winger" to -11, "ST" to -10))
        assertEquals(-7, NationalSide.positionCost(rodri, "FB", "L"))
        assertEquals(0, NationalSide.positionCost(rodri, "DM"))
        // A grid delta above his own role is never a bonus.
        assertEquals(0, NationalSide.positionCost(p(80.0, "CM", grid = mapOf("CB" to -5, "FB" to -3, "DM" to -2, "CM" to 0, "CAM" to 2, "Winger" to -1, "ST" to -4)), "CAM"))
        // No grid: the banded fallback, which costs a good player more.
        val weakCb = NationalSide.positionCost(p(70.0, "CB"), "CM")!!
        val strongCm = NationalSide.positionCost(p(88.0, "CM"), "CB")!!
        assertTrue("a strong CM loses more at CB ($strongCm) than a weak CB at CM ($weakCb) suggests", strongCm < -5)
        // The wrong flank, on top: a right-back at left-back.
        val rightBack = p(80.0, "RB", grid = mapOf("CB" to -2, "FB" to 0, "DM" to -3, "CM" to -5, "CAM" to -6, "Winger" to -5, "ST" to -9))
        assertEquals(0, NationalSide.positionCost(rightBack, "FB", "R"))
        assertEquals(-WcModel.WRONG_SIDE_FULLBACK_PENALTY, NationalSide.positionCost(rightBack, "FB", "L"))
        // Listed on both flanks: no cost either side.
        assertEquals(0, NationalSide.positionCost(p(80.0, "LB", "RB", grid = rightBack.grid), "FB", "L"))
        // The keeper boundary.
        assertNull(NationalSide.positionCost(p(80.0, "GK"), "CB"))
        assertNull(NationalSide.positionCost(p(80.0, "ST"), "GK"))
        assertEquals(0, NationalSide.positionCost(p(80.0, "GK"), "GK"))
    }

    private fun <T> assertNotNullAndGet(value: T?, label: Any): T {
        assertNotNull("$label", value)
        return value!!
    }

    private fun outcome(lh: Double, la: Double): Triple<Double, Double, Double> {
        var home = 0.0
        var draw = 0.0
        for (i in 0 until 12) for (j in 0 until 12) {
            val q = pmf(i, lh) * pmf(j, la)
            when {
                i > j -> home += q
                i == j -> draw += q
            }
        }
        return Triple(home, draw, 1 - home - draw)
    }

    private fun pmf(k: Int, lambda: Double): Double {
        var f = 1.0
        for (i in 2..k) f *= i
        return exp(-lambda) * lambda.pow(k) / f
    }
}
