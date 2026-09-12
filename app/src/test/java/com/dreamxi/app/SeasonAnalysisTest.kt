package com.dreamxi.app

import com.dreamxi.app.sim.RealFixture
import com.dreamxi.app.sim.SeasonAnalyst
import com.dreamxi.app.sim.SeasonSimulator
import com.dreamxi.app.sim.SimPlayer
import com.dreamxi.app.sim.SimSlot
import com.dreamxi.app.sim.SimTeam
import com.dreamxi.app.sim.SquadProfile
import com.dreamxi.app.sim.TeamStrength
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected points against actual, and the home/away split.
 *
 * The value of this to a player is entirely in it being trustworthy: "you
 * finished first despite being worth a projected fourth" is only interesting if
 * the projection is honest. So these tests check the arithmetic reconciles with
 * the season it describes, rather than checking it looks plausible.
 */
class SeasonAnalysisTest {

    private fun club(id: String, ovr: Int, user: Boolean = false): SimTeam {
        val xi = listOf(SimSlot("GK", SimPlayer("$id GK", "GK", 0.0, ovr, 69.0))) +
            listOf("CB", "CB", "FB", "FB", "DM", "CM", "CM", "Winger", "Winger", "ST")
                .map { SimSlot(it, SimPlayer("$id $it", it, 0.12, ovr, goals90 = 0.15)) }
        return SimTeam(
            id = id, name = "Club $id", season = "2023/24",
            rating = TeamStrength.rate(xi), isUserTeam = user, xi = xi,
            squad = SquadProfile.fromXi(xi),
        )
    }

    private val teams = (1..20).map { club("c$it", 74 + (it * 6) / 10) }

    private fun realSeason(): List<RealFixture> {
        val rng = Random(4242)
        val out = mutableListOf<RealFixture>()
        var matchday = 1
        for (i in teams.indices) {
            for (j in teams.indices) {
                if (i == j) continue
                out += RealFixture(
                    (matchday++ % 38) + 1, teams[i].id, teams[j].id,
                    rng.nextInt(0, 4), rng.nextInt(0, 4),
                )
            }
        }
        return out
    }

    private fun season(ovr: Int = 84, seed: Long = 5) = club("dream-xi", ovr, user = true).let { user ->
        user to SeasonSimulator.playCounterfactual(realSeason(), teams, user, "c1", seed)
    }

    @Test
    fun `the record reconciles with the league table`() {
        // If these ever disagree, two screens are describing the same season
        // differently and neither can be trusted.
        val (user, result) = season()
        val analysis = SeasonAnalyst.analyse(result.fixtures, user)
        val row = result.userRow!!

        assertEquals("played", row.played, analysis.played)
        assertEquals("points", row.points, analysis.actualPoints)
        assertEquals("goals for", row.goalsFor, analysis.actualGoalsFor)
        assertEquals("goals against", row.goalsAgainst, analysis.actualGoalsAgainst)
        assertEquals("wins", row.won, analysis.home.won + analysis.away.won)
        assertEquals("draws", row.drawn, analysis.home.drawn + analysis.away.drawn)
        assertEquals("losses", row.lost, analysis.home.lost + analysis.away.lost)
    }

    @Test
    fun `a season splits nineteen home and nineteen away`() {
        // The user inherits a real club's calendar, so the split is exact.
        val (user, result) = season()
        val analysis = SeasonAnalyst.analyse(result.fixtures, user)
        assertEquals(19, analysis.home.played)
        assertEquals(19, analysis.away.played)
    }

    @Test
    fun `each venue's record adds up on its own`() {
        val (user, result) = season()
        val a = SeasonAnalyst.analyse(result.fixtures, user)
        for (record in listOf(a.home, a.away)) {
            assertEquals(record.played, record.won + record.drawn + record.lost)
            assertEquals(record.won * 3 + record.drawn, record.points)
        }
    }

    @Test
    fun `expected points land in the same country as actual points`() {
        // Not equal — the gap is the whole point — but a 38-game season cannot
        // be worth 12 points and return 80.
        val (user, result) = season()
        val a = SeasonAnalyst.analyse(result.fixtures, user)
        println(
            "expected ${"%.1f".format(a.expectedPoints)} pts vs actual ${a.actualPoints}" +
                "  (${"%+.1f".format(a.pointsOverExpectation)})",
        )
        assertTrue("expected points was ${a.expectedPoints}", a.expectedPoints in 10.0..100.0)
        assertTrue(
            "a gap of ${a.pointsOverExpectation} is beyond variance",
            abs(a.pointsOverExpectation) < 25,
        )
    }

    @Test
    fun `a better XI is expected to take more points than a worse one`() {
        val weak = season(ovr = 70).let { (u, r) -> SeasonAnalyst.analyse(r.fixtures, u) }
        val strong = season(ovr = 92).let { (u, r) -> SeasonAnalyst.analyse(r.fixtures, u) }
        println(
            "weak XI expected ${"%.1f".format(weak.expectedPoints)}, " +
                "strong XI expected ${"%.1f".format(strong.expectedPoints)}",
        )
        assertTrue(
            "${weak.expectedPoints} vs ${strong.expectedPoints}",
            strong.expectedPoints > weak.expectedPoints + 15,
        )
    }

    @Test
    fun `expected goals track the lambdas the season was drawn from`() {
        // Averaged over 38 matches the drawn goals should sit near their means.
        val (user, result) = season()
        val a = SeasonAnalyst.analyse(result.fixtures, user)
        println(
            "xG ${"%.1f".format(a.expectedGoalsFor)} vs ${a.actualGoalsFor} scored, " +
                "xGA ${"%.1f".format(a.expectedGoalsAgainst)} vs ${a.actualGoalsAgainst} conceded",
        )
        assertTrue("xG was ${a.expectedGoalsFor}", a.expectedGoalsFor > 0)
        assertTrue("scored ${a.actualGoalsFor} against ${a.expectedGoalsFor} expected",
            abs(a.goalsOverExpectation) < 30)
    }

    @Test
    fun `home advantage shows up across many seasons`() {
        // Not in any single season — 19 games is far too few — but the engine
        // applies a measured 1.2452 multiplier at home, so it must be visible
        // once the noise is averaged out.
        var homePoints = 0.0
        var awayPoints = 0.0
        repeat(30) { seed ->
            val user = club("dream-xi", 82, user = true)
            val result = SeasonSimulator.playCounterfactual(realSeason(), teams, user, "c1", seed.toLong())
            val a = SeasonAnalyst.analyse(result.fixtures, user)
            homePoints += a.home.pointsPerMatch
            awayPoints += a.away.pointsPerMatch
        }
        val home = homePoints / 30
        val away = awayPoints / 30
        println("home ${"%.2f".format(home)} vs away ${"%.2f".format(away)} points per match")
        assertTrue("home $home should beat away $away", home > away)
    }

    @Test
    fun `real historical matches are excluded from the expectation`() {
        // Only the user's own 38 are simulated. A real result stores its own
        // scoreline where a lambda would go, so counting one would compare a
        // number against itself and quietly zero the gap.
        val (user, result) = season()
        val a = SeasonAnalyst.analyse(result.fixtures, user)
        val userFixtures = result.fixtures.count { it.home.id == user.id || it.away.id == user.id }
        assertEquals("every one of the user's matches is simulated", 38, userFixtures)
        // The expectation is built from 38 matches, so it cannot exceed what 38
        // matches can be worth.
        assertTrue(a.expectedPoints <= 38 * 3.0)
    }

    @Test
    fun `a side with no matches produces an empty analysis rather than a crash`() {
        val stranger = club("nobody", 80)
        val (_, result) = season()
        val a = SeasonAnalyst.analyse(result.fixtures, stranger)
        assertEquals(0, a.played)
        assertEquals(0, a.actualPoints)
        assertEquals(0.0, a.expectedPoints, 1e-9)
        assertEquals(0.0, a.home.pointsPerMatch, 1e-9)
    }
}
